# ds-todolist.md — 生产化差距与上线待办

> 审查对象：`agent-workflow-1`（Spring Boot 3.5.16 / Java 17）  
> 审查日期：2026-10-27  
> 测试基线（初次审查快照）：`mvn -o -q test`，34 tests / 0 failures / 0 errors / 0 skipped（JDK 17.0.12）  
> 结论：**当前是“可演示、可测试的安全模拟执行内核”，不是可接入真实业务的生产系统。**  
> 下列 P0 全部关闭前，不应接入真实退款、改地址、支付、物流等写接口，也不应承载真实客户数据。

> **状态更新（2026-10-27 晚）：** 本文保留初次审查快照，用于对照改进前的问题。P0-1（JWT/职责分离）、P0-3（生产配置门禁）、P0-4（多实例幂等与恢复）已完成并通过双实例验收；当前基线为 56 个自动化测试 + 73 项双实例检查。最新就绪状态以 [`docs/production-readiness.md`](docs/production-readiness.md) 为准，本文各条目的“现状”描述不再逐条回写。

本文件是在阅读全部主代码、迁移脚本、配置、Docker/Compose 和测试后整理的代码级清单。  
`docs/production-readiness.md` 已有高层判断；本文件补充了该文档未列出、或文档声称已具备但代码未真正落地的问题（标 `★`），并给出可执行的验收标准。

---

## 0. 总览

| 维度 | 当前状态 | 生产结论 |
| --- | --- | --- |
| 身份认证与授权 | 直接信任 `X-User-Id` / `X-Role` | 不可上线 |
| 真实业务写操作 | 全部为模拟工具，返回 `simulation=true` | 不可上线 |
| 持久化 | 默认内存；JDBC 无连接池、无版本号 | 不可上线 |
| 多实例与故障恢复 | 无任务租约；启动恢复会误伤其他实例的任务 `★` | 不可上线 |
| 长任务执行 | 同步 HTTP + 全局 `synchronized` | 不可上线 |
| 生产配置 | 无 prod profile；默认内存；模型名疑似失效 `★` | 不可上线 |
| 数据安全与合规 | 请求/审计明文；请求原文发送第三方模型 | 不可上线 |
| 健康检查与可观测性 | `/api/v1/health` 固定 UP；无指标/Trace | 不可上线 |
| 审计投递 | Outbox 默认关闭；只有日志 Sink | 不可上线 |
| 测试与发布 | 34 个测试全绿，但全部内存模式；无 CI | 不足以验收 |

### 已经具备、后续不要回退的能力

- 服务端工具白名单、参数校验、角色检查、审批门禁、状态机。
- 模型只能提出计划，不能直接执行副作用；写工具的审批属性由服务端覆盖。
- 任务、步骤、审计事件、Outbox 的基本模型。
- 幂等创建、重复审批保护、取消、只读 checkpoint 恢复。
- 25 条离线评估样例；当前 56 个自动化测试和 73 项双实例检查通过。
- 对提示注入的基础拦截和服务端二次校验。

### 优先级定义

- **P0**：阻断上线。涉及越权、资金/数据错误、不可恢复状态、无法运维。
- **P1**：生产试点前必须完成。涉及正确性、可观测性、发布质量、人工兜底。
- **P2**：规模化、体验和长期维护完善。

---

## 1. P0 — 上线阻断项

### [ ] P0-1 身份认证、租户隔离和职责分离

**现状**

- 项目没有引入 Spring Security；`TaskController` 和 `AgentChatController` 直接读取客户端请求头。
- 任何人只要能访问服务，就能伪造 `X-Role: ADMIN` 或任意 `X-User-Id`。
- 任务表没有 `tenant_id`；主管可以读取任意用户的任务；审批不校验申请人是否等于审批人。

**代码证据**

- `src/main/java/com/yu/agent/workflow/api/TaskController.java:25`、`:34`、`:49`
- `src/main/java/com/yu/agent/workflow/api/AgentChatController.java:27`
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:204`（主管可读任意任务）
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:110`（审批只校验角色，未校验申请人）
- `src/main/resources/db/migration/V1__init.sql`（只有 `user_id`，没有 `tenant_id`）
- `pom.xml` 中没有 `spring-boot-starter-security` 或 OAuth2 Resource Server 依赖。

**风险**

- 越权执行高风险工具；跨租户读取订单、物流、工单和审计。
- 审计事件中的 actor 不可信，无法用于追责。
- 文档要求的“禁止申请人审批自己的高风险任务”在代码中没有实现 `★`。

**必须补**

1. 在 API Gateway 或 Spring Security 中验证 OIDC/JWT/mTLS，禁止业务代码读取原始角色头。
2. 建立 `AuthenticatedPrincipal`（subject、tenantId、roles、scopes），应用服务只接受该对象。
3. 任务表增加 `tenant_id`；所有查询、审批、取消、恢复、审计读取按租户过滤。
4. 审批增加职责分离：`approver != requestedBy`，并按金额/风险等级配置审批层级。
5. 认证失败、越权、跨租户访问必须返回 401/403 并写安全审计。
6. Actuator 端点纳入认证和网络隔离。

**验收标准**

- 伪造 `X-Role: ADMIN` 的请求被 401/403。
- 跨租户读取任务返回 403，不泄露任务是否存在。
- 申请人用自己的主管账号审批自己的高风险任务返回 403。
- 双实例部署下，权限判断不依赖任何 JVM 本地状态。
- 安全测试覆盖：伪造头、越权、自审批、跨租户、无 token、过期 token。

