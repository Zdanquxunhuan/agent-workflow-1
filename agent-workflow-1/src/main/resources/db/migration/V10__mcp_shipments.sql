-- Business tables are single-tenant. The MCP deployment and agent must bind to the same ledger/tenant.
CREATE TABLE mcp_ledger_binding (
 id tinyint PRIMARY KEY, ledger_id char(36) NOT NULL UNIQUE, CHECK(id=1)
) ENGINE=InnoDB;
INSERT INTO mcp_ledger_binding VALUES (1,UUID());

CREATE TABLE mcp_order_grants (
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 subject_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 order_id varchar(64) NOT NULL, enabled boolean NOT NULL DEFAULT false,
 expires_at bigint NOT NULL, revision bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,subject_id,order_id), CHECK(revision>0),
 FOREIGN KEY(order_id) REFERENCES orders(id)
) ENGINE=InnoDB;
-- Re-granting never resurrects an old source revision. Grants are tombstones, not deletable rows.
DELIMITER //
CREATE TRIGGER mcp_grant_revision BEFORE UPDATE ON mcp_order_grants
FOR EACH ROW
BEGIN
 IF BINARY NEW.tenant_id<>BINARY OLD.tenant_id OR BINARY NEW.subject_id<>BINARY OLD.subject_id OR BINARY NEW.order_id<>BINARY OLD.order_id THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MCP grant identity is immutable';
 END IF;
 SET NEW.revision=OLD.revision+1;
END//
DELIMITER ;
CREATE TRIGGER mcp_grant_no_delete BEFORE DELETE ON mcp_order_grants
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Revoke MCP grants instead of deleting them';

CREATE TABLE mcp_grant_events (
 id varchar(36) PRIMARY KEY, tenant_id varchar(80) NOT NULL, actor_id varchar(200) NOT NULL,
 subject_id varchar(200) NOT NULL, order_id varchar(64) NOT NULL,
 enabled boolean NOT NULL, expires_at bigint NOT NULL, revision bigint NOT NULL,
 reason varchar(256) NOT NULL, created_at bigint NOT NULL,
 INDEX idx_mcp_grant_events(tenant_id,subject_id,order_id,created_at),
 INDEX idx_mcp_grant_retention(created_at)
) ENGINE=InnoDB;
CREATE TABLE mcp_task_sources (
 task_id varchar(64) NOT NULL, order_id varchar(64) NOT NULL,
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 subject_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 grant_revision bigint NOT NULL,
 PRIMARY KEY(task_id,order_id,subject_id,grant_revision), FOREIGN KEY(task_id) REFERENCES tasks(id)
) ENGINE=InnoDB;
CREATE TABLE mcp_tool_calls (
 id varchar(36) PRIMARY KEY, task_id varchar(64) NOT NULL,
 tenant_id varchar(80) NOT NULL, subject_id varchar(200) NOT NULL,
 code varchar(40) NOT NULL, elapsed_ms bigint NOT NULL DEFAULT 0, created_at bigint NOT NULL,
 INDEX idx_mcp_calls_task(task_id,created_at),
 INDEX idx_mcp_calls_status(code,created_at), INDEX idx_mcp_calls_retention(created_at)
) ENGINE=InnoDB;
CREATE TABLE mcp_admissions (
 tenant_id varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 subject_id varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 window_start bigint NOT NULL, requests int NOT NULL,
 PRIMARY KEY(tenant_id,subject_id), INDEX idx_mcp_admissions_window(window_start)
) ENGINE=InnoDB;
