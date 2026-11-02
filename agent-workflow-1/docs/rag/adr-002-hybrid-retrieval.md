# ADR-002：V2 授权混合检索、独立重排与可复现评测

日期：2026-11-02。状态：已实现；运行验证见 [V2 记录](verification-v2-2026-11-02.md)。

## 决策

保留 BGE-M3 dense 与 PostgreSQL 权限边界，在同一检索应用增加中文全文索引和编号通道。不开新业务微服务；reranker 单独运行，避免在 API 内加载模型。

- 中文使用固定 `jieba==0.42.1`、`cut_for_search(HMM=False)`、NFKC/casefold 与业务词典。词典内容 SHA256 纳入 lexical profile；否定词、数字保留。文档/query 共用规则，查询词逐项安全构造并参数绑定。
- `chunk_lexical` 是按 tenant/chunk/profile 存储的 sidecar，保存内容 hash、预分词、编号与生成的 `tsvector`；两种 GIN 索引分别服务全文和编号。排序用 PostgreSQL `ts_rank_cd`，不称作 BM25。编号按完整 ASCII 字母数字串及连字符/下划线匹配，不支持任意标点编号语法。
- 每路最多 50 条：dense 使用授权范围内精确 cosine，lexical 使用全文相关性，identifier 使用完整编号匹配。三路总上限 150 条，按 chunk ID 去重后 RRF `sum(1/(60+rank))`；原始分数不直接相加。同分优先用内容 hash、来源标题和 ordinal 排序，避免重建语料时随机 UUID 造成排名波动。索引扫描的 ANN 优化仍未启用。
- 四种固定 profile：`dense-v2`、`lexical-v2`、`hybrid-v2`、`hybrid-rerank-v2`。可请求选择，默认最后一种；参数文件与模型/分词 profile 一起生成 fingerprint。暂不开放任意客户端候选数/融合权重。
- 独立 `BAAI/bge-reranker-v2-m3` 固定 revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`。权重和 tokenizer 文件均校验官方固定大小/hash，`local_files_only=True`、`trust_remote_code=False`。CPU float32，单批 4 对，接口最多 20 对；当前 CPU 检索 profile 固定只重排前 10 个候选，随后拼接未重排 RRF 尾部，每对最多 512 tokens，query 最多 256 tokens，仅截断 passage。已重排命中返回原始 logit，尾部仍标记 RRF 分数；不代表答案正确概率。
- 最后按内容 hash 去重，单文档最多 3 块，总证据预算 2400 BGE tokens（每块另计 24 tokens 引用开销），只返回完整原块，保持引用一致。未实现相邻块合并、近重复去重、父块扩展或回答 LLM token 预算。

## 权限与资源边界

每条 SQL 通道都同时约束 tenant、有效成员、KB ACL、文档 ACL、未删除状态、active version 与 embedding profile；不能先全局召回再在 Python 隐藏未授权命中。发送 passage 给 reranker 之前和返回 hits/debug 之前再次授权、过滤当前可见 chunk。已发送至本地模型的内容无法因随后撤权追溯收回；新输出仍受最终授权检查。

`POST /search/debug` 要求当前 tenant admin 或所有选中 KB 的 manager。它展示当前授权候选、各路 rank/score、融合排序、重排 logit、最终选择和耗时。reader/contributor 只能使用普通搜索。正文仅在响应中返回，不进入检索日志；浏览器使用 textContent、no-store，切换身份/KB 后清除结果并丢弃旧请求响应。

检索编排改用 async HTTP 与独立异步 PG 池，多通道并发 I/O。认证后共享 12 秒检索 deadline，query embedding 子预算 4 秒，reranker 子预算 6 秒，最终权限检查预留时间；数据库 statement/lock/pool 与 HTTP 响应体均有限制。身份验证与代理传输还需在生产网关设置完整请求 deadline。HTTP 超时不能抢占已在 CPU 中运行的模型算子；模型侧用候选/长度上限与单并发锁限制成本。

PG `search_admissions` 按 tenant 使用事务 advisory lock，默认每分钟 120 次、最多 2 个并发检索；多个 API 副本共享状态。正常释放租约，崩溃/超时租约最多保留 deadline+5 秒；返回 429/Retry-After。此机制不包含上传/存储/token/金额配额，也未替代正式压力测试。

## 故障语义

| 条件 | 当前行为 |
| --- | --- |
| dense-only embedding 故障 | 503，保留与“没有证据”的区别 |
| hybrid embedding 故障，当前授权范围词法索引完整 | 返回词法/编号结果，`status=degraded`、`degraded_components=[embedding]` |
| 词法回填不完整 | lexical-only 返回 503；hybrid 使用现有通道并标记 `lexical_index_incomplete`；此时 embedding 故障不允许掩盖为词法降级 |
| reranker 超时/不可用/输入不支持/契约错误 | 回退 RRF，标记 `reranker`；不返回假重排分数 |
| 总预算耗尽 | 504 `SEARCH_DEADLINE`；取消本地并行 I/O |
| 权限撤销/PG 不可用 | 失败关闭；不使用过期权限缓存 |

`distance` 仅对实际 dense 命中有值。新增 `score/score_type` 表达 RRF 或 reranker logit；Java 优先读取 V2 score，同时兼容 V1 distance，避免 null 被当作距离 0。普通搜索与诊断响应均有 OpenAPI 模型。

## 升级与回退

迁移 `0002` 只增加 sidecar/限流表与 RLS/索引，不改写向量。新 Worker 在同一发布事务写 dense 与词法；旧 chunk 用幂等 backfill 生成 sidecar，保留原件、chunk/citation ID 和向量。Compose 让 API 等待回填成功；大规模数据应分租户离线回填并验覆盖率，再切换默认 profile。

回退检索策略用 `RAG_RETRIEVAL_PROFILE=dense-v2`，重建 API 容器；保留 V2 schema，不运行破坏性 downgrade。旧 Worker 回滚后写入的新块没有词法 sidecar，恢复 hybrid 前必须重新回填。不能把切换 profile 称为内容版本回滚。

## 评测与剩余工作

`evals/run.py` 创建独立随机数据库/bucket，使用真实 API、授权、PG/S3 与两个模型；真实 Worker 直接调用，MQ 另由 acceptance 脚本验证。四种 profile 按问题轮换执行顺序，记录文档级 Recall@20、nDCG@10、MRR、p50/p95/p99、降级/错误、权限泄露和逐题失败结果；失败请求仍计入质量分母。

随代码提供 20 文档/44 问题的合成开发语料，分 development/holdout，文件 hash 冻结。它只验证评测与检索机制，不等同于经人工审核的业务标注集。无答案样本报告“仍返回证据的比例”；尚无回答/拒答分类器，不把检索 top-k 当成可信回答。

后续仍需：至少 200 条经审核业务样本、规模/并发/混合导入压测、真实故障与恢复演练、rewrite、父块、内容更新/GC、LLM 回答、正式 IdP 与完整 Java 任务租户隔离。
