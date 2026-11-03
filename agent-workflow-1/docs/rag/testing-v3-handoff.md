# V3 测试交接清单（未执行）

更新：2026-11-03。交接对象：用户安排的测试模型。

本清单最初作为开发后的测试交接。用户随后授权测试，并要求控制范围，现已完成基础回归与 V3 边界测试，实际结果见 [V3 测试记录](verification-v3-2026-11-03.md)。下面仍是**完整候选场景及预期行为**，不表示全部执行；本轮不继续扩展大量样本或完整矩阵。V1/V2 的通过记录只能作为历史基线。

实现说明：[version-3.md](version-3.md)；生产范围：[rag-production-to.md](../../rag-production-to.md)。API 前缀为 `/api/v1`，运行目录为仓库中的 `pom.xml` 所在目录。

## 验证环境与产物

1. 使用独立数据库、S3 bucket、队列/Compose project 和测试租户。若沿用现有隔离集成脚本，先确认其迁移升级到 head，且没有把工作知识库或共享 Worker 接入测试环境。不要在用户现有知识库做删除/回收故障注入。
2. 准备 tenant A/B；A 下 manager、contributor-1、contributor-2、reader；同名 subject 跨租户。另建 tenant admin，验证 admin 也受文档读 ACL 限制的生命周期接口。KB ACL 授权和开发登录沿用现有 API。
3. 文档内容使用唯一标记：v1 `退款审批门槛为 100 元 / POLICY-V3-ALPHA`，v2 `退款审批门槛为 200 元 / POLICY-V3-BETA`，v3 `退款审批门槛为 300 元 / POLICY-V3-GAMMA`；只使用合成资料。保存各版 UUID、chunk ID、原件 SHA256。
4. 分开记录：可控模型 stub 的事务/权限/并发验证，以及真实 BGE-M3/RabbitMQ/PG/S3 的端到端验证。不能用 stub 通过替代模型链路通过。故障注入应放在隔离环境，不修改运行中的业务资料。
5. 导出新的 OpenAPI，确认 `info.version=0.3.0`、路由/参数/状态码覆盖 V3，再更新 `docs/rag/openapi.json`。开发轮保留的是 V2 快照。导出命令见交付文档。
6. 产出报告：代码 commit 或工作区 diff 摘要、环境/依赖/schema 版本、执行命令、场景 ID、通过/失败/阻塞、脱敏证据和问题位置。失败时记录实际响应、数据库不变量和可复现步骤；未执行项不能填通过。

推荐顺序：迁移与导入 → 既有回归 → 基本版本链路 → 权限 → 并发与故障 → GC → 页面 → 真实端到端。是否允许部署由用户另行安排；本清单不要求直接升级正在使用的容器。

## 迁移与兼容

| ID | 操作 | 预期结果/核对点 |
| --- | --- | --- |
| MIG-01 | 空隔离库执行 0001 → 0002 → 0003；重复升级 head | 完整建表；重复升级无新增数据副作用；API/Worker 使用运行角色可访问新表，不能使用 superuser 掩盖 RLS 问题 |
| MIG-02 | 从 0002 数据快照升级，覆盖已发布、待处理、失败、已删除文档 | version_number 按文档连续分配；version_counter 匹配；上传者回填；原 active 版 READY/首次发布标记/profile 回填；原 chunk ID/向量/hash 不变 |
| MIG-03 | 检查已删除文档迁移 | active 指针清空、内容修订递增、retired_at 保留删除时间；仍不可读，未触发物理删除 |
| MIG-04 | 0003 schema 的 API 启动与旧 schema 启动 | 新 schema 可启动；旧 schema 缺少 V3 字段时启动失败关闭，不能半兼容运行；所有旧写服务在升级前停止 |
| MIG-05 | 检查三张新表的复合 FK/RLS 与跨租户连接复用 | 跨文档 version FK 被拒绝；租户 A 无法读写 B 数据；连接回池后 tenant 不残留；无直接降级到 0002 的破坏性脚本 |
| MIG-06 | 运行已有 Python/Java 回归并生成 OpenAPI | 记录真实结果；测试夹具/响应语义若因 V3 改变需明确更新原因，不能放宽权限断言来“通过” |

## 版本、发布与引用

