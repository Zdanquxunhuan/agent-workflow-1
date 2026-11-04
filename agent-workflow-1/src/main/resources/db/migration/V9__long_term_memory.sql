CREATE TABLE memory_owners (
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, owner_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 auto_extract boolean NOT NULL DEFAULT false,
 PRIMARY KEY(tenant_id,owner_id)
) ENGINE=InnoDB;
CREATE TABLE long_term_memories (
 id varchar(36) PRIMARY KEY, tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, owner_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 kind varchar(16) NOT NULL, slot varchar(100) NOT NULL, content text NOT NULL,
 source_task_id varchar(64), revision bigint NOT NULL, state varchar(16) NOT NULL,
 expires_at bigint NOT NULL, created_at bigint NOT NULL, updated_at bigint NOT NULL,
 UNIQUE KEY uq_memory_slot(tenant_id,owner_id,kind,slot),
 INDEX idx_memory_recall(tenant_id,owner_id,state,expires_at),
 INDEX idx_memory_expiry(state,expires_at),
 CHECK(kind IN ('PREFERENCE','TASK_SUMMARY')),
 CHECK(state IN ('CANDIDATE','ACTIVE','DELETED','EXPIRED')),
 CHECK(revision>0),
 FOREIGN KEY(source_task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
CREATE TABLE memory_requests (
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, owner_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_key varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_hash char(64) NOT NULL, memory_id varchar(36) NOT NULL, expires_at bigint NOT NULL,
 PRIMARY KEY(tenant_id,owner_id,request_key), INDEX idx_memory_request_expiry(expires_at)
) ENGINE=InnoDB;
CREATE TABLE memory_events (
 id varchar(36) PRIMARY KEY, memory_id varchar(36) NOT NULL, revision bigint NOT NULL,
 event_type varchar(40) NOT NULL, occurred_at bigint NOT NULL,
 FOREIGN KEY(memory_id) REFERENCES long_term_memories(id),
 INDEX idx_memory_events(memory_id,occurred_at,revision)
) ENGINE=InnoDB;
-- Coalescing transactional outbox: one latest desired operation per object.
CREATE TABLE memory_jobs (
 id varchar(80) PRIMARY KEY, tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, owner_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 kind varchar(16) NOT NULL, target_id varchar(64) NOT NULL, revision bigint NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING', attempts int NOT NULL DEFAULT 0,
 lease_token varchar(36), lease_until bigint NOT NULL DEFAULT 0,
 available_at bigint NOT NULL, error_code varchar(40), created_at bigint NOT NULL,
 INDEX idx_memory_job_poll(state,available_at,lease_until),
 INDEX idx_memory_job_owner(tenant_id,owner_id,kind,created_at),
 CHECK(kind IN ('EXTRACT','INDEX')),
 CHECK(state IN ('PENDING','RUNNING','DONE','DEAD'))
) ENGINE=InnoDB;
CREATE TABLE task_memory_uses (
 task_id varchar(64) NOT NULL, memory_id varchar(36) NOT NULL, revision bigint NOT NULL,
 PRIMARY KEY(task_id,memory_id), FOREIGN KEY(task_id) REFERENCES tasks(id),
 FOREIGN KEY(memory_id) REFERENCES long_term_memories(id)
) ENGINE=InnoDB;
CREATE TABLE task_memory_parents (
 task_id varchar(64) NOT NULL, source_task_id varchar(64) NOT NULL,
 PRIMARY KEY(task_id,source_task_id), FOREIGN KEY(task_id) REFERENCES tasks(id),
 FOREIGN KEY(source_task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
