-- Operator template, NOT a migration. Replace workflow_agent and mcp_reader deployment identities.
-- Create mcp_reader with a secret manager supplied password and REQUIRE SSL before applying grants.
-- Use a restricted network host binding instead of '%' in the actual deployment.
GRANT SELECT (id,order_id,carrier,status,shipped_at,delivered_at) ON workflow_agent.shipments TO 'mcp_reader'@'%';
GRANT SELECT (id,shipment_id,status,occurred_at) ON workflow_agent.shipment_events TO 'mcp_reader'@'%';
GRANT SELECT ON workflow_agent.mcp_order_grants TO 'mcp_reader'@'%';
GRANT SELECT ON workflow_agent.mcp_ledger_binding TO 'mcp_reader'@'%';
GRANT SELECT,INSERT,UPDATE ON workflow_agent.mcp_admissions TO 'mcp_reader'@'%';
-- No orders/customer/payment access, no business DML, no grant mutation and no DDL permissions.
-- Java's runtime role needs SELECT/INSERT/UPDATE on mcp_order_grants, SELECT/INSERT on
-- mcp_grant_events/mcp_task_sources, SELECT/INSERT/UPDATE on mcp_tool_calls, SELECT on
-- mcp_ledger_binding, in addition to its pre-existing workflow permissions.
-- Migration account owns DDL/TRIGGER. Never give runtime roles DROP/ALTER/TRIGGER or blanket database ALL.
