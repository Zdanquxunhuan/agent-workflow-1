# 现有项目与 toAjob 的差距分析

分析及更新日期：2026-11-05。岗位依据：[toAjob](toAjob.md)。项目依据：当前工作区 Java 源码、RAG V1–V4 与 Java V5–V10 实现、架构记录与已有验证报告。

**当前结论：项目已具备较完整的 Java Agent 面试素材，后续重点是独立验证、运行演示与设计解释。** V4/V5 有小样本证据；V6 短期记忆、V7 长期记忆、V8 MCP、V9 预算/双模型/进度取消、V10 澄清恢复/只读重规划代码均已编写但尚未编译和测试。不能把代码覆盖当作生产经验，也不必等全部版本完成才开始面试。下文保留部分早期基线用于解释演进，当前交付状态以第 5、9 节及各版说明为准。

用户已明确以 AI Agent 面试为最终目标，本文原候选路线现已采纳为 [后续版本计划](rag-production-to.md) 的 V5–V10，并细化了 [V5 执行计划](docs/agent/version-5.md)。V5 已按该路线开发，18 项定向本地测试、1 项隔离迁移及 1 条真实合成联调通过；见 [交付说明](docs/agent/delivery-v5.md)、[验证记录](docs/agent/verification-v5-2026-11-03.md)。既有架构边界和 V4 历史交付范围保留。执行顺序以版本计划为准，本文解释岗位差距与取舍。

## 1. 评估口径

这里评价的是项目能提供什么实现与演示证据，不是评价个人是否已经“熟练”或能够“独立负责”。代码、文档和测试能支持面试说明，但不能单独证明真实生产上线、持续运营或个人能力。

区分四种状态：已有可定位实现；已有基础但缺关键闭环；仅有计划；暂无实现。不按文件数量或测试数量估算岗位匹配百分比。

当前基线：

- Java 17 / Spring Boot 业务 Agent：受约束规划、只读 Function Calling、工具注册、角色与审批、任务持久化、业务 Outbox、外部结果确认及恢复。
- 独立 Python/FastAPI RAG：文档异步入库、真实 BGE-M3/pgvector、中文词法与编号召回、RRF/rerank、权限过滤、V3 文档版本/发布/回滚/回收。
- V3 基础回归共 53 项通过，包含 16 项新增用例，使用真实 PG/S3 和推理 stub；没有因此完成真实 V3 模型链路、页面、工作库升级或生产验收。见 [V3 测试记录](docs/rag/verification-v3-2026-11-03.md)。
- V4 已实现 `/answers`、引用/摘录校验、生成前后权限与版本复核、调用限制和反馈；15 项小样本/核心用例及一条真实 DeepSeek 问答/引用/反馈通过。工作库未升级到 0004，V4 未部署；不代表完整语义质量验收。见 [V4 测试记录](docs/rag/verification-v4-2026-11-03.md)。

## 2. 六项岗位要求逐项对应

