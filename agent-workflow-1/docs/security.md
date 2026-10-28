# Security controls

The service deliberately uses demo records. Write tools persist to the local MySQL business schema behind approval and a transactional outbox; payment and logistics providers are still sandbox adapters. The following controls are enforced in Java code and are independent of prompts:

| Control | Enforcement |
| --- | --- |
| Authentication | Spring Security OAuth2 Resource Server validates a signed JWT on every business endpoint; `sub` is the only user identity |
| Identity claims | Roles come from a configurable signed claim (default `roles`, dot paths supported); unknown roles are discarded and a token with no known role is rejected |
| Role authorization | The filter chain requires `SUPERVISOR`/`ADMIN` for approve/reject/resume; `ToolRegistry` re-checks the role before execution |
| Separation of duties | The task requester cannot approve their own task, even with a supervisor/admin role |
| Tool allowlist | `ToolRegistry` resolves fixed names and rejects unknown names |
| Parameter boundary | Each tool rejects missing, oversized, unknown, and out-of-range fields |
| Approval gate | Write plans enter `WAITING_APPROVAL`; no tool call occurs before approval |
| Idempotency | `Idempotency-Key` maps a user request to one task; terminal approvals are safe to repeat |
| Audit | State changes, approval decisions, tool results, and rejections produce task events |
| Business idempotency | Refunds, shipments, address changes, and integration commands carry unique server-derived idempotency keys |
| Data safety | Demo data is seeded only when `COMMERCE_SEED_DEMO_DATA=true`; write results include `verified=true` |

Authentication settings:

- `AGENT_AUTH_MODE=local` verifies HS256 tokens and is intended for offline demos and tests. The local secret must be at least 32 bytes.
- `AGENT_AUTH_MODE=oidc` verifies tokens from an OIDC issuer (`AGENT_AUTH_OIDC_ISSUER_URI`) or a JWK Set (`AGENT_AUTH_JWK_SET_URI`). Missing configuration fails startup. Configure `AGENT_AUTH_ISSUER` and `AGENT_AUTH_AUDIENCE` to pin issuer and audience.
- `AGENT_AUTH_DEV_TOKEN_ENABLED=true` exposes `POST /api/v1/auth/dev-token` and only takes effect while mode is `local`. It must stay `false` outside local demos.
- Client-supplied `X-User-Id`/`X-Role` headers are ignored. A production gateway must forward the `Authorization` header, not user-injected identity headers.