---

### [ ] P0-2 真实写工具、业务幂等和未知副作用处理

**现状**

- `refund.create`、`order.address.update` 都是模拟实现，返回 `simulation=true`。
- `ResultEvaluator` 反而强制要求写工具结果包含 `simulation=true`，接入真实工具后会被直接判定失败 `★`。
- 没有真实下游适配器、业务幂等键、状态查询、人工对账和补偿流程。

**代码证据**

- `src/main/java/com/yu/agent/workflow/tools/RefundTool.java`
- `src/main/java/com/yu/agent/workflow/tools/AddressUpdateTool.java`
- `src/main/java/com/yu/agent/workflow/application/ResultEvaluator.java:13`
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:242`（直接调用工具，没有统一超时/重试执行器）

**风险**

- 对接真实系统后，超时、部分成功、重复请求都可能造成重复退款或错误改地址。
- 进程崩溃后无法判断外部写操作是否已生效。
- `ResultEvaluator` 的模拟标记会阻止真实写工具上线。

**必须补**

1. 定义统一 `ToolExecutor`：按工具契约声明超时、可重试类别、幂等键、状态查询能力。
2. 写操作执行前持久化业务幂等键；结果分为 `RECEIVED`、`SUCCEEDED`、`FAILED`、`UNKNOWN_SIDE_EFFECT`。
3. `UNKNOWN_SIDE_EFFECT` 禁止自动重放，进入人工处理/对账队列。
4. 下游连接/读取超时、TLS/mTLS、请求签名、凭据轮换、连接池、限流、熔断。
5. 至少一个写工具提供“按幂等键查询最终状态”的接口，用于对账。
6. 设计补偿/冲正方案，并明确哪些操作不可补偿。
7. 灰度：按租户和工具 allowlist 放开，默认 fail closed。
8. 修改 `ResultEvaluator`，从“要求 simulation 标记”改为“验证真实业务结果 + 幂等证据”。

**验收标准**

- 同一幂等键重复请求只产生一次外部副作用。
- 下游超时后任务进入 `UNKNOWN_SIDE_EFFECT`，不会自动再次调用写接口。
- 对账任务能根据外部状态关闭或升级人工处理。
- 工具级故障注入（超时、500、429、部分成功）都有测试和处置记录。
- 真实写工具在沙箱环境完成一次端到端、重复请求和进程崩溃演练。

---

### [ ] P0-3 持久化默认值、连接池、事务边界和并发正确性

**现状**

- 默认 `AGENT_PERSISTENCE=memory`，生产误配置会静默丢数据。
- JDBC 使用 `DriverManagerDataSource`，没有连接池、超时、泄漏检测。
- `tasks` 表没有 `version` 字段，审批/状态更新没有乐观锁。
- `JdbcTaskRepository.require` 对普通读取也使用 `SELECT ... FOR UPDATE`，且在事务外调用 `★`。
- `JdbcTaskRepository.save` 每次先 `delete task_steps` 再全量插入；每个审计事件都会触发一次全量重写 `★`。
- `create` 没有事务边界；`approve` 把数据库事务包住了外部工具调用 `★`。
- 跨实例创建同一幂等键时，唯一约束冲突会变成 500，而不是幂等返回或 409 `★`。

**代码证据**

- `src/main/resources/application.properties:16`
- `src/main/java/com/yu/agent/workflow/persistence/JdbcPersistenceConfiguration.java:17-28`
- `src/main/java/com/yu/agent/workflow/persistence/JdbcTaskRepository.java:37-59`、`:62-78`
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:63`（`create` 无 `@Transactional`）
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:109-126`（`approve` 事务内执行工具）
- `src/main/java/com/yu/agent/workflow/application/TaskAuditWriter.java:35-40`

**风险**

- 多实例并发审批/取消/恢复可能丢失更新。
- 每个请求新建数据库连接，连接数和延迟不可控。
- 长事务持有行锁和连接，外部调用超时会拖垮数据库。
- 任务步骤被反复删除重建，审计快照可能丢失或被并发覆盖。
- 生产误用内存模式导致重启后任务和审计全部消失。

**必须补**

1. 生产 profile 强制 `jdbc`，启动时缺少数据库配置直接失败；禁止静默回退 memory。
2. 引入 HikariCP，配置最大连接数、最小空闲、连接超时、泄漏检测、验证查询。
3. `tasks` 增加 `version`，使用 `WHERE id = ? AND version = ?` 乐观锁；或只在写事务中使用 `SELECT ... FOR UPDATE`。
4. 拆分 `require` 和 `requireForUpdate`，普通查询不再加锁。
5. `create` 只把“任务创建 + 幂等绑定 + 初始审计”放进短事务；模型规划不要包在事务里。
6. `approve` 使用短事务完成状态迁移和出站命令，外部写操作交给 Worker；不要在数据库事务中做 HTTP 调用。
7. `task_steps` 改为追加/按 stepId upsert，增加 `unique (task_id, sequence_no)`；不要每次全量 delete 重建。
8. 捕获唯一约束冲突并转换为幂等回放或 409，不得落成 500。
9. 增加真实 PostgreSQL（Testcontainers）集成测试：双实例创建、双实例审批、并发取消、连接池耗尽。

**验收标准**

- 生产配置缺少 JDBC 时应用启动失败。
- 两个实例同时审批同一任务，只有一个成功，另一个得到明确的 `CONFLICT` 或原始终态。
- 两个实例用同一幂等键创建，只产生一个任务，双方都得到同一结果。
- 数据库事务期间不出现外部 HTTP 调用。
- 连接池指标可见，压测下无连接泄漏。

---

### [ ] P0-4 多实例恢复、任务租约和滚动发布

**现状**

- `RecoveryService` 在应用启动时把所有 `RUNNING` 任务直接改成 `RECOVERY_REQUIRED`。
- 没有任务所有者、租约、心跳或 worker 归属。
- 多实例滚动发布时，新实例启动会把其他实例正在执行的任务标记为恢复失败 `★`。
- 只扫描 `RUNNING`；崩溃在 `CREATED`、`PLANNING`、`WAITING_APPROVAL` 的任务会永久卡住 `★`。

**代码证据**

- `src/main/java/com/yu/agent/workflow/application/RecoveryService.java:37-43`
- `src/main/java/com/yu/agent/workflow/application/RecoveryService.java:39`（只查 `RUNNING`）
- `src/main/resources/db/migration/V1__init.sql`（`tasks` 无 `owner_instance`、`lease_until`、`heartbeat_at`）

**风险**

- 一次正常的滚动发布会打断所有在途任务。
- 卡在 `PLANNING` 的任务没有超时清理，占用审批/人工资源。
- 无法判断任务是“正在执行”还是“实例已死”。

**必须补**

1. 任务增加 `owner_instance`、`lease_until`、`heartbeat_at`。
2. Worker 定期续租；恢复只处理租约已过期的任务。
3. 启动恢复不扫描所有 `RUNNING`，只扫描 `lease_until < now()` 的孤儿任务。
4. 增加 sweeper：超时的 `CREATED` / `PLANNING` / `WAITING_APPROVAL` / `RUNNING` 进入明确的失败或人工处理状态。
5. 恢复审计记录发现时间、实例、步骤、外部副作用未知标记。
6. 停止/发布流程先把实例标记为 draining，停止领取新任务。

**验收标准**

- 双实例滚动发布时，活跃任务不会被另一个实例误标恢复。
- 杀死一个 Worker 后，租约过期才允许接管。
- 卡在 `PLANNING` 的任务在超时后自动进入可观测的失败/人工状态。
- 恢复流程不会自动重放任何未知写操作。

---

### [ ] P0-5 同步长请求、全局锁和容量

**现状**

- `POST /api/v1/tasks` 在 HTTP 请求内完成模型规划、工具执行和状态落库。
- `create` 方法加了 `synchronized`，并且模型调用和工具执行都在锁内 `★`。
- 单个模型调用最长 30 秒并可重试；一个请求可能远超网关超时。
- deadline 只在步骤之间检查，模型规划和单个工具执行本身没有硬超时 `★`。
- 没有队列、Worker、202 Accepted、取消令牌、死信队列。

**代码证据**

- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:63`（`synchronized`）
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:80`（锁内调用模型）
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:229-237`（只在步骤间检查 deadline）
- `src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java:242`（单工具无超时）
- `src/main/java/com/yu/agent/workflow/application/DeepSeekTaskPlanner.java:58-67`（连接/读取超时，但整体规划无 deadline）