| ID | 操作 | 预期结果/核对点 |
| --- | --- | --- |
| VER-01 | 首次上传并经过真实或受控 Worker 处理 | 版本号 1、base_revision 0；首版自动发布；job SUCCEEDED、version READY、active 指向首版、content_revision=1；dense/lexical 同时可用 |
| VER-02 | 上传 v2，auto_publish=false，携带当前 expected_content_revision | 202 返回 job/version；处理期间 v1 继续可检索；成功后 v2 READY 但 active 仍 v1，revision 不递增；搜索不返回 v2 草稿 |
| VER-03 | manager 发布 v2，再回滚 v1，均使用新幂等键/当前修订 | 每次成功只递增内容修订一次；切换 active；旧版退休时间更新、目标退休时间清空；发布事件前后 ID/原因/actor/revision 正确；ACL revision 不变 |
| VER-04 | 上传 v3 自动发布，并制造 base_revision 过期 | 未冲突时自动发布；冲突时 job 仍 SUCCEEDED、版本 READY、publication_note=CONTENT_REVISION_CONFLICT，active 不变；manager 可复核后用最新修订明确发布 |
| VER-05 | 文件解析失败、模型失败、人工取消/重试 | 不改变旧版 active/chunks；仅 FAILED+PROCESSING 可重试；READY/PURGING/PURGED 不得重试/取消；重复 MQ 不产生重复就绪版本/发布事件 |
| VER-06 | 幂等上传/发布/回滚：同键同请求、同键异请求、跨文档复用键 | 同请求回放同一 job/version/event，不重复分配版本号；不同请求 409 IDEMPOTENCY_CONFLICT；回放仍检查当前权限。无响应后的客户端重试需保留原键与原请求 |
| VER-07 | 发布未就绪、当前版、回滚未发布草稿；去掉一条词法索引或更换 profile | 拒绝错误状态；VERSION_NOT_READY / ALREADY_CURRENT / INVALID_ROLLBACK / INDEX_INCOMPLETE / PROFILE_MISMATCH；失败不增加 revision/事件 |
| VER-08 | v1 发布时保存引用，发布 v2 后访问该引用和 v1 原件 | 引用仍为 v1 正文/hash，status=historical/is_current=false；原件 hash 相同、X-Document-Version 固定；新搜索只返回当前版 |
| VER-09 | 删除文档后访问旧引用/原件/版本列表并尝试回滚 | 读取 404；active 清空、内容/ACL 修订增加、未成功 job 被取消且 fence 增加；回滚不得复活文档。保留保护/GC 管理接口按权限允许已知已删除 ID |
| VER-10 | 分页历史/发布记录、比较不同版本/大文档/同一版本 | 游标不重复遗漏既有记录；READY 草稿不泄露；比较单版最多 40,000 字符/2,000 输入行、输出最多 2,000 行，有截断标记；同版无差异 |

## 权限与并发

| ID | 操作 | 预期结果/核对点 |
| --- | --- | --- |
| ACL-01 | reader/其他 contributor 请求未发布版历史、任务、原件和 diff | 不返回草稿正文/原件/任务；上传者必须仍有写权限才能读取自己的草稿；manager 还需满足文档读 ACL |
| ACL-02 | reader/contributor 手动发布、回滚、hold、GC | 拒绝管理操作；当前 contributor 可显式选择 auto_publish 是已记录行为，并非强制审批机制，不误报为 manager 人工发布越权 |
| ACL-03 | tenant admin 不在文档 allowed_subjects 中 | 生命周期读取/发布/回收不返回内容或允许操作；既有 ACL 管理/逻辑删除的管理权限语义单独记录，不把它误认为可读正文 |
| ACL-04 | 下载原件期间撤销 KB ACL/成员资格/文档读 ACL，或开始 GC | 下载完成前复核阻止返回正文；S3 缺失异常分支也重新授权；已回收有权者 410，失权者 403/404，无正文 |
| ACL-05 | 所有新路由使用另一租户 doc/version/chunk/job ID，包括同名 subject | 不返回对方内容/元数据，不修改对方数据；错误响应不含对象 key、数据库参数或原件正文 |
| RACE-01 | 同一 base 并发上传多个新版、并发完成自动发布 | 分配不同递增版本号；最多一个从原修订成功发布，其余 READY+冲突提示；不覆盖已经提交的新 active |
| RACE-02 | 不同键、相同 expected revision 同时发布/回滚 | 只有一个成功，其余 409；同键同请求并发则一次提交、其他回放；无重复发布事件/丢失修订 |
| RACE-03 | Worker 提交与删除、取消、ACL 收窄竞争 | 以锁/事务顺序生效；删除完成后不出现可检索版本；迟到/过期 fence 不提交 chunks/active；若先完成发布，后删除仍隐藏内容 |
| RACE-04 | 检索模型调用期间切换 active 或撤权 | 最终结果与诊断不返回已不符合当前过滤条件的候选；可能减少命中，不冒充整库快照/自动重查 |
| RACE-05 | 发布/回滚、hold、GC 请求并发 | active/hold/非终态任务保护不被绕过；进入 PURGING 后不可发布/回滚/下载；所有写路径锁顺序无死锁或永久阻塞 |

## 保留期与 GC 恢复

仅在隔离库中通过受控夹具调整 retired_at/ready_at/created_at/deleted_at，模拟保留期到期。配置最小保留天数和版本数均为 1；不能通过把配置设为 0 绕过保护。默认 30 天、最近 3 个未回收版本。物理删除不可撤销，不在工作 bucket 执行。

