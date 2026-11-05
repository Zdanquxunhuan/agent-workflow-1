# V1–V11 公司售后全流程演示计划

编写日期：2026-11-05。状态：**执行交接计划，尚未按本计划启动 Docker、运行演示或调用模型。** 本次只准备文档、合成政策及本地配置模板。执行者应逐项填写实际结果，不得把预期结果当作通过记录。

目标是让面试官看到一条公司业务链：**运营上传政策 → 知识入库与发布 → 客服查政策和订单物流 → 追问与记忆 → 客户明确申请 → 主管审批 → 退款确认 → 审计与权限回收**。这覆盖各版本的核心能力，不等于所有功能、故障和部署组合均已验收。岗位依据见 [toAjob-distance.md](../../toAjob-distance.md)。

## 1. 业务故事、角色和演示材料

虚构公司“星桥商城”的客服 alice 接到售后请求。客户想了解订单 ORD-1001 的物流及退款条件，之后明确申请退款 20 元。运营 admin 发布售后政策，主管 bob 独立审批，系统以 sandbox 模拟支付渠道确认。

| 身份 | 企业职责 | 本次使用的权限 |
| --- | --- | --- |
| demo / admin | 知识运营与权限管理员 | Java ADMIN；RAG 已有管理员成员；管理 KB 和 MCP 授权 |
| demo / alice | 接待客服、退款申请人 | Java CUSTOMER_SERVICE；RAG 已有成员，另授 KB reader；仅授 ORD-1001 物流读权限 |
| demo / bob | 独立主管 | Java SUPERVISOR；RAG 已有成员，按需授 KB reader；审批 alice 的申请 |
| other / alice | 另一租户的同名用户 | 仅用于一次越租户读取，不授 demo 的权限 |

RAG 的成员/ACL、Java 的角色、MCP 的订单授权是三套不同检查。不能仅修改 JWT 中角色就认为获得了知识库/物流权限。本地多 audience JWT 用于串联开发服务，不代表企业 SSO 或 OAuth token exchange 已部署。

准备材料：

- [政策 V1](fixtures/company-refund-policy-v1.md)：主管两个工作日内处理。
- [政策 V2](fixtures/company-refund-policy-v2.md)：调整为一个工作日，退款仍需主管审批。
- MySQL 自带合成订单 ORD-1001：初始 SHIPPED、CNY 199.00、已有物流；实际演示前重新查询，不将初始值硬编码为实时事实。
- 一份本地执行记录，保存 KB/document/version/job/run/task/memory ID、脱敏结果和截图。只使用一个 KB、一份文档的两个版本、一笔退款。

首次搭环境与下载模型不计入面试演示时间。环境就绪后，完整操作建议预留 30–45 分钟；面试时可用已采集证据压缩到 12–15 分钟。不要为了录像好看反复调用模型或重复退款。

## 2. 需要启动什么

采用 **Docker 依赖服务 + 宿主机 Java 17**。Java MCP 和记忆索引的本地 HTTP 校验要求回环地址；直接把 Java 放进容器后填 `http://shipment-mcp:8094/mcp` 不符合当前校验。

| 服务 | 作用 / 涉及版本 | 暴露给宿主机 | 何时运行 |
| --- | --- | --- | --- |
| postgres | RAG 文档、ACL、版本、pgvector；另有私人记忆索引 | 127.0.0.1:5438 | 全程 |
| rabbitmq | 异步入库任务队列 | 5678；管理页 15678 | 全程 |
| minio | 原始文件与版本对象 | 9010；管理页 9011 | 全程 |
| embedding | 固定 revision BGE-M3 向量化 | 仅 Docker 内 8092 | 全程 |
| reranker | V2 检索重排 | 仅 Docker 内 8093 | 全程 |
| api | RAG 页面/API、V4 问答、V7 内部索引 | 127.0.0.1:8091 | 全程 |
| dispatcher | RAG 数据库 Outbox 转入 MQ | 无 | 全程 |
| worker | 解析、切块、向量化、索引与入库状态 | 无 | 全程 |
| mysql | Java 业务、任务、审批、记忆、运行预算与 MCP 账本 | 127.0.0.1:13371 | 全程 |
| shipment-mcp | 独立 Python MCP 物流服务 | 127.0.0.1:8094 | Java 完成 Flyway、配置专用数据库账号之后 |
| migrate / seed / backfill | RAG 升级、演示成员/bucket 初始化、索引回填 | 无 | 一次性，退出 0 是正常完成 |
| 宿主机 Java | V5–V10 Agent、页面、审批和业务 Outbox 调度器 | 127.0.0.1:8080（浏览器访问） | MySQL 就绪后 |

Java 默认可能监听所有接口；本地运行时另设 `SERVER_ADDRESS=127.0.0.1`，不要把带开发身份的服务对外开放。此设置通过 Spring Boot 环境属性生效。

此次不需要 Redis、Kafka、Elasticsearch、独立向量库、GPU 或另外的业务 HTTP 服务。不要一起启动 `compose.yaml` 的 app 或 `compose.business.yaml`：前者默认配置没有开启完整 Agent；后者使用独立业务账本，不能与当前 V8 共账本校验组合。`version-gc` 是维护服务，不启动自动删除。

