alter table audit_outbox add column if not exists attempts integer not null default 0;
alter table audit_outbox add column if not exists next_attempt_at timestamptz;
alter table audit_outbox add column if not exists locked_by varchar(128);
alter table audit_outbox add column if not exists locked_until timestamptz;
alter table audit_outbox add column if not exists last_error text;

create index if not exists idx_audit_outbox_claim on audit_outbox(published_at, next_attempt_at, locked_until, created_at);
