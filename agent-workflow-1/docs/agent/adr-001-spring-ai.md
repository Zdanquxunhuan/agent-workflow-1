# ADR：Spring AI 适配模型，应用保留执行控制

日期：2026-11-03；状态：已实现，有限验证。

选择 Spring AI 1.1.2 的 spring-ai-openai 核心依赖，通过既有 ToolCallingGateway 适配，保留原审批、审计和业务幂等。Java 17 / Boot 3.5.16 已编译并通过本地 SDK 协议测试及一次真实 DeepSeek 合成样例。

设置 internalToolExecutionEnabled(false)，工具执行仍由应用最多 4 轮循环控制，SDK 最多一次尝试。白名单、参数、角色、租户及审批由服务端判断；身份不作为模型可填写参数。参考 [Spring AI 1.1 调用方工具执行控制](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html#_user_controlled_tool_execution)。

选型基于现有 Spring 工程接入成本，不是框架性能排名；不同时引入 LangChain4j。legacy 显式保留，故障不自动切换。TaskPlanner 暂不重写。旧网关基础测试与新网关协议测试分别验证，未做真实模型 A/B。

Python RAG 独立部署，Java 取证据生成本次 Agent 答案，避免再调用 Python /answers 改写。任务按租户隔离，旧业务适配器限定单一租户。本地委托可演示恢复；企业凭据交换未实现时关闭该路径。

代价是仍需维护消息转换和应用调用预算；收益是引入框架不改变已有业务执行语义。引用结构正确与单样例通过均不能等同生产语义质量。
