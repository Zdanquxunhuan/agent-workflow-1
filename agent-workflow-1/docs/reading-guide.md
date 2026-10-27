# 代码阅读和学习顺序

如果你要按文档学习，建议按下面顺序阅读。每一层都能单独运行测试，不需要先接真实大模型或真实业务系统。

完整端到端调用链见 [complete-call-chain.md](complete-call-chain.md)。它把一次只读查询、一次审批写操作、一次多轮 Tool Calling 和一次重启恢复分别串起来，并标出了每个步骤对应的 Java 类、状态和审计事件。

## 先理解文档和代码的关系

你提供的 `Agent_Workflow_调整版.docx` 是“学习路线和验收要求”，不是当前代码的 API 手册。它回答“应该按哪一天完成什么”；仓库里的 `src/main/java` 才是实际运行逻辑；`docs` 目录是把代码、测试、演示和限制串起来的证据材料。

最适合从 Day 30 开始建立全局认识：先读 [Day 30 业务范围与威胁模型](day-30-business-scope-threat-model.md)，确认系统能做什么、不能做什么、谁能做什么；再读 [完整调用链路](complete-call-chain.md)，沿着一次请求进入代码。Day 30 是边界层，Day 31–36 是执行层，Day 37–49 是可靠性和安全层，Day 50–56 是评估、部署和发布层。

可以用两条请求把整个系统串起来：

1. `查询订单 ORD-1001`：从 `TaskController.create` 进入 `AgentApplicationService.create`，经过 `DeterministicTaskPlanner`、`ToolRegistry` 和 `OrderQueryTool`，最后由 `ResultEvaluator`、任务状态机和事件仓库收尾。
2. `退款 ORD-1001 金额 20`：前半段相同，但因为 `RefundTool.readOnly()` 为 `false`，任务停在 `WAITING_APPROVAL`；再看 `AgentApplicationService.approve`，理解主管审批后如何只执行一次。

如果只想知道“模型在哪里”，读 `DeepSeekTaskPlanner` 和 `DeepSeekToolCallingService`。它们只负责提出结构化计划或只读工具调用，不能直接改任务状态或执行写操作。

## 第一步：先看请求入口

从 [TaskController.java](../src/main/java/com/yu/agent/workflow/api/TaskController.java) 开始，理解请求头中的 `X-User-Id`、`X-Role`、`Idempotency-Key` 和 `X-Trace-Id`。再看 [ApiResponse.java](../src/main/java/com/yu/agent/workflow/api/ApiResponse.java) 和 [GlobalExceptionHandler.java](../src/main/java/com/yu/agent/workflow/api/GlobalExceptionHandler.java)，理解统一响应和错误码。

## 第二步：理解任务状态

阅读 [TaskStatus.java](../src/main/java/com/yu/agent/workflow/domain/TaskStatus.java)、[TaskStateMachine.java](../src/main/java/com/yu/agent/workflow/domain/TaskStateMachine.java) 和 [AgentTask.java](../src/main/java/com/yu/agent/workflow/domain/AgentTask.java)。重点看：

- 任务状态只能沿合法边流转。
- 每次变化都会产生审计事件。
- 终态不能被普通步骤覆盖。

## 第三步：跟一遍执行循环

阅读 [AgentApplicationService.java](../src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java) 的 `create`、`approve` 和 `executePlan`：

```text
创建任务
  -> PromptInjectionGuard
  -> Planner
  -> 计划规范化和白名单校验
  -> 状态迁移
  -> ToolRegistry
  -> ResultEvaluator
  -> 步骤和审计记录
  -> 成功、失败或等待审批
```

## 第四步：理解工具安全边界

阅读 [AgentTool.java](../src/main/java/com/yu/agent/workflow/tools/AgentTool.java)、[ToolRegistry.java](../src/main/java/com/yu/agent/workflow/tools/ToolRegistry.java) 和 `tools` 目录下的模拟工具。工具实现不负责决定“谁可以调用”，统一由注册表检查角色和工具名。

## 第五步：比较两种规划器

- [DeterministicTaskPlanner.java](../src/main/java/com/yu/agent/workflow/application/DeterministicTaskPlanner.java)：离线、稳定、适合测试。
- [DeepSeekTaskPlanner.java](../src/main/java/com/yu/agent/workflow/application/DeepSeekTaskPlanner.java)：只生成 JSON 计划，服务器仍然重新校验，不直接调用工具。

## 第六步：理解持久化和恢复

先看 [TaskRepository.java](../src/main/java/com/yu/agent/workflow/persistence/TaskRepository.java) 接口，再比较 `InMemoryTaskRepository` 和 `JdbcTaskRepository`。最后阅读 [RecoveryService.java](../src/main/java/com/yu/agent/workflow/application/RecoveryService.java)，理解为什么重启后不能自动重放一个结果未知的写操作。

## 第七步：跑测试和演示

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' test
& '.\mvnw.cmd' -Dtest=EvaluationBatchTests test
```

再按 [demo.md](demo.md) 走一遍查询、审批、拒绝、取消、审计和提示注入路径。

## 文档 Day 映射

| 学习主题 | 代码/文档 | 对应 Day |
| --- | --- | --- |
| 业务范围、角色矩阵、威胁模型 | `docs/day-30-business-scope-threat-model.md`、`UserRole`、`ToolRegistry` | Day 30 |
| Web 骨架、健康检查、错误响应 | `api` 包 | Day 2–4 |
| 领域对象和工具白名单 | `domain`、`tools` | Day 31–34 |
| 执行循环和状态机 | `AgentApplicationService`、`TaskStateMachine` | Day 36–37 |
| 权限、审批、幂等 | `ToolRegistry`、审批 API、Repository | Day 40–42 |
| DeepSeek 结构化规划 | `DeepSeekTaskPlanner` | Day 35、44 |
| 结果验证、上下文、注入防护 | `ResultEvaluator`、`ContextBuilder`、`PromptInjectionGuard` | Day 45、47–48 |
| JDBC、Flyway、恢复 | `persistence`、`RecoveryService` | Day 38–39、54 |
| 本地 RAG 和 ACL | `KnowledgeBase`、`KnowledgeSearchTool` | Day 46 |
| 多轮只读 Tool Calling 与聊天审计 | `AgentChatController`、`ChatAuditService`、`DeepSeekToolCallingService`、`ToolCallingGateway` | Day 35、44、49 |
| 评估、演练和发布 | `EvaluationBatchTests`、`docs` | Day 50–56 |
