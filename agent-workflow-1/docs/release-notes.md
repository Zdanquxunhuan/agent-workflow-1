# 发布说明（Day 55–56）

## 交付候选

- 项目：Enterprise Workflow Agent
- 版本：`0.0.1-SNAPSHOT`
- 记录日期：2026-10-25（Asia/Shanghai）
- Java：17（本机验证路径：`F:\ayd\jdk-17`）
- 默认规划：离线确定性规划器
- 可选外部模型：DeepSeek OpenAI 兼容 Chat Completions + JSON Output
- 默认持久化：内存；部署时可设置 `AGENT_PERSISTENCE=jdbc` 使用 PostgreSQL/Flyway

## 本版本包含

1. 受约束的任务状态机、任务步骤和事件时间线。
2. 订单、物流、工单、知识检索模拟工具，以及退款和地址修改模拟写工具。
3. 工具白名单、参数校验、角色权限、审批门禁和结果验证。
4. 幂等创建、重复审批保护、取消、traceId、聊天任务审计、事务化审计写入、V3/V4 审计 Outbox 和发布重试。
5. DeepSeek 结构化计划解析；服务端重新校验模型输出，模型不能直接调用工具或关闭审批。
6. 有界 deadline、有限重试策略、提示注入拦截、任务上下文隔离。
7. JDBC Repository、Flyway V1/V2、Docker Compose 和 OpenAPI/Postman 交付材料。
8. 25 条评估样例逐条执行器和 25 个自动化测试（以 Surefire 报告为准）。
9. 本地 RAG 文档 ACL、引用返回、只读 checkpoint 恢复和带任务审计的多轮只读 Tool Calling。

## 使用前提

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' test
& '.\mvnw.cmd' spring-boot:run
```

启用 DeepSeek 时设置 `DEEPSEEK_ENABLED=true`、`DEEPSEEK_API_KEY` 和可选的 `DEEPSEEK_MODEL`。密钥只放在进程环境或本地密钥管理中，不写入仓库。没有密钥时应用不向外部模型发请求。

## 已知限制和风险

完整的生产就绪审查、阻断项和上线门槛见 [生产就绪审查与上线门槛](production-readiness.md)。

- 工具使用脱敏模拟数据；没有连接真实订单、物流、工单、支付或知识库系统。
- PostgreSQL 适配器和迁移脚本已提供，但本轮未完成 Docker 容器重启持久化验收；Docker daemon 不可用的证据见 [故障演练报告](fault-drill-report.md) 和 [部署复盘](deployment-rehearsal.md)。
- 恢复策略是保守失败并要求人工处理，不会自动重放可能产生副作用的写操作；checkpoint 目前只恢复安全只读步骤。
- 25 条评估数据集已由离线确定性规划器逐条执行通过；不能据此宣称 DeepSeek 模型准确率或生产 SLO。
- 外部向量库、真实业务 ACL、红队测试、压测、成本和延迟指标尚未完成。

## 回滚与停机

1. 停止应用实例，保留 PostgreSQL 数据卷和审计表。
2. 回滚到上一版本镜像/构建产物；不要删除 Flyway 已执行的迁移或手动改写任务状态。
3. 检查 `RECOVERY_REQUIRED`、`PLAN_REJECTED`、`RESULT_REJECTED` 和数据库错误事件。
4. 恢复后先执行健康检查和只读查询，再开放审批写操作。

## 发布前检查表

- [ ] `JAVA_HOME` 指向 Java 17，自动化测试失败数/错误数为 0。
- [ ] DeepSeek 开关、模型名、超时和密钥通过环境变量注入。
- [ ] JDBC 模式的 PostgreSQL 连接、Flyway V1/V2 和健康检查已在目标环境演练。
- [ ] 高风险任务审批前副作用为 0，重复审批不产生第二次副作用。
- [x] 25 条评估样例的批量执行器和逐条结果报告已补齐。
- [ ] 日志、traceId、审计事件和密钥脱敏策略已经过值班人员检查。
