# Architecture

```mermaid
flowchart LR
    Client[HTTP client] --> API[TaskController]
    API --> App[AgentApplicationService]
    App --> Planner[TaskPlanner]
    App --> Registry[ToolRegistry whitelist]
    Registry --> Read[Simulated read tools]
    Registry --> Write[Simulated write tools]
    App --> Tasks[TaskRepository]
    App --> Events[AuditEventRepository]
    Tasks --> Memory[(In-memory MVP)]
    Events --> Memory
    DB[(PostgreSQL + Flyway)] -. optional jdbc profile .-> Tasks
```

The service owns the state transition and execution loop. The deterministic planner is the offline default; the optional DeepSeek adapter implements `TaskPlanner` and receives no direct access to tool implementations. `ToolRegistry` is the execution boundary for role checks and tool-name allowlisting. Set `AGENT_PERSISTENCE=jdbc` to use the PostgreSQL repository; memory remains the default for local tests.

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
