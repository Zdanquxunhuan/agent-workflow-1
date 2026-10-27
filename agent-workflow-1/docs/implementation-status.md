# Implementation status against the supplied workflow brief

The repository now contains a tested vertical slice of the target enterprise workflow Agent.

| Brief requirement | Status |
| --- | --- |
| Spring Boot service and health API | Implemented and covered by MockMvc |
| Structured plan and fixed tool names | Implemented with `TaskPlanner` and `ToolRegistry`; optional DeepSeek JSON planner is available |
| Simulated order, shipment, ticket, and knowledge tools | Implemented with deterministic fixtures |
| State machine and task events | Implemented in `AgentApplicationService` and event repository |
| Deadline and result verification | Implemented with bounded step deadline and simulation result checks |
| Conservative restart recovery | JDBC and memory repositories identify `RUNNING` tasks and fail them with a `RECOVERY_REQUIRED` audit event instead of replaying writes |
| Context isolation and prompt injection guard | Implemented with bounded task-local context and blocked-pattern tests |
| High-risk approval gate | Implemented; approval tests assert zero pre-approval effects |
| RBAC, parameter validation, idempotency | Implemented at the execution boundary and tested |
| API error envelope and audit timeline | Implemented |
| PostgreSQL/Flyway persistence and Compose | `AGENT_PERSISTENCE=jdbc` adapter, V1/V2 migrations, and app+database Compose stack included |
| Durable restart recovery, checkpoint, retry budget, multi-turn external tool calling | Read-only checkpoint resume, bounded retry, and multi-round read-only Tool Calling are implemented; write replay remains blocked |
| RAG and document ACL | Local lexical RAG with role-filtered documents and citations is implemented; external vector store remains optional |
| 25-case evaluation set and API contract | Implemented in `src/test/resources/evaluation/tasks.json`, `EvaluationBatchTests`, and `docs/openapi.yaml` |
| Production deployment rehearsal | Runbook included; Docker daemon was unavailable in the current environment |
| Learning notes and flow diagrams | `docs/reading-guide.md` and `docs/diagrams.md` added for code walkthroughs and demonstrations |
| Day 30 business scope, permission matrix, and threat model | Implemented in `docs/day-30-business-scope-threat-model.md`; runtime controls are mapped to code and tests |
| End-to-end call chain and production gap map | `docs/complete-call-chain.md` maps HTTP, planning, policy, tools, state, persistence, audit, and production adaptations |
| Production readiness review | `docs/production-readiness.md` records the no-go verdict, P0/P1/P2 gaps, phased plan, and release gates |
| Chat task audit | `/api/v1/agent/chat` now creates a task, returns `taskId`, records model rounds/tool calls/results, and supports idempotent replay |
| Transactional task audit and Outbox delivery | `TaskAuditWriter` plus V3/V4 migrations and `AuditOutboxPublisher` provide atomic hand-off, leases, backoff, and idempotent publish marks; real sink/dead-letter remains |

The default app intentionally uses simulated data and an in-memory repository. This keeps local safety tests deterministic while avoiding a connection to real business systems. Use the JDBC profile for durable deployment; the ADR records the persistence decision and its limitations.

## Day 30 补充说明

Day 30 的三项文档产出已补齐：业务范围、角色权限矩阵和威胁模型见 [`docs/day-30-business-scope-threat-model.md`](day-30-business-scope-threat-model.md)。运行时控制此前已经存在，本次补充将它们与代码和测试逐项对齐。

## Day 52–56 收尾材料状态

| Day | 交付物 | 状态 | 说明 |
| --- | --- | --- | --- |
| Day 52 | 故障演练报告与故障矩阵 | 已补齐 | 覆盖模型、工具、审批、恢复、数据库、注入和 deadline；Docker 实际重启列为待环境执行 |
| Day 53 | API 合同与演示脚本 | 已补齐 | `openapi.yaml`、Postman 集合和 `docs/demo.md`；包含审批、拒绝、取消、审计和 DeepSeek 可选路径 |
| Day 54 | 部署复盘 | 已补齐运行手册 | Compose/Flyway/JDBC 路径已写入；本机 Docker daemon 不可用，未虚构容器验收通过 |
| Day 55 | 评估报告与发布门槛 | 已补齐 | 25 条数据集逐条执行通过；25 个自动化测试有 Surefire 证据 |
| Day 56 | 发布说明与已知限制 | 已补齐 | `docs/release-notes.md` 给出版本、配置、回滚、风险和发布前检查表；`docs/diagrams.md` 补充演示流程图 |

### 当前可以对外演示的能力

- 只读订单/物流/工单/知识查询的受约束执行。
- 退款和地址修改的服务端审批门禁，审批前副作用为 0。
- 未知工具、角色越权、非法输入、提示注入和不可信写结果的拒绝。
- 任务状态、traceId、工具步骤和审计事件的可查询性。
- 离线确定性规划和可选 DeepSeek 结构化规划的切换。

### 尚未达到生产验收的能力

- 真实外部业务系统、外部向量库接入、红队和压力/成本指标。
- PostgreSQL 容器停止/启动后的真实持久化验收（受 Docker daemon 环境阻塞）。
- DeepSeek 线上模型指标、生产压测报告和外部业务系统幂等键下沉。
- 写操作 checkpoint 恢复仍然禁止自动重放；当前只恢复安全只读步骤。