**风险**

- 所有创建请求被串行化；一个慢模型调用会阻塞全部新任务。
- Tomcat 线程被外部调用占满，服务整体不可用。
- 客户端断开不代表服务端停止执行，可能继续产生副作用。
- 网关超时后客户端重试，可能放大模型成本和写操作风险。

**必须补**

1. `POST /tasks` 改为“写入任务 + 投递命令 + 返回 202 + taskId”。
2. 独立 Worker 领取任务并执行规划/审批后的动作；审批也作为独立命令。
3. 移除 `synchronized`；并发控制交给数据库版本/租约。
4. 端到端 deadline：规划、模型、工具、重试、排队时间全部计入。
5. 工具调用增加超时和取消；客户端断开触发取消信号。
6. 队列积压、Worker 数量、线程池隔离、背压和限流。
7. 压测：模型 P95 延迟、工具 P95、任务吞吐、线程池饱和、队列积压。

**验收标准**

- 慢模型调用不会阻塞其他任务创建。
- 网关超时后，服务端能取消或明确继续执行，并有审计。
- 压测下错误率、P95/P99、队列深度满足既定 SLO。
- 任务可以从任意阶段被安全取消。

---

### [ ] P0-6 生产配置、模型名和密钥管理

**现状**

- 没有 `application-prod.properties` 或 profile 校验；默认内存、默认开发密码。
- 默认模型名为 `deepseek-flash`，不是当前 DeepSeek 官方文档中的模型标识。2026 年官方模型为 V4 Flash / V4 Pro，旧 `deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 停用 `★`。
- API key 通过环境变量注入；Compose 使用明文开发密码；Actuator 无认证。
- 没有启动时的配置完整性校验（例如生产环境必须 JDBC、必须 TLS、必须密钥）。

**代码证据**

- `src/main/resources/application.properties:12`（`deepseek-flash`）
- `src/main/java/com/yu/agent/workflow/application/DeepSeekTaskPlanner.java:53`
- `src/main/java/com/yu/agent/workflow/application/DeepSeekToolCallingGateway.java:35`
- `.env.example:12`
- `compose.yaml:10-11`、`:22-23`（开发密码）
- `Dockerfile:1`（基础镜像未固定 digest）

**风险**

- 模型名错误会导致所有模型调用 400/404，生产直接不可用。
- 生产误用内存模式、开发密码或未加密连接。
- 密钥泄漏、无法轮换、无审计。

**必须补**

1. 增加 `application-prod.properties`，生产 profile 强制 JDBC、TLS、密钥、非默认密码。
2. 按官方当前模型列表修正并固定模型标识；启动时做一次轻量连通性校验或明确失败。
3. 密钥进入 Vault/KMS/Secret Manager；Compose 密码只用于本地。
4. 生产禁用 Actuator 敏感端点或加认证；关闭 `/actuator/env` 等。
5. 固定基础镜像 digest、依赖版本、模型版本；记录在发布清单。
6. 配置项集中到 `@ConfigurationProperties`，启动时校验非法组合。

**验收标准**

- 生产 profile 缺少 JDBC/密钥/模型配置时启动失败。
- 模型名与官方当前文档一致，冒烟调用成功。
- 仓库和镜像中不存在生产密码/密钥。
- 配置变更可审计、可回滚。

---

### [ ] P0-7 数据安全、PII 和合规

**现状**

- 用户请求原文直接发送给 DeepSeek；没有脱敏、最小化、数据出境评估。
- 任务、步骤、审计事件、Outbox 都存明文；没有字段级加密、保留期、删除策略。
- 审计日志没有防篡改（哈希链/WORM）和独立访问控制。
- 没有租户隔离（见 P0-1）。

**代码证据**

- `src/main/java/com/yu/agent/workflow/application/DeepSeekTaskPlanner.java:77-79`（system + 原始 request）
- `src/main/java/com/yu/agent/workflow/application/DeepSeekToolCallingService.java:40-42`（原始 request 进入消息）
- `src/main/resources/db/migration/V1__init.sql`（`request text`、`message text` 明文）
- `src/main/java/com/yu/agent/workflow/application/LoggingAuditEventSink.java`（仅本地日志）

**风险**

- 客户手机号、地址、订单信息进入第三方模型和日志，违反数据最小化/合规要求。
- 数据泄漏后无法定位、删除或证明未篡改。
- 审计数据长期增长，无归档和保留策略。

**必须补**

1. 与模型供应商完成 DPA/数据驻留/保留期评估；必要时使用私有化模型。
2. 模型调用前做 PII 识别和脱敏；只发送完成任务所需字段。
3. 日志、审计、Outbox 统一脱敏；禁止记录密钥、完整地址、支付信息。
4. 数据库/备份加密；敏感字段 tokenization 或字段级加密。
5. 审计防篡改（哈希链、WORM 存储或 SIEM 不可变留存）和分级访问控制。
6. 数据保留/归档/删除策略，支持数据主体删除请求。
7. 明确哪些数据可以进入模型上下文，哪些只能留在受控存储。

**验收标准**

- PII 扫描显示模型请求、日志、审计中无未脱敏敏感字段。
- 删除请求可以在任务、步骤、审计、Outbox、备份保留策略中闭环。
- 审计数据可验证未被篡改，访问有审批和记录。
- 合规/安全评审签字。

---

### [ ] P0-8 健康检查、可观测性和告警

**现状**

- `/api/v1/health` 固定返回 UP，不检查数据库、模型、下游。
- 没有 readiness/liveness 探针组；Actuator 只暴露 health/info。
- 没有 Micrometer Prometheus、OpenTelemetry、结构化日志、MDC traceId 传播。
- `GlobalExceptionHandler` 对未知异常不记录日志 `★`。
- 没有 SLO、告警、值班看板。

**代码证据**

- `src/main/java/com/yu/agent/workflow/api/HealthController.java:14`
- `src/main/resources/application.properties:3-4`
- `src/main/java/com/yu/agent/workflow/api/GlobalExceptionHandler.java:44-48`
- `pom.xml` 无 `micrometer-registry-prometheus`、`micrometer-tracing`、`opentelemetry`、日志 JSON encoder。

**风险**

- 数据库已挂但健康检查仍显示 UP，流量继续打入。
- 故障只能靠用户投诉发现。
- 无法定位慢模型、慢工具、审批等待、Outbox 积压。
- 未知异常没有日志，线上无法排障。

**必须补**

1. `/actuator/health/readiness` 检查数据库、Flyway、关键下游；`/actuator/health/liveness` 只检查进程。
2. 删除或改造固定 UP 的 `/api/v1/health`，避免误导。
3. Micrometer + Prometheus 指标：请求量、错误率、延迟、模型 token/成本、工具延迟、审批等待、队列深度、Outbox lag、DLQ。
4. OpenTelemetry trace/span，traceId 写入日志 MDC 并透传下游。
5. 结构化 JSON 日志、PII/密钥脱敏、错误堆栈记录。
6. SLO/错误预算、告警规则、值班看板、告警路由。
7. 增加合成监控和关键路径黑盒探针。

**验收标准**

- 数据库不可用时 readiness 返回 DOWN，liveness 仍正常。
- 每个任务可以通过 traceId 串起 HTTP、模型、工具、数据库和审计。
- 告警能在故障注入后按阈值触发。
- 线上 P95/P99、错误率、成本有看板。

---

### [ ] P0-9 审计 Outbox 闭环

**现状**

- `AuditOutboxPublisher` 默认关闭；唯一 Sink 是本地日志。
- 没有真实 Kafka/SIEM/审计平台适配器。
- 发布语义是至少一次；`markPublished` 在 Sink 调用之后，崩溃窗口会重复投递 `★`。
- 没有最大重试次数、死信队列、人工重放、积压告警和保留策略。

**代码证据**

- `src/main/java/com/yu/agent/workflow/application/AuditOutboxPublisher.java:21`（默认关闭）
- `src/main/java/com/yu/agent/workflow/application/AuditOutboxPublisher.java:51-64`（发布后标记）
- `src/main/java/com/yu/agent/workflow/application/LoggingAuditEventSink.java`
- `src/main/java/com/yu/agent/workflow/persistence/JdbcAuditOutboxRepository.java:95-115`（`attempts` 无上限）

**风险**

- 审计事件没有真正离开应用，合规审计不可用。
- Sink 故障时无限重试，表持续膨胀。
- 重复投递导致下游重复告警/重复工单。
- 进程崩溃后无法确认哪些事件已投递。

**必须补**

1. 接入真实 AuditEventSink（Kafka/SIEM/审计平台），启用 publisher。
2. 下游按 `eventId` 幂等消费，或提供去重表。
3. 最大重试次数 + 死信表/死信队列 + 人工重放工具。
4. Outbox 积压、失败率、最老未发布事件告警。
5. 发布器多实例租约已具备，补真实并发集成测试。
6. 审计事件/Outbox 的归档、保留、分区和清理策略。

**验收标准**

- Sink 宕机恢复后，事件最终全部投递且无重复副作用。
- 毒消息进入 DLQ，可人工查看和重放。
- Outbox lag 可观测、可告警。
- 审计平台可检索任务全链路。

---

## 2. P1 — 生产试点前必须完成

### [ ] P1-1 API 输入边界、幂等语义和契约

**问题**

- 请求头长度没有校验：`user_id` 限 128、`trace_id` 限 64、`idempotency_key` 限 256，超长会直接触发数据库错误变成 500 `★`。
- `Idempotency-Key` 传入空字符串时，`create` 不当作空值处理，JDBC 唯一约束会把第二个空 key 任务打成 500 `★`。
- 同一幂等键配不同请求体，会静默返回旧任务，而不是 409 或重放校验 `★`。
- 事件时间线无分页/上限，任务事件多时响应体不可控。
- OpenAPI 没有 `securitySchemes`，也没有错误码全集；没有契约测试。
- 没有请求体大小、速率限制、`429 Retry-After`。

**必须补**

- 统一规范化并校验所有 header；空字符串转 null；超长返回 400。
- 幂等记录保存请求体哈希；同 key 不同 body 返回 409。
- 将 `DataIntegrityViolationException` 映射为幂等回放或 409，并记录日志。
- 事件查询增加 `limit/cursor`；默认上限。
- OpenAPI 补安全方案、错误模型、示例；增加契约测试或改用 springdoc 生成。
- 增加限流、配额、请求大小限制和 429 响应。
- 明确业务错误码到 HTTP 状态码的映射；不要把所有业务错误都落 400。

**验收**

- 空/超长 header、同 key 不同 body、重复并发请求都有明确的 4xx/幂等结果。
- OpenAPI 与实际接口一致，CI 可校验。
- 事件查询有分页，响应体有上限。

---

### [ ] P1-2 模型、RAG 和聊天生产化

**问题**

- `/agent/chat` 每次请求创建新任务，没有会话历史、上下文窗口、会话存储。
- 没有 token/成本上限、租户配额、模型降级、内容审核。
- 没有人工接管/转人工入口；没有“无证据时明确拒答”的完整产品流程。
- RAG 是本地词法检索，没有真实文档源、租户 ACL、文档版本、删除传播、检索超时。
- 提示注入防护是关键词黑名单，只能做纵深防御，不能作为主控制。
- 没有线上模型评估、红队、prompt/模型版本登记和回归门槛。

**必须补**

- 会话历史存储、上下文裁剪、token 预算、每租户成本配额。
- 人工接管、转人工队列、拒答/低置信度升级。
- 真实知识库接入、ACL 同步、版本/删除传播、引用校验、检索超时。
- 模型/prompt 版本登记，任何升级先跑评估集和安全回归。
- 线上模型质量、延迟、成本、拒答率、越权阻断率看板。
- 内容审核和输出过滤；模型供应商故障时的降级/停用开关。

**验收**

- 多轮对话能保持上下文，但不会跨任务/跨租户泄漏。
- 单用户/单租户成本可控，超限返回明确错误。
- 无证据问题不会编造政策，能转人工。
- 模型升级有评估报告和回滚开关。

---

### [ ] P1-3 Checkpoint、审批生命周期和人工兜底

**问题**

- `Checkpoint` 只是执行循环中的对象，主要靠 `CHECKPOINT_SAVED` 事件表达；没有独立 `task_checkpoints` 表和版本 `★`。
- 写操作执行前没有持久化业务幂等键和“即将执行”状态。
- `WAITING_APPROVAL` 没有过期、提醒、升级、审批队列。
- 没有统一的工具重试执行器；`RetryPolicy` 主要用于模型请求。
- 没有针对卡在 `PLANNING`/`WAITING_APPROVAL` 任务的清理器。

**必须补**

- `task_checkpoints` 表：任务、步骤、版本、业务幂等键、外部状态、创建时间。
- 写工具执行前持久化 `ATTEMPTING` 和幂等键；返回未知进入 `UNKNOWN_SIDE_EFFECT`。
- 审批 TTL、提醒、升级、审批队列、拒绝理由必填（高风险）。
- `ToolExecutor` 按工具契约统一超时/重试/熔断/幂等。
- 卡单 sweeper 和人工处理队列。
- 补偿/冲正/对账流程。

**验收**

- 进程在写操作前/后崩溃，都能从持久化 checkpoint 判断下一步。
- 审批超时后自动升级或取消，不会无限等待。
- 非幂等写工具不会被自动重试。
- 人工处理队列可查询、可分配、可审计。

---

### [ ] P1-4 数据库 schema 和运维能力

**问题**

- `tasks` 没有 `tenant_id`、`version`、`owner_instance`、`lease_until`。
- 状态、角色等字段是普通 varchar，没有 check constraint；JSON 列没有 schema 校验。
- `task_steps` 没有 `(task_id, sequence_no)` 唯一约束。
- 缺少 `tasks(status)`、`tasks(user_id, created_at)` 等查询索引。
- 没有备份/PITR、恢复演练、RPO/RTO、HA/只读副本、分区/归档。
- Flyway 迁移没有回滚/前滚演练；多实例启动迁移策略未验证。

**必须补**

- 补字段、约束、唯一索引、查询索引、`updated_at` 触发器或应用统一维护。
- 审计/Outbox 按时间分区，制定保留和归档策略。
- 备份、PITR、恢复演练、RPO/RTO 目标。
- 生产 HA、连接数规划、慢查询监控。
- 迁移前滚/回滚策略；大表迁移使用在线变更。

**验收**

- 从备份可以恢复到指定时间点，演练记录可查。
- 关键查询有索引，压测无慢查询。
- 迁移在真实 PostgreSQL 和多实例环境验证。
- 数据保留/归档策略执行有监控。

---

### [ ] P1-5 测试、CI 和发布质量

**现状**

- 34 个测试全部通过，但全部是内存模式；没有任何 JDBC/PostgreSQL 集成测试 `★`。
- 没有 Testcontainers、没有双实例并发、没有真实故障注入、没有压测、没有安全测试。
- 没有 CI 配置（无 `.github/workflows` 等）、没有静态分析、覆盖率门槛、依赖漏洞扫描、SBOM、镜像扫描。
- 评估集是 25 条离线确定性样例，不能代表线上模型质量。

**必须补**

- Testcontainers PostgreSQL：Flyway、JDBC Repository、Outbox、恢复、事务边界。
- 双实例并发测试：创建幂等、审批、取消、恢复、滚动发布。
- 故障注入：数据库断开、模型超时/429/5xx、工具 500/超时、进程 kill、重复消息、时钟偏移。
- 压测/浸泡测试：吞吐、P95/P99、连接池、线程池、成本。
- 安全测试：伪造头、越权、跨租户、自审批、注入、PII 泄漏。
- CI：编译、测试、静态分析、覆盖率、依赖扫描、SBOM、镜像扫描、部署/回滚。
- 线上模型评估：样本量、延迟、成本、拒答率、越权阻断率、回归阈值。

**验收**

- CI 红灯阻止合并；覆盖率/静态分析有门槛。
- JDBC 和双实例测试成为发布必跑项。
- 故障演练有报告和修复闭环。
- 发布包有 SBOM 和漏洞扫描结果。

---

### [ ] P1-6 部署、容器和供应链

**问题**

- Dockerfile 单阶段、root 用户、基础镜像未固定 digest、无 healthcheck、无 JVM 容器参数 `★`。
- Compose 使用开发密码、暴露 PostgreSQL 端口、无资源限制/重启策略/日志驱动。
- 没有 Kubernetes/Helm/IaC；没有优雅停机、readiness gating、PDB、网络策略。
- 没有镜像签名、SBOM、制品仓库、回滚自动化。

**必须补**

- 多阶段构建、非 root、固定 digest、`HEALTHCHECK`、`-XX:MaxRAMPercentage`、只读根文件系统。
- 生产用 K8s/Helm 或等效平台：requests/limits、探针、HPA、PDB、NetworkPolicy、Secret。
- `server.shutdown=graceful` 和停机超时；发布前 draining。
- 镜像签名、SBOM、漏洞扫描、制品仓库、版本 tag、回滚脚本。
- 环境分离（dev/staging/prod）和配置管理。

**验收**

- 容器以非 root 运行，健康检查生效。
- 滚动发布不丢请求、不误伤在途任务。
- 镜像有签名和 SBOM，漏洞扫描通过策略。
- 回滚在演练环境验证。

---

### [ ] P1-7 运维手册、值班和灾难恢复

**问题**

- 没有针对数据库不可用、模型不可用、Outbox 积压、DLQ、未知副作用、卡单、回滚、恢复的值班手册。
- 没有 on-call、告警路由、升级路径、事故复盘流程。
- 没有容量规划、成本预算、SLO 评审和灾备演练。

**必须补**

- Runbook：每种故障的检测、止血、恢复、验证、复盘步骤。
- On-call 轮值、告警分级、升级矩阵、联系人。
- 事故流程、复盘模板、行动项跟踪。
- 灾备演练：数据库恢复、区域故障、模型供应商故障。
- 容量/成本基线，按季度评审。

**验收**

- 值班人员能在无原作者情况下按手册处理 P0 故障。
- 每季度至少一次故障演练和一次恢复演练。
- 事故复盘行动项有闭环。

---

### [ ] P1-8 审批运营和人机协同

**问题**

- 审批只有 API，没有审批队列、提醒、SLA、升级。
- 没有金额/风险等级对应的多级审批。
- 聊天没有人工接管；未知副作用没有人工处理界面。
- 审批理由可选，高风险场景证据不足。

**必须补**

- 审批队列/工作台、待办提醒、超时升级、SLA 指标。
- 按金额/风险配置审批层级和职责分离。
- 高风险理由必填，保留审批证据。
- 聊天转人工、未知副作用处理队列、对账结果回写。
- 审批操作全链路审计和导出。

**验收**

- 高风险任务不会因无人审批无限挂起。
- 审批人、理由、证据、时间可完整追溯。
- 人工接管和未知副作用处理有明确 SLA。

---

## 3. P2 — 规模化、体验和长期维护

### [ ] P2-1 API 与产品体验

- 聊天支持 SSE/流式输出，降低首字延迟。
- 增加任务列表、审批列表、审计导出、事件分页。
- 审批/取消支持 `ETag`/`If-Match` 或版本号，避免覆盖。
- 提供 SDK/OpenAPI 示例、错误码文档、幂等键使用指南。
- 明确 API 版本和废弃策略。

### [ ] P2-2 代码质量与可维护性

- 校验计划步骤 `sequence` 唯一且递增；当前没有校验 `★`。
- 抽取 `ToolExecutor`，避免执行循环继续膨胀。
- 配置项改为 `@ConfigurationProperties`，减少散落的 `@Value`。
- 业务错误码改为枚举，补全 HTTP 映射。
- 清理测试中手工 `new TaskAuditWriter(...)` 的旁路构造，统一依赖注入。
- 引入 ArchUnit/Checkstyle/SpotBugs/PMD，防止分层和边界退化。
- 评估 `synchronized`、`Thread.sleep` 重试、重复工具名转换等实现细节。

### [ ] P2-3 文档与一致性

- `docs/release-notes.md` 写“25 个自动化测试”，当前实际 34 个，需更新 `★`。
- `docs/deployment-rehearsal.md` 只写 Flyway V1/V2，实际已有 V3/V4，需更新 `★`。
- `docs/fault-matrix.md` 声称“数据库不可用会 readiness/startup 失败”，但当前健康检查固定 UP、且默认可回退 memory，声明未落地 `★`。
- OpenAPI 缺少认证方案、错误码全集和实际响应示例。
- `pom.xml` 的 license/developer/scm 为空；版本仍是 `0.0.1-SNAPSHOT`，需制定发布版本策略。

### [ ] P2-4 可观测性和成本精细化

- 增加模型 token/成本、RAG 命中率、拒答率、审批等待、工具失败分类看板。
- 增加 Outbox lag、DLQ、队列深度、连接池饱和、Tomcat 线程饱和告警。
- 增加合成监控和关键租户探针。
- 建立成本预算、按租户/工具分摊和异常成本告警。

### [ ] P2-5 容器与平台完善

- 使用 distroless/精简镜像，减少攻击面。
- 配置 JVM 内存、GC、日志轮转、只读根文件系统。
- 增加 NetworkPolicy、资源配额、优先级和驱逐策略。
- 建立镜像保留/清理和制品生命周期策略。

### [ ] P2-6 模型与知识治理

- 将 `ContextBuilder` 真正接入规划链，明确 token 预算和截断策略。
- 登记模型、prompt、工具 schema 版本，支持灰度和回滚。
- 增加模型输出质量抽检、人工反馈和回归集。
- 评估确定性规划器的覆盖率和兜底体验。
- 知识库增加文档版本、引用有效性、删除传播和检索质量指标。

---

## 4. 上线门槛总表

以下条件全部满足，才建议进入生产试点。

### 安全与权限

- [ ] 生产身份由 OIDC/JWT/mTLS 验证，应用不信任客户端角色头。
- [ ] 租户隔离在数据库、查询、审批、审计全链路生效。
- [ ] 职责分离：申请人不能审批自己的高风险任务。
- [ ] Actuator、审计、管理接口有认证和网络隔离。
- [ ] 无生产密钥/密码进入仓库、镜像、日志。

### 数据与写操作

- [ ] 生产强制 JDBC，禁止静默回退内存。
- [ ] 连接池、事务边界、版本/乐观锁、幂等唯一约束经过真实 PostgreSQL 验证。
- [ ] 至少一个真实写工具具备业务幂等键、状态查询、未知副作用处理和人工对账。
- [ ] 写操作不会在数据库事务中调用外部 HTTP。
- [ ] 数据脱敏、保留、删除、加密和审计防篡改方案通过评审。

### 多实例与可用性

- [ ] 任务租约/心跳/所有者机制上线，滚动发布不误伤在途任务。
- [ ] 卡在 `CREATED`/`PLANNING`/`WAITING_APPROVAL`/`RUNNING` 的任务有超时清理。
- [ ] 同步长请求改为 202 + Worker，或证明当前同步模式满足 SLO。
- [ ] 双实例创建、审批、取消、恢复、故障接管测试通过。
- [ ] 数据库、模型、工具故障注入演练通过。

### 可观测与运维

- [ ] liveness/readiness 真实反映依赖状态。
- [ ] 指标、Trace、结构化日志、PII 脱敏、告警和 SLO 上线。
- [ ] 审计 Outbox 接入真实 Sink，具备幂等、DLQ、重放和积压告警。
- [ ] 值班手册、告警路由、升级路径、事故复盘流程就绪。
- [ ] 备份/PITR、恢复演练、RPO/RTO 达标。

### 发布质量

- [ ] CI 包含构建、测试、静态分析、覆盖率、依赖/镜像扫描。
- [ ] 真实 PostgreSQL/Testcontainers、双实例、故障注入、压测成为必跑项。
- [ ] 模型线上评估、红队、成本/延迟/拒答/越权指标达标。
- [ ] 发布说明列明已实现/未实现能力、已知限制、回滚步骤和人工处理路径。
- [ ] 发布包有 SBOM、镜像签名和回滚自动化。

---

## 5. 建议实施顺序

### 阶段 A：先做到“可控试点”

1. 接入 OIDC/JWT，移除 `X-User-Id`/`X-Role`；补租户和职责分离。
2. 生产 profile 强制 JDBC；引入 HikariCP；补 `version`、事务边界和跨实例幂等。
3. 修正模型名；补 PII 脱敏、密钥管理和配置校验。
4. 补 readiness/liveness、结构化日志、指标、Trace 和异常日志。
5. 接入真实审计 Sink，启用 Outbox，补 DLQ 和积压告警。
6. 补输入校验、幂等语义、分页和限流。

### 阶段 B：小流量真实写操作

1. 选择一个有明确幂等键和状态查询的写工具，接沙箱。
2. 实现 `ToolExecutor`、`UNKNOWN_SIDE_EFFECT`、对账和人工队列。
3. 引入任务租约和 Worker；把长任务改为 202 + 队列。
4. 完成双实例、滚动发布、进程崩溃、数据库中断、模型超时、工具 500 演练。
5. 按租户/工具 allowlist 灰度；默认 fail closed。

### 阶段 C：扩大范围

1. 扩大工具和角色前，更新权限矩阵、威胁模型和评估集。
2. 每个真实工具补契约测试、幂等证明、补偿/回滚说明。
3. 以 SLO、成本、越权阻断率、人工介入率作为发布门槛。
4. 完善聊天会话、人工接管、RAG 治理和模型版本管理。

---

## 6. 最值得先做的 10 个改动

1. **加 Spring Security + OIDC/JWT**，彻底移除客户端角色头；同时加租户和自审批拦截。
2. **生产 fail-fast + HikariCP + version 乐观锁**，并拆分读/写 `require`。
3. **修 `RecoveryService`**：按租约恢复，启动时不要扫描所有 `RUNNING`。
4. **移除 `create` 的全局 `synchronized`**，改为 202 + Worker + 数据库并发控制。
5. **修正模型名**（按官方当前 V4 模型标识），并加 PII 脱敏和模型调用预算。
6. **把 `/api/v1/health` 改成真实 readiness**，补 Micrometer/OTel/JSON 日志。
7. **接真实审计 Sink**，补 Outbox 幂等、最大重试、DLQ 和积压告警。
8. **补输入边界**：header 长度、空幂等键、请求体哈希、`DataIntegrityViolation` 映射。
9. **加 Testcontainers + 双实例并发 + 故障注入测试**，让 JDBC 路径真正被覆盖。
10. **硬化 Dockerfile/Compose**：非 root、固定 digest、healthcheck、资源限制、无开发密码。

---

## 附录：本次审查证据

- 测试命令：`JAVA_HOME=/f/ayd/jdk-17 <cached-maven>/bin/mvn -o -q test`
- 结果：34 tests / 0 failures / 0 errors / 0 skipped（Surefire 报告时间 2026-10-27 14:11）。
- Maven Wrapper 3.10.0 因网络无法下载，本次使用本机缓存的 Maven 3.9.16；这不影响结论，但说明构建环境需要固定/镜像化。
- 已核对：`pom.xml`、`application.properties`、全部 `src/main/java`、V1-V4 迁移、`Dockerfile`、`compose.yaml`、`.env.example`、34 个测试类。
- 未完成：真实 Docker Compose 启动、真实 PostgreSQL 集成、双实例并发、压测、线上模型调用、合规评审。

### 关于 DeepSeek 模型名

当前默认值 `deepseek-flash` 与 2026 年 DeepSeek 官方模型标识不一致。官方资料显示 V4 系列为 V4 Flash / V4 Pro，旧 `deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 停用。上线前必须按账户可用的官方模型列表核实并固定，避免所有模型请求 400/404。

参考：

- [DeepSeek API 上线 V4-Pro 与 V4-Flash，旧模型名将停用](https://aihot.virxact.com/items/hywyfyjmyyxtexdsttsl4mive)
- [deepseek-chat / deepseek-reasoner 7 月 24 日停用：改一行迁移到 V4 Flash / Pro](https://ofox.io/zh/blog/deepseek-chat-reasoner-retire-migrate-v4-2026/)
