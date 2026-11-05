# V11 小样本验证记录

日期：2026-11-05。证据来源为实际 Maven / Surefire 输出和测试保存的运行数据。去重汇总见 [JSON](reports/v11-verification-2026-11-05.json)。本记录不把历史 V5 成绩计入 V11。

## 环境和边界

Java 17.0.12；Spring Boot 3.5.16；Spring AI 1.1.2；现有 Maven wrapper 离线运行；Docker MySQL 8.0.46。容器为本轮新建 `agent-v11-mysql-20261105`，端口仅绑定 `127.0.0.1:13371`，数据库 `v11_test`，不挂载工作数据卷。

实际运行 Spring Boot 全上下文和 MockMvc Servlet/API 流程、真实 HS256 JWT 签名/校验、异步 worker、Spring AI 的真实 HTTP 协议适配器、MySQL 行锁/事务/外键及所有迁移。模型与 RAG 使用进程内随机 loopback HTTP 服务；支付为本地 SandboxCommerceGateway。

MockMvc 不等于真实浏览器或经 TCP 进入 Tomcat 的完整部署；HTTP 外部模型/RAG 链路确实经过 localhost 网络。RAG 返回数据及其撤权响应由 fixture 控制，并非真实检索质量评测。MCP 只验证持久来源授权撤销，不声称启动并验证了独立 Python MCP 服务。memory 启用、自动提取关闭；确认偏好的召回走真实 MySQL，不涉及向量检索。

## 结果

| 不同用例 | 结果与关键断言 |
| --- | --- |
| 正常查询 | 订单+政策工具、3 次 SDK/HTTP 模型响应、真实引用校验、360 合成 tokens/360 micro-USD 记账，其他 owner 即使 ADMIN 也无法读 run |
| 缺参恢复 | run 和 task 持久等待；补编号后同一 run/task、4 次累计尝试、480 合成 tokens；同 key 重试不增加调用，旧 revision 冲突 |
| 有限重规划 | 授权知识无结果可改写一次，父版本可追踪；重复归一化查询停止 |
| 来源撤销 | 查询到政策后暂停，fixture 撤权，补输入后在下一次模型调用前以 RAG_ACCESS_DENIED 停止；无最终结果 |
| 预算及未知用量 | 暂停后耗尽的模型次数不能通过回复重置；未知 usage 使用预留计账，重复 settle 不重复收费 |
| 恢复/取消竞争 | 屏障控制旧 worker 暂停后尚未返回；两个并发补输入只有一个成功，新 worker 已开始时旧 worker 不结束新阶段；取消后无晚到答案 |
| 会话/记忆遗忘 | **首轮失败，修复后通过**：候选→确认→会话消费→遗忘后旧会话 MEMORY_CHANGED |
| MCP 来源撤权 | 真实 MySQL grant 和 task source，撤权触发 revision 递增，旧答案读取被 MCP_EVIDENCE_CHANGED 拒绝；无新模型调用 |
| 独立审批退款 | 自批被拒绝；独立主管批准后 WAITING_EXTERNAL；手动运行一次真实 Outbox dispatcher，sandbox 确认 SUCCEEDED；重复提交和审批只有一条成功退款 |

共 **9 个不同用例最终通过**。首次运行 8 项，7 通过/1 失败；修复后的定向运行包含失败项和新退款项，共 2 项通过。没有再跑整套，也没有执行全库既有测试。测试类共 9 项；统计中的重复复验不算额外场景。

全部生产源码首次编译通过，修复后又随定向测试重新编译；首轮从空 MySQL 应用 V1–V12 成功，第二次启动成功验证现有迁移校验和并确认无需新迁移。这不等于对某个实际旧工作库完成升级验收。

## 失败与修复记录

首轮唯一失败是 `POST /api/v1/memories` 返回 500。错误为 MemoryService.quota 直接访问 `store.jdbc` 导致 NullPointerException。实际 Spring Repository 代理暴露了手动构造测试不一定遇到的对象字段问题。

