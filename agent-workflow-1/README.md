# Enterprise Workflow Agent

这是一个使用脱敏演示数据的售后流程 Agent 服务，包含 MySQL 订单/支付/物流业务库。服务端拥有执行控制权：规划结果必须经过工具白名单、参数校验、角色授权和审批门禁，DeepSeek 只能提出结构化计划，不能直接执行副作用。

## 已实现的垂直切片

- 自然语言请求解析为受约束的订单、支付、物流、工单和知识查询计划。
- `refund.create`、`order.address.update`、`shipment.create` 为真实写工具：审批通过后写入 MySQL 业务表，并通过事务性 Outbox 投递支付/物流命令；默认使用 `SandboxCommerceGateway` 模拟下游受理，不会调用真实支付或承运商接口。
- 角色 `CUSTOMER_SERVICE`、`SUPERVISOR`、`ADMIN`；只有主管或管理员可以审批高风险动作。
- JWT 资源服务器认证：用户身份取自签名令牌的 `sub`，角色取自可配置 claim；客户端提交的 `X-User-Id`/`X-Role` 不再被信任。审批还执行职责分离（申请人不能审批自己的任务）。
- 固定工具注册表拒绝未知工具；每个工具拒绝未知字段和非法值。
- 任务状态机：`CREATED -> PLANNING -> RUNNING/WAITING_APPROVAL -> SUCCEEDED/FAILED/CANCELLED`。
- 幂等创建（`Idempotency-Key`）和重复审批保护；并发重试由数据库唯一约束兜底，跨实例返回同一任务，写工具在业务表中另有唯一幂等键，重复执行会回放既有结果。
- 可选 DeepSeek 规划器：使用 OpenAI 兼容 Chat Completions 和 JSON Output；关闭时自动使用离线确定性规划器。
- 默认 `memory` 仓储用于离线测试；设置 `AGENT_PERSISTENCE=jdbc` 后使用 MySQL/Flyway 保存任务、步骤、审计事件和业务数据，启动时连接失败会直接退出（fail-closed）。
- 业务写模型：退款、改地址、创建物流单都先落本地 `PENDING` 记录和 `integration_commands`，由调度器带租约领取、调用网关并结算为 `ACKNOWLEDGED/FAILED/UNKNOWN`。

## 运行

### 独立业务系统接入（本地 Docker / 合成数据）

新增独立订单/支付服务与独立 MySQL；Agent 通过真实 HTTP 查询订单/支付并提交异步退款，包含幂等、状态轮询对账、人工队列和操作审计。支付数据均为合成数据，不涉及真实资金。

先构建两个 jar，再启动：

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17' # 按本机 Java 17+ 路径设置
.\mvnw.cmd -B -ntp package
.\mvnw.cmd -B -ntp -f business-system/pom.xml package
docker compose -p workflow-business -f compose.business.yaml up -d --build
python scripts/business_integration_acceptance.py --fault-drill
```

Agent 地址 `http://localhost:18090`，业务服务 `http://localhost:8091`。新环境与旧演示库隔离。详见 [接入说明、人工处理与流程图](docs/business-integration.md) 和 [2026-10-27 评估记录](docs/production-assessment-2026-10-27.md)。JDBC 写任务先返回 `WAITING_EXTERNAL`，只有下游确认后才成功；已受理的外部操作不能直接取消。

Docker Hub 无法下载 Java 镜像时，可改用 `python scripts/local_business_stack.py start`，再运行 `python scripts/business_integration_acceptance.py --host-jvm --fault-drill`；MySQL 仍运行在 Docker 中。

需要 Java 17+ 和 Maven。项目使用 Maven Wrapper：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17-or-newer'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

使用本机 JDK 17 时可执行：

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' test
```

健康检查：`GET http://localhost:8080/api/v1/health`；就绪检查：`GET http://localhost:8080/api/v1/health/readiness`（JDBC 模式下会校验业务库）。

### 身份认证

所有业务接口都要求 `Authorization: Bearer <JWT>`。身份和角色只来自已验证的令牌：

- `sub` 是用户 ID，写入任务、审批和审计记录。
- 角色来自 `agent.auth.roles-claim`（默认 `roles`，支持 `realm_access.roles` 这类点路径），并按 `ADMIN > SUPERVISOR > CUSTOMER_SERVICE > AGENT` 取最高角色。
- `POST /api/v1/tasks/{taskId}/approve|reject|resume` 在过滤器链和业务层都要求 `SUPERVISOR` 或 `ADMIN`。
- 审批职责分离：申请人不能用任何角色审批自己的任务，必须由另一位主管审批。

