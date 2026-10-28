alter table audit_outbox
    add column attempts integer not null default 0,
    add column next_attempt_at datetime(6),
    add column locked_by varchar(128),
    add column locked_until datetime(6),
    add column last_error text;

create index idx_audit_outbox_claim on audit_outbox(published_at, next_attempt_at, locked_until, created_at);
