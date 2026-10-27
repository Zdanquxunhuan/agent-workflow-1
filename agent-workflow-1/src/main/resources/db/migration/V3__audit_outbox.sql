create table if not exists audit_outbox (
    id varchar(64) primary key,
    event_id varchar(64) not null unique,
    task_id varchar(64) not null references tasks(id),
    event_type varchar(64) not null,
    payload_json jsonb not null,
    created_at timestamptz not null,
    published_at timestamptz
);

create index if not exists idx_audit_outbox_pending on audit_outbox(published_at, created_at);
