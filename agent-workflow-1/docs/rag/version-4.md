# Version 4：有证据的单轮知识问答

日期：2026-11-03。本版本沿用原 V4 范围：回答 API、引用/权限门禁、DeepSeek Provider、运行控制、问答页面与基础反馈。没有采纳 toAjob 的框架迁移、记忆或 MCP 候选路线。

## 行为与边界

`POST /api/v1/answers` 复用 V2 授权检索、混合召回、重排与块预算。在模型调用前固定选中证据的 chunk/version/hash/content_revision，生成后重新检查**全部发给模型的来源**，不仅检查模型最后引用的子集。撤权、删除、版本切换，包括切换后又回滚原版本导致内容修订变化，都会拒绝返回旧结果。

本轮为单轮、非流式；问答不触发工具、退款或其他业务写入。模型输出 JSON 结论列表，每条必须包含来源 ID 和连续原文摘录。服务端检查 schema、引用属于本次来源、摘录确实在对应正文中；引用 URL/版本号由服务端生成，回答正文由通过校验的结论和引用标号组装。

这能检查引用可追溯性，**不能证明结论在语义上被摘录支持，也不能保证自动识别所有冲突或消除提示注入**。排序分数不作为答案正确概率。模型被要求忽略资料中的指令、只用本次证据，无法回答时明确拒答；仍需用户核对关键政策。

| 情况 | 返回行为 |
| --- | --- |
| 无检索命中 | 200 / `insufficient_evidence` / `no_retrieved_evidence`；不调用回答模型 |
| 模型判断证据不足 | 200 / `insufficient_evidence` / `model_insufficient_evidence`；无生成结论或引用 |
| 有证据回答 | 200 / `answered`，逐条结论、引用与原文摘录 |
| 模型判断资料冲突 | 200 / `conflicting_evidence` / `source_conflict`；至少引用两个来源并展示分歧，非独立冲突检测器 |
| 无效 JSON/截断/空内容 | 502 / `ANSWER_INVALID_OUTPUT`；不展示未经校验正文 |
| 来源 ID 或摘录不匹配 | 502 / `ANSWER_INVALID_CITATION` |
| 生成过程中证据/文档权限变化 | 409 / `ANSWER_EVIDENCE_CHANGED`；KB/租户失权仍按现有 403/404 处理 |
| 模型超时/整体超时 | 504 / `ANSWER_MODEL_TIMEOUT` 或 `ANSWER_DEADLINE` |
| 模型网络/HTTP 错误 | 503 / `ANSWER_PROVIDER_UNAVAILABLE`，不伪装成无证据 |
| 模型余额/额度不足、配置拒绝 | 503 / `ANSWER_PROVIDER_QUOTA` 或 `ANSWER_PROVIDER_CONFIGURATION`，retryable=false，不建议盲目重试 |
| 功能未配置/租户未允许 | 503 / `ANSWERS_DISABLED` 或 403 / `ANSWER_PROVIDER_FORBIDDEN` |
| 并发/频率预算已满 | 429 / `ANSWER_RATE_LIMITED` |

检索故障保留原错误，检索允许的降级通过 `degraded_components` 告知。已检索到正文但没有完整块能装入回答输入预算时，返回 422 `ANSWER_CONTEXT_BUDGET`，不谎称没有知识。

## API

请求字段沿用检索的 `query`、`knowledge_base_ids`、`profile`；top_k 默认 5、最多 8，不接受客户端提交历史消息、系统 Prompt 或任意资料正文。

```json
{
  "query": "已发货订单退款需要谁审批？",
  "knowledge_base_ids": ["知识库 UUID"],
  "top_k": 5,
  "profile": "hybrid-rerank-v2"
}
```

响应主要字段：`answer_id`、`status/reason`、`answer`、`statements`、`citations`、`model`、`prompt_version`、`retrieval_profile_id`、`degraded_components`、`usage`、`prompt_bytes`、分阶段耗时。citation 包含文档/版本/chunk、content_revision/hash、页/章节、原文摘录和鉴权读取 URL。

`POST /api/v1/answers/{answer_id}/feedback` 接受 `rating=helpful|unhelpful` 和可选原因 `inaccurate|incomplete|outdated|other`。只允许回答发起者提交，复核当前 KB/文档读取权限；已回收证据返回 410。同一个回答/用户采用 upsert 更新反馈，不重复计数。此版本不存自由文本意见。

没有回答历史读取 API，避免把未做重授权的持久回答当作安全历史。反馈可针对已换版但仍可读取的历史证据，不强制它仍为 active。

## DeepSeek 与配置