模型分两类：BGE-M3/reranker 在本机 Docker 中运行；回答/规划 LLM 需要执行者另行确定的服务。V11 测试内嵌的 HTTP stub 不是可直接启动的独立服务。

## 3. 开始前检查与配置

### 3.1 工作目录与隔离

以下命令全部是给后续执行者的 **PowerShell 操作说明**，本次未执行。

```powershell
Set-Location 'F:\myproject\agent-workflow-1\agent-workflow-1'
git status --short
git rev-parse HEAD
docker version
docker compose version
docker info --format '{{.OSType}}'
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -in @(8080,8091,8094,13371,5438,5678,15678,9010,9011) } |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

要求 Docker 使用 Linux containers。建议给 Docker 约 16 GiB 或更多内存作为起点，依实测调整；两个推理容器各配置 6 GiB 上限，还要容纳数据库与其他服务。确认模型权重、镜像与数据库有足够磁盘空间；这不是容量测评结论。

使用项目名 `company-demo` 隔离命名卷。**项目名只隔离资源名，不隔离宿主机端口。** 若端口被既有工作环境占用，先记录，选择空闲端口并同步所有 URL/audience/Host allowlist；不要停止或删除未知容器来抢端口。若已存在 company-demo 数据，先确认是否为同一演示，不能当作空库重新种数据。

```powershell
New-Item -ItemType Directory -Force target/company-demo | Out-Null
Copy-Item deploy/company-demo.env.example target/company-demo/demo.env
$dc = @('compose','-p','company-demo','--env-file','target/company-demo/demo.env',
        '-f','compose.rag.yaml','-f','deploy/company-demo.compose.yaml')
