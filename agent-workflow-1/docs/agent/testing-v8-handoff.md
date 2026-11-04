# V8 测试实现与执行交接

2026-11-04。**本文只有测试架构与用例设计；没有新增测试代码，没有执行编译、测试、依赖安装、迁移或联调。** 本轮用户明确把测试实现/执行交给后续模型。实现范围与已知部署边界见 [V8 说明](version-8.md)。

## 最小投入原则

- 默认真实 LLM 调用 0、真实 embedding 调用 0、真实企业 IdP 调用 0。MCP 协议互通不是 LLM 质量评估，不需要模型生成工具参数。
- 一个临时 MySQL 8 库，一组合成用户/订单，真实 Python MCP SDK 服务复用启动一次；不要为每个参数重建 Docker。禁止连接工作库，禁止用生产 token/订单。
- Java 单元/HTTP 替身只覆盖协议拒绝分支；协议正例必须经过实际 SDK 服务，不能只用双方自行编写的 JSON mock 证明互操作。
- 本地生成 RSA 密钥和可控 JWKS/token-exchange 替身，覆盖 OIDC 与交换；HS256 只用于明确开发配置的快速正例，不能替代生产 OIDC 路径。
- 默认先编译 Java、导入 Python 与验证迁移，再跑下面 8 组高风险场景。只执行受影响的已有回归；通过后不自动加全量回归/性能压测/付费调用。

## 测试架构

| 层 | 被测对象 | 替身/真实依赖 | 主要证据 |
| --- | --- | --- | --- |
| 契约与 HTTP | McpContract、McpShipmentClient、凭据交换 | 本地可控 HTTP/TLS server，固定时间/闩锁 | 请求顺序、请求 ID、字节/超时限制、异常码、禁止重定向/回退 |
| 服务端 | app.py 的认证、工具处理、限流与取消 | 真实 MCP SDK + 临时 MySQL + 本地 JWKS | 标准客户端与 Java 的响应形状、最小数据、SQL 权限 |
| 持久权限 | McpGrantService、McpEvidenceGuard、迁移触发器 | MySQL 8，至少两个独立连接 | CAS/回滚、原授权 revision、读取者权限、快照外重查 |
| Agent 集成 | 工具注册、单轮/会话、回放、长期记忆 | 固定 ToolCallingGateway 响应，复用 V6/V7 夹具 | 唯一别名、来源边落库、撤权后的所有正文出口、审计关联 |

## 8 组必要场景

| 编号 | 设置与动作 | 必须断言 |
| --- | --- | --- |
| P1 实际协议正例 | 真实 SDK 服务；给 alice/ORD-1001 授权；Java 完整调用；另用官方客户端读取相同工具一次 | initialize→initialized→list→call；协议 2025-11-25；无 session ID；工具 schema 与本地契约一致；JSON/structuredContent 一致；业务结果与真实测试库一致；source/mcpCallId 与日志/journal 对得上；无真实 LLM |
| P2 契约与响应拒绝 | 参数化错误版本/ID、重复 JSON key、分页、同名 schema 漂移、额外工具、write hint、恶意 description/instructions、错 orderId/ledgerId、字段越界、文本和结构化不一致、SSE/会话响应 | 不调用未允许工具；描述不会进入 prompt；坏结果不写 source、不成为最终证据；错误码稳定；额外姓名/地址/运单号字段拒绝；服务端也拒绝未知参数和其他工具名 |
| P3 用户身份与委托 | 本地 RSA/JWKS；issuer/aud/scope/client/tenant/sub/exp 不符；direct-audience 正例；token exchange 正例、交换改 subject/tenant、错 issued_token_type、无当前身份 | 无权限没有业务查询结果；scope/client 不能在生产缺省；token 只发往配置的目标与 token endpoint；不盲目转发错误 audience；无令牌落库/日志；失效 JWT 不能驱动后台重试；本地模式不能在 production 开启 |
| P4 授权生命周期 | alice 与 Alice、bob、其他 tenant；ADMIN grant/get；普通用户修改；create revision0、并发 CAS、撤销、过期、重新授权；注入审计写失败 | 二进制 subject/tenant 不串数据；ADMIN 查询仍受 grant；变更与事件同事务；30 天期限与 DB 时间一致；冲突 409；更新必增 revision；DELETE/变更主键被触发器拒绝；撤销再授权不复活旧来源 |
| P5 撤销传播 | task A 的物流结果→会话 B→长期记忆来源 C；生成中撤销；共享给 bob；关 MCP/关 MEMORY 后读旧数据；外层 REPEATABLE READ 先读授权再让另一连接撤销 | 单轮模型下一轮与返回前、task 详情/事件、chat 幂等回放、会话 history/summary、记忆 get/recall/派生结果都不能绕过当前权限；同时校验原授权 revision 和当前读者 grant；不被旧事务快照放行；既有来源图关系不丢 |
| P6 故障与资源回收 | 网络拒绝、401/403/429/5xx、慢头/慢体、64KiB+、重定向、断连/取消、三次基础设施失败；Python DB worker 超时但暂未退出 | 网络段总 12 秒涵盖交换和全部往返；body subscriber 有界；取消不泄漏 Java permit；熔断 30 秒恢复；Python 不因请求取消提前释放实际 DB worker permit；同身份跨实例计数超过 120/min 拒绝；无自动重试、本地回退或业务写入 |
| P7 审计与数据库真实性 | 真实只读 DB 用户；不同台账 UUID；日志 capture；journal start/finish 注入失败；进程在 STARTED 后中止 | MCP 账号不能读客户/地址/支付或修改业务/grant；只返回最近 20 条并标截断；独立台账错配拒绝；日志无 bearer/原始 body/敏感异常；审计不可用不返回成功；STARTED 不被误算为成功；请求完成后源码中 source/revision 与持久记录一致 |
| P8 迁移与配置兼容 | 从项目 V9 的临时库升 V10；真实保存/重载任务；启用/关闭 MCP、memory/JDBC 组合；本地旧 ShipmentQueryTool；HTTP commerce 模式；生产配置 | 迁移含 trigger/外键可执行；旧任务不凭空产生 MCP 依赖；始终只有一个 shipment.query bean；enabled+memory persistence 或 HTTP commerce 明确启动失败；关闭 MCP 后旧结果门禁仍存在；配置/证书错误 fail closed；生产 TLS/Host/Origin 边界与文档一致 |

