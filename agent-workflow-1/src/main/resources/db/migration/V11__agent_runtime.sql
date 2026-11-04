CREATE TABLE agent_runtime_wallets (
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 owner_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 budget_day date NOT NULL, currency char(3) NOT NULL,
 limit_micros bigint NOT NULL, accounted_micros bigint NOT NULL DEFAULT 0, reserved_micros bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,owner_id,budget_day,currency)
) ENGINE=InnoDB;

CREATE TABLE agent_runs (
 id varchar(36) PRIMARY KEY,
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 owner_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 entrypoint varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_key varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, request_hash char(64) NOT NULL,
 conversation_id varchar(36), task_id varchar(64), instance_id varchar(36) NOT NULL,
 status varchar(24) NOT NULL, cancel_requested boolean NOT NULL DEFAULT false, cleanup_done boolean NOT NULL DEFAULT false, error_code varchar(64),
 created_at bigint NOT NULL, started_at bigint, ended_at bigint, deadline_at bigint NOT NULL,
 budget_day date NOT NULL, currency char(3) NOT NULL,
 max_cost_micros bigint NOT NULL, max_tokens bigint NOT NULL, max_attempts int NOT NULL, max_tools int NOT NULL,
 attempt_count int NOT NULL DEFAULT 0, tool_count int NOT NULL DEFAULT 0, fallback_used boolean NOT NULL DEFAULT false,
 accounted_tokens bigint NOT NULL DEFAULT 0, reserved_tokens bigint NOT NULL DEFAULT 0,
 accounted_micros bigint NOT NULL DEFAULT 0, reserved_micros bigint NOT NULL DEFAULT 0,
 UNIQUE KEY uq_agent_run_key(tenant_id,owner_id,entrypoint,request_key),
 INDEX idx_agent_run_deadline(deadline_at,status), INDEX idx_agent_run_task(task_id),
 INDEX idx_agent_run_owner_state(tenant_id,owner_id,status), INDEX idx_agent_run_owner_time(tenant_id,owner_id,created_at),
 INDEX idx_agent_run_cleanup(cleanup_done,status,created_at)
) ENGINE=InnoDB;

CREATE TABLE agent_model_attempts (
 id varchar(36) PRIMARY KEY, run_id varchar(36) NOT NULL,
 model_alias varchar(16) NOT NULL, model_name varchar(128) NOT NULL, route_reason varchar(64) NOT NULL,
 price_version varchar(64) NOT NULL, input_per_million decimal(18,8) NOT NULL, output_per_million decimal(18,8) NOT NULL,
 currency char(3) NOT NULL, status varchar(24) NOT NULL, code varchar(64), usage_source varchar(24) NOT NULL,
 reserved_input bigint NOT NULL, reserved_output bigint NOT NULL, reserved_micros bigint NOT NULL,
 input_tokens bigint, output_tokens bigint, accounted_micros bigint,
 started_at bigint NOT NULL, ended_at bigint,
 FOREIGN KEY(run_id) REFERENCES agent_runs(id), INDEX idx_model_attempt_run(run_id,started_at), INDEX idx_model_attempt_state(status,started_at)
) ENGINE=InnoDB;

CREATE TABLE agent_run_events (
 id bigint AUTO_INCREMENT PRIMARY KEY, run_id varchar(36) NOT NULL,
 event_type varchar(32) NOT NULL, payload_json varchar(2048) NOT NULL, created_at bigint NOT NULL,
 FOREIGN KEY(run_id) REFERENCES agent_runs(id), INDEX idx_run_events(run_id,id)
) ENGINE=InnoDB;
