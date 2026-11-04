# V6 测试架构与用例交接

2026-11-04。**仅设计，未写测试代码、未编译或执行任何测试。** 实现范围见 [V6 说明](version-6.md)。用户要求减少测试和 token 消耗；后续模型应先实现以下最小集合，失败后只复验相关部分，不扩大矩阵、不默认调用真实模型。

## 分层与替身

1. 纯逻辑：ConversationContext 使用固定合成 transcript，检查消息序列、摘要 provenance 和预算；不启动 Spring、不接网络。
2. 服务：真实 ConversationService/Repository(memory)/ChatAuditService，加脚本式 ToolCallingGateway、合成只读工具和可撤销 KnowledgeSearchClient；保存送模 messages，通过闩锁控制并发时序，不用 sleep。身份通过 SecurityContext JWT 设置，结束清理。
3. HTTP 合约：MockMvc + 上述服务替身，验证 resolver/bean wiring、校验与状态映射；不重复服务全部排列组合。RAG revalidation 可用本机 HTTP stub 验证当前读者 token 和依赖链，不调用公网。
4. JDBC：一次专用 MySQL 集成场景，实际 Flyway V1–V8；两个 repository 实例共享库模拟多进程 CAS。不要用 H2 冒充 MySQL JSON_CONTAINS/JSON_EXTRACT/JSON 序列化行为。若环境不具备，标记未执行，不启动长期环境或触碰工作库。

测试模型所有 complete 调用都返回预设工具请求/答案；断言调用计数，防止重放/冲突触发新调用。默认 DEEPSEEK_ENABLED=false，避免启动完整容器/RAG/embedding/reranker。V6 无需新增真实模型授权。

## 最小用例集合（建议 6 个场景）

| 编号 | 设置与操作 | 关键断言 |
| --- | --- | --- |
| C1 两轮与重放 | owner 创建会话，先问 ORD-1001，完成一次新鲜查询；第二轮问“那运费呢”；同 key/同问题重发，然后改问题重发 | 第二轮 messages 含第一轮完整问答和当前问题，模型按脚本重新调用工具；成功响应有新 revision/taskId；同 key 回放不增加模型次数，改问题 409；另一新会话 messages 不包含该历史 |
| C2 窗口/摘要 | 构造超过 4 轮含两个订单的历史，减小预算使整对裁剪 | 最新旧订单指代、摘要 sourceTasks/throughTurn/version 正确；无历史 tool_call 孤儿、失败轮不入 prompt；总估算在限额内；原 transcript 不被改写；单条问题放不下拒绝，模型调用为 0 |
| C3 权限与依赖 | 同租户 bob、其他租户同 subject 尝试读取/续写；owner 分享 bob；在 RAG stub 撤销原 task 的 ACL/委托或变更 revision | 未授权 404，分享后只读、不能写/删除；用 bob 当前 JWT 验证 RAG；撤权后 GET/重放/下一轮和直接读取派生 task 都不泄漏旧正文，模型调用前失败；在末轮响应前撤权同样拒绝 |
| C4 并发与迟到 | 闩锁停住 A 的模型响应；B 同 revision 发送；取消或删除 A 会话后释放 A，再尝试下一轮 | B 冲突且不调用模型；A 迟到结果不覆盖取消、删除或下一轮；记录任务关联期间的 CAS 失败也不能写回旧状态；异常只存安全 errorCode，不存供应商错误正文；崩溃遗留 RUNNING 可用列表 revision 取消 |
| C5 JDBC 持久化 | 专用空库迁移到 V7 后插入旧任务，再迁移 V8；创建含摘要/依赖的会话，重建仓储并双实例抢相同 revision | 旧任务不变；JSON 的 Instant/Set/嵌套 record 可往返；仅一个 CAS 成功；tenant/participant 列表语义正确，删除标记不挤占列表；过期读取拒绝且定时清理仅清会话；依赖重载后仍复核祖先，审计保留 |
| C6 HTTP/UI 薄合约 | 少量 MockMvc 请求覆盖 create/send、缺 key/非法 message、外租户；页面人工检查一次合成问答 | 响应包装和 revision 与契约一致；原单轮入口仍接通旧重载；无 JWT API 401，但静态页面可加载；页面换 token/会话不显示旧异步响应，HTML 字符按文本显示，失败后可刷新，不自动重试模型 |

C3 的撤权/修订可采用同一用例中的小型参数化，不做完整角色×故障笛卡尔积。C4 优先覆盖真实 CAS 竞争，不能仅 mock repository 永远成功。C5 若未执行必须保留迁移与 JDBC 未验证限制。

## 故障观察与交付

关注 audit.complete 已成功而会话提交失败的非原子边界：不要求抹除审计，但不能返回成功会话或自动再调用模型。检查历史依赖的去重/100 任务上限及共享复核 deadline，不按网络请求次数写脆弱断言。

实现测试代码后先编译，再仅运行上述 V6 用例；只有发现相关回归才运行对应旧测试。报告记录新增用例数、实际执行范围、失败原因/修复、是否 MySQL、是否浏览器；明确真实模型调用应为 **0**，不引用 V5 成绩作为 V6 成绩。未通过前不将路线状态改成“验证完成”。
