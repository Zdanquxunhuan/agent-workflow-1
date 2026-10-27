# 25 条评估集逐条执行

`EvaluationBatchTests` 直接调用真实的 `AgentApplicationService`，逐条读取 `src/test/resources/evaluation/tasks.json`，对每条样例检查：

1. 任务最终状态是否符合 `expectedStatus`。
2. 计划的第一个工具是否符合 `expectedTool`。
3. 计划错误、提示注入和缺少参数是否以结构化 `BusinessException` 拒绝。

执行命令：

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' -Dtest=EvaluationBatchTests test
```

当前 Java 17 验证结果：E01–E25 共 25 条全部通过，状态和第一个工具均与数据集期望一致。

这个执行器用于回归和安全边界验证，不代表 DeepSeek 线上准确率、延迟、token 或成本指标。启用 `DEEPSEEK_ENABLED=true` 后，必须另外固定模型版本、API 参数和原始响应，才能形成可比较的模型评估报告。
