CREATE TABLE conversations (
    id varchar(64) PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    owner_id varchar(128) NOT NULL,
    revision bigint NOT NULL,
    expires_at bigint NOT NULL,
    state_json json NOT NULL,
    INDEX idx_conversation_owner (tenant_id,owner_id,expires_at)
) ENGINE=InnoDB;
-- Dependencies protect derived answers even when accessed through the original task API.
CREATE TABLE task_knowledge_dependencies (
    task_id varchar(64) NOT NULL,
    source_task_id varchar(64) NOT NULL,
    PRIMARY KEY(task_id,source_task_id),
    FOREIGN KEY(task_id) REFERENCES tasks(id),
    FOREIGN KEY(source_task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