docker @dc config --quiet
```

**仅首次复制模板**；续跑不要覆盖已有密钥配置。主文件必须放在 overlay 前面，保证构建和挂载相对路径以仓库根为准。只做 `config --quiet`，不要将含密钥的完整合并配置放进日志/报告。

配套文件：[Compose overlay](../../deploy/company-demo.compose.yaml)、[环境模板](../../deploy/company-demo.env.example)。其中数据库口令、JWT secret 是公开的本地演示值，只用于这套隔离环境。现有工作环境的 `.env` 不需要覆盖。

### 3.2 分两阶段启用

**阶段 A**：模板默认关闭生成模型、V9 runtime、V10 dialogue 和 V4 answers。先打通基础设施、入库、搜索、版本与授权；`MEMORY_ENABLED` 和 MCP 配置可提前打开。这个阶段会运行本地 embedding/reranker，但不应发起生成模型调用。

**阶段 B**：执行者确定模型、外发数据和预算后，在 `target/company-demo/demo.env` 填好以下项，再重建 api 容器并重启 Java：

| 配置 | 要求 |
| --- | --- |
| DEEPSEEK_ENABLED / AGENT_RUNTIME_ENABLED / AGENT_DIALOGUE_ENABLED / RAG_ANSWER_ENABLED | 全部改为 true |
| DEEPSEEK_API_KEY | 经授权的密钥，只留在忽略目录/进程环境，不写报告 |
| DEEPSEEK_MODEL / RAG_ANSWER_MODEL | 服务实际支持的模型名；不要假设示例模型仍可用 |
| DEEPSEEK_BASE_URL / RAG_ANSWER_BASE_URL | 经授权的兼容服务；模板为 DeepSeek HTTPS 地址 |
| RUNTIME_PRIMARY_INPUT_PRICE / OUTPUT_PRICE / PRICE_VERSION | 经核实的每百万 token 单价与价格版本，不填猜测值 |
| RUNTIME_CURRENCY / REQUEST_BUDGET / DAILY_BUDGET | 同一币种，明确单 run 和用户日预算 |
| DEEPSEEK_ALLOWED_TENANTS / RAG_ANSWER_ALLOWED_TENANTS | Java 为 `demo`；RAG 为 JSON 数组 `["demo"]` |

保留 `DEEPSEEK_GATEWAY=spring-ai`、JDBC、`COMMERCE_TENANT=demo`、`COMMERCE_GATEWAY=sandbox`。记忆索引内部 secret 必须与用户 JWT secret 不同，并在 Java/RAG 两端相同。默认禁用备用模型、自动记忆提取和审计外部发布。

本地配置不启用 prod profile；这是**模拟公司流程的开发部署**，不是生产部署验收。正式 OIDC/JWKS、TLS、独立迁移账号、备份/监控另有部署要求。

### 3.3 小样本与调用预算

只走一遍主线，不跑完整测试套件、压测或批量评测。建议执行者申请**最多 22 次生成模型请求**的全局上限，预期通常少于上限；若希望更少，可先执行阶段 A，将生成环节标记待执行。历史 V5 的调用许可不自动延续到这次。

| 用途 | 预留次数 | 备注 |
| --- | --- | --- |
| V4 独立知识问答 | 1 | 不重复生成，直接复用引用和反馈 |
| V10 缺参、补充、订单/知识/MCP 查询及最终回答 | 最多 6 | 同一 run 的配置上限，预算不随补充输入重置 |
| V6 两轮会话 | 最多 12 | 两个 run，各最多 6；若提前回答完成就停止 |
| 独立退款任务规划 | 最多 2 | 既有规划器包含重试，不误记为固定 1 次 |
| 余量 | 1 | 仅处理事先允许的单次情况，不默认用完 |

上述是交接的总调用安排，**当前 V9 账本并不统一限制 V4 answers、旧任务规划器、embedding 和所有外部调用**。执行者要分路径记账；需要严格全局硬上限时使用已有调用代理/供应商限额，否则按各阶段的最坏剩余额度决定是否开始，无法确认次数时停止。不得默默追加循环或换模型。

待授权的外发内容应包括合成政策、合成问题、工具实际返回的合成字段及记忆摘要。种子订单含合成客户/地址字段，不能未经检查就声称“外发仅含状态金额”。先核对工具输出和提示构造，向用户说明实际范围；不发送真实客户资料。任何费用数字均区分估算、可信 usage 与真实账单。

## 4. Docker 和 Java 启动顺序

每步检查退出码；失败停在当前步骤，不连带跑后续命令。PowerShell 中原生命令失败不一定抛异常，可用 `if ($LASTEXITCODE -ne 0) { throw '当前步骤失败' }`。下列分组不是要求一次整块无条件粘贴。

### 4.1 构建与启动基础设施

```powershell
docker @dc build migrate minio embedding shipment-mcp
docker @dc up -d postgres rabbitmq minio mysql
docker @dc ps
```

四项基础设施应 healthy；使用 `docker @dc logs --tail 60 服务名` 定点看失败，不倾倒所有日志。网络受限导致镜像/依赖下载失败时记录原因，不能私换模型或未审查的镜像。

### 4.2 初始化 RAG

基础设施就绪后，依次执行：

```powershell
docker @dc run --rm --no-deps migrate
docker @dc run --rm --no-deps seed
docker @dc run --rm --no-deps backfill
```

三步分别检查退出 0。升级到当前 Alembic head（含 V7 记忆索引所需迁移），不要只按历史 V4 文档升级到 0004。seed 创建 demo/other 的演示成员和对象桶，不会替你完成本次 KB/文档上传。backfill 对新库通常没有历史数据，但保留该步骤确保流程完整。

### 4.3 准备本地模型并启动 RAG 工作进程

隔离项目使用新的模型卷，不能因为旧项目下载过就假设此卷也有权重。首次按仓库固定 revision 下载；已有完整缓存时脚本会检查/复用，不要删除模型卷重下。

```powershell
$downloadScript = Join-Path (Get-Location).Path 'rag-inference/download_model.py'
docker @dc run --rm --no-deps --user 0 -v "${downloadScript}:/app/download_model.py:ro" embedding python /app/download_model.py
docker @dc run --rm --no-deps --user 0 reranker python /app/download_reranker.py
docker @dc up -d --no-deps embedding reranker
docker @dc ps
```

等两个模型都 healthy，再启动：

```powershell
docker @dc up -d --no-deps api dispatcher worker
Invoke-RestMethod http://127.0.0.1:8091/health/live
Invoke-RestMethod http://127.0.0.1:8091/health/ready
Invoke-RestMethod http://127.0.0.1:8091/api/v1/config
```

RAG ready 检查数据库、对象存储和 embedding；**它不替代 RabbitMQ、worker、reranker 的检查**。api 的 Docker healthcheck 只是 live。还要看 worker 已连接 `rag.ingest` 队列、dispatcher 正常，以及稍后的真实上传 job。模型冷启动可能较慢，先看进度，不把等待写成检索延迟。

### 4.4 启动宿主机 Java，完成 Flyway

在用于 Java 的 PowerShell 中导入模板（每次重启前重新导入；不执行文件里的文本）：

```powershell
Get-Content target/company-demo/demo.env | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith('#')) {
    $parts = $line -split '=', 2
    if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[A-Z][A-Z0-9_]*$') {
      throw '无效的环境配置行'
    }
    [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
  }
}
$env:SERVER_ADDRESS = '127.0.0.1'
$env:JAVA_HOME = 'F:/ayd/jdk-17'
& "$env:JAVA_HOME/bin/java.exe" -version
.\mvnw.cmd -o -B -ntp '-Dmaven.test.skip=true' package
# 上一步成功后，在此终端前台运行；其他操作在第二个 PowerShell 中做。
& "$env:JAVA_HOME/bin/java.exe" -jar target/agent-workflow-1-0.0.1-SNAPSHOT.jar
```

这是一条给执行者的跳过测试构建命令，本次没有执行。若 JDK 路径不同需替换；离线 Maven 缓存不全时先报告缺失依赖，再决定是否在线构建，不追加完整测试。第二个终端重设工作目录和 `$dc`，检查 `http://127.0.0.1:8080/actuator/health`。

保存启动日志中的 Flyway 成功记录，确认 Java 当前迁移 V1–V12 全部应用到 **13371 的隔离 MySQL**。应用版本 V10 与数据库 migration V12 不是同一种编号。不要让这次命令连到以前的工作数据库。

### 4.5 初始化 MCP 专用只读业务账号，再启动 MCP

Java 建表后，管理员在此次隔离 MySQL 中建账号，并应用现有最小授权模板。以下固定口令仅与演示 overlay 匹配：

