# V8：独立只读 MCP 物流服务

2026-11-04。**生产代码与测试设计已编写；未编译、未编写或执行测试、未安装依赖、未迁移工作库、未部署，也没有真实模型调用。** 测试实现及执行交给后续模型，见 [测试交接](testing-v8-handoff.md)。V6/V7 的待验证状态不因本次开发改变。

## 调用链与版本选择

```text
模型 Function Calling / 确定性计划
  → ToolRegistry：角色、固定业务租户、只读工具
  → shipment.query（启用后绑定 McpShipmentQueryTool）
  → 当前用户凭据 / 配置的 OAuth token exchange
  → initialize → notifications/initialized → tools/list → tools/call
  → 独立 Python MCP 服务 shipment_track
  → JWT + scope/client/tenant + 订单授权 → JDBC 商业台账中的物流表
  → 共享 JSON 契约、台账 ID、授权修订号校验
  → 来源依赖 + 调用审计 → 受引用约束的回答
```

Python 使用官方 MCP SDK `1.30.0`；两端固定协议 `2025-11-25`，使用 **无会话、JSON 响应的 Streamable HTTP**。这是明确限定的互操作配置，不能称为支持任意 MCP server，也不宣称采用最新协议。Java 实现这一小段协议，复用现有 Spring AI 工具循环，避免引入会改变现有 Spring AI 依赖关系的 MCP starter。协议升级需同时变更两端及契约并重新验收。

