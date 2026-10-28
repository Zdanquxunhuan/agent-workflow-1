# ADR 001 Persistence and planner boundaries

## Decision

Keep the first vertical slice deterministic and in memory by default. Provide an opt-in DeepSeek planner behind the `TaskPlanner` interface, then add relational persistence behind the repository interfaces. MySQL is the single store for both workflow state and commerce data.

## Reason

The document requires the execution controls to be server-owned and testable before an external model is connected. A deterministic planner makes whitelist, validation, approval, idempotency, and audit tests reproducible. When enabled, DeepSeek uses JSON Output to propose a plan; the server still validates every tool and argument. Flyway migrations under `src/main/resources/db/migration` define the durable schema, including commerce tables.

## Consequence

The default memory profile still loses tasks and events on restart. The `jdbc` profile uses MySQL/Flyway, HikariCP fail-closed startup, and conservative recovery; production deployment should use that profile and verify the deployment runbook. Commerce writes are committed to MySQL and handed to the transactional outbox; the gateway adapter is sandbox-only until real payment/logistics providers are integrated.
