-- MySQL schema for the durable repository profile. No real customer or
-- production data belongs in the local demo database.
create table if not exists tasks (
    id varchar(64) primary key,
    user_id varchar(128) not null,
    role varchar(32) not null,
    request text not null,
    status varchar(32) not null,
    idempotency_key varchar(256),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    result_message text,
    side_effect_count integer not null default 0,
    constraint uq_tasks_idempotency unique (user_id, idempotency_key)
) engine = InnoDB default charset = utf8mb4;

create table if not exists task_steps (
    id varchar(64) primary key,
    task_id varchar(64) not null,
    sequence_no integer not null,
    tool_name varchar(128) not null,
    arguments_json json not null,
    status varchar(32) not null,
    result_code varchar(64),
    result_message text,
    started_at datetime(6) not null,
    finished_at datetime(6),
    constraint fk_task_steps_task foreign key (task_id) references tasks(id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists audit_events (
    id varchar(64) primary key,
    task_id varchar(64) not null,
    trace_id varchar(64) not null,
    event_type varchar(64) not null,
    actor varchar(128),
    tool_name varchar(128),
    status varchar(32),
    message text,
    metadata_json json not null,
    occurred_at datetime(6) not null,
    constraint fk_audit_events_task foreign key (task_id) references tasks(id)
) engine = InnoDB default charset = utf8mb4;

create index idx_audit_events_task_time on audit_events(task_id, occurred_at);
