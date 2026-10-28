# 本地独立业务接入验收记录

## 环境与事实边界

日期：2026-10-27，Asia/Shanghai。两个独立 Docker MySQL 8.0 容器，两个本机 Java 17 服务，通过真实 HTTP 连接；业务数据全部为合成数据，不涉及真实资金。旧演示数据库未用于本轮业务数据，双实例回归仅使用专用 `workflow_agent_verify` schema。

Docker Hub 的 Java 镜像下载因网络连接失败，未完成完整应用容器构建验收。使用 `scripts/local_business_stack.py` 的本机 Java 回退路径完成联调。完整容器部署文件保留在 `compose.business.yaml`，其配置已通过 `docker compose config --quiet`。

## 已通过的构建和验收

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 主项目 Maven package | 68 个测试，0 失败、0 错误、0 跳过；构建成功 | `target/business-build-main.log`、`target/surefire-reports/` |
| 独立业务服务 Maven package | 构建成功；业务行为由以下真实数据库端到端验收覆盖 | `target/business-build-service.log` |
| Python 脚本语法 | 三个启动/验收脚本编译通过 | `python -m py_compile` |
| 业务端到端验收（含停机演练） | **39/39 通过** | `target/business-integration/acceptance-report.json` |
| 双实例并发与恢复回归 | **73/73 通过** | `target/two-instance/acceptance-report.json`、`target/two-instance-console.log` |

业务验收运行 ID：`6087529a8684`；报告完成时间：2026-10-27 23:42:43 +08:00。报告包含各检查结果及本轮任务 ID，不保存 JWT 或服务密钥。

## 端到端验证的行为

- 独立业务库 50 个合成订单；Agent 库的本地 `orders` 表没有复制种子数据，订单事实经 HTTP 获取。
- 订单查询结果持久化，重启后 `steps.resultData` 完整保留。
- 未审批不产生外部命令；客服不能审批；审批后任务先为 WAITING_EXTERNAL。
- 正常退款最终修改外部订单/支付账本，再结算本地任务。
- 重复任务、重复审批和并发下游重试不增加重复退款；同幂等键改变金额被拒绝。
- 业务已受理但 HTTP 超时后，Agent 先进入 UNKNOWN，再轮询取得凭据；外部账本只有一条退款。
- 下游拒绝导致任务失败并进入人工处理队列；主管可以附原因确认失败处置。
- 长期处理中达到确认预算后停止自动轮询，进入 MANUAL_REVIEW / UNKNOWN_SIDE_EFFECT。
- 未知结果不能直接取消或人为判失败；普通客服不能执行人工操作。
- 业务侧使用独立操作凭据完成合成账本处置，Agent 主管只查询权威回执，随后任务与处理单一并关闭，操作者及原因留痕。
- 未支付订单没有创建外部退款。
- 重启 Agent 后，任务与结构化结果保留。
- 停止业务服务后，readiness 返回 503；直接订单聚合查询返回 503；创建查询任务仍按资源创建合同返回 201，但任务明确为 FAILED、错误码 DOWNSTREAM_UNAVAILABLE，结果为空，不回退到演示数据。

本轮“对账”是按稳定幂等键核对订单、金额、业务引用和最终状态，保证本地意图与远端账本结果一致；不包含银行/支付渠道日账单全量对账。

## 已发现并修复的联调问题

1. 当前 MySQL 驱动在 Map 查询中将 DATETIME 返回为 LocalDateTime，支付查询原本强转 Timestamp 导致 500。已改为 ResultSet 的显式时间读取，并在真实 MySQL 端到端查询中验证。
2. 停机验收最初把“创建任务成功”和“任务业务成功”混为一谈，错误期待创建任务返回 503。已按现有合同同时验证直接查询的 503，以及任务资源 201 + FAILED 的业务结果。
3. 独立业务服务 readiness 增加应用就绪状态检查，确保种子初始化完成后才接受流量。
4. 双实例回归首次因本机 Docker MySQL 冷建库/迁移超过原 180 秒启动等待而中止；增加可配置启动等待后，以 `--startup-timeout 600` 重跑，73/73 通过。业务竞争与结果断言保留。此现象不能作为生产启动性能达标证明。日志中的 prod 配置门禁异常是“拒绝不安全默认配置”测试的预期结果。

## 使用与停止

Agent：`http://localhost:18090`；业务服务：`http://localhost:8091`。本机服务后台运行，日志和 PID 信息位于 `target/business-integration/runtime/`。运行 `python scripts/local_business_stack.py stop` 只停止本轮两个 Java 服务，不删除数据；Docker 数据库可按接入文档停止。

详见 [接入说明与五张流程图](business-integration.md) 与 [原始生产评估](production-assessment-2026-10-27.md)。租户隔离、完整任务 Worker/心跳、真实支付/承运商生产接入、生产监控/审计/灾备等仍按原评估追踪，不能据此宣称全面生产就绪。