本地离线演示使用 HS256 校验令牌，并可临时开启令牌签发端点：

```powershell
$env:AGENT_AUTH_MODE = 'local'
$env:AGENT_AUTH_DEV_TOKEN_ENABLED = 'true'  # 仅本地演示，生产必须保持 false
$env:AGENT_AUTH_LOCAL_SECRET = 'local-dev-only-change-me-32-bytes-minimum'
& '.\mvnw.cmd' spring-boot:run
```

应用启动后签发令牌：

```powershell
# 签发客户服务令牌
$ALICE = (curl.exe -s -X POST http://localhost:8080/api/v1/auth/dev-token `
  -H "Content-Type: application/json" `
  -d '{"subject":"alice","role":"CUSTOMER_SERVICE"}' | ConvertFrom-Json).data.token

# 签发主管令牌
$MANAGER = (curl.exe -s -X POST http://localhost:8080/api/v1/auth/dev-token `
  -H "Content-Type: application/json" `
  -d '{"subject":"manager","role":"SUPERVISOR"}' | ConvertFrom-Json).data.token
```

生产环境设置 `AGENT_AUTH_MODE=oidc`，并提供 `AGENT_AUTH_OIDC_ISSUER_URI`（OIDC discovery）或 `AGENT_AUTH_JWK_SET_URI`（直接 JWK Set）；建议同时配置 `AGENT_AUTH_ISSUER` 和 `AGENT_AUTH_AUDIENCE` 做严格校验。缺少 OIDC 配置或本地密钥少于 32 字节时应用会拒绝启动，`AGENT_AUTH_DEV_TOKEN_ENABLED=true` 只允许在 `local` 模式生效。生产环境还应关闭 `AGENT_AUTH_DEV_TOKEN_ENABLED`，并由真实 IdP 负责令牌签发、轮换和吊销。

### 本地 MySQL + 演示数据

`compose.yaml` 提供 MySQL 8.0（宿主机端口 `3307`，避免与本机 3306 冲突）。首次启动会自动执行 Flyway `V1..V6` 并写入脱敏演示数据（`ORD-1001..1010`、支付、退款、物流、工单）：

```powershell
docker compose up -d mysql
$env:JAVA_HOME = 'F:\ayd\jdk-17'
$env:AGENT_PERSISTENCE = 'jdbc'
$env:COMMERCE_SEED_DEMO_DATA = 'true'
& '.\mvnw.cmd' spring-boot:run
```

查看数据：

```powershell
docker exec -it agent-workflow-1-mysql-1 mysql -uworkflow -pworkflow_dev_only -D workflow_agent `
  -e "select id,status,paid_amount,refunded_amount from orders order by id"
```

演示写流程（先按上一节签发 `$ALICE` 和 `$MANAGER`；退款需主管审批，审批后约 3 秒由 Outbox 调度器结算）：

```powershell
curl.exe -X POST http://localhost:8080/api/v1/tasks `
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE" `
  -H "Idempotency-Key: demo-refund-1" `
  -d '{"request":"为订单 ORD-1001 退款 20 元"}'

curl.exe -X POST http://localhost:8080/api/v1/tasks/{taskId}/approve `
  -H "Content-Type: application/json" -H "Authorization: Bearer $MANAGER" `
  -d '{"reason":"符合演示政策"}'
```

`GET /api/v1/commerce/orders/{orderId}` 返回订单、支付、物流和退款聚合视图。演示数据只用于本地验证，生产环境必须保持 `COMMERCE_SEED_DEMO_DATA=false`。

### 创建只读任务

```powershell
curl.exe -X POST http://localhost:8080/api/v1/tasks `
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE" `
  -H "Idempotency-Key: demo-1" `
  -d '{"request":"查询订单 ORD-1001 的物流"}'
```

### 审批退款

```powershell
curl.exe -X POST http://localhost:8080/api/v1/tasks/{taskId}/approve `
  -H "Content-Type: application/json" -H "Authorization: Bearer $MANAGER" `
  -d '{"reason":"符合模拟政策"}'
```

任务查询使用 `GET /api/v1/tasks/{taskId}`，审计时间线使用 `GET /api/v1/tasks/{taskId}/events`，取消使用 `POST /api/v1/tasks/{taskId}/cancel`。
恢复中断任务使用 `POST /api/v1/tasks/{taskId}/resume`，仅主管/管理员可以恢复安全的只读步骤；多轮只读 Tool Calling 使用 `POST /api/v1/agent/chat`，响应会返回可查询审计时间线的 `taskId`。

