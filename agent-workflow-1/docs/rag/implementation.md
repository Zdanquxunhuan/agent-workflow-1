# RAG V4 实现与本地运行

本次实现的是 M1/M2 的第一条真实链路：上传 → PostgreSQL 任务/Outbox → RabbitMQ → Worker → 解析/分块 → BGE-M3 → pgvector → 授权检索/引用。完整生产路线仍以 `rag-production-to.md` 为准；本文不代表生产发布验收完成。

V1 历史验收见 [记录](verification-2026-11-02.md)。V2 增加中文词法/编号、RRF、真实 reranker、证据预算、检索诊断、租户限流与评测工具，设计见 [ADR-002](adr-002-hybrid-retrieval.md)，历史结果见 [V2 验证](verification-v2-2026-11-02.md)。

V3 增加单文档版本生命周期，详见 [V3 交付与升级说明](version-3.md) 与 [V3 基础测试记录](verification-v3-2026-11-03.md)。V4 增加单轮问答、证据门禁、DeepSeek Provider 和反馈，详见 [V4 说明](version-4.md) 与 [V4 小样本记录](verification-v4-2026-11-03.md)。15 项小样本/核心测试通过；真实 DeepSeek 首次返回 402，用户充值后同一条问题的回答/引用/反馈复验通过。工作库未升级，V4 未部署。OpenAPI 按当前 0.4.0 路由生成。

## 本次能力

- 独立 FastAPI 服务、Alembic 迁移、非超级用户运行账号、tenant RLS；API、Worker、队列调度使用分离账号。
- 租户成员、KB 读/上传/管理权限、文档读权限收窄；引用与原件重新授权；撤销 KB 权限或逻辑删除立即影响新读取。
- TXT、Markdown、文本 PDF、DOCX；大小、UTF-8、PDF 文件签名、DOCX 展开大小/宏限制；空内容不发布。
- 固定 `BAAI/bge-m3` revision `5617a9f61b028005a4858fdac845db406aefb181`，真实 1024 维 CLS/归一化 dense 推理。tokenizer offset 保留原文，按真实 token 切块。
- 同键上传回放/冲突检查、事务 Outbox、publisher confirm、late ACK、数据库租约/心跳/fencing、有限重试、失败任务人工重试、取消和原子文档发布。
- V3 新增：不可变新版上传、READY 与发布状态分离、content_revision CAS、发布/回滚审计、历史版本引用/原件、版本比较、保留保护和显式排队的可恢复 GC；这些新增能力待独立验证。
- 检索默认使用 hybrid-rerank-v2：授权集合内 dense-exact、中文词法和编号三路召回，RRF 后使用独立 reranker。保留四种 profile 对照；建有 HNSW，但尚未启用 ANN。
- 原生 HTML/CSS/JS 管理页面，包含开发登录、KB 管理、上传、进度、搜索、引用查看、成员授权与撤销。没有前端构建依赖；后续复杂交互再迁移 React。
- Java `KnowledgeSearchClient` 的 local/http 适配与无状态 `/api/v1/knowledge/search` 桥接。
- V4：单轮非流式回答、结构化结论/原文摘录校验、生成前后全来源权限/修订复核、模型输入/输出/时间预算、租户共享限流、元数据记录和基础反馈。页面使用当前 KB/检索 profile；模型默认关闭，需明确配置密钥和允许发送资料的租户。

## 启动

