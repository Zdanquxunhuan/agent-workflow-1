package com.yu.agent.workflow;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.tools.AgentTool;
import com.yu.agent.workflow.tools.ToolContext;
import com.yu.agent.workflow.tools.ToolRegistry;
import com.yu.agent.workflow.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTests {
    @Test
    void unknownToolIsRejectedBeforeExecution() {
        AgentTool spy = new AgentTool() {
            public String name() { return "known"; }
            public boolean readOnly() { return true; }
            public Set<UserRole> allowedRoles() { return Set.of(UserRole.CUSTOMER_SERVICE); }
            public ToolResult execute(Map<String, Object> args, ToolContext context) { throw new AssertionError("must not execute"); }
        };
        ToolRegistry registry = new ToolRegistry(Set.of(spy));
        assertThatThrownBy(() -> registry.execute("not-registered", Map.of(), new ToolContext("u", UserRole.CUSTOMER_SERVICE, "t", "x")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not registered");
    }

    @Test
    void roleIsCheckedAtTheExecutionBoundary() {
        AgentTool restricted = new AgentTool() {
            public String name() { return "restricted"; }
            public boolean readOnly() { return false; }
            public Set<UserRole> allowedRoles() { return Set.of(UserRole.SUPERVISOR); }
            public ToolResult execute(Map<String, Object> args, ToolContext context) { return ToolResult.ok("bad", Map.of()); }
        };
        ToolRegistry registry = new ToolRegistry(Set.of(restricted));
        assertThatThrownBy(() -> registry.execute("restricted", Map.of(), new ToolContext("u", UserRole.CUSTOMER_SERVICE, "t", "x")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not allowed");
    }
}
