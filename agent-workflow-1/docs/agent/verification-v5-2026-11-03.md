# V5 小样本验证记录

日期：2026-11-03。只验证本次关键边界，重复运行不重复计数，V3/V4 历史成绩不计入本版。

## 已执行

| 测试类 | 用例数 | 结果 |
| --- | --- | --- |
| Version5Tests | 6 | 通过：真实 SDK/HTTP 替身、JSON 模式、无重试、tenant/幂等、RAG 修订/委托/撤销、引用及回放失权 |
| HttpRagKnowledgeSearchClientTests | 6 | 通过：HTTP 契约与失败边界，显式租户 |
| AgentChatAuditTests | 2 | 通过：审计、同请求幂等、变更请求冲突 |
| ChatAuditServiceTests / ToolCallingServiceTests / TaskAuditWriterTests | 1 / 2 / 1 | 通过：既有只读循环与审计，Outbox 元数据一致 |
| Version5JdbcTests | 1 | 通过：隔离 MySQL 8 的 V1–V6 到 V7、legacy 归属、跨租户 key、持久授权/来源/撤销重载 |
| Version5LiveTests | 1 个场景 | 最终通过：同一合成订单与政策，真实 DeepSeek 与 RAG HTTP/PG/S3，2 次模型调用 |

合计 18 项定向本地测试 + 1 项隔离数据库测试 + 1 个真实联调场景；[机器可读汇总](reports/v5-test-summary-2026-11-03.json) 从各类最新 Surefire 报告提取，不代表同一次全量运行。最后一次联调重新编译了全部 Java 主代码和测试代码。

## 真实联调与失败历史

仅发送 ORD-1001 脱敏字段与政策“已发货订单退款需要主管审批。”到 api.deepseek.com，模型 deepseek-flash；没有姓名、地址或客户标识。

1. 初次最多 4 次调用授权：回答被 ANSWER_INVALID_CITATION 拦截，未返回正文。脚本未保存准确次数/usage/原始回答，因此具体格式或摘录错误无法确认；见 [初次记录](reports/v5-live-initial-2026-11-03.json)。随后补 JSON 输出约束、allowedSources、逐次记录和硬预算。
2. 用户追加同一样例最多 3 次：先使用 1 次调用后，临时 RAG 已超过 600 秒寿命，知识工具连接失败。provider usage 输入 519、输出 73；见 [调用记录](reports/v5-live-fixture-expired-2026-11-03.json)。增加调用模型前 RAG 预检。
3. 重建夹具后，限制仅剩余 2 次，复验通过。provider usage 合计输入 **1746**、输出 **216** tokens；两次耗时 1418 / 1151 ms。工具为 order.query、knowledge.search，回答通过来源/原文校验；见 [成功记录](reports/v5-live-2026-11-03.json)。追加授权合计恰好使用 3 次，未继续调用。

MySQL 首次尚未初始化就绪而连接失败，就绪后同一迁移用例通过。失败不计作额外通过场景。

## 范围与重跑

实际联调直接调用 Java 服务层，task/commerce 使用内存合成数据，不是完整 Java HTTP 入口验收。RAG 随机数据库/bucket、FastAPI/PG/S3 真实，lexical-v2；embedding 和切块使用替身，没有运行向量/reranker/MQ 全链路。数据库重载通过重新实例化组件验证，不是完整 JVM 宕机恢复演练。

没有全量回归、完整权限/故障矩阵、企业 IdP/token exchange、压力/HA、页面或语义正确率评测，也没有真实 legacy/Spring AI A/B。临时引用 URL 清理后不可用；报告保留合成输出和来源标识。

本地检查不调用模型：

```powershell
.\mvnw.cmd -o -B -ntp '-Dtest=Version5Tests,HttpRagKnowledgeSearchClientTests,AgentChatAuditTests,ChatAuditServiceTests,ToolCallingServiceTests,TaskAuditWriterTests' test
```

数据库测试仅在 V5_MYSQL_URL 指向专用空 v5_test 库时启用，用测试类的固定临时凭据，不能指向工作库。真实测试默认跳过，需 V5_LIVE=true、DEEPSEEK_API_KEY、V5_LIVE_MAX_CALLS，及 [隔离夹具](../../rag-service/scripts/v5_fixture.py)（经 scripts/run_tests.py 的 RAG_INTEGRATION 隔离入口，8091 映射本机 18091，挂载 target/v5-handoff）。启动前清除上轮 target/v5-handoff/v5-fixture-stop 标记；夹具寿命 600 秒，写入该标记提前退出。新测试需新的模型预算，本次授权已耗尽。

本次夹具已正常退出清理随机库/bucket；临时 MySQL 删除，postgres/minio 恢复停止状态，工作数据卷保留。未升级或部署工作库。
