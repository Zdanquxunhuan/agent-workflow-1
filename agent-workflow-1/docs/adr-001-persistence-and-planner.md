# ADR 001 Persistence and planner boundaries

## Decision

Keep the first vertical slice deterministic and in memory by default. Provide an opt-in DeepSeek planner behind the `TaskPlanner` interface, then add PostgreSQL persistence behind the repository interfaces.

## Reason

The document requires the execution controls to be server-owned and testable before an external model is connected. A deterministic planner makes whitelist, validation, approval, idempotency, and audit tests reproducible. When enabled, DeepSeek uses JSON Output to propose a plan; the server still validates every tool and argument. The Flyway schema in `src/main/resources/db/migration/V1__init.sql` is ready for the durable adapter.

## Consequence

The default memory profile still loses tasks and events on restart. The `jdbc` profile uses PostgreSQL/Flyway and conservative startup recovery; production deployment should use that profile and verify the deployment runbook.
