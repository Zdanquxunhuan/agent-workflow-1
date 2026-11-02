# 第一条 RAG 链路验收记录

日期：2026-11-02。环境：Windows + Docker Desktop Linux containers，JDK 17，容器 Python 3.12，CPU BGE-M3。范围是本地第一条入库/检索链路，不是整套生产功能验收。

## 已通过

| 验证 | 结果 |
| --- | --- |
| Java `mvnw.cmd test` | 73 项测试通过，包含远程 RAG 身份、错误处理和旧任务门禁测试 |
| Java `mvnw.cmd -DskipTests package` | 打包成功 |
| Python 隔离测试 `python scripts/run_tests.py` | 14 项通过；真实 PostgreSQL/MinIO，明确 stub 推理；测试库和 bucket 已自动清理 |
| 真实 `python scripts/acceptance.py` | BGE-M3 + RabbitMQ + pgvector + MinIO 全链路通过，无模型或 MQ 替代 |
| Java HTTP → RAG 实际调用 | `/api/v1/knowledge/search` 返回成功，包含命中文本、版本、chunk ID 和引用 |
| 管理页面 | 浏览器实测开发登录、知识库选择、已发布状态、中文搜索、引用弹窗通过 |

隔离测试覆盖同键上传回放/冲突、提交后原子可见、重复投递、跨租户 RLS、连接池租户上下文清理、KB/文档权限、引用/原件撤权、删除后迟到发布、旧租约 fencing、过期租约恢复、Outbox 发布失败重放、向量服务故障与查询途中撤权。

真实验收上传 `售后政策.md`，提问“包裹已经寄出了，退款要找谁审核？”，命中“已发货订单退款必须经过主管审批”。已验证 alice 授权后可检索，撤权后引用不可读取，其他租户无法搜索或访问引用。成功样本保留用于页面体验：

- 知识库：`真实 BGE 验收 7ae54b2d` / `f5972e06-79d8-4dcd-809d-3b4a9efce9da`
- 文档：`bd19be4b-649e-4f01-8068-893d9362a5c7`
- 入库任务：`36fd30c6-d505-41c5-9215-c862a9c0d89c`
- 模型：`BAAI/bge-m3`，revision `5617a9f61b028005a4858fdac845db406aefb181`，1024 维 dense，CPU 推理。

测试中的 Starlette/httpx 弃用提示不影响本次通过；后续升级测试客户端时处理。依赖版本已经锁定，不代表已完成漏洞扫描或生产依赖准入。

## 运行与下一步

本地管理台：<http://localhost:8091>，开发身份选择 `demo / admin`。启动/停止、测试和 Java 接入命令见 [运行说明](implementation.md)。Java 桥接的验收临时实例使用 8088，验收后已停止，不是对原 Java 服务部署配置的修改。RAG Docker 服务继续运行。

下一步先建立检索评测集，增加中文词法召回、RRF、rerank，再补文档更新/回滚和物理删除。Query rewrite、LLM 有引用回答、正式 OIDC 接入、完整 Java Agent 任务租户改造、监控/配额、备份恢复与负载验收仍待完成。一个中文样本检索成功不能代表召回率、回答正确率或生产容量达标。
