package com.yu.agent.workflow.security;

import com.yu.agent.workflow.api.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Request identity only. Durable execution must carry the task's stored tenant. */
public final class TenantIdentity {
    private TenantIdentity() {}
    public static String current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            String tenant = jwt.getToken().getClaimAsString("tenant_id");
            if (tenant != null) return validate(tenant);
        }
        return "legacy"; // Explicit namespace for old single-tenant development data.
    }
    public static String validate(String tenant) {
        if (tenant == null || !tenant.matches("[a-zA-Z0-9_-]{1,80}"))
            throw BusinessException.of("FORBIDDEN", "A valid tenant identity is required");
        return tenant;
    }
    public static void require(String tenant) {
        if (!current().equals(tenant)) throw BusinessException.of("TASK_NOT_FOUND", "Task does not exist");
    }
    public static void requireBusinessTenant(String tenant, String supported) {
        if (!tenant.equals(supported)) throw BusinessException.of("FORBIDDEN", "Business adapter is not enabled for this tenant");
    }
}