默认使用 `deepseek` Provider，调用配置地址的 `/chat/completions`，启用 JSON Output、非流式、`thinking.type=disabled`，限制输出 tokens；不发送 tools，不自动重试，不自动切换其他模型。协议依据 [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/) 与 [JSON Output](https://api-docs.deepseek.com/guides/json_mode/)。同时保留 `chat_completions` 适配模式，省略 DeepSeek 特有 thinking 参数；未宣称兼容所有厂商。

模板为 [rag-service/.env.example](../../rag-service/.env.example)。复制为已忽略的 `rag-service/.env`，在本机填写 `DEEPSEEK_API_KEY`，将 `RAG_ANSWER_ENABLED=true` 并明确允许的租户列表。`RAG_ANSWER_ALLOWED_TENANTS` 是运营者对向指定模型发送这些租户资料的允许配置，默认空列表。它不是完整的 tenant/KB 管理策略 API。

Compose 仅向 API 容器注入回答模型凭据；Worker、dispatcher 不需要该凭据。显式使用 `--env-file rag-service/.env` 才读取此文件；不会把主机未配置的值猜成有效 key。不要把 `docker compose config` 的完整展开结果或容器环境写进报告。

| 配置 | 默认值 / 作用 |
| --- | --- |
| ANSWER_ENABLED / ANSWER_ALLOWED_TENANTS | false / []，未配置保持关闭 |
| ANSWER_PROVIDER / BASE_URL / MODEL | deepseek / https://api.deepseek.com / deepseek-flash |
| ANSWER_DEADLINE_SECONDS | 40 秒，包含检索、模型与最终复核 |
| ANSWER_MODEL_TIMEOUT | 25 秒模型子预算，同时受剩余总期限约束 |
| ANSWER_MAX_OUTPUT_TOKENS | 1200，单次模型生成上限 |
| ANSWER_PROMPT_MAX_BYTES | 24000，含系统指令、问题与完整证据块的 UTF-8 JSON 字节预算 |
| ANSWER_RESPONSE_MAX_BYTES | 131072，模型 HTTP 响应体上限 |
| ANSWER_REQUESTS_PER_MINUTE / TENANT_CONCURRENCY | 30 / 2，PG 持久租约在 API 多副本间共享 |

表中配置使用 `RAG_` 前缀；`RAG_ANSWER_API_KEY` 在 Compose 中从本地 `DEEPSEEK_API_KEY` 注入。URL 不接受内嵌凭据、query/fragment，生产模式要求 HTTPS；仅运营者配置服务地址，用户请求不能指定 URL。

输入预算是字节数，检索预算仍使用 BGE tokenizer，**都不冒充回答模型的准确 token 数**。`usage` 只有供应商返回有效 prompt/completion 数值时标为 provider，否则为 unavailable/null；不估算费用、不按猜测价格计算账单。无证据路径不调用模型，usage 仍为 unavailable，model=null。

每次问答至多发起一次回答模型调用。网络失败后供应商是否计费可能无法确认；错误调用或不可解析响应的 usage 可能未知，不能把日志中的 null 解释成零费用。用户重复提交是新请求，当前没有回答幂等/答案缓存。

## 持久化、页面与运维

0004 新增 `answer_runs`、`answer_feedback`，使用 tenant RLS 和运行角色授权。保存 actor、KB、来源定位/修订/hash、状态、模型/Prompt 版本、用量、耗时、错误码；**不保存 query、Prompt、模型原始输出、回答正文或摘录**。来源元数据和反馈的自动到期清理尚未实现，需按部署保留策略补齐。

运行租约为整体期限加 5 秒；成功/失败会释放，并记录状态。进程崩溃遗留的 RUNNING 在后续租户请求时标记失败，不永久占用并发名额。元数据写入失败时不返回未经审计完成的成功答案。

页面增加“知识问答”、原文引用、模型用量/耗时、反馈。切换身份/KB、手动发布或回滚后清空旧回答；在途请求使用 generation 检查防止旧响应重新填回页面。内容用 textContent 展示，不执行模型 HTML/Markdown。客户端 Abort 主要阻止旧响应展示，不保证外部供应商停止计费。

这是非流式结果，最终权限检查之后仍存在一般 HTTP 响应的并发时间边界，不能承诺撤回已发送或用户已复制的内容。后来发生发布/撤权，打开引用时仍重新鉴权并标注历史版本。

## 升级与小样本验证

本轮开发/测试不自动升级工作库或替换运行服务。既有 V3 环境先备份、停止 API 写入口，构建 API 共用镜像并执行迁移，再启动服务；如从更早版本升级，还需遵守 V3 对旧 Worker 的停写要求。0004 为加法迁移，不重算向量。

```powershell
# 部署操作，测试时不对工作库执行。
docker compose --env-file rag-service/.env -f compose.rag.yaml stop api
docker compose --env-file rag-service/.env -f compose.rag.yaml build migrate
docker compose --env-file rag-service/.env -f compose.rag.yaml run --rm --no-deps migrate
docker compose --env-file rag-service/.env -f compose.rag.yaml up -d --pull never
```

验证使用自动清理的随机数据库/bucket；首轮只运行 V4 小样本与核心用例，不重跑全部历史矩阵：

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/rag-service:/app" api python scripts/run_tests.py pytest -q -p no:cacheprovider tests/test_answers.py tests/test_core.py
# 单条真实 DeepSeek 调用；词法检索、真实 PG/S3，入库向量为明确测试替身。
docker compose --env-file rag-service/.env -f compose.rag.yaml run --rm --no-deps -v "${PWD}/rag-service:/app" -v "${PWD}/docs/rag/reports:/reports" api python scripts/run_tests.py python scripts/acceptance_v4.py /reports/v4-deepseek-2026-11-03.json
```

真实调用脚本只有一篇合成短政策和一条问题，不是 BGE/MQ/语义检索质量或并发压测。实际结果见 [V4 验证记录](verification-v4-2026-11-03.md)：15 项本地小样本/核心测试通过，首次真实请求 402；用户充值后仅复验同一条，真实回答、原文引用和反馈通过。保留首轮失败及充值后成功两份报告。

## 未纳入本次

query rewrite、多轮会话/长期记忆、SSE token 流、Java 任务租户迁移、Spring AI/MCP、自动语义事实核验、独立冲突检测、多模型路由、费用配额、完整 tenant 配置 UI、历史回答 API、自动元数据保留清理、生产容量和 HA 演练。V4 不解除 Java 的 `RAG_TASK_TENANCY_REQUIRED` 门禁。
