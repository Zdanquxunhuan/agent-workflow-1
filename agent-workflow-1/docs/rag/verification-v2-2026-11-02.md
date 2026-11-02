# RAG V2 本地验证记录（2026-11-02）

V2 的中文/编号混合检索、RRF、独立重排、证据预算、检索诊断、迁移回填与租户检索控制已实现。本记录是开发机验证，**不是生产发布批准**。

## 环境与回归

- Windows + Docker Desktop Linux：6 个可用 CPU、约 15.54 GiB 内存。Embedding 限制 4 CPU/6 GiB，reranker 2 CPU/6 GiB；真实 CPU float32 模型。
- BGE-M3 revision `5617a9f61b028005a4858fdac845db406aefb181`，1024 维归一化 CLS；reranker revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`，下载文件大小/hash 与启动完整性校验通过。
- Java/JDK17：74 项测试通过，包括 V2 score/null distance 兼容。此轮 Java 测试使用本地 HTTP 测试服务；V1 的真实 Java→RAG 联调见历史记录。
- Python：37 项测试通过。真实 PG/S3，推理仅在确定性回归中显式 stub；覆盖四路授权、跨租户、reader 禁止诊断、重排前过滤和在途撤权、删除、回填、超时、限流、候选预算、尾部分数与同分排序。
- 迁移 0001→0002：工作库原有 1 个 chunk 的 ID、正文 hash、向量 SHA256 均未变化；已发布数据缺失词法索引数为 0。回填重复执行为 0 新增；原引用继续可读。
- 存在 Starlette/TestClient 的 httpx 弃用提示；当前测试通过，后续依赖升级需单独验证。

## 真实模型对照

[最终完整报告](reports/synthetic-v2-2026-11-02.json) 包含逐题结果、profile 参数/fingerprint、分组指标与语料 SHA256。44 题中 38 题有相关文档标注；另含无答案、文档 ACL 和跨租户 KB 请求（预期 404，不算错误）。最终四种方式均为 0 意外 HTTP 错误、0 降级、0 被禁止的证据返回。

| Profile | Recall@20 | nDCG@10 | MRR | p50 ms | p95 ms | p99 ms |
| --- | --- | --- | --- | --- | --- | --- |
| dense-v2 | 1.0000 | 0.9903 | 0.9868 | 355.96 | 1685.88 | 2646.55 |
| lexical-v2 | 0.9737 | 0.8880 | 0.8601 | 161.05 | 718.80 | 937.45 |
| hybrid-v2 | 1.0000 | 0.9476 | 0.9309 | 337.88 | 893.82 | 1767.54 |
| hybrid-rerank-v2 | 1.0000 | 1.0000 | 1.0000 | 3684.14 | 4635.17 | 5687.70 |

这些数值有明确限制：

- 语料仅 20 篇合成短文，属于开发夹具，并非经审核业务数据；同语料分措辞 development/holdout。单租户不足 20 篇可见文档，Recall@20 容易饱和，不能据此声称解决了规模召回问题。
- 串行查询、ASGI TestClient 传输、真实数据库/对象存储/两个模型；入库直接调用真实 Worker。最后一轮期间另有镜像构建与确定性测试，宿主资源存在竞争；延迟不是独占硬件、稳定吞吐或生产 SLA 压测结果。
- 该集 dense 已很强；简单 RRF 在部分口语/跨语言题上反而降低排序质量，reranker 在此小集合纠正了这些问题。不宣称 hybrid 对所有业务总是更好。
- 4 道无答案题在 dense/hybrid/rerank 下仍全部返回候选，lexical 为一半。当前没有回答/拒答分类器，不能把检索命中视作可信回答。
- 最后一轮观测到繁忙时 0.5 秒租约释放子预算超时的日志，但没有请求错误/越权。随后将释放预算扩为剩余总 deadline 内最多 2 秒，37 项回归通过；此项不改变排序，表中记录保留为该次实测，不冒充变更后的独立延迟基准。

[首轮 CPU 报告](reports/synthetic-v2-initial-cpu-2026-11-02.json) 保留了 20 候选导致 30/44 次重排降级的失败结果。根据成本而非 holdout 标签把当前 CPU profile 缩为前 10 候选，剩余候选保持 RRF 顺序；请求 top_k=20 不扩大推理成本。[预算修正后的中间报告](reports/synthetic-v2-cpu10-before-stable-ties-2026-11-02.json) 也予以保留，随后加上跨重建稳定的同分排序。

## 最终镜像与页面烟测

真实 RabbitMQ/Outbox/Worker/BGE 入库验收通过，未替换模型或队列。保留的体验知识库 ID 为 `6cf326a7-ec56-4b71-a910-d37c5a4ae922`，job `5ac5204a-6530-404c-b1e0-127175d0dd62`；验证上传幂等、四种 profile、真实重排、引用、跨租户拒绝、reader 禁止 debug 及 KB 授权/撤销。该单文档重排请求约 607 ms，仅作功能烟测。

Chrome headless 页面交互通过：开发登录、选择 KB/profile、展示真实证据和诊断表、切换身份清除结果；额外延迟在途请求后切换到 bob，旧响应没有重新渲染。无 JavaScript 页面错误，页面截图人工检查通过。

真实停止 reranker 后，查询在约 3607 ms 返回授权证据、`status=degraded` 和 `degraded_components=[reranker]`，所有分数为 RRF；未伪装成重排成功。随后启动 reranker，重新查询恢复 `status=ok`、实际 logit 排序，耗时约 632 ms。

## 尚未通过的生产门槛

至少 200 条经审核业务题、长文/表格/多块/无答案证据门槛、GPU/长块/并发和混合导入压测、正式 IdP、凭据隔离、解析沙箱、HA/PITR/恢复与完整故障演练仍待完成。CPU 上长达 512 tokens 的重排成本尚未作为吞吐基准验收。知识更新/generation/GC、query rewrite、父块、LLM 回答和 Java 任务/聊天的完整租户改造均未在 V2 交付。

复现命令与升级/回退见 [运行说明](implementation.md)，实现范围与取舍见 [ADR-002](adr-002-hybrid-retrieval.md)。
