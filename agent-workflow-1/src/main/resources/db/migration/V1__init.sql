-- PostgreSQL schema for the durable repository profile. No real customer or
-- production data belongs in the local demo database.
create table if not exists tasks (
    id varchar(64) primary key,
    user_id varchar(128) not null,
    role varchar(32) not null,
    request text not null,
    status varchar(32) not null,
    idempotency_key varchar(256),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    result_message text,
    side_effect_count integer not null default 0,
    constraint uq_tasks_idempotency unique (user_id, idempotency_key)
);

create table if not exists task_steps (
    id varchar(64) primary key,
    task_id varchar(64) not null references tasks(id),
    sequence_no integer not null,
    tool_name varchar(128) not null,
    arguments_json jsonb not null,
    status varchar(32) not null,
    result_code varchar(64),
    result_message text,
    started_at timestamptz not null,
    finished_at timestamptz
);

create table if not exists audit_events (
    id varchar(64) primary key,
    task_id varchar(64) not null references tasks(id),
    trace_id varchar(64) not null,
    event_type varchar(64) not null,
    actor varchar(128),
    tool_name varchar(128),
    status varchar(32),
    message text,
    metadata_json jsonb not null,
    occurred_at timestamptz not null
);

create index if not exists idx_audit_events_task_time on audit_events(task_id, occurred_at);