修复 MemoryStore 封装和 MemoryService 调用方式后，目标用例通过。没有放宽权限校验、关闭 Spring 代理、改低断言或替换真实 MySQL 来掩盖问题。为避免失败日志输出 JWT，测试中的 MockMvc 自动请求转储已关闭；原始开发日志只在忽略版本管理的 target 目录，不作为公开面试材料。

## 局部观测数据

| 场景 | 测试场景耗时 | 本地替身模型响应 |
| --- | ---: | ---: |
| 正常订单/政策查询 | 15,694 ms | 3 |
| 缺参→补充→结果及幂等检查 | 14,140 ms | 4 |
| 无结果→改写→答复 | 11,002 ms | 3 |
| 来源撤权停止 | 9,346 ms | 2 |
| 预算阻止恢复后模型调用 | 6,592 ms | 1 |
| 并发恢复/取消 | 6,938 ms | 2 |
| 独立审批/退款确认及幂等 | 5,080 ms | 1（旧写流程 planner） |

这些是单个 fixture 场景的实际壁钟观测，包含 HTTP API、数据库审计、轮询和断言等开销；**不是模型真实生成延迟、生产 SLA、平均值或 P95**。本机 Docker 的首轮 12 个迁移约 2 分 22 秒；编译/初始化与正常运行场景耗时分开看。

模型 stub 每个响应固定返回 100 input + 20 output tokens，账本据此按 fixture 输入/输出价格各 1 USD/百万 tokens 计算。正常查询的 0.000360 USD、澄清链的 0.000480 USD 都只是**合成响应下的账本计算验证**。真实模型调用为 0，不能把这些数字写成真实供应商用量、费用或节省比例。旧退款 planner 不在 V9 账本覆盖范围内，单独记录其替身调用数。

## 重现方式

仅在专用临时环境执行，不能指向工作库；不需要真实 API key。保持现有锁定依赖，以下 Maven 命令使用离线缓存。首次运行需确认容器已完成初始化。

```powershell
docker run -d --name agent-v11-mysql-20261105 --label agent.verification=v11 `
  -p 127.0.0.1:13371:3306 -e MYSQL_ROOT_PASSWORD=v11_isolated_test_only `
  -e MYSQL_DATABASE=v11_test mysql:8.0
docker exec agent-v11-mysql-20261105 mysqladmin '--host=127.0.0.1' '--user=root' '--password=v11_isolated_test_only' ping

$env:JAVA_HOME='F:/ayd/jdk-17' # 按机器上实际 JDK17 路径设置
$env:V11_MYSQL_URL='jdbc:mysql://127.0.0.1:13371/v11_test?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC'
.\mvnw.cmd -o -B -ntp '-Dtest=Version11IntegrationTests' test
```

本轮修复后仅执行：

```powershell
.\mvnw.cmd -o -B -ntp '-Dtest=Version11IntegrationTests#conversationUsesConfirmedPreferenceAndForgettingRevokesHistory+refundRequiresIndependentApprovalAndConfirmsOnlyOnceInSandbox' test
```

测试以环境变量和固定 localhost 测试库地址显式启用；未设置时跳过。结果在 target/surefire-reports 和 target/v11；公开报告只提取允许的结果字段，不直接发布可能含凭据的原始 XML/请求日志。临时容器和其匿名数据卷在本轮验证后清理，引用用的临时 localhost URL 不再可用。

## 未执行项

未运行真实 DeepSeek、多供应商 fallback 联调、企业 OIDC/token exchange、Python MCP SDK 互操作、pgvector/embedding/reranker 全链路、浏览器/SSE 界面冒烟、进程崩溃与跨实例恢复、长时间稳定性或压力测试。退款网关是 sandbox，不是真实支付平台。各版本测试设计中未被上表覆盖的场景仍保持未执行。

因此可以说“关键闭环和安全边界完成本地小样本验证”，不能说“V6–V10 全量验收完成”或“已达到生产上线标准”。