| 岗位要求 | 当前实现证据 | 主要差距 | 判断 |
| --- | --- | --- | --- |
| J01：Java Agent 需求、设计、规划、执行、上线 | Java 状态机、DeepSeek JSON 规划、只读工具循环、审批/幂等/恢复；V6–V10 新代码待验证 | 受控重规划已有代码，真实部署与持续运行证据仍缺；会话/委托/取消已有代码，尚需验证 | 已能深入讲设计，不能声称完整生产上线 |
| J02：向量库选型、搭建、维护与高效检索 | PG/pgvector、1024 维 BGE-M3、混合检索/RRF/rerank、ACL/RLS、版本与 GC；V4 单轮引用问答 | HNSW 建索引但当前采用精确检索；规模下 ANN 取舍、维护/容量/恢复经验不足；整库重建与业务回答质量验收未完成 | 六项中较接近，但“搭起来”不等于大规模运维成熟 |
| J03：短期/长期记忆与上下文管理 | V6 会话身份边界、窗口与摘要；V7 MySQL/pgvector 记忆写入、确认、召回、纠错、遗忘和依赖门禁 | 代码未验证；记忆选择效果和实际运行证据待补 | 功能代码已覆盖主要设计，验证仍是缺口 |
| J04：Prompt、多模型、工具链、Java AI 框架 | Spring AI 1.1.2、Function Calling、HTTP 工具；V8 MCP 与 V9 默认/备用模型路由代码 | MCP 互操作、备用模型兼容与来源重授权待验证；未采用通用插件平台 | 框架有有限实践，MCP/多模型目前只有代码证据 |
| J05：延迟、流式、异步、Token、幻觉、检索优化 | RAG 检索/引用门禁；V9 已编写只读问答共享 deadline、预算账本、异步运行、SSE 进度和取消 | 未验证、未量化优化收益；没有正文 token 流；账本不覆盖所有外部生成/embedding 服务 | 有针对性的工程代码，不能宣称已降低延迟/费用或消除幻觉 |
| J06：技术跟踪、选型与评估 | ADR、固定模型 revision、V2 四路检索对照及失败结果记录 | 缺 Java AI 框架/模型选择的实际对照与持续决策记录；现有合成检索集不能代表真实业务质量 | 有方法基础，需要补少量有业务目的的评估 |

## 3. 容易高估或低估的现有能力

### 3.1 已有 Function Calling，不能算从零开始

[DeepSeekToolCallingService](src/main/java/com/yu/agent/workflow/application/DeepSeekToolCallingService.java) 会向模型提供工具 schema，解析 tool calls，执行注册的只读工具，将结果回传模型，并最多进行 4 轮调用。[Gateway](src/main/java/com/yu/agent/workflow/application/DeepSeekToolCallingGateway.java) 使用 `tools`、`tool_choice` 等字段调用模型。

这是实际的工具调用闭环，不只是按关键词挑工具。缺口在标准框架接入、MCP、持续会话、总调用预算和模型路由；不能为了对齐招聘关键词把现有能力全部推倒重写。

### 3.2 已有规划与安全执行，但不是完整的自主重规划

[DeepSeekTaskPlanner](src/main/java/com/yu/agent/workflow/application/DeepSeekTaskPlanner.java) 生成受约束计划，[AgentApplicationService](src/main/java/com/yu/agent/workflow/application/AgentApplicationService.java) 验证并执行。主流程按已有计划顺序运行，工具失败通常终止；只读聊天可以根据工具结果继续调用，但尚无统一的“观察结果 → 判断是否需要补充信息/修订计划 → 有界执行”机制。

岗位中的“自主”不要求开放任意工具或绕过审批。后续可补明确的缺参澄清、只读重规划次数限制和计划修订记录；资金/物流等写操作仍由确定性权限、审批和幂等控制。

### 3.3 四种“记忆”必须区分

| 当前概念 | 实际含义 | 不能直接宣称的能力 |
| --- | --- | --- |
| `InMemoryTaskRepository` / `AGENT_PERSISTENCE=memory` | 任务数据存放在当前进程内存 | Agent 短期或长期语义记忆 |
| `ContextBuilder` | 截取当前任务请求及最近 8 个步骤，限制请求字符数 | 跨会话记忆、按 token 管理的完整 LLM 上下文策略 |
| `DeepSeekToolCallingService.messages` | 一次 HTTP 请求内保存模型/工具往返消息 | 用户下次请求能延续上次对话 |
| `ChatAuditService`、任务表、审计表 | 可追溯执行、幂等回放、恢复依据 | 自动筛选值得记住的事实并在后续任务召回 |

[ContextBuilder](src/main/java/com/yu/agent/workflow/application/ContextBuilder.java) 和 [ChatAuditService](src/main/java/com/yu/agent/workflow/application/ChatAuditService.java) 是可复用基础，但目前 [ChatRequest](src/main/java/com/yu/agent/workflow/api/AgentChatController.java) 只有 message，没有持续 conversation 标识；每次工具聊天重新创建 messages。