P2/P3 用参数化场景复用进程，不需要为每种坏字段搭新套件。P6 用闩锁/可控完成时间；若固定常量无法注入时钟，只执行一次代表性慢响应与一次熔断恢复，其他断言复用状态，不写大量长 sleep。跨进程限流可复用同一库的两服务实例，不做压力排名。

## 夹具与关键时序

- 仅 2 个合成订单，1 条物流、21 条状态事件；alice/Alice/bob 与一个 tenant B 用户。精确断言最新 20 条按时间正序返回，不能只断言长度。
- 固定 gateway 返回 `shipment_query(orderId=ORD-1001)` 后给出合法引用答案；assert 远端网络方法以及最终 answer 引用实际状态。另一个回答在模型等待期间撤权，最终必须拒绝，而不是只验证下次请求。
- 外层事务测试不得依赖 mock JdbcTemplate：先建立一致性快照，另一连接提交撤权，再执行真实来源校验。观察第二个连接可见新授权，不只断言代码里出现 REQUIRES_NEW 或连接调用。
- 验证 grant 变更后旧 task 始终失效；新 task 重新调用、记录新 revision 后可读。bob 即使是会话参与者/管理员，没有订单 grant 仍不能读原任务正文。
- 检查 MCP 关闭时本地适配器是显式配置选择，而非调用失败后的隐式降级。旧来源受 guard 保护，但不能宣称 V8 给所有原有业务工具补了同一订单 ACL。
- DDL 与权限验收使用独立迁移账号、Java 运行账号和 MCP 账号。测试结束清理专用库；不要为方便删除 grant tombstone 而在生产代码里增加绕过。
- 测试交换端必须实现或准确模拟 RFC 8693 响应；真实企业 IdP 策略、密钥轮换、证书链和网络隔离留作部署验收，不能由本地替身宣称完成。

## 交付要求

后续模型应记录实际运行的命令、版本、通过/失败、未覆盖项；失败修正后只重跑受影响场景。生成经过解析的 Python 完整依赖 lock/SBOM，确认 SDK API、Spring 装配、Java 17 编译以及 MySQL/Flyway 的 trigger 支持。若依赖服务不可用，标明阻塞，不编造通过率，不回退 H2 代替需要真实 MySQL 行为的场景。

不得沿用 V3/V5 的历史测试数字证明 V8 正确。默认成本预算为 0 次真实模型调用；任何以后安排的真实 LLM 演示单独说明样例与调用上限，不属于本次交接已执行内容。
