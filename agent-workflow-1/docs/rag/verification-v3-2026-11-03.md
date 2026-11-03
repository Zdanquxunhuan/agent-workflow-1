# V3 基础测试记录（2026-11-03）

按用户最新要求控制测试范围，完成基础回归和必要的 V3 边界验证，未扩展大量样本、压力测试或完整端到端矩阵。本记录不是生产发布验收。

## 实际结果

- 第一轮：**51 passed**，耗时 64.00 秒，包含既有 37 项回归与新增 14 项 V3 用例。
- 第二轮：仅补跑新加的两个用例，**2 passed、14 deselected**，耗时 5.63 秒。
- 合计 **53 个不同用例通过，其中 V3 新增 16 项**；不是重复跑同一批累加数量。
- 使用现有 Docker 镜像依赖，挂载当前源码。PostgreSQL/pgvector、RLS 和 S3 为真实基础设施，推理在确定性集成测试中明确使用 stub。
- 每轮由 `scripts/run_tests.py` 创建随机命名的独立数据库和 bucket，升级迁移到 head、初始化测试成员；退出时清理本轮资源。工作知识库没有写入模拟向量，没有升级工作库 schema。
- 已从实际 FastAPI 路由生成 `docs/rag/openapi.json`，版本 0.3.0。
- 现有 Starlette/TestClient 的 httpx 弃用提示仍存在，未影响本轮通过；没有为消除提示而临时升级依赖。

## 覆盖范围

新增用例位于 [test_lifecycle_integration.py](../../rag-service/tests/test_lifecycle_integration.py)：

- 新版准备时旧版仍可检索；人工发布、回滚、内容与 ACL 修订分离。
- 历史引用固定原文、固定版本原件下载；分页与小文档正文比较。
- 上传/发布幂等回放；自动发布冲突保留 READY 候选。
- 两线程并发发布的 CAS 冲突与同键幂等。
- 草稿作者/其他 contributor/reader 权限、跨租户和文档读 ACL；原件下载成功或 S3 报错后的撤权复核。
- 更新失败/取消不改变当前发布版，重试及迟到 Worker 防护。
- 索引不完整、模型 profile 不兼容、未发布草稿回滚被拒绝。
- 回收保护、整批原子校验、引用 tombstone、物理对象删除及重复请求。
- S3 删除失败的退避恢复；删除后、数据库提交前模拟进程中断，租约过期后恢复。
- 请求人撤权后管理员接管；S3 删除与最终提交之间重新排队，旧 fence 不得完成任务。
- 已删除文档的 hold 管理和删除后保留期。

本轮未发现需要修改产品代码的失败；新增测试和生成的契约保留在仓库。

## 复现

在 `pom.xml` 所在目录、开发基础设施就绪后执行。命令只运行隔离测试，不部署 API/Worker：

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/rag-service:/app" api python scripts/run_tests.py
```

第二轮实际只运行：

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/rag-service:/app" api python scripts/run_tests.py pytest -q -p no:cacheprovider tests/test_lifecycle_integration.py -k "gc_revoked_actor_takeover_and_fence or version_pagination_and_comparison"
```

## 明确未执行

未执行带既有 V2 业务数据的迁移演练、真实 MQ/BGE/reranker 的 V3 端到端链路、浏览器自动化、Java 回归、完整故障矩阵、压力/容量测试或生产部署。测试中包含空隔离库升级 head，不能把它当成已有工作库迁移验收。BGE/reranker 容器曾启动作准备，本轮确定性测试没有以真实模型推理作为 V3 证据。

[完整测试交接清单](testing-v3-handoff.md) 仍是可选的后续验证参考，不表示本轮全部执行。按用户要求，本轮不继续扩展测试。
