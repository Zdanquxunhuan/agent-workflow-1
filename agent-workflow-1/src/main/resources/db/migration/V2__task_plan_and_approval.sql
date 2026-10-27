alter table tasks add column if not exists plan_json jsonb;
alter table tasks add column if not exists approval_json jsonb;
