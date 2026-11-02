# ADR-001：独立 RAG 的第一条实现链路

状态：已采纳，2026-11-01。

RAG 与 Java Agent 同仓维护、独立部署。RAG 管理 PG、对象存储、MQ、文档和检索；Java 业务交易继续使用自己的 MySQL/Flyway。选择 FastAPI + psycopg 显式事务，Alembic 管理迁移；当前没有引入 ORM 实体层，以便清楚审查租约、权限过滤、原子发布和 SQL。

API 不加载模型。独立 CPU 推理进程使用官方固定版本 BGE-M3，CLS pooling 后 L2 归一化生成 1024 维向量；该路径仅提供 dense，与后续 sparse/multi-vector 分开。调用方检查 profile 和向量维数，不接受伪向量降级。

任务状态以 PG 为真相。Outbox 保证任务提交与消息投递的可恢复关系；RabbitMQ/Celery 按至少一次处理，唯一约束、租约和 fencing 保证一次可见发布。最初实现整文档重算恢复，后续再增加阶段检查点与部分批次恢复。

首版查询对已授权集合进行精确距离排序，避免未经过评测的 ANN 过滤召回损失。索引/版本设计采用每文档 active_version 指针，检索只读该完整版本；全 KB generation manifest 属于后续阶段，不能把这两个保证混同。

原生管理页面随 API 部署，减少第一轮构建与认证跨域环节。页面数据使用 textContent，不把文档正文作为 HTML 执行。

Java 第一期只提供无状态知识查询桥接，要求经过 Spring 验证的 JWT 含 tenant 和 RAG audience，RAG 再次验签并检查当前成员。旧任务表、聊天审计和幂等回放没有 tenant 维度，因此禁止远程证据进入这些入口。完整 Agent 集成需先迁移任务/审计的租户边界及持久授权委托。

此实现用于真实本地验证与后续迭代，不作为生产 HA、安全和检索质量已达标的证据。
