-- Agent-owned intents and evidence. The external business ledger uses a separate database.
alter table task_steps add column result_json json null;
alter table integration_commands add column task_id varchar(64) null;
create index idx_integration_commands_task on integration_commands(task_id);
update integration_commands c join refunds r on c.aggregate_id=r.id and c.command_type='REFUND'
    set c.task_id=r.task_id where c.task_id is null;

create table external_refund_requests (
    id varchar(64) primary key,
    order_id varchar(64) not null,
    amount decimal(12,2) not null,
    status varchar(32) not null,
    reason varchar(500) not null,
    idempotency_key varchar(160) not null unique,
    external_ref varchar(128),
    requested_by varchar(128) not null,
    task_id varchar(64) not null,
    requested_at datetime(6) not null,
    completed_at datetime(6),
    constraint fk_external_refund_task foreign key(task_id) references tasks(id),
    constraint chk_external_refund_amount check(amount>0)
) engine=InnoDB default charset=utf8mb4;
create index idx_external_refund_order on external_refund_requests(order_id,requested_at);

create table integration_reviews (
    id varchar(64) primary key,
    command_id varchar(64) not null unique,
    status varchar(32) not null,
    reason varchar(500) not null,
    resolution varchar(500),
    resolved_by varchar(128),
    created_at datetime(6) not null,
    resolved_at datetime(6),
    constraint fk_review_command foreign key(command_id) references integration_commands(id)
) engine=InnoDB default charset=utf8mb4;
create index idx_integration_reviews_status on integration_reviews(status,created_at);

create table integration_events (
    id varchar(64) primary key,
    command_id varchar(64) not null,
    actor varchar(128) not null,
    action varchar(64) not null,
    message varchar(500) not null,
    occurred_at datetime(6) not null,
    constraint fk_integration_event_command foreign key(command_id) references integration_commands(id)
) engine=InnoDB default charset=utf8mb4;
create index idx_integration_events_command on integration_events(command_id,occurred_at);
