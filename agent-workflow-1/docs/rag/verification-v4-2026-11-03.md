# V4 小样本验证记录（2026-11-03）

范围按用户要求控制：少量合成短政策、核心边界。共两次真实 DeepSeek 请求：首次余额不足；用户充值后，仅重跑同一条并通过。没有大规模数据、压力测试或完整历史回归矩阵；未升级工作库或部署 V4。

## 确定性验证

首轮 `tests/test_answers.py tests/test_core.py`：**15 passed**，13.94 秒，其中 V4 9 项、既有核心 6 项。真实 PostgreSQL/pgvector、RLS 和 S3；入库/query 推理及回答模型在这一轮明确使用测试替身。随机命名数据库/bucket 由 `scripts/run_tests.py` 建立、迁移到 0004、初始化并在退出时清理。

V4 覆盖：

- 正常结构化回答、引用原文及版本/hash、反馈更新与主体/跨租户隔离、运行元数据不保存正文。
- 无证据不调用模型、功能关闭与无证据的区分。
- 模型拒答与无效摘录分别处理，拒绝返回错误引用生成的正文。
- 模型调用期间文档 ACL 或 content_revision 变化，整份答案作废。
- Chat Completions 请求参数、非流式/JSON 模式、DeepSeek thinking 关闭、供应商 usage、超时和非法 JSON。
- 超时失败记录及并发租约释放、租户频率限制。
- 两篇短政策的模型冲突输出必须带两个真实来源；不把测试替身判断视为真实模型冲突识别准确率。

三个前端脚本 `app.js`、`versions.js`、`answers.js` 的 `node --check` 通过。这只是语法检查，未执行浏览器自动化或视觉验收。

## 真实 DeepSeek：首次失败，充值后单条复验通过

首次使用用户指定的 DeepSeek 配置发起 1 次真实请求。一篇合成政策通过真实 S3/PG 入库；使用 lexical-v2 召回 1 条证据，入库向量明确为测试替身，没有启动 BGE/MQ 验收。

请求到达 `https://api.deepseek.com/chat/completions` 后返回 **HTTP 402 Payment Required**。根据 [DeepSeek 官方错误码](https://api-docs.deepseek.com/quick_start/error_codes/)，该状态表示余额不足。因此没有真实模型回答、引用质量或 usage 验证结果；null 用量不表示已确认零费用。

初次调用由通用分支转换为 503 `ANSWER_PROVIDER_UNAVAILABLE`。随后代码补充 402 → `ANSWER_PROVIDER_QUOTA`、retryable=false，并为供应商配置类 4xx 增加 `ANSWER_PROVIDER_CONFIGURATION`；余额未恢复前不重试。原始失败与后续映射记录在 [首轮报告](reports/v4-deepseek-2026-11-03.json)，没有覆盖失败记录。

修改后只定向复跑同一个 Provider 用例：**1 passed、8 deselected**，3.45 秒，包含新增的本地 HTTP 402 模拟断言。它是前述 15 项中的复测，不累加成 16 个不同用例。Starlette/TestClient 的 httpx 弃用提示仍存在，当前通过，不为此临时升级依赖。

密钥仅位于 Git 忽略的 `rag-service/.env`；模板/报告/源码不保存密钥。没有访问真实业务资料、没有进行充值操作。

用户明确告知已充值后，用同一篇短政策和同一条问题重跑一次，**真实 DeepSeek HTTP 200，`answered`，原文引用读取与反馈保存均通过**。见 [充值后独立报告](reports/v4-deepseek-after-balance-2026-11-03.json)。

- 模型：deepseek-flash；问题：已发货订单退款需要谁审批？
- 回答：`已发货订单退款需要主管审批。 [C1]`，摘录与合成政策原文一致。
- 供应商 usage：输入 342 tokens、输出 41 tokens；没有据此估算费用。
- 单次服务耗时：检索 116.77 ms、生成 986.36 ms、总计 1320.32 ms。
- 本轮合计 2 次外部请求，1 次因余额拒绝、1 次成功；成功报告的 `live_answer_calls=1` 指该次运行。
- 这是一条词法检索 + 真实 LLM 的功能烟测，不是延迟 SLO、全模型链路或回答准确率结论。每次随机测试库/bucket 均由脚本清理。

## 复现与剩余边界

命令与配置见 [V4 交付说明](version-4.md)。目前已完成约定的单条复验，不继续扩大样本或重复调用。OpenAPI 已导出为 0.4.0，前端静态语法与 diff 空白检查通过；本轮启动的 PG/S3 恢复为测试前停止状态。

未完成：真实模型幻觉/拒答质量集、浏览器交互、Java 完整任务集成、生产迁移演练、并发容量和 HA。现有引用门禁证明的是来源可追溯及权限/版本检查，不证明所有语义结论正确。
