# Version 3：文档生命周期开发交付

本轮交付实现代码、0003 迁移、页面和接口说明。开发阶段按要求未运行测试；用户后续授权测试后，已完成 **53 项基础测试，其中 V3 新增 16 项**，详情见 [V3 测试记录](verification-v3-2026-11-03.md)。测试只使用隔离数据库/bucket；工作库未迁移，V3 未部署。未执行大量样本、压力测试和完整端到端矩阵，不能宣称生产验收通过。

更新：2026-11-03。设计取舍见 [ADR-003](adr-003-document-lifecycle.md)，完整候选验证范围见 [V3 测试交接清单](testing-v3-handoff.md)；实际执行范围以测试记录为准。下文升级/回收运行命令仍为交接说明，未对工作库执行。

## 实现范围

文档增加独立的 `content_revision`（发布、回滚和删除递增），原 `revision` 继续用于文档 ACL。`version_counter` 在文档锁内分配单调版本号；版本保存上传者、修改说明、提交时基准修订号、是否自动发布、模型 profile、首次发布时间、退休时间和保留标记。

处理状态仍在 ingestion_jobs，版本生命周期为 PROCESSING → READY → PURGING → PURGED。READY 表示索引完整，不等同于当前发布；当前版本由 documents.active_version_id 决定。任务 SUCCEEDED 现在表示索引处理成功，自动发布冲突会在版本 publication_note 标记 CONTENT_REVISION_CONFLICT，仍保留 READY 版本供管理员复核。

- 首次上传兼容自动发布。上传新版默认只准备索引，调用者可以显式开启自动发布；自动发布仍要求上传者有 KB 写权限、当前文档读权限，并且内容修订号等于上传时的 base_revision。
- 默认人工发布尚不是强制审批策略：contributor 可以主动选择自动发布。KB 级强制审批和上传/审批职责分离待后续实现，不能将本轮 UI 默认值作为权限门禁。
- 新版解析/向量化不修改旧版原件或 chunks。Worker 在单事务内写 dense 和词法索引、记录 READY，并在条件满足时原子切换 active_version_id。失败/取消/迟到任务不切换旧版。
- 上传、发布、回滚、任务重试/取消、删除和回收使用文档锁串行化；发布用修订号 CAS。并行准备多个版本是允许的，旧基准自动发布会冲突；管理员可在比较后，以当前修订号明确发布旧基准候选。
- 发布/回滚要求 tenant admin 或 KB manager，并满足文档读 ACL；Idempotency-Key 和 request hash 保证同键同请求回放，不同请求 409。publication_events 保存前后版本、修订号、操作者、原因。
- 回滚只允许曾发布且索引完整、未回收、embedding profile 兼容的版本。文档删除不可被回滚复活，权限不随版本回滚。
- 历史引用固定读取原 chunk/version，返回 `status=historical`、`is_current=false`。读取时继续检查当前 tenant/KB/document ACL；不改成新版正文。删除文档后 404；原版本已回收时，仍有文档读取权者得到 410，否则 404。
- 草稿正文、原件、任务详情和版本历史仅对当前有写权限的上传者或有读取权的 manager 开放。reader 可读曾发布版本；PURGING/PURGED 原件和引用不可读。
- 页面增加新版上传、默认人工发布、历史版本分页、原件下载、正文差异、发布/回滚、任务重试/取消、保留标记、发布记录分页和回收预览。比较最多每版 40,000 字符/2,000 行，输出最多 2,000 行；它比较索引块正文，可能包含重叠段落，不是 PDF/DOCX 的版式比较。

## 接口

以下均以 `/api/v1` 为前缀，身份来自已验证 Bearer token。

| 方法和路径 | 请求/行为 |
| --- | --- |
| GET /documents/{id} | 返回内容修订号、当前版本、版本计数与 can_update/can_manage |
| POST /documents/{id}/versions | multipart file、expected_content_revision、change_note、auto_publish；要求 Idempotency-Key；202 返回 job/version ID |
| GET /documents/{id}/versions | limit 默认 30，最大 100；before 为版本号游标，响应 next_before |
| POST /documents/{id}/versions/{version}/publish | JSON expected_content_revision、reason；要求 Idempotency-Key |
| POST /documents/{id}/versions/{version}/rollback | 与 publish 相同；目标必须曾发布 |
| GET /documents/{id}/publication-events | before_revision 游标、limit；事件不包含幂等键/请求 hash |
| GET /documents/{id}/versions/compare | from_version、to_version；有界 unified diff 与 truncated 标记 |
| GET /documents/{id}/versions/{version}/original | 下载固定版本；返回 X-Document-Version、X-Version-Current |
| PUT /documents/{id}/versions/{version}/retention-hold | JSON enabled；manager 可保护/解除保护未回收版本，支持已知已删除文档 ID |
| POST /documents/{id}/retention/preview | manager 查看保护原因和回收任务状态，不删除数据；支持已逻辑删除文档 ID |
| POST /documents/{id}/retention/purge | JSON expected_content_revision、version_ids（1–20）；重新校验规则后排队 |

