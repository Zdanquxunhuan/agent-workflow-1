# V7：受控长期记忆

后续状态（2026-11-05）：V11 已完成统一 Java 编译及部分关键链路验证，并修复长期记忆创建故障；详见 [实际验证记录](verification-v11-2026-11-05.md)。本文原交付状态和测试设计保留，不代表完整矩阵已通过。

2026-11-04。**按生产目标编写代码；未编译、未编写或执行测试、未迁移工作库、未部署。** 用户把测试实现和执行交给后续模型，本轮交付生产代码、架构取舍与 [测试交接](testing-v7-handoff.md)。V6 的验证状态不因本次开发而改变。

## 存储与执行职责

MySQL 是权威来源，保存记忆正文、tenant/owner、类型/槽位、来源任务、确认状态、修订号、有效期，以及仅含元数据的变更记录。pgvector 保存独立 memory_vectors 表：向量、ID、修订号、profile、有效期和删除标记，不保存记忆正文。没有把私人记忆写入公共 KB，也没有新增数据库产品。

Java 新增 memory 包、MemoryController、MySQL Flyway V9；RAG 新增 memory_index 路由和 Alembic 0005。V7 必须使用 JDBC，不提供生产功能向 memory 仓储静默回退。身份字段使用区分大小写的 MySQL collation，避免不同 JWT subject 的元数据串读。

记忆变更、审计元数据及索引事件在同一 MySQL 事务内提交。memory_jobs 是合并到最新修订的事务性队列，任务成功时可在原任务事务中排入提取任务。Worker 使用独立单线程、有界队列，不让索引 HTTP 等待占用原业务调度线程；通过数据库租约跨实例领取，完成时校验 lease token 和 revision。索引端以修订号拒绝旧写入，删除标记保留以阻止旧消息恢复数据。

## 支持的记忆与生命周期

| 类型 | 内容与写入 |
| --- | --- |
| PREFERENCE | response_style：concise/detailed；language：zh/en；format：paragraph/bullets/conclusion_first。用户显式创建，唯一槽位防止矛盾偏好同时生效 |
| TASK_SUMMARY | 已成功且属于当前用户的任务；异步提取请求前 350 字符、结果前 450 字符并标明历史性质。默认 30 天，生成候选，必须人工确认 |

摘要采用确定性摘录，不新增 LLM 提取调用，也不宣称能够自动理解或解决事实冲突。任务摘要可以人工纠错；实时订单状态、支付结果和政策仍需查工具。此策略是当前生产设计选择，不是待替换的模拟实现。

状态为 CANDIDATE → ACTIVE；编辑产生新修订并回到 CANDIDATE，确认后才再次使用。删除/到期会擦除权威正文，保留无正文的修订和来源元数据。新建偏好请求按 tenant/owner/key/hash 幂等 7 天；同键改内容冲突，重放返回当前元数据，不恢复已删除内容。修改使用 revision CAS，客户端冲突后必须刷新。

自动提取默认关闭，要求 MEMORY_AUTO_EXTRACT=true 且用户通过 settings 接口许可。关闭个人设置阻止后续自动入队；已接受的任务仍可完成候选，用户可删除候选。显式提取不依赖自动开关。同一源任务重复提取不会重复生成候选，也不会恢复已遗忘的摘要。

## 召回与保护派生答案

持续会话 `/api/v1/conversations/{id}/messages` 已接入跨会话召回；原单轮 `/api/v1/agent/chat` 保持单轮行为。结构化偏好直接查 MySQL；仅存在有效摘要时才调用向量查询。向量结果只提供候选 ID/revision/distance，Java 回查 tenant/owner、ACTIVE、有效期和修订号，再验证来源任务当前权限及其依赖。

最多取 8 个语义候选、最终 6 条记忆，记忆条目预算约 4000 UTF-8 字节；与 V6 历史窗口共享其总预算，实际 prompt 包装开销计入窗口。语义距离默认 <=0.45，可配置，尚未经过业务数据校准，不能解释为正确率。索引不可用时仅保留可用偏好，并记录 semantic_index_unavailable；权限、来源验证服务故障不会被伪装为成功授权。

送模记忆作为不可信的偏好/历史线索，不是系统指令或当前证据。原模型租户出域允许列表适用于 V7，即使选择 legacy 网关也不能绕过。记忆的选用原因、修订号、估算大小及降级状态写入 MEMORY_RECALL 任务事件，不记录原始 prompt。

task_memory_uses 和 task_memory_parents 保存派生关系。每轮调用、返回前、会话展示/回放、独立 task 详情/事件读取都会检查记忆与来源；删除、修订、到期会阻止旧派生正文展示。私人记忆不因会话分享而自动共享，参与者读取包含其他 owner 私人记忆的历史答案会被拒绝。关闭 MEMORY_ENABLED 后，JDBC 下 MemoryProvenanceGuard 仍保留，避免功能开关变成绕过遗忘检查的途径。

来源任务的短期知识授权过期后，可显式 reauthorize：使用当前已验证用户 JWT 重新检查 KB 范围、全部来源权限及版本，范围不能扩大；显式撤销的授权不能恢复。仅续期直接来源的授权，祖先授权若也过期需分别处理。知识内容换版需要新任务和新摘要，不能用续期认可旧正文。本功能不代替企业后台 token exchange。

## API 与页面

页面 `/memory.html` 提供列表、内容查看、创建偏好、查看后确认、修改、删除、提取、授权刷新、索引重建和后台状态。访问令牌只在当前页面内存；内容以 textContent 显示。所有 API 位于 `/api/v1/memories`，主体来自已验签 JWT，管理员无私人记忆读取特权。

