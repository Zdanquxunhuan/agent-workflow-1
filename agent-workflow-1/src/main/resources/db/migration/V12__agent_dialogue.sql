-- V10: one durable run retains all V9 counters across clarification pauses.
ALTER TABLE agent_runs
 ADD COLUMN dialogue_enabled boolean NOT NULL DEFAULT false,
 ADD COLUMN dialogue_revision int NOT NULL DEFAULT 0,
 ADD COLUMN plan_version int NOT NULL DEFAULT 1,
 ADD COLUMN replan_count int NOT NULL DEFAULT 0,
 ADD COLUMN knowledge_required boolean NOT NULL DEFAULT false,
 ADD COLUMN max_replans int NOT NULL DEFAULT 0,
 ADD COLUMN max_clarifications int NOT NULL DEFAULT 0,
 ADD COLUMN clarification_field varchar(32),
 ADD COLUMN chain_expires_at bigint,
 ADD COLUMN execution_remaining_ms bigint,
 ADD COLUMN segment_opened_at bigint,
 ADD COLUMN wait_seconds int;

CREATE TABLE agent_dialogue_inputs (
 run_id varchar(36) NOT NULL,
 revision int NOT NULL,
 request_key varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 field_name varchar(32) NOT NULL,
 input_text varchar(2000) NOT NULL,
 created_at bigint NOT NULL,
 PRIMARY KEY(run_id,revision),
 UNIQUE KEY uq_dialogue_input_key(run_id,request_key),
 FOREIGN KEY(run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB;