常见状态：409 CONTENT_REVISION_CONFLICT / VERSION_NOT_READY / PROFILE_MISMATCH / INDEX_INCOMPLETE / RETENTION_PROTECTED / IDEMPOTENCY_CONFLICT；410 VERSION_GONE / CITATION_GONE。调用方遇到修订冲突应重新获取当前版本并人工确认，不自动把修订号替换后重试发布。

发布示例：

```json
{"expected_content_revision": 2, "reason": "已复核新版退款审批条件"}
```

### 回收执行

默认至少保留 30 天、最近 3 个尚未回收的版本，并始终保护 active version、retention_hold、非终态入库任务。计算时间采用退休/就绪/创建时间；已删除文档还要求经过删除后的保留期，且不再保护最近 N 版。首次迁移保留旧 chunk ID 和向量，已删除文档解除 active pointer 并保留退休时间。

预览每次最多返回最早 500 个未完成回收的版本；完成回收后再次预览会向后推进。用户明确排队才把版本置为 PURGING。该状态不可发布、回滚或下载，不提供取消回收功能。物理回收由单独的租户进程执行，不在 HTTP 请求中删除 S3。

回收请求在文档锁内重新检查整批规则；已 PURGING 的版本允许重新排队接管，已 PURGED 的重复请求返回 `already_purged` 而不再次创建任务。重试仍需携带当前内容修订号。保留标记可通过已知 ID 管理已删除文档，但 PURGING/PURGED 不能新增或解除标记。

GC 使用 PG 持久任务、120 秒租约、fencing 和退避重试。S3 DELETE 幂等，完成后事务性保存 citation tombstone、删除 chunks（词法表级联）、标记 PURGED；版本元数据和发布审计保留。S3 成功/PG 提交前崩溃可重复 DELETE 恢复。每次处理前复核请求者当前管理和文档权限；权限被撤销则记录失败并退避，另一个有权限的管理员可以重新提交同一 PURGING 版本接管任务。

```powershell
# 仅在其他模型验证迁移/权限/回收边界后执行。
$env:RAG_GC_TENANT = 'demo'
docker compose -f compose.rag.yaml --profile maintenance up -d version-gc
# 或只处理指定租户当前到期任务；不是“所有任务保证成功”的验收命令。
docker compose -f compose.rag.yaml run --rm --no-deps worker python -m app.version_gc --tenant demo --once
```

`RAG_VERSION_RETENTION_DAYS` / `RAG_VERSION_KEEP_LATEST` 可经 Compose 主机变量配置，最小均为 1；这是开发默认值，正式保留政策由业务确定。GC 进程不扫描所有租户，也不枚举/删除 S3 孤立对象。已删除文档的回收目前通过已知 doc ID 的 API 操作，页面尚无回收站浏览器。

## 升级与验证交接

本轮未执行以下命令。先由负责验证的模型验证临时数据库迁移/回滚边界，备份后再安排部署。V3 不能与 V2 Worker 混跑：旧 Worker 不维护版本生命周期和内容修订号，也可能覆盖新版本。

```powershell
# 停止写入和所有旧 Worker/dispatcher（包括其他机器上的副本）
docker compose -f compose.rag.yaml stop api worker dispatcher
docker compose -f compose.rag.yaml build migrate
docker compose -f compose.rag.yaml run --rm --no-deps migrate
docker compose -f compose.rag.yaml up -d --pull never
```

0003 向已有版本补编号、上传者和已发布标记，不重新计算向量。迁移前保留数据库/对象存储备份；迁移后不以直接运行 V2 写服务作为回退方式。默认不会启动 maintenance profile，也没有自动触发物理删除。

开发环境生成 OpenAPI 的命令（只生成契约，不启用 lifespan，不运行测试/迁移）：

仓库 `docs/rag/openapi.json` 已使用下面的导出脚本从实际路由生成，版本为 0.3.0。

```powershell
docker compose -f compose.rag.yaml run --rm --no-deps -v "${PWD}/rag-service:/app" -v "${PWD}/docs/rag:/docs" api python scripts/export_openapi.py /docs/openapi.json
```

交给验证模型的重点场景：首版兼容；v2 处理期间 v1 可检索；处理失败旧版不变；人工发布；自动发布冲突；同键重试；同时发布与回滚；发布/撤权/删除竞态；重复 MQ 和过期 Worker；历史引用与原件权限；不兼容 profile 回滚拒绝；GC 保护规则；S3 删除前后崩溃恢复；GC 请求者被撤权；多租户、草稿越权；页面身份切换和在途响应。

## 仍待后续实现

整库 generation manifest、影子全量重建/CAS、跨文档批次原子发布、完整对象孤儿扫描、S3 开启 versioning 后的历史 object versions 生命周期、多租户 GC 编排、回收站 UI、解析阶段产物检查点，以及正式存储保留/备份删除传播策略。这些不应与本次单文档生命周期混为一项完成。

LLM 回答、证据门禁、rewrite、完整 Java 任务租户化继续按生产计划推进。
