package com.yu.agent.workflow.mcp;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** A deliberately fixed service binding, never an address supplied by a model or MCP response. */
@Component
@ConditionalOnProperty(name="agent.mcp.shipment.enabled", havingValue="true")
public class McpSettings {
    public static final String PROTOCOL = "2025-11-25";
    public final URI endpoint, tokenEndpoint;
    public final String clientId, clientSecret, tenant;
    public McpSettings(Environment env) {
        if (!"jdbc".equals(env.getProperty("agent.workflow.persistence"))
                || "http".equals(env.getProperty("agent.commerce.gateway", "sandbox")))
            throw new IllegalStateException("MCP requires JDBC and the agent's own commerce ledger");
        boolean local = env.getProperty("agent.mcp.shipment.allow-local-http", Boolean.class, false);
        if (local && env.matchesProfiles("prod", "production"))
            throw new IllegalStateException("Local MCP transport is forbidden in production");
        if (env.matchesProfiles("prod", "production")
                && !env.getProperty("mysql.url", "").toLowerCase(java.util.Locale.ROOT).matches(".*[?&]sslmode=verify_identity(&.*)?$"))
            throw new IllegalStateException("Production MCP provenance requires MySQL sslMode=VERIFY_IDENTITY");
        endpoint = address(env.getRequiredProperty("agent.mcp.shipment.url"), local);
        if (!"/mcp".equals(endpoint.getPath())) throw new IllegalStateException("MCP endpoint must end in /mcp");
        tenant = env.getProperty("agent.commerce.tenant", "legacy");
        String tokenUrl = env.getProperty("agent.mcp.shipment.token-url", "");
        tokenEndpoint = tokenUrl.isBlank() ? null : address(tokenUrl, false);
        clientId = env.getProperty("agent.mcp.shipment.client-id", "");
        clientSecret = env.getProperty("agent.mcp.shipment.client-secret", "");
        if (tokenEndpoint != null && (clientId.isBlank() || clientSecret.isBlank()))
            throw new IllegalStateException("MCP token exchange requires client credentials");
    }
    private static URI address(String text, boolean local) {
        URI uri = URI.create(text);
        boolean loopback = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && loopback && "http".equals(uri.getScheme())))
            throw new IllegalStateException("MCP addresses require fixed HTTPS URLs (HTTP only on explicit local loopback)");
        return uri;
    }
}