学习和演示材料：

- `docs/day-30-business-scope-threat-model.md`：Day 30 的业务范围、角色矩阵、流程和威胁控制证据。
- `docs/complete-call-chain.md`：从 HTTP 入口到规划、权限、工具、状态、持久化和审计的完整调用链，并区分当前实现与生产目标。
- `docs/production-readiness.md`：生产就绪审查、阻断项、优化方向和上线门槛。当前版本可写本地 MySQL，但支付/物流仍为沙箱网关，不能直接对接生产资金和承运商系统。
- 审计 Outbox 默认关闭发布器；启用前需要把 `AuditEventSink` 替换为真实 Kafka/SIEM/审计平台适配器。
- `docs/reading-guide.md`：按代码路径和文档 Day 顺序阅读。
- `docs/diagrams.md`：组件图、只读流程、审批时序、恢复流程和 DeepSeek 安全边界图。
- `docs/demo.md`：查询、审批、拒绝、取消、审计和提示注入演示。
- `docs/evaluation-runner.md`：逐条执行 25 条评估样例。

### 启用 DeepSeek

以 `.env.example` 为配置参考，设置 `DEEPSEEK_ENABLED=true` 和真实 `DEEPSEEK_API_KEY`；也可以直接设置进程环境变量（Spring Boot 默认不会自动读取 `.env` 文件）：

```powershell
$env:DEEPSEEK_ENABLED = 'true'
$env:DEEPSEEK_API_KEY = 'your-key'
$env:DEEPSEEK_MODEL = 'deepseek-flash'
& '.\mvnw.cmd' spring-boot:run
```

模型返回的内容必须是 `{"steps":[...]}` JSON。服务端仍会再次验证工具名、参数、角色和审批，不信任模型的权限字段。

### 生产运行基线

生产环境使用 `SPRING_PROFILES_ACTIVE=prod` 启动。该 profile 启用优雅停机、就绪探针和 fail-closed 配置门禁：缺少 JDBC、OIDC/JWK、audience，仍允许 dev token，或使用默认数据库密码时，应用拒绝启动。生产必须同时设置：

- `AGENT_PERSISTENCE=jdbc`
- `AGENT_AUTH_MODE=oidc` 及 OIDC/JWK 参数
- `AGENT_AUTH_DEV_TOKEN_ENABLED=false`
- `COMMERCE_SEED_DEMO_DATA=false`
- 非默认的 `MYSQL_PASSWORD`

恢复扫描使用 `AGENT_RECOVERY_STALE_SECONDS`（默认 120）识别实例崩溃后遗留的 `CREATED/PLANNING/RUNNING` 任务；早于阈值且仍无进展的任务转为 `RECOVERY_REQUIRED`，活跃任务不受影响。

双实例验收使用独立 schema `workflow_agent_verify`，不会触碰演示库：

```bash
python scripts/two_instance_acceptance.py
```

脚本启动 A/B 两个实例、一个第三实例和 `prod` 门禁进程，覆盖跨实例令牌、并发幂等创建、并发审批、审批/取消竞争、实例停机接管、过期任务恢复与生产门禁。证据写入 `target/two-instance/acceptance-report.json` 和各实例日志。

## 数据库与边界

`compose.yaml` 启动 MySQL 8.0 和应用容器。`src/main/resources/db/migration/V1..V6` 定义任务、步骤、结构化结果、审计、Outbox、外部退款意图、人工处理以及订单/支付/退款/物流/地址变更/工单表；`src/main/resources/db/seed/commerce-demo-data.sql` 提供可重复执行的脱敏演示数据。

`AGENT_PERSISTENCE=jdbc` 时应用使用 HikariCP + Flyway 保存任务与业务数据，连接失败会 fail-closed。退款、改地址和创建物流单会在同一事务中写业务记录与 `integration_commands`，由 `CommerceCommandDispatcher` 领取命令并调用 `CommerceGateway`；默认 `SandboxCommerceGateway` 只生成确定性单号，不访问真实资金或承运商系统。

真实接入前仍需补充：对接企业 IdP 的 OIDC 参数与令牌生命周期管理、真实支付/物流适配器（签名、超时、限流、对账）、死信/人工处理队列、生产观测与 SLO。多实例并发正确性已通过双实例验收（同幂等键、并发审批、审批/取消竞争、Outbox 唯一命令、过期任务恢复），详见 `docs/deployment-rehearsal.md` 与 `docs/production-readiness.md`。

不要把真实 API key、客户信息或生产连接串写入仓库；复制 `.env.example` 为本地环境文件后再按需设置变量。
