# V7 测试架构与用例设计

2026-11-04。**只有设计，没有测试代码或执行结果。** 用户指定其他模型负责测试实现/执行。本轮生产代码及契约见 [V7](version-7.md)；V6 未验证的事项仍以 [V6 交接](testing-v6-handoff.md) 为准。

## 分层与执行预算

1. 先编译 Java、检查 Python/前端能加载，报告真实错误；本轮作者没有运行这些步骤。
2. 核心服务测试使用脚本式 ToolCallingGateway、可撤权的 KnowledgeSearchClient、固定 identity/时钟边界；索引 HTTP 使用本机服务替身。断言实际模型调用次数，不调用公网模型。
3. MySQL 事务/唯一索引/JSON/租约/CAS 必须用专用 MySQL 8，PG 向量/RLS/ON CONFLICT 必须用专用 pgvector 和非 BYPASSRLS 账号；不要用 mock/H2 冒充数据库保障。两库各一次准备，复用夹具，不为每个断言重建 Docker。无法提供环境就保留“未执行”，不测试工作库。
4. FastAPI embedding 使用固定合法 1024 维向量替身，测试检索顺序、profile 和预算即可；默认真实 LLM 与真实 embedding 调用均为 0。语义阈值质量校准属于后续明确安排的质量评估，不能由固定向量证明。
5. UI 只做一次关键流程和一次换身份检查，安全边界以服务/API 断言为主。通过后不自动扩大全量回归，不新建付费联调。

## 最小高风险场景（建议 8 组，可复用夹具）

| 编号 | 设置、操作 | 必须验证的结果 |
| --- | --- | --- |
| M1 生命周期与冲突 | 同 owner 创建 response_style=concise；相同 key 重发、改内容重发；确认、编辑、旧 revision 提交、重新确认、删除；过期请求 key 再使用 | 候选不召回；同 key 不新增记录、不同 payload 冲突；一槽位只保留一个权威值；编辑立即使旧版本失效且需再确认；删除擦除正文，重放只返回当前元数据；7 天窗口准确 |
| M2 身份与继承 | tenant A/alice、A/Alice、A/bob、tenant B/alice、管理员尝试 list/detail/edit/delete/recall/jobs；把含私人记忆的会话分享给 bob；关 MEMORY_ENABLED 后读旧 task | 大小写 subject、租户和 owner 不串数据；管理员不能越过私人 owner；参与者读不了他人的私人记忆派生答案；关闭功能后 JDBC 读取门禁仍工作；用户 JWT 不能冒充索引服务 token |
| M3 召回与当前证据 | 新会话调用偏好+摘要；mock 索引返回错 owner、错 tenant、旧 revision、删除/过期 ID、合法 ID；生成中修订/撤权 | 仅合法当前记忆进 prompt，来源权限重新检查；4000 字节/6 条限制及 V6 总预算成立；历史记忆不能成为本次引用证据或执行写工具；调用前和返回前变更均拒绝；旧 task/会话回放同样受保护 |
| M4 队列与崩溃时序 | MySQL 两 worker 抢 job；claim 后退出，租约到期接管；索引写成功但 ACK 丢失；旧 worker 在新修订之后完成；第 5 次尝试后进程退出 | 单次领取、持久恢复、lease token/revision fencing；不会把新 job 标 DONE；索引同修订重放不重复改变数据；最后一次崩溃进入 DEAD；手动 retry 仅 owner 可用；无业务调度线程被网络等待占用 |
| M5 删除/过期与乱序索引 | active rev2 开始 embedding，先提交 delete rev3，再释放 rev2；重复 rev3；同 revision 不同 payload；重建/换 profile；断网后删除 | 旧写入不能恢复向量；同修订不同内容 409；MySQL 删除立即阻止召回/旧派生答案，不等索引 ACK；超时/错误有 DEAD 状态且可补偿；reindex 新修订和 tombstone 生效；过期不因 Worker 延迟继续可读 |
| M6 提取、许可与预算 | 自动全局关闭/开启，用户未许可/许可；成功/失败/别人的 task；重复提取；关个人设置；超过 20 次/100 条；源任务继承已删记忆 | 自动需双开关且只作用后续入队；显式请求仅 owned SUCCEEDED；提取不调用 LLM，生成未确认候选；重复/已遗忘 slot 不恢复；自动预算不足不回滚业务成功；失权候选不可读/确认、但可删；接受后排队任务的行为与文档一致 |
| M7 来源续期与接口薄合约 | 过期未撤销 grant，用当前 JWT renew；并发 revoke；KB 权限收窄/文档改版；缺 key、非法 days/content/revision；索引不可用 | 续期不扩大 KB、不复活 revoked，不接受过期版本；CAS 不覆盖并发 revoke；服务身份不能代用户隐式续期；HTTP 状态及错误码准确；索引故障只有偏好降级并有明确 metadata，来源鉴权故障不能忽略 |
| M8 迁移与操作面 | V8 旧库→V9，PG 0004→0005；真实保存/重载依赖图；非超级用户跨 tenant 查询；浏览器偏好确认→新会话→删除，换令牌时延迟响应 | 旧任务/会话保留，新事务及外键可运行；RLS 与 owner 条件有效；索引仅返回 ID/revision/distance，无正文；页面无 localStorage token 和 HTML 注入，旧身份异步响应不展示；源码新增配置默认关闭 |

