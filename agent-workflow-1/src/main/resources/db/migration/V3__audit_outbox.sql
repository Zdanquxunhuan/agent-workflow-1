create table if not exists audit_outbox (
    id varchar(64) primary key,
    event_id varchar(64) not null unique,
    task_id varchar(64) not null,
    event_type varchar(64) not null,
    payload_json json not null,
    created_at datetime(6) not null,
    published_at datetime(6),
    constraint fk_audit_outbox_task foreign key (task_id) references tasks(id)
) engine = InnoDB default charset = utf8mb4;

create index idx_audit_outbox_pending on audit_outbox(published_at, created_at);
