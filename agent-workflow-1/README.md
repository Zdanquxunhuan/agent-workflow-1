# Enterprise Workflow Agent

这是一个只使用脱敏模拟数据的售后流程 Agent 服务。服务端拥有执行控制权：规划结果必须经过工具白名单、参数校验、角色授权和审批门禁，DeepSeek 只能提出结构化计划，不能直接执行副作用。

## 已实现的垂直切片

- 自然语言请求解析为受约束的订单、物流、工单和知识查询计划。
- `refund.create` 与 `order.address.update` 为模拟高风险工具，默认进入 `WAITING_APPROVAL`，审批前副作用计数始终为 0。
- 角色 `CUSTOMER_SERVICE`、`SUPERVISOR`、`ADMIN`；只有主管或管理员可以审批高风险动作。
- 固定工具注册表拒绝未知工具；每个工具拒绝未知字段和非法值。
- 任务状态机：`CREATED -> PLANNING -> RUNNING/WAITING_APPROVAL -> SUCCEEDED/FAILED/CANCELLED`。
- 幂等创建（`Idempotency-Key`）和重复审批保护；任务事件可按时间线查询。
- 可选 DeepSeek 规划器：使用 OpenAI 兼容 Chat Completions 和 JSON Output；关闭时自动使用离线确定性规划器。
- 当前仓库实现为可替换的内存 Repository，Flyway/PostgreSQL schema 已提供，便于下一阶段切换到 durable repository。
- 设置 `AGENT_PERSISTENCE=jdbc` 后，应用使用 PostgreSQL/Flyway 保存任务、步骤和审计事件；默认 `memory` 便于离线开发。

## 运行

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

健康检查：`GET http://localhost:8080/api/v1/health`。

### 创建只读任务

```powershell
curl.exe -X POST http://localhost:8080/api/v1/tasks `
  -H "Content-Type: application/json" -H "X-User-Id: alice" `
  -H "X-Role: CUSTOMER_SERVICE" -H "Idempotency-Key: demo-1" `
  -d '{"request":"查询订单 ORD-1001 的物流"}'
```

### 审批模拟退款

```powershell
curl.exe -X POST http://localhost:8080/api/v1/tasks/{taskId} -H "X-User-Id: alice" ...
curl.exe -X POST http://localhost:8080/api/v1/tasks/{taskId}/approve `
  -H "Content-Type: application/json" -H "X-User-Id: manager" -H "X-Role: SUPERVISOR" `
  -d '{"reason":"符合模拟政策"}'
```

任务查询使用 `GET /api/v1/tasks/{taskId}`，审计时间线使用 `GET /api/v1/tasks/{taskId}/events`，取消使用 `POST /api/v1/tasks/{taskId}/cancel`。
恢复中断任务使用 `POST /api/v1/tasks/{taskId}/resume`，仅主管/管理员可以恢复安全的只读步骤；多轮只读 Tool Calling 使用 `POST /api/v1/agent/chat`，响应会返回可查询审计时间线的 `taskId`。

学习和演示材料：

- `docs/day-30-business-scope-threat-model.md`：Day 30 的业务范围、角色矩阵、流程和威胁控制证据。
- `docs/complete-call-chain.md`：从 HTTP 入口到规划、权限、工具、状态、持久化和审计的完整调用链，并区分当前实现与生产目标。
- `docs/production-readiness.md`：生产就绪审查、阻断项、优化方向和上线门槛。当前版本不能直接连接真实业务写接口。
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

## 数据库与边界

`compose.yaml` 启动本地 PostgreSQL 和应用容器。`src/main/resources/db/migration/V1__init.sql` 定义任务、步骤和审计表；当前应用默认仍使用内存 Repository，数据库连接会在下一阶段替换为 durable adapter。所有写工具都明确返回 `simulation=true`。

启用 PostgreSQL 持久化时，在启动应用前设置 `AGENT_PERSISTENCE=jdbc`，并设置 `POSTGRES_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD`。Flyway 会执行 `V1__init.sql` 和 `V2__task_plan_and_approval.sql`。

不要把真实 API key、客户信息或生产连接串写入仓库；复制 `.env.example` 为本地环境文件后再按需设置变量。
