# 评估报告（Day 50/51/55）

> 数据集：`src/test/resources/evaluation/tasks.json`  记录日期：2026-10-25  
> 评估目标：检查计划约束、审批门禁、工具安全、输入校验和审计行为。

## 数据集概况

数据集包含 25 条稳定样例，ID 为 E01–E25 且无重复。它是回归样例集，不是生产流量统计，也不包含真实客户数据。

| 维度 | 数量 |
| --- | ---: |
| 总样例 | 25 |
| 期望 `SUCCEEDED` | 11 |
| 期望 `WAITING_APPROVAL` | 6 |
| 期望 `FAILED` | 2 |
| 期望 `ERROR` | 6 |
| `read` 安全类 | 12 |
| `approval` 安全类 | 5 |
| `prompt_injection` 安全类 | 3 |
| `validation` 安全类 | 3 |
| `parameter` 安全类 | 1 |
| `citation` 安全类 | 1 |

工具覆盖：`order.query` 4 条、`shipment.query` 3 条、`ticket.query` 3 条、`knowledge.search` 3 条、`refund.create` 4 条、`order.address.update` 2 条；6 条预期工具为 `none`，用于拒绝类场景。

## 本轮可复现证据

| 测试类 | 测试数 | 证明内容 |
| --- | ---: | --- |
| `AgentWorkflow1ApplicationTests` | 3 | 只读订单流程、审批前零副作用/重复审批、创建幂等 |
| `TaskControllerTests` | 3 | 健康接口、请求校验、缺少身份头的错误封装 |
| `ToolRegistryTests` | 2 | 未知工具和角色越权在执行边界拒绝 |
| `TaskStateMachineTests` | 3 | 终态无出边、非法状态迁移和恢复状态迁移 |
| `ReliabilityControlTests` | 4 | 注入拦截、重试边界、上下文隔离、写结果校验 |
| `RecoveryServiceTests` | 1 | 中断任务不自动重放，记录恢复事件 |
| `DeepSeekTaskPlannerTests` | 2 | 结构化计划解析、缺少 `steps` 拒绝 |
| `EvaluationDatasetTests` | 1 | 25 条样例、唯一 ID、安全类别覆盖 |
| `EvaluationBatchTests` | 1 | 逐条执行 E01–E25，并对照期望状态和第一个工具 |
| `KnowledgeBaseTests` | 2 | RAG 文档 ACL 和引用边界 |
| `CheckpointRecoveryTests` | 1 | 只读 checkpoint 恢复不重放写操作 |
| `ToolCallingServiceTests` | 2 | 多轮只读 Tool Calling 和写工具阻断 |
| **合计** | **25** | 以 `target/surefire-reports/TEST-*.xml` 为测试结果依据 |

`EvaluationBatchTests` 已逐条执行 E01–E25，当前离线确定性规划器的状态和第一个工具均与数据集期望一致。这个结果只代表固定模拟数据和离线规划器的回归通过，不代表 DeepSeek 线上准确率、延迟或成本指标。

## 与数据集的覆盖关系

| 数据集类别 | 代表样例 | 已有证据 | 当前结论 |
| --- | --- | --- | --- |
| 只读查询 | E01、E04、E06、E08、E16–E19、E25 | `EvaluationBatchTests` + 工具白名单/结果审计测试 | 12 条逐条通过 |
| 审批门禁 | E10–E12、E20–E22 | `EvaluationBatchTests` + 审批端到端测试 | 6 条逐条进入等待审批 |
| 输入校验 | E09、E13、E23 | 批量执行器和 MockMvc 校验 | 3 条按预期拒绝 |
| 提示注入 | E14、E15、E24 | 批量执行器和注入拦截单元测试 | 3 条按预期拒绝 |
| 引用/知识 | E08、E19、E25 | 本地 RAG + ACL、批量执行器 | 本地文档检索通过，外部向量库尚未接入 |

## 执行命令与记录规则

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' test
```

记录以下字段：执行日期、Java 版本、Maven 版本、总测试数、失败数、错误数、是否启用 DeepSeek、是否启用 JDBC。DeepSeek 未配置时，默认使用离线确定性规划器；因此本地测试结果不能作为 DeepSeek 线上质量指标。

## 发布门槛

- 自动化测试：失败数和错误数必须为 0。
- 安全不变量：高风险任务审批前副作用为 0；未知工具、越权角色、注入模式和未验证写结果必须被拒绝。
- 数据集：E01–E25 已由批量执行器逐条对照状态和工具；仍不能把离线模拟结果当成 DeepSeek 生产指标。
- 生产质量指标（模型准确率、P95 延迟、费用、召回率）尚未采集，不得在发布说明中声称已达标。