参考：[固定版本 SDK](https://github.com/modelcontextprotocol/python-sdk/tree/v1.30.0)、[Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[工具协议](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[OAuth token exchange](https://www.rfc-editor.org/rfc/rfc8693)。本实现不提供 SSE 工具结果、持久 MCP session、sampling、动态 server 注册、资源订阅或写工具。

## 可执行的权限与信任边界

- 本地别名仍是 `shipment.query`，远端名称固定为 `shipment_track`，来源标识为 `mcp:shipment-service/shipment_track`。一次发现只接受一个符合本地允许列表的工具；发现不会自动增加模型工具。
- 唯一参数为 `orderId`。模型不能指定服务 URL、用户、租户、SQL、凭据或远端工具名称。远端描述、初始化 instructions、错误文本不进入模型指令。
- 输入/输出契约来自同一份 [shipment-v1.json](../../src/main/resources/mcp/shipment-v1.json)。Java 校验发现的 schema、四项 annotations、JSON-RPC ID/版本、结构化内容及对应 JSON 文本；任何不一致均失败。Java 校验器仅支持此本地 schema 使用的子集，不是通用 JSON Schema 引擎。
- 输出限订单/物流 ID、承运商、状态、时间与最近 20 条状态事件，超出时 `truncated=true`。不返回收件人、地址、客户 ID、运单号、事件地址或自由描述。`grantRevision`、`ledgerId` 仅用于服务端校验；模型收到来源、观察时间与 `mcpCallId`。
- 服务必须与 Java 使用同一份 MySQL 业务台账，并绑定同一个 `COMMERCE_TENANT` / `SHIPMENT_TENANT`。迁移生成的台账 UUID 会随结果返回并由 Java 对照本库；另一个独立建库不能仅靠相同订单 ID/修订号通过。备份克隆仍须隔离地址和身份配置，UUID 不是跨环境鉴权凭据。
- 现有独立 `business-system` 没有物流查询能力，因此 V8 启用时拒绝 `COMMERCE_GATEWAY=http`。保留 `sandbox` 历史配置名只表示使用项目自身台账；生产禁用 demo seed，并由实际业务流程维护物流表。V8 不补造独立业务系统的物流数据，也不把已有写工具变成新的生产物流系统。

服务验证 RS256 签名、固定 issuer/JWKS、audience=完整 `/mcp` URL、`shipment:read` scope、允许的 `client_id`（或 `azp`）、tenant 与 subject。订单权限由 `mcp_order_grants` 决定，默认空表，管理员也必须获得查询目标订单的授权；MCP 服务不信任 Java 请求里自行声明的角色。

Java 只接受当前经过 Spring Security 验证、与 ToolContext 的 subject/tenant 一致的 JWT。只有 audience 已包含 MCP resource 才直接发送；否则向**配置的 HTTPS 授权服务器**按 RFC 8693 交换令牌。交换结果必须保持 subject/tenant，且目标 audience 正确；JWT 签名最终由 MCP 服务校验。不缓存/落库令牌，不使用无用户上下文的服务账号冒充用户，不从远端错误或 metadata 自动选择 token endpoint。授权服务器需要支持这个委托策略；代码不会替部署方配置 IdP。

开发模式显式设置 `SHIPMENT_ENVIRONMENT=development`、`SHIPMENT_AUTH_MODE=local`，才允许 HS256 和缺失 client/scope 时的开发默认值；仍验证签名、issuer、目标 audience、tenant、subject、有效期和订单授权。生产使用 OIDC、HTTPS、验证证书与主机名的 MySQL TLS；Java `prod` 下也要求 `MYSQL_URL` 的 `sslMode=VERIFY_IDENTITY`。

## 授权管理与撤销传播

`GET /api/v1/mcp/shipment/grants?subjectId=alice&orderId=ORD-1001` 返回当前授权元数据；不存在时 revision=0。`PUT` 仅允许固定业务租户的 ADMIN，示例请求结构如下，时间需由调用方填写真实 Unix 秒：

```json
{"subjectId":"alice","orderId":"ORD-1001","enabled":true,"expiresAt":1900000000,"expectedRevision":0,"reason":"指定订单售后跟进"}
```

示例时间只是字段格式，不能直接拿来部署。启用授权的期限须在数据库当前时间之后且不超过 30 天；更新携带当前 revision。撤销用相同入口 `enabled=false`，保留 tombstone。CAS 冲突返回 409，应重新读取后明确决定，不盲目重发。变更与 `mcp_grant_events` 同事务。没有公开物理删除 API；数据库触发器禁止删除、修改授权主键，所有更新自动增加 revision，重新授予也不会复活旧答案。

每次成功调用把 task/order/original subject/revision 写入 `mcp_task_sources`。任务展示、幂等回放、会话历史及长期记忆的来源图遍历时，同时检查原授权仍为相同 revision，以及当前读者仍有该订单的有效授权。模型下一轮调用前、最终回答返回前再次检查当前任务。关闭 MCP 或记忆功能后，JDBC 的来源校验仍保留。

来源边读取参与原事务；授权查询使用独立 autocommit 连接，避免外层 MySQL REPEATABLE READ 快照读到旧权限。校验承诺是“在使用/返回前重新检查”，无法追溯收回已经交付给客户端的数据；也不是把网络发送与授权变更做成一个原子事务。撤销后需要重新查询生成新任务；本版不提供旧来源 revision 的静默续期。

## 资源限制、错误与审计

| 位置 | 实现的限制 |
| --- | --- |
| Java 网络 | connect 2 秒；一次调用内凭据交换与全部 MCP 往返共享 12 秒；完整响应上限 64 KiB；不跟随重定向、不自动重试 |
| Java 并发 | 每实例最多 8 个 MCP 网络调用；连续 3 次指定基础设施/协议故障后打开熔断 30 秒；不是跨实例全局熔断器 |
| Python | MCP body 16 KiB；MCP handler 10 秒；工具 DB 工作等待 7 秒；DB worker 最多 8，取消后仍持有许可直到实际线程退出；Uvicorn 并发限制 32 |
| 数据库 | 连接 2 秒、读写 3 秒、锁等待 2 秒；物流 SELECT hint 2 秒；按 tenant/subject 的共享分钟计数限制 120 次工具调用；订单查询前后检查 grant |
| Java 来源校验 | SQL query timeout 2 秒，连接获取使用现有池超时；复用来源图的 200 节点/15 秒协作式预算 |

12 秒是 MCP **网络段**总上限，不包括审计与来源数据库工作，也不是整个 Agent 请求的硬截止；现有 workflow deadline 在步骤/模型轮边界检查，V9 再统一请求预算和交互取消。增加 Java 请求并发时，必须为外层事务与独立校验/审计连接同时预留池容量，示例池上限 20，不能把 8 个网络许可当成整站容量证明。

常见错误：无身份/越权 `MCP_IDENTITY_REQUIRED`、`MCP_ACCESS_DENIED`；旧来源 `MCP_EVIDENCE_CHANGED`；超时 `MCP_TIMEOUT`；过载 `MCP_BUSY`/`MCP_RATE_LIMITED`；契约漂移 `MCP_CONTRACT_MISMATCH`；传输/格式 `MCP_PROTOCOL_ERROR`；超大正文 `MCP_RESPONSE_TOO_LARGE`。故障不切回本地物流数据，且不会触发退款、改地址、创建物流等副作用。

`mcp_tool_calls` 在调用前写 STARTED，完成时记录错误码/耗时，独立事务保留失败轨迹；调用 UUID 同时是远端 `tools/call` JSON-RPC ID。进程崩溃可能留下 STARTED，应作为“结果未确认”告警，不能当成功，也不自动重跑。服务日志不记 bearer、RPC body 或原始异常；工具结果与 mcpCallId 留在受原权限门禁保护的任务步骤中。部署告警应覆盖 STARTED 长时间未完成、MCP 错误率/延迟、限流、授权变更及审计写入失败。

## 部署交接

1. 在专用验证库先验收 [V10 迁移](../../src/main/resources/db/migration/V10__mcp_shipments.sql)。应用版本 V8 与数据库迁移 V10 是不同编号。升级要先备份，使用具备 DDL/TRIGGER 权限的迁移账号；运行账号不应拥有这些权限。此次没有迁移任何库。
2. 创建独立 MCP DB 账号，按 [权限模板](../../mcp-shipment/database-permissions.sql) 授权；仅物流必要列和授权/台账只读，配额表允许有限写入。不要授予业务写权限或数据库级 ALL。Java 权限增量也写在模板中。
3. 以 [服务配置](../../mcp-shipment/.env.example) 与 [Java 配置](../../mcp-shipment/agent.env.example) 为模板，注入真实 OIDC、客户端密钥、DB 密码和 CA。秘密进入部署 secret 系统，不进入 Git。生产必须显式启用 Java `prod` profile。
4. 从项目目录以 `mcp-shipment/Dockerfile` 构建镜像，上下文为本项目根。容器非 root，8094 只开放给受控入口；TLS 反向代理保留经过配置的 Host，限制请求大小/连接数、拒绝外部直连内部明文端口。Host/Origin 显式允许列表不接受 `*`。
5. `/health/ready` 检查表的可读性，不替代鉴权、写配额和真实协议验收。健康端点只供内部探针，代理限制访问频率；身份 metadata 端点可供授权客户端读取。数据库、issuer/JWKS/token endpoint 的出站地址须由网络策略限制。
6. 管理员为合成用户和订单创建授权，再由后续模型按测试交接执行一次真实 SDK 互通（无需真实 LLM）。确认权限与回放门禁之后才开放业务流量。

新依赖已固定直接版本，尚未解析/审计完整传递依赖和镜像；发布流水线应生成带哈希的完整 lock/SBOM、固定验证后的镜像 digest，并检查漏洞与许可证。这些产物目前没有，不能把本文当作完成生产验收的证明。

定期清理超过一天且不再活跃的 `mcp_admissions`；工具审计、授权变更审计按组织保留期限归档后分批清理。授权 tombstone 不删除，task sources 在对应任务及派生记录仍存在时不能删。不可通过删除来源行、关闭校验、回滚到不认识来源表的旧 Java 版本来恢复访问。功能关闭只停止 MCP 执行；原有本地适配器是另一个独立配置的授权范围，不能把切换开关当作撤销控制。

## 面试解释重点

可以解释 Function Calling 负责模型提出请求、MCP 负责外部工具协议；工具发现与执行授权分离；audience 与用户委托；schema/结果双向校验；来源 revision 如何穿过会话和长期记忆传播撤销。当前准确表述是“完成了该限定协议与权限链路的生产目标代码，等待独立验证”，不是“已在生产稳定运行的通用 MCP 平台”。
