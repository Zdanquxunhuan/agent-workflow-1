# V5 交付说明

日期：2026-11-03；本地实现与小样本验证完成，未部署。对应 [原计划](version-5.md)、[验证记录](verification-v5-2026-11-03.md)。

## 已实现

| 部分 | 实现与边界 |
| --- | --- |
| 框架 | Java 17 / Boot 3.5.16 / Spring AI 1.1.2；新增 SpringAiToolCallingGateway，显式保留 legacy 网关。关闭 SDK 自动工具执行与隐式重试，原 ToolRegistry 控制执行；最多 4 轮、8 次工具调用、64000 字节输入，默认 1200 输出 tokens。 |
| 任务隔离 | 从已验签 JWT 取 tenant，持久贯通 task/view/context；父任务授权覆盖步骤、事件和回放。审计与 Outbox 元数据一致。幂等键含 tenant/subject/entrypoint/key，改请求复用 key 返回冲突。审批、取消、恢复先检查租户与原有角色/owner 权限。 |
| 委托 | 冻结最多 5 个可读 KB，保存一小时有效期和撤销状态；本地恢复签发原主体 60 秒凭据，RAG 每次重新验证 ACL。不保存 Bearer token。企业 OIDC token exchange 未实现，缺凭据时拒绝执行。 |
| 引用回答 | Java 取 `/search` 证据并联合业务工具回答，JSON 中的来源必须属于本次证据且摘录连续匹配；返回前、任务详情/聊天回放和恢复时复核权限、版本、hash、content revision。Python `/answers` 继续服务独立问答。 |
| 观测 | 保存 model、供应商 usage/unknown、耗时、Prompt/schema 版本；统一跨 Java/RAG trace header、完整费用账本仍待补。 |

业务表和下游仍为单租户适配：业务工具、直接业务查询及运维操作固定在 `COMMERCE_TENANT`，其他租户拒绝调用。任务租户化不代表业务系统具备完整多租户或逐客户 ACL。写操作继续走原任务、审批、幂等、Outbox 与下游确认；聊天只开放只读工具。

订单送模字段收窄为状态、金额、币种等白名单，不含姓名、地址、客户标识和商品列表；其他业务只读工具的出域字段仍需按实际契约审查。

## 配置与接口

默认关闭真实模型及远程任务 RAG。本地合成演示显式启用：

```dotenv
DEEPSEEK_ENABLED=true
DEEPSEEK_GATEWAY=spring-ai
DEEPSEEK_API_KEY=<只放被忽略的本地环境文件>
DEEPSEEK_ALLOWED_TENANTS=demo
DEEPSEEK_MAX_OUTPUT_TOKENS=1200
COMMERCE_TENANT=demo
AGENT_KNOWLEDGE_BACKEND=http
RAG_HTTP_BASE_URL=http://localhost:8090
RAG_HTTP_AUDIENCE=rag-api
RAG_TASK_ENABLED=true
AGENT_DEADLINE_SECONDS=30
```

端口按部署调整。JWT 必须包含 sub、tenant_id、角色及目标 audience，并被 Java/RAG 两侧信任；RAG 中须已有该主体的成员身份和 KB 权限。非 local 认证缺 tenant 拒绝；旧 local 演示缺 tenant 归 legacy，不能视为公共租户。

本地后台恢复另设 `AGENT_AUTH_MODE=local`、`RAG_DELEGATION_ISSUER`、至少 32 字符的 `RAG_DELEGATION_LOCAL_SECRET`，与 RAG 验证器一致。此签名器仅供 local 演示，非 local 模式不启用，不代表企业委托落地。

- `/api/v1/agent/chat`：框架只读工具循环与引用回答。
- `/api/v1/tasks`：保留原状态机；知识步骤使用原申请人的资料权限，审批角色不替代资料授权。
- `DELETE /api/v1/tasks/{id}/knowledge-grant`：任务 owner 撤销授权。
- local + dev-token 开关下的 `/api/v1/auth/dev-token` 新增可选 tenantId。
- Python 引用响应增加 content_revision，以识别同版本回滚等变化。

旧网关用 `DEEPSEEK_GATEWAY=legacy` 显式选择，不在失败时自动切换。远程 RAG 即使选择 legacy，仍需租户允许列表与最终引用门禁。

## 数据升级

Flyway V7 增加任务 tenant/entrypoint、新幂等唯一约束、授权和来源快照表。旧任务归 legacy，有 CHAT_STARTED 事件的归 chat，其余归 tasks；不得从 userId 猜真实租户。子步骤/事件/Outbox 通过不可变父任务继承访问边界。

隔离 MySQL 已验证 V1–V6 旧记录到 V7、跨租户同 key、持久授权与撤销重新加载。**未升级工作库。** 正式升级需备份、审核历史归属并运行迁移；新库结构不能直接回退旧应用，不能用删除新增表/约束代替回滚方案。

## 已验证与限制

18 项定向本地测试、1 项隔离迁移和 1 条真实合成联调通过，详情见验证记录。实际链路为 Java 服务层 → Spring AI/DeepSeek → ToolRegistry → 合成订单和真实 RAG HTTP/PG/S3 → 来源复核 → 回答；未跑完整 Java HTTP 入口。Java 使用内存仓储，RAG 使用 lexical 检索、embedding/切块替身，未执行向量/reranker 链路。

摘录校验验证来源与连续原文，不证明语义蕴含。成功样例的订单段包含多个字段，摘录仅 SHIPPED，是此限制的具体例子。deadline 在调用间及返回前检查，不能强制中断已开始的请求或保证停止计费。未做全量回归、企业 IdP、生产容量或部署验收。

下一版 V6 做短期记忆：tenant/owner 范围的会话消息、跨请求指代、消息配对、上下文预算、摘要版本与历史证据重授权。企业委托与工作库部署继续保留为发布前置工作。