同样，企业知识库保存政策文档，长期记忆保存“某个用户/任务在什么条件下确认过什么信息”，两者的来源、有效期和访问范围不同。长期记忆不能简单把所有聊天塞进现有 KB。聊天历史与提供给模型的记忆上下文也应分开；这一概念区分与 [LangChain4j Chat Memory 文档](https://docs.langchain4j.dev/tutorials/chat-memory/) 一致。

### 3.4 Java 框架已有有限实践，仍需更多场景积累

当前 [pom.xml](pom.xml) 已锁定 Spring AI 1.1.2，通过既有 ToolCallingGateway 适配；真实 SDK 协议测试及一条真实模型联合回答通过，旧 RestClient 网关保留。可以讲清框架边界，但单次集成不能等同熟练掌握框架全部能力。

V5 已选择 **Spring AI 接通已有 Java 模型/只读工具链**：这是基于当前 Spring Boot 项目的集成成本判断，不是两个框架的性能排名。Spring AI 官方提供工具调用、聊天记忆和 MCP 集成能力，可覆盖多个岗位要求。[Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)、[Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)、[MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)。

已在 Java 17 / Spring Boot 3.5.16 下编译验证所选版本，后续升级仍须重新核对兼容关系。Spring AI 与 LangChain4j 不必同时引入；也不要把 LangChain4j 与现有 ADR 提及的 LangGraph4j 混为同一个框架。

### 3.5 多个模型进程，不等于多 LLM 编排已经完成

现有 DeepSeek、BGE-M3、reranker 分别负责规划/工具对话、向量化、重排，确实构成多种模型参与的系统；但尚无根据任务复杂度、费用或 SLA 选择不同回答/规划 LLM 的统一策略。

可从“默认模型 + 一个显式备用模型 + 是否允许降级”开始，记录选用原因、耗时和用量。模型调用重试与工具副作用重试必须分开，切换模型不能重做已受理退款。

### 3.6 生产 RAG 是优势，但继续只加 RAG 管理功能会失衡

已有异步入库、混合检索、权限、版本、回收和 V4 单轮引用问答，比纯问答演示更有工程深度。当前 Python RAG 已负责独立知识问答生成；V5 Java 已增加 task/chat 远程证据链路和最终门禁，默认关闭，须配置 tenant 允许列表与授权；生产部署尚未完成。

当前 [HttpRagKnowledgeSearchClient](src/main/java/com/yu/agent/workflow/application/HttpRagKnowledgeSearchClient.java) 仅在显式启用任务 RAG、可信租户和凭据齐备时开放。任务/回放边界与本地持久委托已实现；企业 token exchange 未实现，旧业务系统固定单租户，不能称全链路多租户生产化。

## 4. 与现有开发方向偏离大不大

**整体是中等幅度的补强与重排，不是架构转向。** V4 可信问答已沿原方向完成；现以岗位覆盖为优先目标，把 Java 框架、任务租户化、记忆和模型交互提前，避免连续多个版本只增加 RAG 运维功能。

| 可能的调整 | 相对现有方向的偏离 | 原因 |
| --- | --- | --- |
| V4：回答、证据/引用、拒答、模型超时与预算、问答页面 | 已交付的连续演进 | `/answers` 与证据门禁已实现，直接对应岗位 J02/J04/J05；完整质量/部署验收未完成 |
| Java 任务/聊天租户化与真实 RAG 集成 | 小到中 | 原生产计划已有，岗位目标让它更早成为完整演示链路 |
| Java Spring AI/LangChain4j 适配 | 中 | 增加框架实战，但可通过现有接口渐进接入，不必替换业务状态机 |
| 短期与长期记忆 | 中，且是主要新增工作 | 原计划有会话，但长期记忆策略尚未形成独立实现范围 |
| 一个真实 MCP 只读服务的接入 | 小到中 | 扩展现有工具适配层；若扩大成通用插件平台，范围才会明显膨胀 |
| 流式、模型用量、多 LLM 路由 | 中 | 沿着问答/聊天链路补能力，需要新增状态与成本控制 |
| 改用其他向量数据库、全改 Java RAG、重做工作流框架 | 大，而且当前无必要 | 截图未要求特定向量库、纯 Java 推理或指定 Agent 工作流框架 |

FastAPI RAG 可以保留。Java 继续掌握任务规划、工具选择、审批和业务执行，Python 提供解析、索引、检索/问答能力，这是合理的服务分工。岗位写“基于 Java 技术栈”不等于全部模型推理、文档处理都必须重写成 Java；但需要能清楚演示 Java 本身的 Agent 与 AI 框架能力，不能只剩一层 HTTP 转发。

保留 [独立 RAG 边界](docs/rag/adr-001-service-boundary.md) 和 [显式工作流状态机决策](docs/adr-002-workflow-framework.md)。如果未来框架引入要求改变这两项边界，再单独评审，不在本文直接改决策。

## 5. 已采纳的后续版本安排

原 A–F 是岗位对齐讨论编号，现映射为下列版本；详细范围与验收统一维护在 [主计划第 11 节](rag-production-to.md#11-分阶段实施计划)。V5 本地实现与有限验证已完成；V6–V10 生产目标代码已编写但未编译/测试，V8 交付见 [说明](docs/agent/version-8.md) 与 [测试设计](docs/agent/testing-v8-handoff.md)；V9 见 [说明](docs/agent/version-9.md) 与 [测试设计](docs/agent/testing-v9-handoff.md)，V10 见 [说明](docs/agent/version-10.md)、[测试设计](docs/agent/testing-v10-handoff.md) 和 [演示手册](docs/agent/demo-v10.md)，同样未验证，不承诺缺少模型、业务与部署条件时的日历工期。

| 原阶段 → 版本 | 交付重点 | 可展示的完成标准 |
| --- | --- | --- |
| A → V4，已有小样本证据 | 单轮可信问答 | 有引用、无证据拒答、失权阻断、故障区分；完整业务质量另验收 |
| B → **V5，本地交付** | Spring AI 网关适配、租户/委托、Java 工具与 RAG 联合回答 | 真实 Java Agent 链路可运行，引用可追踪，跨主体/租户及回放/恢复不泄露；拆 V5A/B/C 三项交付 |
| C → V6 | 短期记忆、摘要/token 预算、会话授权 | “那运费呢”能延续指代；不串会话；历史证据重授权、工具消息成对保存/裁剪 |
| D → V7 | 确认的偏好与任务摘要、纠错/过期/遗忘 | 跨会话调用授权记忆，删除后不再召回；实时业务事实仍从工具取得 |
| E → V8 | 已编写独立物流 MCP 服务、Java 协议接入、订单权限与撤销传播；待验证 | 真实 SDK 发现、授权、执行与审计闭环；未允许工具不可调用，旧派生结果不能绕过撤权 |
| E → V9 | 已编写进度流/取消、默认与备用模型、费用预留结算账本及只读问答总预算；待验证 | 解释进度与正文流的差别、路由与实际/未知 usage、预算竞争和取消边界；没有运行测量数据 |
| F → V10 | 已编写持久澄清、同 run 恢复/累计预算、只读受控重规划、计划回放；待验证 | 已交付部署演练、少量测试设计与面试手册；有限纠错、失败停止和恢复仍需实际证据 |

V4 已有基础 Provider usage/耗时，V5 已将它延伸到 Java 工具循环，V9 再补费用/路由/交互策略。估算 token 与供应商返回用量要分开，不把估算记作实际账单。

V5A 框架适配已有只读工具验证；**完整 RAG task/chat、持久会话和记忆上线必须依赖 tenant/主体隔离与可信委托到位**，不能先把未隔离的聊天记录写进长期记忆再补权限。

V4 的独立问答继续在 Python 生成；V5 Java Agent 用 `/search` 取证据，由 Java 模型链路联合业务工具回答并执行最终门禁。每条请求只指定一个主要生成流程，两侧共用证据契约与授权规则，避免同一回答重复生成。

## 6. 关键模块应怎样设计，才真正补上岗位差距

### 6.1 记忆：从可控数据开始，不做“所有聊天自动永久记住”

短期记忆建议由 Java 管理会话权威状态，以 tenant/owner/participants/conversation 共同限定访问。对话历史保存用户实际看到的记录；LLM context 是从历史、摘要、工具结果和当前证据中选择出来的有预算视图。摘要应有原消息范围、版本和更新条件，不能因并发请求互相覆盖。

长期记忆首批只选一两类低风险内容，例如经用户确认的回答格式偏好和已完成任务摘要。可用现有 MySQL 保存权威元数据、独立 PG 表保存向量索引，不要求增加一种数据库；写入异步索引时必须有版本/状态与删除传播，避免把旧向量当成最新记忆。

最少记录：tenant、owner/授权范围、类型、内容、来源消息/任务、确认状态、有效期、修订号、删除状态。采用“候选提取 → 校验或确认 → 存储 → 按权限召回 → 注入上下文”；没有必要每条偏好都向量化，结构化偏好可直接查表。

退款是否成功、订单是否发货等实时业务事实仍从工具取。用户偏好、旧任务摘要和政策知识都不能替代当前业务系统事实。

### 6.2 MCP：先展示协议接入能力，不搭通用平台

优先将一个已有只读订单查询或受控知识查询能力做成独立 MCP 服务，由 Java Agent 作为客户端调用。实现服务允许列表、工具 schema/参数校验、权限上下文、超时和审计。工具发现不自动等于调用授权；工具名称冲突需处理；跨服务身份使用目标服务认可的凭据/委托，不盲目转发任意 Bearer token。

Function Calling 是模型提出工具请求的机制，MCP 是接入外部工具/资源的协议；二者可组合，现有 HTTP 业务工具也可以继续存在。岗位没有要求把所有内部接口都改为 MCP。协议能力参考 [Spring AI MCP 官方说明](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)。

### 6.3 流式、成本和幻觉：明确各自解决的问题

- 流式主要改善用户等待体验，需要测首个有效输出时间与总完成时间，不能仅凭“字在动”就称总耗时下降。任务进度事件也不等于模型 token 流式。
- 严格证据问答可先完整生成/校验后返回；后续流式需要定义权限变更、断连取消与错误事件行为，已经发送的正文无法撤回。
- 每个请求有总调用轮数、总 deadline、输入/输出 token 和费用预算；重试与备用模型也计入预算。
- 引用存在、属于本次证据只是必要条件，不证明答案语义正确。需要冲突处理、无证据拒答和少量人工核对例子，不能宣称“消灭幻觉”。

### 6.4 向量数据库：讲清楚取舍，比换一个名字更有价值

应能解释为什么当前选 PG/pgvector：与事务、权限和版本管理放在一起，复用现有运行环境；同时承认检索规模、向量索引维护和资源竞争的边界。当前 [检索实现](rag-service/app/retrieval/database.py) 与 [生产计划](rag-production-to.md) 表明采用授权集合中的精确距离检索，不能把已建 HNSW 写成“已完成 ANN 调优”。

后续仅当资料量和时延暴露需要时，用一组小而有代表性的样本比较 exact/ANN 的召回、耗时与 ACL 过滤效果，记录参数和取舍。岗位截图没有要求 Milvus、Qdrant 或 Elasticsearch，不应为了关键词引入第二套向量存储。

## 7. 对齐优先级与范围控制

截至 V10，下一步优先事项变为：**让其他模型完成有限验证、按固定合成场景录制演示、独立讲清设计取舍**。V6–V10 已有代码，不应继续用版本数量代替运行证据；后续功能由实际失败或面试反馈驱动。

这不是降低生产要求。权限、凭据、错误隔离和真实业务接入仍按现有计划推进；区别在于暂不优先增加整库影子重建、多向量、GraphRAG、通用插件市场或复杂多 Agent 协作。它们不是截图明确要求，也不直接弥补当前最大缺口。

验证遵循用户当前偏好：少量代表场景、关键边界、按需扩展。每个模块先用正常、越权、超时/预算、失效/删除几类场景；复用已有样本，不为了展示测试数量批量造数据，不在需求分析任务中启动额外测试。

## 8. 最适合这份岗位的最终演示

保留售后业务主线，用一条贯穿场景展示能力：

1. 用户在 Java 对话入口咨询某订单的退款条件，Agent 查询当前订单状态并检索有权读取的政策。
2. 结合知识证据回答，附真实版本引用；资料不够时说明缺口，不替用户编造政策。
3. 用户继续问“那运费呢”，短期记忆保留当前订单指代；更换用户或会话不能读取原会话内容。
4. 用户明确确认“以后优先给我简洁回答”，长期记忆按授权保存偏好，并提供修改/忘记入口。
5. 用户请求退款，模型可提出动作；服务端仍校验角色、参数、审批和幂等，最终从业务系统确认结果。
6. 某个只读查询通过 MCP 完成；页面显示处理进度，后台可定位模型、工具、证据版本、耗时与 token 用量。

这比堆多个框架演示更能说明“需求 → 架构 → 模型/工具 → 记忆 → 可靠执行 → 运维”的完整能力。上述是目标演示，不是当前已全部打通的流程。

## 9. 证据索引与当前不能声称的成果

| 证据 | 可支持的说明 |
| --- | --- |
| [pom.xml](pom.xml) | Java/Spring Boot 技术栈；已锁定 Spring AI 1.1.2，未引入第二套 Java AI 框架 |
| [ToolRegistry](src/main/java/com/yu/agent/workflow/tools/ToolRegistry.java)、[业务接入说明](docs/business-integration.md) | 工具执行控制及本地独立业务系统，不等于生产支付接入 |
| [ContextBuilder](src/main/java/com/yu/agent/workflow/application/ContextBuilder.java)、[AgentChatController](src/main/java/com/yu/agent/workflow/api/AgentChatController.java) | 任务局部上下文与当前聊天入口边界 |
| [V2 检索报告](docs/rag/verification-v2-2026-11-02.md) | 真实模型的小型合成数据对照，有局限和失败记录 |
| [V3 基础测试记录](docs/rag/verification-v3-2026-11-03.md) | 单文档生命周期的基础回归，明确未执行项 |
| [V4 小样本记录](docs/rag/verification-v4-2026-11-03.md) | 单轮引用问答的关键边界及一条真实模型回答；不能推断广泛语义正确率 |
| [生产计划](rag-production-to.md)、[RAG 服务边界](docs/rag/adr-001-service-boundary.md) | 现有路线与后续任务，不是已交付证明 |

现在可以如实介绍“实现了 Java 受控业务 Agent，以及独立的多租户 RAG 检索、文档生命周期和单轮引用问答服务，完成了有限范围验证”。不能提前写成“完整长期记忆平台”“熟练落地 Spring AI/MCP”“大规模 ANN 优化”“全链路多租户生产上线”或“53 项测试证明问答正确”。

**V5 已形成有代码与小样本证据的本地闭环，V6–V10 生产代码已编写，等待其他模型实现并执行 [V6 测试](docs/agent/testing-v6-handoff.md)、[V7 测试](docs/agent/testing-v7-handoff.md) 、[V8 测试](docs/agent/testing-v8-handoff.md) 、[V9 测试](docs/agent/testing-v9-handoff.md) 与 [V10 测试](docs/agent/testing-v10-handoff.md)。V8 已编写目标服务的 token exchange 路径，但企业 IdP 策略配置、工作库升级和生产运行证据仍是缺口，当前能力以交付说明与已执行验证为准。**
