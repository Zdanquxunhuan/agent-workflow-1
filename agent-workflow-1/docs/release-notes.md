# 发布说明（Day 55–57）

## 交付候选

- 项目：Enterprise Workflow Agent
- 版本：`0.0.1-SNAPSHOT`
- 记录日期：2026-10-27（Asia/Shanghai）
- Java：17（本机验证路径：`F:\ayd\jdk-17`）
- 默认规划：离线确定性规划器
- 可选外部模型：DeepSeek OpenAI 兼容 Chat Completions + JSON Output
- 默认持久化：内存；部署时设置 `AGENT_PERSISTENCE=jdbc` 使用 MySQL/Flyway（`V1..V5`）

## 本版本包含

1. 受约束的任务状态机、任务步骤和事件时间线。
2. 订单、支付、物流、工单、知识检索工具；退款、地址修改和创建物流单写入 MySQL 业务表，并通过事务性 Outbox + 沙箱网关结算。
3. 工具白名单、参数校验、角色权限、审批门禁和结果验证。
4. 幂等创建、重复审批保护、取消、traceId、聊天任务审计、事务化审计写入、V3/V4 审计 Outbox 和发布重试。
5. DeepSeek 结构化计划解析；服务端重新校验模型输出，模型不能直接调用工具或关闭审批。
6. 有界 deadline、有限重试策略、提示注入拦截、任务上下文隔离。
7. JDBC Repository、Flyway V1..V5、MySQL Compose、演示种子数据和 OpenAPI/Postman 交付材料。
8. 25 条评估样例逐条执行器和 56 个自动化测试（以 Surefire 报告为准）。
9. 本地 RAG 文档 ACL、引用返回、只读 checkpoint 恢复和带任务审计的多轮只读 Tool Calling。
10. JWT 资源服务器认证、可配置角色 claim、本地/ OIDC 双模式和审批职责分离；业务接口不再信任 `X-User-Id`/`X-Role`。
11. 生产运行基线：`prod` profile 下的 fail-closed 配置门禁、优雅停机、liveness/readiness、可配置的过期任务恢复阈值（`AGENT_RECOVERY_STALE_SECONDS`）。
12. 双实例正确性验收：移除创建路径的进程内锁和读前置检查，由数据库唯一约束裁决并发幂等；行锁审批、Outbox 唯一命令、`SKIP LOCKED` 租约和过期任务恢复在独立 schema 上通过 73 项检查。

## 使用前提

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
& '.\mvnw.cmd' test
& '.\mvnw.cmd' spring-boot:run
```

启用 DeepSeek 时设置 `DEEPSEEK_ENABLED=true`、`DEEPSEEK_API_KEY` 和可选的 `DEEPSEEK_MODEL`。密钥只放在进程环境或本地密钥管理中，不写入仓库。没有密钥时应用不向外部模型发请求。

## 已知限制和风险

完整的生产就绪审查、阻断项和上线门槛见 [生产就绪审查与上线门槛](production-readiness.md)。

- 业务数据为脱敏演示数据；本地 MySQL 读写已实现，但支付/物流下游仍是沙箱网关，未接入真实资金或承运商系统。
- MySQL 适配器、Flyway V1..V5 和 Docker 演示已完成单实例读写验证；双实例创建/审批/取消竞争、实例停机接管和过期任务恢复已通过 73 项独立 schema 验收。MySQL 重启、数据库中断和真实下游仍未验收，见 [部署复盘](deployment-rehearsal.md)。
- 恢复策略是保守失败并要求人工处理，不会自动重放可能产生副作用的写操作；checkpoint 目前只恢复安全只读步骤。
- 25 条评估数据集已由离线确定性规划器逐条执行通过；不能据此宣称 DeepSeek 模型准确率或生产 SLO。
- 外部向量库、真实业务 ACL、红队测试、压测、成本和延迟指标尚未完成。

## 回滚与停机

1. 停止应用实例，保留 MySQL 数据卷和审计表。
2. 回滚到上一版本镜像/构建产物；不要删除 Flyway 已执行的迁移或手动改写任务状态。
3. 检查 `RECOVERY_REQUIRED`、`PLAN_REJECTED`、`RESULT_REJECTED` 和数据库错误事件。
4. 恢复后先执行健康检查和只读查询，再开放审批写操作。

## 发布前检查表

- [ ] `JAVA_HOME` 指向 Java 17，自动化测试失败数/错误数为 0。
- [ ] DeepSeek 开关、模型名、超时和密钥通过环境变量注入。
- [ ] JDBC 模式的 MySQL 连接、Flyway V1..V5、就绪检查和 Outbox 结算已在目标环境演练。
- [x] 双实例并发验收通过（73/73），`prod` profile 对不安全默认配置 fail-closed。
- [ ] 高风险任务审批前副作用为 0，重复审批不产生第二次副作用。（单实例与双实例竞争均已验证，目标环境仍需复跑）
- [x] 25 条评估样例的批量执行器和逐条结果报告已补齐。
- [ ] 日志、traceId、审计事件和密钥脱敏策略已经过值班人员检查。
