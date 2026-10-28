# Implementation status against the supplied workflow brief

The repository now contains a tested vertical slice of the target enterprise workflow Agent.

## 本地独立业务接入补充

新增 `business-system/` 独立订单/支付服务和独立 MySQL，使用 50 个合成订单。`COMMERCE_GATEWAY=http` 启用真实 HTTP 只读查询及一种异步退款写操作，具备幂等、状态轮询对账、有限预算、人工处理队列和操作审计。JDBC 写任务进入 `WAITING_EXTERNAL`，取得业务凭据后再更新成功/失败；结构化结果持久化在步骤中。详见 [接入说明与流程图](business-integration.md)。这不代表真实支付平台或承运商已接入；旧表格中的沙箱描述适用于默认 `sandbox` 模式。

| Brief requirement | Status |
| --- | --- |
| Spring Boot service and health API | Implemented and covered by MockMvc |
| Structured plan and fixed tool names | Implemented with `TaskPlanner` and `ToolRegistry`; optional DeepSeek JSON planner is available |
| Order, payment, shipment, ticket, and knowledge tools | Implemented; commerce tools read/write `CommerceRepository`, knowledge search uses local lexical RAG |
| Real commerce writes with approval and outbox | Refund, address change, and shipment creation write business rows plus `integration_commands`; sandbox gateway settles commands with leases, retries, and `UNKNOWN` handling |
| State machine and task events | Implemented in `AgentApplicationService` and event repository |
| Deadline and result verification | Implemented with bounded step deadline and simulation result checks |
| Conservative restart recovery | JDBC and memory repositories identify `RUNNING` tasks and fail them with a `RECOVERY_REQUIRED` audit event instead of replaying writes |
| Context isolation and prompt injection guard | Implemented with bounded task-local context and blocked-pattern tests |
| High-risk approval gate | Implemented; approval tests assert zero pre-approval effects |
| RBAC, parameter validation, idempotency | Implemented at the execution boundary and tested |
| API error envelope and audit timeline | Implemented |
| MySQL/Flyway persistence and Compose | MySQL schema `V1..V6`, Hikari fail-closed startup, and demo plus independent-business Compose stacks included |
| Durable restart recovery, checkpoint, retry budget, multi-turn external tool calling | Read-only checkpoint resume, bounded retry, and multi-round read-only Tool Calling are implemented; write replay remains blocked |
| RAG and document ACL | Local lexical RAG with role-filtered documents and citations is implemented; external vector store remains optional |
| 25-case evaluation set and API contract | Implemented in `src/test/resources/evaluation/tasks.json`, `EvaluationBatchTests`, and `docs/openapi.yaml` |
| Production deployment rehearsal | Runbook plus Docker MySQL single-instance rehearsal and a two-instance acceptance script (`scripts/two_instance_acceptance.py`, 73/73 checks) |
| Learning notes and flow diagrams | `docs/reading-guide.md` and `docs/diagrams.md` added for code walkthroughs and demonstrations |
| Day 30 business scope, permission matrix, and threat model | Implemented in `docs/day-30-business-scope-threat-model.md`; runtime controls are mapped to code and tests |
| End-to-end call chain and production gap map | `docs/complete-call-chain.md` maps HTTP, planning, policy, tools, state, persistence, audit, and production adaptations |
| Production readiness review | `docs/production-readiness.md` records the no-go verdict, P0/P1/P2 gaps, phased plan, and release gates |
| Chat task audit | `/api/v1/agent/chat` now creates a task, returns `taskId`, records model rounds/tool calls/results, and supports idempotent replay |
| Transactional task audit and Outbox delivery | `TaskAuditWriter` plus V3/V4 migrations and `AuditOutboxPublisher` provide atomic hand-off, leases, backoff, and idempotent publish marks; real sink/dead-letter remains |

The default app intentionally uses an in-memory repository seeded with the same demo data, keeping local safety tests deterministic. `AGENT_PERSISTENCE=jdbc` switches task, audit, and commerce persistence to MySQL; the sandbox gateway still stands in for real payment and logistics providers. The ADR records the persistence decision and its limitations.

## Day 30 补充说明

Day 30 的三项文档产出已补齐：业务范围、角色权限矩阵和威胁模型见 [`docs/day-30-business-scope-threat-model.md`](day-30-business-scope-threat-model.md)。运行时控制此前已经存在，本次补充将它们与代码和测试逐项对齐。

## Day 52–57 收尾材料状态

| Day | 交付物 | 状态 | 说明 |
| --- | --- | --- | --- |
| Day 52 | 故障演练报告与故障矩阵 | 已补齐 | 覆盖模型、工具、审批、恢复、数据库、注入和 deadline；Docker 实际重启列为待环境执行 |
| Day 53 | API 合同与演示脚本 | 已补齐 | `openapi.yaml`、Postman 集合和 `docs/demo.md`；包含审批、拒绝、取消、审计和 DeepSeek 可选路径 |
| Day 54 | 部署复盘 | 已补齐并已验证 | Compose/Flyway/JDBC 单实例路径已在 Docker MySQL 上实跑；双实例验收补充在 `docs/deployment-rehearsal.md` |
| Day 55 | 评估报告与发布门槛 | 已补齐 | 25 条数据集逐条执行通过；56 个自动化测试有 Surefire 证据 |
| Day 56 | 发布说明与已知限制 | 已补齐 | `docs/release-notes.md` 给出版本、配置、回滚、风险和发布前检查表；`docs/diagrams.md` 补充演示流程图 |
| Day 57 | 生产运行基线与双实例验收 | 已补齐并已验证 | `prod` fail-closed 门禁、优雅停机、恢复阈值；双实例 73/73 检查覆盖幂等、审批/取消竞争、停机接管和过期恢复 |

### 当前可以对外演示的能力

- 只读订单/支付/物流/工单/知识查询的受约束执行。
- 退款、改地址和创建物流单的服务端审批门禁，审批后写入本地 MySQL 业务表并经 Outbox 结算。
- 未知工具、角色越权、非法输入、提示注入和不可信写结果的拒绝。
- 任务状态、traceId、工具步骤和审计事件的可查询性。
- 离线确定性规划和可选 DeepSeek 结构化规划的切换。

### 尚未达到生产验收的能力

- 真实支付/物流适配器、外部向量库接入、红队和压力/成本指标。
- MySQL 重启/主从切换、数据库中断和长时间 Outbox 积压下的恢复演练（双实例并发审批已完成）。
- DeepSeek 线上模型指标、生产压测报告和外部业务系统幂等键下沉。
- 写操作 checkpoint 恢复仍然禁止自动重放；当前只恢复安全只读步骤。
