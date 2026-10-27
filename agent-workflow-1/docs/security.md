# Security controls

The service deliberately uses simulated records and write tools. The following controls are enforced in Java code and are independent of prompts:

| Control | Enforcement |
| --- | --- |
| Tool allowlist | `ToolRegistry` resolves fixed names and rejects unknown names |
| Parameter boundary | Each tool rejects missing, oversized, unknown, and out-of-range fields |
| Role authorization | `ToolRegistry` checks the authenticated request role before execution |
| Approval gate | Write plans enter `WAITING_APPROVAL`; no tool call occurs before approval |
| Idempotency | `Idempotency-Key` maps a user request to one task; terminal approvals are safe to repeat |
| Audit | State changes, approval decisions, tool results, and rejections produce task events |
| Data safety | Demo data is hard-coded and write results include `simulation=true` |

Authentication is represented by `X-User-Id` and `X-Role` only for the local demo. A production adapter must replace these headers with a verified identity from the service's authentication layer.
