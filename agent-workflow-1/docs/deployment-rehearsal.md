# Deployment rehearsal

The reproducible path is:

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-17'
.\mvnw.cmd clean package -DskipTests
docker compose up --build -d
curl.exe http://localhost:8080/api/v1/health
curl.exe http://localhost:8080/api/v1/health/readiness
docker compose ps
docker compose logs app
```

Compose sets `AGENT_PERSISTENCE=jdbc`, points the application at the `mysql` service, waits for the MySQL health check, and lets Flyway apply `V1..V5`. It also enables `COMMERCE_SEED_DEMO_DATA=true` for the demo stack and the commerce Outbox dispatcher. Stop and restart with `docker compose down` and `docker compose up -d`; the named volume `workflow_mysql` preserves the database.

For a local run with only the database in Docker:

```powershell
docker compose up -d mysql
$env:AGENT_PERSISTENCE = 'jdbc'
$env:COMMERCE_SEED_DEMO_DATA = 'true'
& '.\mvnw.cmd' spring-boot:run
```

MySQL is exposed on host port `3307` because a native MySQL often owns `3306`. Verify keys after startup:

```powershell
docker exec -it agent-workflow-1-mysql-1 mysql -uworkflow -pworkflow_dev_only -D workflow_agent `
  -e "select count(*) from orders; select status,count(*) from integration_commands group by status"
```

A rehearsal was performed on 2026-10-27 with Docker MySQL 8.0 and JDK 17:

- Flyway applied `V1..V5`; demo seed loaded 10 orders.
- Read path: order/payment/shipment queries returned MySQL data through the task API.
- Write path: approved refund `ORD-1001` 20 CNY, address change and shipment creation on `ORD-1003` all settled to `ACKNOWLEDGED`; orders, payments, shipments, address log, audit events and outbox rows matched.
- Rejection path: over-refund and address change after shipment returned a `FAILED` task with `REJECTED` step and no partial business row.
- Idempotency: repeating task creation with the same `Idempotency-Key` returned the same task id and did not duplicate writes.

Remaining environment checks: MySQL restart under load and replacing the sandbox gateway with real providers.

## Two-instance acceptance (2026-10-27)

Run from the project root with Docker MySQL available:

```bash
python scripts/two_instance_acceptance.py
```

The script recreates only `workflow_agent_verify`, grants it to the workflow user, builds the jar, starts instances A/B on ports `18081`/`18082`, a third instance on `18083`, and a `prod`-profile guard process on `18084`. It issues local HS256 tokens and writes evidence to `target/two-instance/acceptance-report.json` plus per-instance logs.

Result: **73/73 checks passed, exit 0**:

- Cross-instance token acceptance; missing token returns 401; forged `X-User-Id`/`X-Role` headers are ignored and still return 403 for supervisor-only actions.
- Six concurrent creates with the same `Idempotency-Key` returned one task id and exactly one database row; duplicate-key fallback was exercised (six resolutions across A and B) rather than a pre-check.
- Concurrent approvals both returned 200, the task became `SUCCEEDED` with `sideEffectCount=1`, and exactly one refund, one transaction, one `ACKNOWLEDGED` command and one `TOOL_EXECUTED` event were written; the order ended `PARTIALLY_REFUNDED` 2.00.
- Approve-vs-cancel races across three iterations always produced one winner: `SUCCEEDED` with one write XOR `CANCELLED` with none, and no duplicate outbox idempotency keys.
- Stopping instance A left instance B ready and serving.
- An artificially stale `RUNNING` task was moved to `RECOVERY_REQUIRED` with exactly one recovery audit event, while a live `RUNNING` task was untouched.
- The `prod` profile refused to start with unsafe defaults (memory persistence, local auth, dev token, demo seed, default password) and listed every violation.

Graceful shutdown and readiness are configured in `application-prod.properties`; the acceptance script asserts readiness on both instances but does not assert connection draining during shutdown.