在 `pom.xml` 所在目录执行，需要 Docker Desktop Linux containers。下面用于新建开发环境；**已有 V2 数据应先按 [V3 升级步骤](version-3.md#升级与验证交接) 停止旧 API/Worker/dispatcher；V4 的 0004 与模型配置见 [V4 升级说明](version-4.md#升级与小样本验证)。** 启用问答需显式添加 `--env-file rag-service/.env`，没有密钥仍可使用检索。开发栈使用约 16 GiB Docker 可用内存中的部分资源；embedding 与 reranker 两个 CPU 进程分别限制 6 GiB，首次需要各下载约 2.3 GB 权重，实际速度取决于网络。

```powershell
docker compose -f compose.rag.yaml build
docker compose -f compose.rag.yaml run --rm --no-deps --user 0 -e DOWNLOAD_WORKERS=8 reranker python download_reranker.py
docker compose -f compose.rag.yaml up -d --pull never
docker compose -f compose.rag.yaml ps -a
docker compose -f compose.rag.yaml logs -f worker embedding dispatcher
```

API 镜像由 `migrate` 服务构建，其他运行角色共用。`migrate`、`seed` 和 `backfill` 正常以 0 退出；其余服务长期运行。开发初始化只创建 demo/other 租户及 admin/alice/bob 成员，不创建写死的知识文档。

Worker 等模型健康后再启动，避免冷启动加载权重时耗尽已有任务的重试次数。运行中模型短暂不可用仍由任务重试/租约机制处理。

入口：

| 服务 | 本地地址 | 说明 |
| --- | --- | --- |
| RAG 管理台 | http://localhost:8091 | 选择 demo/admin 开发登录，然后创建 KB、上传文件 |
| OpenAPI JSON | http://localhost:8091/openapi.json | 由实际 FastAPI 路由生成 |
| 就绪探针 | http://localhost:8091/health/ready | PG、对象存储、模型 profile；下载未完成时返回 503 |
| RabbitMQ 管理台 | http://localhost:15678 | 本地账号 rag / rag_dev_only |
| 对象存储控制台 | http://localhost:9011 | 本地账号 rag_local / rag_object_dev_only |
| PostgreSQL | localhost:5438 / rag | 本地管理账号 postgres / postgres_dev_only；API 不使用此账号 |

端口仅绑定回环地址。上述凭证只用于本地开发，不能复制到生产。

可以停止容器而保留数据：

```powershell
docker compose -f compose.rag.yaml stop
docker compose -f compose.rag.yaml start
```

PostgreSQL、MQ、原件和模型分别在命名卷中保存；`down -v` 会删除这些数据，不作为日常停止命令。

## 网络不稳定时

Python/RabbitMQ 官方镜像可从官方 ECR 镜像仓库获取，然后使用本地标签：

```powershell
docker pull public.ecr.aws/docker/library/python:3.12-slim-bookworm
docker tag public.ecr.aws/docker/library/python:3.12-slim-bookworm python:3.12-slim-bookworm
docker pull public.ecr.aws/docker/library/rabbitmq:4.1-management-alpine
docker tag public.ecr.aws/docker/library/rabbitmq:4.1-management-alpine rabbitmq:4.1-management-alpine
docker compose -f compose.rag.yaml build --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
```

默认 PyPI 来源保留官方地址；build arg 仅在需要时选择镜像。MinIO 的 Docker Hub/Quay/二进制下载在本机不可用，因此 Dockerfile 从固定官方源码 tag 构建，通过 Go 模块校验和验证依赖；需要可访问 ECR、Go 模块代理和校验和服务。后续生产使用组织批准的对象存储和镜像仓库。

模型自动加载也可以提前下载到模型卷。`rag-inference/download_model.py` 从官方 HF 固定 revision 下载必要文件，支持部分传输重试，完成后记录各文件 SHA256 与 manifest，再由推理进程加载。下载不会用随机向量替代，也不会将权重提交到 Git。

在镜像构建完成后、启动推理服务前可执行：

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps --user 0 -v "${PWD}/rag-inference/download_model.py:/app/download_model.py:ro" embedding python download_model.py
```

下载完成后重新运行 `up -d --pull never`。已经下载好的模型卷可以反复使用，不需要重新下载。

## 测试

本节保留测试入口；V3 已新增 16 项用例，连同既有回归合计 53 项通过，执行边界见 [测试记录](verification-v3-2026-11-03.md)。完整候选场景仍保留在 [testing-v3-handoff.md](testing-v3-handoff.md)，未全部执行。

核心测试：

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps api pytest -q tests/test_core.py
```

真实 PostgreSQL/对象存储集成测试使用明确的模型 stub，验证权限、事务、幂等、删除、撤权在途请求、租约恢复与 Outbox 重投。测试工具创建独立的随机测试数据库和 S3 bucket，结束后清理，不向工作知识库写入模拟向量，也不需要停止工作队列：

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps api python scripts/run_tests.py
```

真实 BGE-M3 / MQ 端到端验收不替换模型或队列：

```powershell
docker compose -f compose.rag.yaml exec api python scripts/acceptance.py
```

该脚本创建独立验收 KB，上传中文政策，验证异步发布、幂等、不同措辞的检索、引用、跨租户隔离及授权撤销；保留成功文档供页面体验。它是端到端烟测，不等于召回率/回答正确率或生产压测。

Java 测试需要 JDK 17+，不能用机器默认的 JDK 8：

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17' # 替换为实际安装的 JDK 17 路径
.\mvnw.cmd test
```

## Java 接入边界

本地启动 Java 时设置：

```powershell
$env:AGENT_KNOWLEDGE_BACKEND = 'http'
$env:RAG_HTTP_BASE_URL = 'http://localhost:8091'
$env:AGENT_AUTH_MODE = 'local'
$env:AGENT_AUTH_ISSUER = 'rag-local'
$env:AGENT_AUTH_AUDIENCE = 'workflow-agent'
$env:AGENT_AUTH_LOCAL_SECRET = 'rag-local-development-secret-at-least-32-bytes'
```

用 RAG 开发登录签发的 token 调用 Java `POST /api/v1/knowledge/search`，JSON 为 `{"query":"已发货订单退款需要什么审批？"}`。该 token 同时含 Java/RAG audience，RAG 还会查询当前成员与 KB 权限。客户端只向配置的服务源站发送凭证，不跟随重定向；返回体与完整请求时间有上限。

桥接当前查询该用户可访问的最多 5 个 KB；超过上限明确报错，应调用 RAG `/search` 指定 KB。生产必须使用正式 OIDC/委托配置与 HTTPS，开发 token 不可用。

**V5 已实现 Java 任务/聊天租户隔离与受控知识集成。** 默认仍关闭任务 RAG；显式 RAG_TASK_ENABLED、可信 tenant、出域允许列表及授权凭据齐备后开放。持久委托支持本地签名恢复，企业 token exchange 未实现时关闭后台路径；业务适配固定单租户。引用响应新增 content_revision，Java 在返回、回放与恢复时复核权限和修订。见 [V5 交付说明](../agent/delivery-v5.md)；不代表整个 Agent 多租户生产化。

## 当前限制与下一批工作

- V3 已编写单文档更新、CAS 发布、回滚和版本管理代码，待验证；整库 generation manifest、跨文档原子发布、并发合并和影子重建尚未实现。
- 解析/向量化在一个可重试 job 内完成，中断可重新计算；还没有逐阶段产物检查点。失败任务保留 PG 可重试列表，尚未完成独立 MQ DLQ 运维流程。
- 文档逻辑删除先阻止读取，原件/向量保留。V3 已编写保留期预览、显式排队、单租户 GC 和引用 tombstone；默认不启动回收进程。孤立对象、S3 历史 object versions、备份删除传播仍待实现。
- 无 OCR、复杂版面质量审核、病毒扫描、解析子进程硬超时；当前只允许可信本地样本，不开放公网文件接入。
- 已实现 hybrid/rerank 和 V4 单轮问答，真实 DeepSeek 单条功能烟测通过。仍无 rewrite、父块、SSE/会话、经审核业务质量集、上传/存储/费用配额和 HA/PITR 演练。问答频率/token 上限不等于完整计费配额。
- 当前 UI 每 4 秒刷新文档任务列表，不提供任务 SSE；KB/文档列表最多 100 项。V3 版本和发布记录有游标分页，版本面板手动刷新；批量上传和回收站页面待补。
- OIDC 验签接口已实现，但尚未对接实际企业 IdP 和组同步；开发账号只用于本机验证。
- 生产配置校验只是必要条件，不能替代安全回归、权限撤销在途测试、负载与恢复验收。

真实单条问答已验证，后续按需要安排迁移部署和页面检查；不因小样本通过自动扩展测试矩阵。SSE/会话、生命周期剩余项及 Java 企业委托/生产验收仍按计划推进。


## V2 操作与复现

以下是 V2 历史操作说明。V1 → V2 的 0002 为加法迁移并提供幂等词法回填，保留原向量与引用；当前源码还含 0003，不能照此作为 V3 不停机升级流程。V3 按上文专门升级步骤执行。旧数据核对命令：

```powershell
# 先创建 .rag-local；before 快照文件只能创建一次，after 读取同一文件
New-Item -ItemType Directory -Force .rag-local | Out-Null
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/.rag-local:/reports" api python scripts/verify_upgrade.py before /reports/v1-chunks.json
docker compose -f compose.rag.yaml up -d --pull never
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/.rag-local:/reports" api python scripts/verify_upgrade.py after /reports/v1-chunks.json
# 大规模数据可单独分租户回填；默认全部租户由一次性 provisioning 角色枚举
docker compose -f compose.rag.yaml run --rm --no-deps api python -m app.backfill --tenant demo
```

快照是可选升级核对工具；新安装无需快照。日常不要覆盖旧快照，也不要以清空卷解决迁移问题。词典发生变化会产生新 lexical profile，必须重新回填再放量。

真实模型的四路评测使用独立随机数据库与 bucket，不修改工作知识库；报告落到指定目录：

```powershell
New-Item -ItemType Directory -Force .rag-local/reports | Out-Null
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/.rag-local/reports:/reports" api python scripts/run_tests.py python -m evals.run --output /reports/v2.json
```

内置 20 文档/44 题是合成开发集，manifest 固定语料 hash，按题型、development/holdout 输出 Recall@20、nDCG@10、MRR、延迟、错误与降级。使用真实 BGE/重排模型，但 API 传输为 ASGI、入库直接调用真实 Worker；MQ 仍用 `scripts/acceptance.py` 独立验收。无答案题的“返回证据率”不是回答正确率或已实现的拒答能力。报告明确保留失败和降级，不将失败请求排除出质量分母。

管理页面可选择四种检索方式，勾选诊断可看各路排名与耗时；诊断要求 tenant admin 或所有选中 KB 的 manager。普通 reader 请求诊断会返回 403。检索接口增加可选 `profile`；`distance` 对仅词法命中为 null，排序分数由 `score_type=rrf/reranker_logit` 解释。

| 配置 | 默认值 | 范围 |
| --- | --- | --- |
| RAG_RETRIEVAL_PROFILE | hybrid-rerank-v2 | dense-v2 / lexical-v2 / hybrid-v2 / hybrid-rerank-v2 |
| RAG_SEARCH_DEADLINE_SECONDS | 12 | 认证后的检索整体预算 |
| RAG_QUERY_EMBEDDING_TIMEOUT | 4 | 在线 query 编码，独立于入库 120 秒预算 |
| RAG_RERANK_TIMEOUT | 6 | 重排 HTTP 子预算 |
| RAG_SEARCH_REQUESTS_PER_MINUTE | 120 | PG 协调的租户滑动窗口 |
| RAG_SEARCH_TENANT_CONCURRENCY | 2 | 跨 API 副本共享并发租约 |
| RAG_RERANKER_URL / KEY / PROFILE | Compose 内部服务与固定模型 profile | 生产替换凭据，不对主机开放模型端口 |

Compose 环境块可设置这些 `RAG_*` 值；只有 `RAG_RETRIEVAL_PROFILE` 已提供主机变量插值。检索策略回退可执行 `$env:RAG_RETRIEVAL_PROFILE='dense-v2'` 后重建 API 容器，保留加法 schema，切回时清除该主机变量。不是内容版本回滚。

`/health/ready` 仍检查 PG/S3/embedding；reranker 是允许降级的依赖，其独立健康探针与每次检索的 degraded_components 共同反映状态，不能仅看 API ready 就推断已执行重排。模型单并发锁忙时也会降级；此 CPU 配置尚未完成生产吞吐验收。

## V7 私人记忆索引（代码已编写、未验证）

新增 Alembic 0005 与 `/internal/memory-index/upsert`、`/internal/memory-index/query`，由专用短期服务 token 访问。MySQL 保持记忆权威状态；PG 保存独立向量、修订与删除标记，结果不含正文。索引启用默认关闭，生产 embedding 传输须 HTTPS；具体配置、授权和限制见 [V7 说明](../agent/version-7.md)。本轮未运行迁移或测试，不改变既有历史验收结果。
