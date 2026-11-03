# 现有项目与 toAjob 的差距分析

分析及更新日期：2026-11-03。岗位依据：[toAjob](toAjob.md)。项目依据：当前工作区 Java 源码、RAG V1–V4 实现、架构记录与已有验证报告。

**结论：方向没有走偏，已有业务 Agent 与 RAG 工程基础；能力分布偏重业务执行可靠性和知识库治理，距离岗位强调的“Java AI 框架 + 会话记忆 + 多模型编排 + 交互优化”仍有明显缺口。** V4 可信问答已实现并完成小样本验证，但仍不能覆盖这份招聘需求；下一版优先补 Java 框架与安全 RAG 集成。

用户已明确以 AI Agent 面试为最终目标，本文原候选路线现已采纳为 [后续版本计划](rag-production-to.md) 的 V5–V10，并细化了 [V5 执行计划](docs/agent/version-5.md)。本次只更新规划与状态，不开发功能或运行额外模型测试；既有架构边界和 V4 历史交付范围保留。执行顺序以版本计划为准，本文解释岗位差距与取舍。

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
| J01：Java Agent 需求、设计、规划、执行、上线 | Java 状态机、DeepSeek JSON 规划、最多 4 轮只读工具调用、审批/幂等/恢复；本地独立业务系统接入 | 一次规划后按既定步骤执行为主；缺跨请求持续对话、基于结果的受控重规划、完整任务租户化与生产运行证据 | 基础较强，离完整生产 Agent 仍有中等差距 |
| J02：向量库选型、搭建、维护与高效检索 | PG/pgvector、1024 维 BGE-M3、混合检索/RRF/rerank、ACL/RLS、版本与 GC；V4 单轮引用问答 | HNSW 建索引但当前采用精确检索；规模下 ANN 取舍、维护/容量/恢复经验不足；整库重建与业务回答质量验收未完成 | 六项中较接近，但“搭起来”不等于大规模运维成熟 |
| J03：短期/长期记忆与上下文管理 | 单请求内 messages、任务局部上下文裁剪、任务/聊天审计记录 | 缺 conversation 身份边界、跨请求记忆、摘要/token 窗口、长期记忆写入/召回/纠错/遗忘策略 | 最大功能缺口之一 |
| J04：Prompt、多模型、工具链、Java AI 框架 | Java Prompt、直接 HTTP 模型客户端、真实 Function Calling、业务工具/RAG HTTP 适配 | pom 未引入 Spring AI/LangChain4j；无 MCP 接入；无面向任务的多 LLM 路由/用量治理；Prompt 版本化不足 | Function Calling 已有，框架与模型治理缺口较明显 |
| J05：延迟、流式、异步、Token、幻觉、检索优化 | MQ 异步入库、业务 Outbox、检索 deadline/限流/降级、RRF/rerank；V4 回答证据门禁、Provider usage 与小型评测 | Java 联合回答尚无等效证据门禁；缺流式、统一用量账本与费用预算；Agent 主执行未形成完整异步运行平台 | RAG 生成侧已有基础，Java 交互与成本治理仍有差距 |
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

### 3.4 Java 框架经验确实缺，Spring Boot 本身不能代替

当前 [pom.xml](pom.xml) 有 Spring Boot、Web、JDBC、安全等依赖，没有 Spring AI 或 LangChain4j。模型网关主要由 `RestClient` 手写，说明已有协议与控制边界经验，但不能称为已熟练使用上述 AI 框架。

若对齐此岗位，我倾向**先选一个框架接通已有 Java 模型/只读工具链，优先评估 Spring AI**：这是基于当前 Spring Boot 项目的集成成本判断，不是两个框架的性能排名。Spring AI 官方提供工具调用、聊天记忆和 MCP 集成能力，可覆盖多个岗位要求。[Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)、[Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)、[MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)。

接入前仍需核对所选发布版与项目 Spring Boot/Java 版本的兼容关系，不因文档展示新版本就直接升级主项目。Spring AI 与 LangChain4j 不必同时引入；也不要把 LangChain4j 与现有 ADR 提及的 LangGraph4j 混为同一个框架。

### 3.5 多个模型进程，不等于多 LLM 编排已经完成

现有 DeepSeek、BGE-M3、reranker 分别负责规划/工具对话、向量化、重排，确实构成多种模型参与的系统；但尚无根据任务复杂度、费用或 SLA 选择不同回答/规划 LLM 的统一策略。

可从“默认模型 + 一个显式备用模型 + 是否允许降级”开始，记录选用原因、耗时和用量。模型调用重试与工具副作用重试必须分开，切换模型不能重做已受理退款。

### 3.6 生产 RAG 是优势，但继续只加 RAG 管理功能会失衡

已有异步入库、混合检索、权限、版本、回收和 V4 单轮引用问答，比纯问答演示更有工程深度。当前 Python RAG 已负责独立知识问答生成；Java 虽能生成工具聊天答复，远程 RAG 尚未打通完整 task/chat 链路，Java 联合回答仍需自己的最终证据门禁。