| ID | 操作 | 预期结果/核对点 |
| --- | --- | --- |
| GC-01 | 预览 active、最近 N 版、hold、未到期、处理中、符合条件版本 | blocked_by 分别标明保护原因；preview 只读；已删除文档不保护最近 N 版，但仍检查删除后的保留期/hold/非终态 job |
| GC-02 | 提交混合可回收/受保护列表、跨文档 ID、过期内容修订、重复 ID | 全批次验证后写入，任一无效不得部分排队；修订冲突 409；重复 ID 去重；最多 20 个 |
| GC-03 | 合法排队后重复提交；完成后再提交相同 ID | PURGING 任务可重新排队并增加 fence；PURGED 不重复建任务/删除，返回 already_purged；当前 doc 修订仍需匹配 |
| GC-04 | 默认 Compose 与显式启动 maintenance | 默认不运行 version-gc；仅执行指定租户、已经明确排队的任务，不自行扫描删除对象；--once 只排空当前可领取项，不等价于全部成功 |
| GC-05 | 完整执行一次 GC | S3 原件删除，chunks 与词法侧表删除，citation tombstone 保存；version PURGED，任务 DONE；版本元数据和发布审计保留 |
| GC-06 | 引用在 PURGING/PURGED 状态读取、未发布草稿 chunk ID 读取 | 曾发布引用有权限者 410；没有权限/文档删除不可拿到正文；未发布内容不能通过猜测引用 ID 读取，回收 tombstone 不放宽草稿边界 |
| GC-07 | S3 DELETE 之前 kill；DELETE 成功、PG 提交前 kill；数据库提交失败 | RUNNING 租约过期后可恢复；重复 DELETE 不报业务重复错误；最终对象/索引/任务一致；不能恢复为 READY 或 active |
| GC-08 | S3 网络故障、PG 故障、120 秒租约过期、两个 runner 同时处理 | 有界退避 RETRY_WAIT 或租约恢复；旧 fence 不能写 DONE；重复删除仅针对同一不可恢复的 PURGING 原件；无活跃版本损失 |
| GC-09 | 排队人撤权、随后另一个有权限 manager 重新排队接管 | 每次删除前检查当前权限；撤权失败记录错误并退避；接管更新 actor/fence 后可执行；旧 actor/fence 不覆盖新任务状态 |
| GC-10 | 已删除文档带 hold，再解除 hold；尝试解除 PURGING hold | 仅有当前管理/文档权限者可通过已知 ID 解除已删除文档的保留；PURGING/PURGED 操作 410，不承诺取消已开始的回收 |
| GC-11 | 超过 500 个未回收版本 | 预览返回最早 500 个，按保护规则操作；完成回收后下一批前进；当前 API 不承诺任意版本直接跳过预览窗口排队 |

S3 开启 versioning 后的旧 object versions、孤立 PUT 对象、多租户 runner 编排、备份/副本删除传播不在本轮实现范围，报告列为剩余能力，不能用当前 GC 通过推导为完整数据擦除通过。

## 管理页面与真实链路

| ID | 操作 | 预期结果/核对点 |
| --- | --- | --- |
| UI-01 | 新版上传、手动刷新、发布、回滚、比较、原件下载、hold/GC 预览 | 默认新版 auto_publish 未选中；显示处理完成与当前生效的区别；冲突有提示，不自动重试覆盖；危险回收需显式确认 |
| UI-02 | 快速切换身份/KB/文档，延迟旧页面请求返回；连续点击更多/刷新 | 旧身份/文档响应不填回新版面板；分页不重复追加；旧刷新不覆盖新刷新；发布后清空旧搜索结果和引用弹窗 |
| UI-03 | reader/manager 角色切换、表单提交中切换身份、特殊文件名/正文 | 操作可见性与权限符合预期；服务端权限作为最终边界；使用 textContent 展示文本，不执行 HTML/脚本 |
| E2E-01 | 真实模型/MQ 完成 v1 入库 → v2 处理 → 发布 → 搜索 → 历史引用 → 回滚 | 保留模型 revision/profile、任务/消息和响应证据；v2 处理中 v1 可查；发布后只检索 active；旧引用仍指旧内容 |
| E2E-02 | 真实 GC 单租户排队与执行，并调用 Java 无状态搜索桥接 | API/Worker/GC/Java 与 schema/模型配置匹配；删除/回收不会通过桥接重新暴露资料；完整 Java task/chat 的 RAG_TASK_TENANCY_REQUIRED 门禁仍保持 |

V3 交付验收至少覆盖上述权限、迁移、并发、物理回收恢复项；任何数据泄露、active 被错误回收、旧任务覆盖新发布都应阻断部署。性能、真实 IdP、整库重建、完整 Agent 租户化仍按生产计划另行推进。
