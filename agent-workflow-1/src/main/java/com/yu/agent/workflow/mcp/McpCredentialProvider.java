package com.yu.agent.workflow.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTParser;
import com.yu.agent.workflow.tools.ToolContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class McpCredentialProvider {
    private final McpSettings settings;
    private final McpHttp http;
    private final ObjectMapper mapper;
    McpCredentialProvider(McpSettings settings, McpHttp http, ObjectMapper mapper) {
        this.settings=settings; this.http=http; this.mapper=mapper;
    }
    String token(ToolContext context, long deadline) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || !context.userId().equals(jwt.getToken().getSubject())
                || !context.tenantId().equals(jwt.getToken().getClaimAsString("tenant_id"))
                || jwt.getToken().getExpiresAt() == null || !jwt.getToken().getExpiresAt().isAfter(Instant.now()))
            throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
        String incoming = jwt.getToken().getTokenValue();
        if (incoming.length() > 8192) throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
        if (jwt.getToken().getAudience()!=null && jwt.getToken().getAudience().contains(settings.endpoint.toString())) return incoming;
        if (settings.tokenEndpoint == null) throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
        String form = "grant_type="+encode("urn:ietf:params:oauth:grant-type:token-exchange")
                +"&subject_token_type="+encode("urn:ietf:params:oauth:token-type:access_token")
                +"&requested_token_type="+encode("urn:ietf:params:oauth:token-type:access_token")
                +"&subject_token="+encode(incoming)+"&resource="+encode(settings.endpoint.toString())+"&scope=shipment%3Aread";
        String basic = Base64.getEncoder().encodeToString((encode(settings.clientId)+":"+encode(settings.clientSecret)).getBytes(StandardCharsets.UTF_8));
        var response = http.post(settings.tokenEndpoint, Map.of("Content-Type","application/x-www-form-urlencoded",
                "Accept","application/json","Authorization","Basic "+basic), form, deadline);
        if (response.statusCode()!=200) throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
        try {
            var data=mapper.readTree(response.body());
            String token=data.path("access_token").asText("");
            if (token.isBlank() || token.length()>8192 || !"Bearer".equalsIgnoreCase(data.path("token_type").asText())
                    || !"urn:ietf:params:oauth:token-type:access_token".equals(data.path("issued_token_type").asText()))
                throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
            // Consistency check, NOT local signature validation. The MCP resource validates signature/issuer/audience.
            // Credentials only come from the configured HTTPS authorization server; never from remote discovery.
            var claims=JWTParser.parse(token).getJWTClaimsSet();
            if (!context.userId().equals(claims.getSubject()) || !context.tenantId().equals(claims.getStringClaim("tenant_id"))
                    || !claims.getAudience().contains(settings.endpoint.toString()) || claims.getExpirationTime()==null
                    || !claims.getExpirationTime().toInstant().isAfter(Instant.now()))
                throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
            return token;
        } catch (Exception ex) { throw McpHttp.failure("MCP_IDENTITY_REQUIRED"); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