当前 [HttpRagKnowledgeSearchClient](src/main/java/com/yu/agent/workflow/application/HttpRagKnowledgeSearchClient.java) 用 `RAG_TASK_TENANCY_REQUIRED` 阻止未租户化任务承载远程知识证据。这个限制有明确理由：任务、聊天审计、幂等回放、后台恢复需要一起建立 tenant 边界与可信委托。为了演示效果直接解除限制，会绕过现有安全设计。

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

原 A–F 是岗位对齐讨论编号，现映射为下列版本；详细范围与验收统一维护在 [主计划第 11 节](rag-production-to.md#11-分阶段实施计划)。V5–V10 均未实现，不承诺缺少模型、业务与部署条件时的日历工期。

| 原阶段 → 版本 | 交付重点 | 可展示的完成标准 |
| --- | --- | --- |
| A → V4，已有小样本证据 | 单轮可信问答 | 有引用、无证据拒答、失权阻断、故障区分；完整业务质量另验收 |
| B → **V5，下一版** | Spring AI 网关适配、租户/委托、Java 工具与 RAG 联合回答 | 真实 Java Agent 链路可运行，引用可追踪，跨主体/租户及回放/恢复不泄露；拆 V5A/B/C 三项交付 |
| C → V6 | 短期记忆、摘要/token 预算、会话授权 | “那运费呢”能延续指代；不串会话；历史证据重授权、工具消息成对保存/裁剪 |
| D → V7 | 确认的偏好与任务摘要、纠错/过期/遗忘 | 跨会话调用授权记忆，删除后不再召回；实时业务事实仍从工具取得 |
| E → V8 | 一个真实只读 MCP 服务 | 发现、授权、执行与审计闭环；未允许工具不可调用 |
| E → V9 | 流式/取消、默认与备用模型、用量账本/总预算 | 量化首个有效输出与完成时延，解释路由与实际/未知 usage，模型重试不重做副作用 |
| F → V10 | 缺参澄清、只读受控重规划、部署演练与面试材料 | 有限纠错、可追踪计划修订、失败停止和恢复证据，串起完整售后演示 |

V4 已有基础 Provider usage/耗时，V5 将它延伸到 Java 工具循环，V9 再补费用/路由/交互策略。估算 token 与供应商返回用量要分开，不把估算记作实际账单。

V5A 框架适配可先用已有业务只读工具完成；**完整 RAG task/chat、持久会话和记忆上线必须依赖 tenant/主体隔离与可信委托到位**，不能先把未隔离的聊天记录写进长期记忆再补权限。

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

若只选择下一步最影响岗位覆盖的三项：**V5 Java AI 框架与安全 RAG 集成、V6 短期记忆、V7 长期记忆**。V4 可信问答已有基础，MCP、流式与模型成本随后按小模块补齐。当前版本与回收能力可以作为工程亮点，不需要再连续扩展多个治理版本才开始记忆开发，也不必等 V10 才开始面试。

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
| [pom.xml](pom.xml) | Java/Spring Boot 技术栈；目前未引入 Spring AI/LangChain4j |
| [ToolRegistry](src/main/java/com/yu/agent/workflow/tools/ToolRegistry.java)、[业务接入说明](docs/business-integration.md) | 工具执行控制及本地独立业务系统，不等于生产支付接入 |
| [ContextBuilder](src/main/java/com/yu/agent/workflow/application/ContextBuilder.java)、[AgentChatController](src/main/java/com/yu/agent/workflow/api/AgentChatController.java) | 任务局部上下文与当前聊天入口边界 |
| [V2 检索报告](docs/rag/verification-v2-2026-11-02.md) | 真实模型的小型合成数据对照，有局限和失败记录 |
| [V3 基础测试记录](docs/rag/verification-v3-2026-11-03.md) | 单文档生命周期的基础回归，明确未执行项 |
| [V4 小样本记录](docs/rag/verification-v4-2026-11-03.md) | 单轮引用问答的关键边界及一条真实模型回答；不能推断广泛语义正确率 |
| [生产计划](rag-production-to.md)、[RAG 服务边界](docs/rag/adr-001-service-boundary.md) | 现有路线与后续任务，不是已交付证明 |

现在可以如实介绍“实现了 Java 受控业务 Agent，以及独立的多租户 RAG 检索、文档生命周期和单轮引用问答服务，完成了有限范围验证”。不能提前写成“完整长期记忆平台”“熟练落地 Spring AI/MCP”“大规模 ANN 优化”“全链路多租户生产上线”或“53 项测试证明问答正确”。

**岗位对齐路线已采纳为后续计划；下一版按 V5A 框架、V5B 隔离/委托、V5C 联合回答实施。规划采纳不等于功能完成，当前能力仍以代码和已执行验证记录为准。**
