-- All pre-V5 records belong to the explicit legacy namespace. This migration
-- does not infer tenants from user ids. Reassign only after an audited mapping.
ALTER TABLE tasks ADD COLUMN tenant_id varchar(80) NOT NULL DEFAULT 'legacy',
                  ADD COLUMN entrypoint varchar(16) NOT NULL DEFAULT 'tasks';
UPDATE tasks t SET entrypoint='chat' WHERE EXISTS
    (SELECT 1 FROM audit_events e WHERE e.task_id=t.id AND e.event_type='CHAT_STARTED');
ALTER TABLE tasks DROP INDEX uq_tasks_idempotency,
    ADD CONSTRAINT uq_tasks_idempotency UNIQUE (tenant_id,user_id,entrypoint,idempotency_key);
CREATE INDEX idx_tasks_tenant_status ON tasks(tenant_id,status);
-- Steps/events/outbox remain owned by the immutable task_id FK. User-facing
-- access must authorize the parent task before reading any child record.
CREATE TABLE task_knowledge_grants (
    task_id varchar(64) PRIMARY KEY,
    grant_json json NOT NULL,
    CONSTRAINT fk_knowledge_grant_task FOREIGN KEY(task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
CREATE TABLE task_knowledge_sources (
    task_id varchar(64) NOT NULL,
    chunk_id varchar(36) NOT NULL,
    version_id varchar(36) NOT NULL,
    content_hash varchar(64) NOT NULL,
    content_revision bigint NOT NULL,
    PRIMARY KEY(task_id,chunk_id,content_revision),
    CONSTRAINT fk_knowledge_source_task FOREIGN KEY(task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