```powershell
$createMcpUser = "CREATE USER IF NOT EXISTS 'mcp_reader'@'%' IDENTIFIED BY 'company_mcp_local_only';"
$grantSql = Get-Content mcp-shipment/database-permissions.sql -Raw
($createMcpUser + "`n" + $grantSql) |
  docker @dc exec -T mysql mysql -uroot -pcompany_root_local_only workflow_agent
docker @dc up -d --no-deps shipment-mcp
Invoke-RestMethod http://127.0.0.1:8094/health/ready
```

生产授权模板建议 TLS/受限来源；这里的 `%` 和无 TLS 仅限隔离的开发容器网络。MCP 不可用 root/workflow 账号，不应获得 orders/customer/payment 内容访问权。账号已存在但密码不符时核对所属环境，不盲改密码。

ready 只说明服务基础条件，最终以一次实际 MCP 工具调用和授权记录为准。Java 与 MCP 指向同一个 MySQL/`mcp_ledger_binding`；不要手改 UUID 绕过账本不一致。

### 4.6 启用阶段 B

阶段 A 的文档/版本操作完成、调用预算确定后，填好 3.2 的配置。停止 Java 前先确认没有运行中的任务，随后：

```powershell
docker @dc up -d --no-deps --force-recreate api
```

Java 终端重新导入 env 并启动同一个 jar，不必重新编译；确认 RAG config 中 `answers_enabled=true`。检查 Java 日志没有价格缺失、tenant allowlist、MCP 或 memory 配置失败。不要用关掉引用/授权检查的方法解决启动或回答失败。

## 5. 身份、知识库与 MCP 授权准备

### 5.1 本地开发令牌

RAG 自带 dev-token 只有 `rag-api` 和 `workflow-agent` 两个 audience；Java dev-token 也不直接签多 audience。它们不足以让同一令牌直接访问 MCP。本方案由执行者在本地 api 容器内使用已有 PyJWT 签临时开发令牌：

```powershell
$issue = @'
import os, sys, time, jwt
now = int(time.time())
claims = {
  'sub': sys.argv[1], 'roles': [sys.argv[2]], 'tenant_id': sys.argv[3],
  'iss': os.environ['RAG_AUTH_ISSUER'],
  'aud': ['rag-api', 'workflow-agent', 'http://127.0.0.1:8094/mcp'],
  'iat': now, 'exp': now + 3600,
  'scope': 'shipment:read', 'client_id': 'workflow-agent'
}
print(jwt.encode(claims, os.environ['RAG_AUTH_SECRET'], algorithm='HS256'))
'@
$adminToken = ($issue | docker @dc exec -T api python - admin ADMIN demo).Trim()
$aliceToken = ($issue | docker @dc exec -T api python - alice CUSTOMER_SERVICE demo).Trim()
$bobToken = ($issue | docker @dc exec -T api python - bob SUPERVISOR demo).Trim()
$otherToken = ($issue | docker @dc exec -T api python - alice CUSTOMER_SERVICE other).Trim()
$admin = @{ Authorization = "Bearer $adminToken" }
$alice = @{ Authorization = "Bearer $aliceToken" }
$bob = @{ Authorization = "Bearer $bobToken" }
```

令牌留在变量或本机页面，不能加入报告或命令日志；超时按同一身份续发。浏览器使用分别隔离的配置/窗口，避免把 admin 的令牌误用于客服截图。若知识库页面没有令牌粘贴入口，可用其开发登录完成纯 RAG 操作；Java/MCP 仍使用上述多 audience 令牌。不得将这个开发签发流程接入真实生产。

### 5.2 创建 KB 并授阅读权

RAG 页面为 `http://127.0.0.1:8091`，Java 页面为 `/dialogue.html`、`/conversation.html`、`/memory.html`。下面 API 作为页面不可用时的准确操作依据；Java 普遍用 `data` 包装响应，RAG 多为直接对象，**不要混用解析方式**。

```powershell
$kb = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/v1/knowledge-bases `
  -Headers $admin -ContentType 'application/json' -Body '{"name":"星桥售后政策演示"}'