| 接口 | 契约 |
| --- | --- |
| GET /?after=ID | ID 顺序的元数据列表，最多 100 条；最后一个 ID 作为下一页 cursor，不返回正文 |
| GET /{id} | 重新授权后读取正文 |
| POST / | Idempotency-Key；`{"slot":"response_style","value":"concise","days":90}`，生成候选 |
| POST /{id}/confirm | `{"revision":1}` |
| PUT /{id} | `{"revision":2,"content":"detailed","days":90}`；摘要 content 为人工修订文本，最长 1000 字符 |
| DELETE /{id}?revision=N | 可在来源失权后删除；重复删除不会恢复内容 |
| POST /extractions | `{"taskId":"..."}`，返回 jobId；当前来源必须成功且有权读取 |
| POST /recall | `{"query":"之前处理的退款问题"}`，返回授权记忆、选择/排除原因及降级标记 |
| GET /settings；PUT /settings | 读取设置；`{"automaticExtraction":true}` 设置用户自动提取许可 |
| GET /jobs；POST /jobs/{id}/retry | 查询自己的作业；只重试 DEAD 作业，无后台无限重试 |
| GET /{id}/events | 读取修订和事件元数据，无历史正文副本 |
| POST /{id}/reindex | 从权威状态重建，产生新修订，旧派生答案失效；包括删除标记的重新传播 |
| POST /{id}/reauthorize | 刷新未撤销、仍可读且未换版的直接来源授权 |

重建产生修订而不是直接删除 PG tombstone；适用于索引丢失或 profile 变更后的显式重建。新模型 profile 的向量不可混排，变更 profile 应先停止索引写入，滚动更新服务后逐页重建。

## 资源和错误边界

- 每用户最多 100 条未到期候选/生效记忆，每 24 小时最多 20 个新提取任务；自动提取耗尽预算不回滚已成功业务任务，显式请求返回 MEMORY_QUOTA/429。
- Worker 每轮最多 5 个 job，90 秒租约，最多 5 次尝试，指数退避上限 300 秒；租约耗尽/错误进入 DEAD，owner 可手动重试。正文不进入队列或错误日志。
- Java 索引调用完整响应 10 秒、64 KiB 上限、不跟随重定向、不自动重试；embedding 调用 6 秒、128 KiB 上限。索引服务每租户每分钟最多 120 次请求。
- 记忆有效期 1–365 天；每分钟扫描至多 50 条到期项，读取门禁立即按时间拒绝，不等清理。权威正文先擦除，向量删除最终一致；失败作业在 jobs 中可见。PG 删除标记不随向量删除。
- 幂等请求映射保留 7 天。无正文变更事件、来源关联、删除标记和独立业务审计继续保留；备份、日志系统、底层存储页的物理擦除必须遵循部署保留策略，本版不声称跨备份即时物理删除。

## 启用与部署交接

Java：AGENT_PERSISTENCE=jdbc、MEMORY_ENABLED=true、MEMORY_INDEX_URL、MEMORY_INDEX_SECRET、DEEPSEEK_ALLOWED_TENANTS；可选 MEMORY_AUTO_EXTRACT、MEMORY_MAX_DISTANCE。RAG：RAG_MEMORY_INDEX_ENABLED=true，RAG_MEMORY_INDEX_SECRET 与 Java 的索引密钥一致，且必须不同于用户 JWT 签名密钥。Compose 已把新配置仅传给 API，不传给 Worker/Dispatcher。

索引使用专用服务凭据：HS256，issuer=workflow-memory、audience=memory-index、subject=owner、tenant、操作和原始 body SHA-256，30 秒有效期。普通用户 JWT 无法调用内部索引。生产要求 Java→索引以及索引→embedding 使用 HTTPS，内部路由应只在服务网络暴露；本地开发仅允许 Java 用 loopback HTTP。当前 Compose 仍是开发配置，不能直接当生产编排。

摘要索引写入和查询还要求对应 owner 在 RAG 中有启用的租户成员身份；删除传播不受成员禁用阻碍。RAG 不自动替 Java 创建成员。认证、限流、输入和版本冲突分别记录为 MEMORY_INDEX_AUTH/RATE_LIMIT/INPUT/CONFLICT，后台失败记录不包含正文或凭据。

部署顺序：先备份并在隔离环境验证 MySQL V9 与 PG 0005；部署索引服务并配置独立密钥/TLS；部署 Java JDBC 实例；保持功能关闭完成检查后再启用。API 启动时会检查索引表（启用时），DB 角色必须不绕过 RLS。向量表按 tenant RLS 隔离，查询同时限定 owner，活动摘要数有界，采用精确距离排序而非 ANN。

索引重试、DEAD 数量和滞留时间应接入部署监控；当前提供 owner 作业 API、结构化错误元数据和应用日志，没有交付外部告警平台/生产容量结论。密钥轮换需协调两端，短暂失败由持久队列恢复。灾备恢复后必须重放最新删除/到期记录并重建索引，不能拿过期快照直接对用户开放。

回退优先在当前版本关闭 MEMORY_ENABLED（管理接口与 Worker 同时关闭），保留 V9 和读取门禁；需处理遗忘请求时应保留启用的管理服务。不得直接回滚到不认识记忆依赖的旧二进制或删除来源表。没有执行上述部署操作。

## 当前验证状态

仅完成代码编写与源代码审阅。没有运行编译、单元/集成/UI 测试、迁移、Docker 或真实模型/embedding 联调。功能完成度和生产可用性须由后续测试记录证明；V5 的成绩不适用于 V6/V7。测试负责人可从 [测试设计](testing-v7-handoff.md) 开始，先验证高风险边界，按实际失败扩大检查范围。
