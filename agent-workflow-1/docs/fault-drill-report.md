# 故障演练报告（Day 52）

> 记录日期：2026-10-25（Asia/Shanghai）  
> 适用版本：`0.0.1-SNAPSHOT`  目标：验证 Agent 在模型、工具、审批、恢复和部署故障下保持“无越权、无盲目重放、可审计”。

## 结论摘要

本次报告以仓库中可重复执行的自动化测试为证据，不把尚未在本机执行的 Docker/MySQL 演练写成已通过。当前已验证的控制包括：

- `RecoveryServiceTests.interruptedRunningTaskIsNotReplayed`：启动时发现过期 `RUNNING` 任务后转为 `RECOVERY_REQUIRED`，记录恢复审计事件，不自动重放写操作。
- `ReliabilityControlTests`：提示注入拦截、有限重试策略、任务上下文边界和写工具结果校验均通过。
- `AgentWorkflow1ApplicationTests`：只读任务成功；高风险任务审批前副作用为 0；重复审批副作用保持为 1；幂等键返回原任务。
- `ToolRegistryTests` 和 `TaskStateMachineTests`：未知工具、角色越权和非法状态迁移在执行边界被拒绝。

Docker Compose 的应用重启演练在本轮未执行成功：当时 Docker Desktop Linux 引擎不可连接（Docker API 权限/daemon 问题）。2026-10-27 已在本地 Docker MySQL 8.0 上完成单实例启动、Flyway、读写和 Outbox 结算验证；双实例创建/审批/取消竞争、实例停机接管、过期任务恢复和 prod 配置门禁已由 `scripts/two_instance_acceptance.py` 覆盖并通过（73/73）。MySQL 重启仍未演练。

## 演练矩阵

| 场景 | 期望安全性质 | 证据/执行方式 | 状态 |
| --- | --- | --- | --- |
| DeepSeek 超时/瞬态错误 | 仅瞬态或限流错误在预算内重试；不重试权限、参数和未知副作用错误 | `RetryPolicy` 单元测试；DeepSeek 请求实现带有配置的有限重试 | 已验证策略 |
| DeepSeek 返回非法 JSON | 计划被拒绝，不执行工具 | `DeepSeekTaskPlannerTests.rejectsAResponseWithoutSteps`；应用层计划校验 | 已验证解析边界 |
| 未知工具 | 工具调用前失败，无工具副作用 | `ToolRegistryTests.unknownToolIsRejectedBeforeExecution` | 通过 |
| 角色不允许工具 | 工具执行前失败，无工具副作用 | `ToolRegistryTests.roleIsCheckedAtTheExecutionBoundary` | 通过 |
| 参数缺失/非法 | 请求或计划在边界被拒绝 | `TaskControllerTests.invalidRequestIsRejectedWithoutStackTrace`；工具注册表校验 | 通过 |
| 高风险动作未审批 | 进入 `WAITING_APPROVAL`，副作用为 0 | `AgentWorkflow1ApplicationTests.highRiskWorkflowCannotExecuteBeforeApprovalAndApprovalIsIdempotent` | 通过 |
| 审批被拒绝 | 进入 `CANCELLED`，不执行写工具 | `AgentApplicationService.reject` 路径；需在环境中补充 HTTP 演示 | 代码路径具备，待独立演练 |
| 重复审批 | 返回终态，不重复写入 | 同一自动化测试中的第二次审批 | 通过 |
| 进程在 `RUNNING` 时重启 | 标记 `RECOVERY_REQUIRED` 并记录恢复事件，不盲目重放 | `RecoveryServiceTests.interruptedRunningTaskIsNotReplayed`、双实例脚本 | 通过（内存仓库 + 双实例 MySQL） |
| 数据库不可用 | JDBC 模式启动/就绪失败，禁止静默降级到内存 | Compose/Flyway 配置、JDBC 配置检查、prod 门禁 | 启动 fail-closed 已验证；运行中断连待演练 |
| Compose 停止再启动 | 任务和审计记录仍可查询 | `docs/deployment-rehearsal.md` 操作步骤 | 未执行：Docker daemon 不可用 |
| 双实例创建/审批/取消 | 唯一约束与行锁保证只产生一次写入 | `scripts/two_instance_acceptance.py`（73/73） | 通过（独立 schema） |
| 过期任务恢复 | 只恢复超过阈值的任务，不误伤活跃实例 | 同上 | 通过 |
| 生产不安全默认配置 | 拒绝启动并列出违规项 | 同上（prod guard 退出码 1） | 通过 |
| 提示注入 | 请求在规划前被阻断 | `ReliabilityControlTests.injectionGuardRejectsInstructionOverridePatterns` | 通过 |
| 写工具结果缺少可验证标志 | 结果被拒绝，不进入成功态 | `ReliabilityControlTests.writeResultMustContainAverifiableSimulationMarker` | 通过 |

## 操作步骤

### 本地自动化演练

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' test
```

以 `target/surefire-reports/TEST-*.xml` 为准记录测试数量、失败和错误；不要只根据 Maven 进程退出码填写报告。

### Compose/MySQL 演练

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' clean package -DskipTests
docker compose up --build -d
docker compose ps
docker compose logs app
docker compose down
docker compose up -d
curl.exe http://localhost:8080/api/v1/health
```

只有在 `docker compose ps` 显示应用和数据库健康、Flyway 完成 `V1..V5`、停止后重新启动仍能查询任务和事件时，才可把“Compose 停止再启动”标为通过。当前报告不替代这项验收。

## 事故处置约束

1. 模型不可用：保留任务和 traceId，允许离线确定性规划器运行；如果配置要求 DeepSeek，则返回明确的模型错误，不绕过服务端校验。
2. 任务处于 `FAILED/RECOVERY_REQUIRED`：先检查任务事件和外部系统结果，再通过显式恢复流程处理；禁止直接重新提交可能产生副作用的原写操作。
3. 数据库不可用：恢复 MySQL/Flyway 后重启应用；不要把 JDBC 模式静默切换到内存模式。
4. 发现越权或提示注入：保留原始请求、traceId 和 `PLAN_REJECTED`/错误事件，修复输入或策略后创建新任务。

## 未完成项

- 补做 MySQL 停止/启动、健康检查和 Flyway 验证；双实例创建/审批/取消/恢复已由验收脚本覆盖。
- 对审批拒绝和 HTTP 取消各补一条可复制的黑盒演练记录。
- 引入可控故障注入器后，补测 DeepSeek 超时、限流和数据库连接中断的实际重试行为。