$kbId = $kb.id
foreach ($subject in @('alice','bob')) {
  Invoke-RestMethod -Method Put -Uri "http://127.0.0.1:8091/api/v1/knowledge-bases/$kbId/acl" `
    -Headers $admin -ContentType 'application/json' `
    -Body (@{subject=$subject;role='reader'} | ConvertTo-Json)
}
```

建立一个 KB 即可，避免 Java 既有“最多访问若干 KB”的限制及旧资料干扰。

### 5.3 授予 ORD-1001 物流读取

管理员先 GET `/api/v1/mcp/shipment/grants?subjectId=alice&orderId=ORD-1001` 读取 revision。新授权返回 revision 0。随后 PUT 同路径，正文：

```json
{"subjectId":"alice","orderId":"ORD-1001","enabled":true,"expiresAt":0,"expectedRevision":0,"reason":"合成售后演示临时授权"}
```

**发送前**把 `expiresAt` 替换为 `[DateTimeOffset]::UtcNow.AddHours(2).ToUnixTimeSeconds()` 的值，revision 替换成当前值；0 只是占位，不能直接提交启用。记录返回 revision，收尾撤权用新 revision。MCP 内部不因为 JWT 含 `shipment:read` 就跳过订单级授权。

## 6. 一条完整公司业务流程

步骤按顺序执行。每步记录“实际状态 / 关键 ID / 证据路径 / 是否消耗生成调用”。出现阻断时保存失败现场，不伪造后面的成功。

### A. 运营上传政策，观察真正异步入库（V1）

1. admin 在“文档入库”选择上述 KB，上传 `fixtures/company-refund-policy-v1.md`。或调用 `POST /api/v1/knowledge-bases/{kbId}/documents`，multipart 字段 `file`，唯一 `Idempotency-Key`。
2. 保存返回的 documentId、jobId、versionId（以实际字段名为准）。立即展示 202 受理与“处理记录”；不要将上传返回当作索引完成。
3. 轮询 `GET /api/v1/jobs/{jobId}`，可每 3–5 秒一次，设置有限截止时间；失败时只查该 job 的 dispatcher/worker 日志。保存实际出现的中间状态，不要求录像必须捕捉每个瞬态。
4. 完成后读取 `GET /api/v1/documents/{documentId}`，确认 job SUCCEEDED、首版已可检索且 active version 正确；若仅 READY，检查发布状态再继续。
5. 解释文件进入 MinIO、元数据和 Outbox 进入 PG、dispatcher 发 MQ、worker 解析切块和 embedding 后写 pgvector 的分工。证据用 job、文档和检索结果即可，不要直接改数据库模拟成功。

**验收**：至少一个可定位的真实上传任务和可读 chunk，使用真实 BGE-M3；没有用预塞的政策字符串代替入库。

### B. 客服搜索，展示检索诊断与权限（V2）

alice 在“检索知识”输入“已发货订单退款是否需要审批”，KB 固定本次一个，top_k=3，profile=`hybrid-rerank-v2`。记录命中文本、chunkId、documentVersionId、citation URL 和实际耗时。

admin 用同一句问题调用 `POST /api/v1/search/debug`，观察词法/向量候选、融合与重排的实际返回字段。只做一次对照，可加一次 `lexical-v2` 搜索；不跑 44 题评测集，也不把单句结果称为质量提升证明。

通用搜索正文：

```json
{"query":"已发货订单退款是否需要审批","knowledge_base_ids":["替换为本次 kbId"],"top_k":3,"profile":"hybrid-rerank-v2"}
```

API 为 `POST /api/v1/search`。通过当前用户 Bearer token 打开返回引用，核对原文。other/alice 用同一个资源 ID 读取时应无权取得 demo 原文；具体可能是隐藏式不存在或拒绝，记录实际状态，不要求所有接口一律 403。

**验收**：政策确实来自上传文件，引用能定位；跨租户不能读取。搜索分数不等于回答正确率，单次耗时不等于 p95。

### C. 运营发布政策变更（V3）

1. admin 在“文档版本”对同一个 document 上传 V2，`auto_publish=false`，填写“审批时效从两个工作日调整为一个工作日”。API：`POST /api/v1/documents/{documentId}/versions`，multipart 字段 `file`、`expected_content_revision`、`change_note`、`auto_publish`，带新幂等键。
2. 等 job 完成，新版 READY；发布前搜索“主管多久处理”仍应使用当前 V1。不要上传到另一个 document，造成两个“最新版”同时被检索。
3. admin 在页面比较 V1/V2，展示版本标识与时效条款变化，主管审批规则没变。bob 可人工复核本地 V2 文件或 admin 展示的差异，admin 再发布；bob 的 reader 权限不意味着能直接查看未发布草稿。
4. 发布 API：`POST /api/v1/documents/{documentId}/versions/{v2Id}/publish`，带幂等键，JSON 为 `expected_content_revision` 和 `reason`。revision 必须刚读，不硬编码。
5. 再搜时效，命中 V2 和“一个工作日”；保存发布事件、active_version_id 和 content_revision。

**边界**：当前代码提供版本、权限与 CAS 发布，没有强制文档四眼审批工作流。bob 复核是本次企业操作程序，不能宣称系统强制“上传者不可发布”。

### D. 客服独立知识问答，验证引用与反馈（V4）

完成阶段 B 的模型启用后，alice 在“知识问答”问：“已发货订单退款需要谁审批，主管应在多久内处理？”只生成一次。

API：`POST /api/v1/answers`，与上述搜索同类 query/KB/top_k/profile 参数。预期 answered，回答包含主管审批及当前 V2 时效，返回结构化 statements/citations/usage。点开至少一个引用核对逐字摘录与版本；反馈 `POST /api/v1/answers/{answerId}/feedback`，正文 `{"rating":"helpful"}`。

若返回 insufficient_evidence、conflicting_evidence、引用门禁拒绝或供应商故障，分别记下真实原因；不能绕过校验强行展示 answered。格式/引用校验通过也不能等价于所有语义正确。

### E. Java 使用同一份政策，而非内置示例（V5）

alice 调用 Java `POST /api/v1/knowledge/search`，正文 `{"query":"已发货订单退款是否需要审批"}`。记录返回政策 ID/引用与前面上传版本的一致性。

这是无状态 HTTP 检索桥接，不单独再请求一次生成。下一步的 Java 工具调用经过 Spring AI；保存实际工具调用记录证明 Java → RAG 和模型 SDK 参与，不拿页面上相似的文字当成集成证据。

### F. 客服补问客户信息，联合查订单、物流和政策（V8、V9、V10，复用 V5）

1. alice 在 `/dialogue.html` 提问：“帮我查一下订单现在的状态和物流，能不能退款，需要什么审批？”故意不填订单号。API：`POST /api/v1/agent/dialogues`，`{"message":"上述问题"}`，唯一幂等键。
2. 等到 WAITING_INPUT，保存 runId、taskId、待补字段、revision、当前 usage 和计划。若模型未按预期澄清，记录实际行为，不反复重开直到成功。
3. 输入 ORD-1001。API：`POST /api/v1/agent/dialogues/{runId}/inputs`，`{"revision":当前值,"field":"orderId","value":"ORD-1001"}`，新幂等键。确认仍是同一 run/task，已花次数和预算没有清零。
4. 查看订单工具、RAG 工具和 **远端 MCP 工具**的实际执行记录。启用 MCP 时 `shipment.query` 由 McpShipmentQueryTool 接管，关闭时是本地实现，两者工具名相同；因此必须看 MCP 调用账本/服务记录，不能只凭工具名证明远端协议已跑。若模型未选择物流工具，记录“此轮未覆盖”，不要据此宣称 V8 已打通或随意追加调用。
5. 最终答案应区分当前订单/物流事实与政策要求，不承诺已经退款。通过 `GET /api/v1/agent/runs/{runId}/result` 获取结果和当前来源校验；WAITING_INPUT 时不能提前展示最终答案。
6. 查看 `GET /api/v1/agent/runs/{runId}`、`/{runId}/usage`、`/{runId}/events` 和 `GET /api/v1/agent/dialogues/{runId}/plans`。这里 events 是 SSE 进度，不是正文逐 token 流。

**验收**：真实工具调用、同 run 恢复、带当前来源的答案、实际用量/unknown 记录。只根据输入措辞不能保证模型按固定顺序调用工具，覆盖矩阵以轨迹为准。

### G. 客服保存偏好并跨轮追问（V6、V7）

这一步切换到现有 `/memory.html` 和 `/conversation.html`。**V10 页面当前没有自动导入 V6/V7 会话和偏好**，不是在同一个聊天窗口里自动完成。

1. alice 显式创建偏好：`POST /api/v1/memories`，`{"slot":"response_style","value":"concise","days":1}`，唯一幂等键。保存 CANDIDATE；`POST /api/v1/memories/{id}/confirm`，正文 `{"revision":当前值}`，确认变成 ACTIVE。
2. `POST /api/v1/conversations` 建会话，第一轮问“查询 ORD-1001 当前状态和退款规则”；`POST /api/v1/conversations/{id}/messages`，正文 `{"revision":当前值,"message":"问题"}` 和幂等键。
3. 第二轮同一 conversation 问“那主管多久处理？”，使用返回的新 revision 和新幂等键。观察历史上下文和简洁偏好被选择，业务事实仍走当前查询。用返回记录/可审计的记忆选择证明偏好使用，不单靠“看起来比较短”。
4. 选取一个属于 alice、已 SUCCEEDED 的查询 taskId，`POST /api/v1/memories/extractions`，`{"taskId":"该任务 ID"}`。当前提取是确定性摘要，不调用额外 LLM；由后台作业生成 TASK_SUMMARY 候选，不自动确认。
5. 从 `GET /api/v1/memories` 找到摘要，确认；观察 `/memories/jobs` 的索引作业状态，再 `POST /memories/recall`，`{"query":"ORD-1001 的退款查询历史"}`。记录 semantic_summary 选择或真实未命中/降级；索引服务恢复正常不等于必然命中。
6. 用 bob 读取 alice 的 memory ID 应不可见。最后 DELETE `/memories/{id}?revision=当前值` 遗忘偏好/摘要，查看事件。再次读取依赖该记忆的旧会话可能触发 MEMORY_CHANGED，这是撤销依赖的边界，不是继续用旧内容的许可。该检查只读，不需要再发生成问题。

**验收**：会话有两轮；偏好经历候选→确认；摘要经过 MySQL→索引→召回并复核 owner/revision/source；遗忘后不继续用旧记忆。若语义索引未命中，应把 V7 拆成“显式偏好已覆盖 / 语义召回未覆盖”，不要混为通过。

### H. 客户明确申请，主管独立审批与退款确认（既有业务闭环，V5/V11 交付）

1. 先保存 `GET /api/v1/commerce/orders/ORD-1001` 的订单/支付/退款聚合快照，记录现有退款数量和总额，确认本次环境尚未执行过该退款。
2. 客户明确要退 20 元后，alice 新建写任务：`POST /api/v1/tasks`，`{"request":"为订单 ORD-1001 退款 20 元"}`，专用幂等键，例如 `company-demo-refund-本次批次号`。
3. 保存独立 writeTaskId，等待 WAITING_APPROVAL，核对工具、订单、金额。**query runId 与 writeTaskId 只在演示记录中关联**，没有自动把回答或澄清输入变成写授权。
4. alice 尝试批准应失败，记下是角色不足还是职责分离拒绝；客服没有审批角色，因此这一次不能单独证明“有审批角色的人也不能自批”。后者已有 V11 定向证据，本次不为此另建退款任务。
5. bob 调用 `POST /api/v1/tasks/{writeTaskId}/approve`，`{"reason":"已核对合成订单、政策和 20 元申请"}`。保存审批主体、状态事件，观察 WAITING_EXTERNAL/最终状态；中间态可能很快，不要求一定被页面轮询捕获。
6. 等业务 Outbox 和 sandbox 确认，任务 SUCCEEDED 后再读订单聚合，核对本次仅增加一笔 20 元退款及对应外部命令结果。不同来源/状态字段以实际响应为准。
7. 用完全相同幂等键、完全相同正文重交申请，应返回已有任务；重交审批不得新增退款。再次核对退款数量，而不是只看接口返回 200。

**验收**：审批前无本次退款，审批人与申请人不同，受理和最终确认分开，最终只新增一笔退款。sandbox 模拟支付下游，不是真实支付公司退款；不再从 V10 自动重规划重放写动作。

### I. 运营收尾：权限回收、政策回滚和审计

1. 保存所有成功阶段证据后，admin 用当前 revision 将 alice 的 MCP 订单 grant 改为 disabled。若 F 的答案实际依赖 MCP，再读取原 run result，当前来源门禁应阻止继续发布旧授权结果；不发新的生成请求。
2. admin 删除 alice 的 KB ACL：`DELETE /api/v1/knowledge-bases/{kbId}/acl/alice`。alice 再访问之前的具体引用，应不能获得正文；记录实际状态，不能仅靠“搜索没结果”证明引用撤权。
3. admin 在版本页执行回滚到曾发布的 V1，API `POST /api/v1/documents/{documentId}/versions/{v1Id}/rollback`，带新幂等键和当前 `expected_content_revision`、`reason`。admin 搜索时效应重新命中 V1；这只是知识版本回滚，不撤销已经完成的业务退款。
4. 汇总入库 job、发布 revision、answerId、query run/task、conversationId、memoryId、writeTaskId、审批/Outbox 结果、引用版本、用量和错误。不得把没有共同 trace 的服务包装成已有完整分布式追踪；可用本次批次号人工关联。
5. 历史文档引用是否可读还受 ACL/版本保留策略影响，不能声称“发布新版本自动让所有旧引用 404”。此处通过真实撤权验证不可读。

## 7. 版本覆盖与面试讲解卡

| 版本 | 本次主线证据 | 面试需要讲清楚 | 不由本次主线证明 |
| --- | --- | --- | --- |
| V1 | A 上传→job→chunk→引用 | Outbox/MQ、异步受理、对象与索引分工 | OCR、海量吞吐、所有故障恢复 |
| V2 | B hybrid/rerank 与一次诊断 | 词法/向量互补、RRF、重排与预算 | 统计意义的质量/延迟提升、ANN 规模能力 |
| V3 | C 新版 READY→发布；I 回滚 | 原子 active 指针、CAS、历史版本 | 强制文档双人审批、执行破坏性 GC |
| V4 | D 引用问答与一次反馈 | 无证据拒答、引用门禁及局限 | 消除幻觉、业务准确率指标 |
| V5 | E Java 桥接；F Spring AI 真实工具轨迹 | 框架负责协议，执行器负责权限/状态 | 框架对比性能和通用插件平台 |
| V6 | G 两轮会话 | 历史窗口/摘要、当前事实重查 | 长对话极限与多人并发完整验收 |
| V7 | G 确认、摘要索引/召回、遗忘 | MySQL 权威、pgvector 召回、owner/revision/source 检查 | 自动学习所有轮次、召回质量指标 |
| V8 | F 独立 MCP；I 撤权 | 契约、audience、scope、订单 grant、共账本 | 企业 IdP 委托、所有 MCP 客户端互操作 |
| V9 | F 同链用量与 SSE、终态 | 配额/预留/unknown 与账单的区别 | 备用模型真实切换、正文 token 流、全局成本覆盖 |
| V10 | F 缺参→等待→同 run 恢复 | 有限计划、用户输入 revision、预算不重置 | 每次真实模型都能触发重规划 |
| V11 | H 与证据汇总；结合已有验证记录 | 说清已验证、替身、本次观测的边界 | 全版本全量验收或真实生产履历 |

若面试需覆盖 **V9 取消/备用模型或 V10 有限重规划**，优先展示 [V11 已有验证报告](verification-v11-2026-11-05.md) 及 [V10 手册](demo-v10.md) 的设计，不为演示临时大量调用。确需实跑时作为追加小场景单独安排：

- 取消：新建一条只读等待 run，取消后尝试补输入，检查不可恢复；不取消退款来证明只读取消。
- 重规划：在隔离夹具中让首次授权知识查询无结果、改写后有结果，核对修订次数；拒绝/撤权不能换路。真实模型不稳定时记录未覆盖，不改数据反复撞成功。
- 备用模型：独立可控供应商失败场景和明确备用预算；不能把关闭备用模型的主线说成路由已验证。
- restart/deadline/GC：本次不额外做；已有测试记录或独立后续演练承担。

这些追加场景不包含在上述主线 22 次建议预算中，需要执行者另行安排。完整业务演示不要求把所有故障测试在现场重做。

## 8. 常见阻断与停止条件

| 现象 | 先检查 | 本次处理原则 |
| --- | --- | --- |
| 容器都 running 但上传不完成 | MQ、dispatcher Outbox、worker 队列、embedding ready、具体 job 错误 | 只定位此 job，不重复上传大量副本 |
| RAG ready，但 hybrid 不工作 | reranker 健康、检索 profile、模型 revision | 记录真实降级；不能改成 lexical 后仍声称真实 rerank 通过 |
| 新版入库完成，仍搜到旧时效 | READY/发布状态、active_version_id、content_revision | 按 CAS 发布，不直接修改索引表 |
| Java/RAG 401 或 403 | issuer、audience、secret、tenant、成员和 ACL | 先修身份配置，不放开鉴权 |
| MCP NOT_FOUND / 拒绝 | 精确资源 audience、Host、scope/client、grant、过期、账本绑定 | 不改用未授权本地工具绕过 |
| Java 启动失败 | JDK17、隔离 JDBC、Flyway、价格、allowlist、配置守卫 | 不切回内存假装持久链路成功 |
| 记忆偏好有了，语义索引没有 | 提取/确认/索引作业、内部 secret、当前 source 与 owner | 分开记录偏好与语义召回结果 |
| 生成回答被引用门禁拒绝 | 工具证据、结构化响应、版本/权限变化 | 保存失败；不补造引用、不反复付费重试 |
| 退款一直 WAITING_EXTERNAL | dispatcher、integration_commands、sandbox 确认及任务状态 | 不换幂等键重提退款 |
| 预算/调用数不明或超限 | 各路径调用记录与供应商结果 | 停止生成；保留已完成的无模型步骤 |

网络下载、构建或代码缺陷使本计划不能执行时，输出阻断点和最小修复建议。用户此次授权的是演示计划；后续执行模型也应遵守届时实际执行范围，不能把修业务逻辑、补测试或生产部署自动加入任务。

## 9. 证据格式与完成判据

建议本地原始材料存 `target/company-demo/evidence/`；最终脱敏报告单独放 `docs/agent/reports/company-demo-实际日期.md`。不要覆盖旧 V11 报告。建议截图 8 张即可：上传/job、检索引用、版本差异/发布、V4 回答、V10 澄清/工具、记忆选择、审批/退款、账本/撤权。

记录模板：

```text
批次 / 日期 / git SHA：
执行者 / Java 与镜像版本：
环境：company-demo，端口，模型名称与价格版本（无密钥）
状态：PASS / FAIL / BLOCKED / NOT_RUN（逐阶段填写）
KB / document / v1 / v2 / job：
answerId / 引用版本：
queryRunId / queryTaskId / plan revisions / 工具名称：
conversationId / turn revisions / memoryId / index job：
writeTaskId / requestor / approver / business command：
退款前后数量和金额：
生成调用：V4 __；V9/V10 __；V6 __；旧写规划 __；unknown __
provider usage / 预留估算 / 账单：分别记录，没有就写未知
失败点、恢复动作、尚未覆盖：
证据文件 / 关闭与保留资源：
```

完成至少要求：真实文件上传到当前索引；新版本发布后被引用；Java 使用同一知识与当前业务事实；实际 MCP 协议调用；持久澄清和两轮会话；显式记忆及摘要索引的真实结果；独立审批后仅一笔 sandbox 退款；至少一次当前权限回收阻止旧读取；记录所有模型调用和未覆盖项。

缺项时报告“主线部分完成 + 缺项”，不要写“V1–V10 全打通”。已有 [V11 9 项验证](verification-v11-2026-11-05.md) 与本次公司业务演示是不同证据，不能互相冒名。

## 10. 结束、保留与清理

1. 停止发新请求，确认运行中 run 已终止，已审批退款完成或有明确待处理记录；导出脱敏证据。
2. 撤销本次 alice 的临时 MCP/KB 权限，遗忘本次确认的私人记忆；不要删除旧项目资料。
3. Java 前台进程用 Ctrl+C 正常退出；若执行者使用后台启动，记录并仅停止本次 PID，不能按所有 java 进程批量终止。
4. 后续还要复盘时执行 `docker @dc stop`；结束容器但保留卷时执行 `docker @dc down`。两者择一，不默认删卷。
5. 本次默认**不执行 `down -v`、`docker system prune`、全局 volume prune 或数据库清库**。若以后确需删除，只在核对 company-demo 项目标签、容器/卷名与证据留存后处理该项目；模型卷删除会丢缓存，资金模拟结果不会通过应用回滚自动消失。
6. 确认没有此次服务继续消耗资源；清除终端 token 变量和浏览器中的本次开发身份。真实 API key 的保留/撤销遵循用户选择，不写进提交。

## 11. 可直接交给下一模型的执行任务

> 请按照 docs/agent/company-demo-plan.md，在隔离本地环境完成一遍公司售后全流程演示。先读当前代码与配套 compose/env，检查端口和已有资源，不覆盖原工作环境。先执行无生成模型的阶段 A；真实模型服务、外发合成字段与总调用预算由用户明确后再执行阶段 B。只用一份政策的两个版本、一个主订单、一笔 20 元 sandbox 退款；不要运行全量测试或追加大量模型试错。每阶段记录实际 ID、状态、引用和用量，未覆盖就明确标注，不把计划写成成功。最后提交脱敏报告及版本覆盖表，停止本次进程并保留数据卷。遇到阻断先定位最小原因，不以关掉权限、引用、预算或幂等校验绕过。
