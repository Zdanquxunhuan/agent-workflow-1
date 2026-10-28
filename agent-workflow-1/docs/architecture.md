# Architecture

```mermaid
flowchart LR
    Client[HTTP client] --> API[TaskController]
    API --> App[AgentApplicationService]
    App --> Planner[TaskPlanner]
    App --> Registry[ToolRegistry whitelist]
    Registry --> Read[Commerce read tools]
    Registry --> Write[Commerce write tools]
    Read --> Commerce[CommerceRepository]
    Write --> Commerce
    App --> Tasks[TaskRepository]
    App --> Events[AuditEventRepository]
    Commerce --> Memory[(In-memory demo)]
    Tasks --> Memory
    Events --> Memory
    Commerce --> DB[(MySQL + Flyway)]
    Tasks --> DB
    Events --> DB
    DB --> Outbox[(integration_commands)]
    Outbox --> Dispatcher[CommerceCommandDispatcher]
    Dispatcher --> Gateway[SandboxCommerceGateway]
```

The service owns the state transition and execution loop. The deterministic planner is the offline default; the optional DeepSeek adapter implements `TaskPlanner` and receives no direct access to tool implementations. `ToolRegistry` is the execution boundary for role checks and tool-name allowlisting. Set `AGENT_PERSISTENCE=jdbc` to use MySQL for tasks, audit events, and commerce data; memory remains the default for local tests. Write tools commit business rows and `integration_commands` in one transaction, and the dispatcher settles them through a replaceable gateway adapter.

## State machine

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PLANNING
    PLANNING --> RUNNING: read-only plan
    PLANNING --> WAITING_APPROVAL: high-risk plan
    WAITING_APPROVAL --> RUNNING: supervisor approves
    WAITING_APPROVAL --> CANCELLED: supervisor rejects
    RUNNING --> SUCCEEDED
    RUNNING --> FAILED
    CREATED --> CANCELLED
```
