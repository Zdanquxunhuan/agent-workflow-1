# ADR 002 Workflow framework selection

## Decision

Keep the explicit Java state machine as the production path. Do not rewrite it around LangGraph4j yet.

## Evidence

The current state machine has a small, inspectable set of nodes: planning, approval, execution, verification, and terminal states. Its transitions and security invariants are directly covered by tests. A framework PoC would be useful only after durable checkpoint and recovery semantics are complete.

## Consequence

The project remains easy to debug and deploy now. A future LangGraph4j PoC must implement one isolated approval flow and compare checkpointing, observability, testability, and operational complexity before any migration decision.
