# Fault matrix

本矩阵描述可重复的故障响应。`通过`表示已有自动化测试证据；`待演练`表示代码/运行手册已具备，但当前环境没有完成真实黑盒或容器执行。

| Fault | Expected state | Retry | Evidence / operator action | Status |
| --- | --- | --- | --- | --- |
| DeepSeek timeout | Task fails with `MODEL_UNAVAILABLE` or uses the configured offline planner | At most one bounded retry according to planner settings | Check API availability, timeout and traceId; do not bypass server validation | 策略已验证，真实超时待注入 |
| Invalid model JSON | Task fails with `MODEL_INVALID_RESPONSE`/invalid plan before tool execution | No | Inspect model/version and captured traceId | 通过解析测试 |
| Unknown tool | Plan rejected before execution | No | Fix planner or tool registry contract | 通过 `ToolRegistryTests` |
| Parameter/permission failure | Task fails before tool side effect | No | Correct request or role | 通过边界测试 |
| Approval rejected | Task becomes `CANCELLED` | No | Record reason; create a new task only if policy allows | 代码路径具备，黑盒待演练 |
| Duplicate approval | Original terminal result returned | No | No operator action | 通过重复审批测试 |
| Database unavailable | JDBC application fails readiness/startup; no silent memory fallback | No blind retry | Restore MySQL/Flyway, then restart and inspect recovery events | 本地 MySQL 已演练 |
| Process restart with JDBC | Interrupted `RUNNING` task becomes `RECOVERY_REQUIRED`; only a supervisor/admin can resume a safe read-only suffix | No automatic write replay | Inspect external side effects before any manual recovery | 内存恢复测试通过，JDBC 待容器验收 |
| Prompt injection | Plan rejected before model/tool execution | No | Preserve request and traceId; create a new task after policy fix | 通过注入测试 |
| Write result without simulation marker | Result rejected; task does not become successful | No | Investigate tool contract and external result | 通过结果校验测试 |
| Deadline exceeded | Task fails with `DEADLINE_EXCEEDED` | No | Inspect slow tool/model and adjust bounded deadline | 代码路径具备，故障注入待演练 |

## 运行记录规则

发生故障时至少记录：UTC/Asia/Shanghai 时间、任务 ID、traceId、任务状态、事件类型、工具名、是否产生副作用、恢复动作和最终责任人。禁止在事件或日志中写入 DeepSeek API key、真实客户数据或完整授权凭据。