M3 可一次脚本式索引响应包含多个坏候选，避免按候选种类重复启动全套场景。M4/M5 用闩锁/可控 Future/人工调整测试库 lease_until，禁止长 sleep 或启动真实模型。已有 V6 相同 CAS 场景无需复制，新增断言集中在记忆依赖。

## 数据与断言细节

- 使用合成用户和合成订单，任务 A 提供政策源，任务 B 使用 A 的摘要，任务 C 继承 B；撤销 A、删除/编辑摘要、关闭模块后分别读取 B/C，验证所有公开路径（会话、幂等回放、task 详情与事件）一致。
- 测试 DB 事务回滚：在 memory_events 或 memory_jobs 插入前注入失败，正文/CAS/事件/队列必须全部回滚；TASK_SUCCEEDED 与自动提取入队同事务。计数配额正常耗尽不得把业务成功变成失败。
- SQL 身份字段应区分大小写；直接用数据库默认 case-insensitive collation 建专用库，以证明 V9 的显式 collation 起作用。
- 服务 token 测试仅需本地签名：错误 issuer/audience/operation、改 body、过期、错误 key 不产生 embedding 调用；正常 token 的 tenant 与 owner 是唯一索引范围。索引删除在 owner 成员失效后仍能执行，以完成遗忘传播。
- 索引断流/超大响应/慢响应验证 Java 完整 10 秒上限和 64 KiB 限制、Python embedding 6 秒与 128 KiB 限制。用短测试配置/可控替身而非多次真实等待；若当前常量无法缩短，最多运行一个代表场景。
- 任务来源授权续期与个人记忆 TTL 是两件事；不应因为记忆有效 90 天就绕过一小时 grant。续期要求相同来源仍授权且未变更；祖先失权仍拒绝派生正文。
- API 查询元数据可显示 ID/state/sourceTaskId，不能借 list/jobs/events 绕过正文授权；已删除正文不保存在 memory_events、memory_jobs 或 PG 索引中。独立任务审计仍有原内容，必须通过依赖门禁保护，备份删除不能虚报。

## 交付报告与发布门槛

报告分清：编译结果、执行的场景、实际数据库/角色、mock 与真实依赖、跳过项及真实外部调用数。只修复和复验已发现问题；不要引用 V5 成绩或虚构 V6/V7 测试通过数。

发布前另需确认专用 TLS/密钥、索引内部路由网络限制、JDBC/PG 备份与恢复策略、DEAD/积压监控、阈值与容量取舍；这些属于部署/质量验收，不是要求本轮自动扩成压测。没有上述证据时记录“代码待验证/待部署”，不能改写为生产上线完成。
