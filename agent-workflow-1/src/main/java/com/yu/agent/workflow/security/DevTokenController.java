package com.yu.agent.workflow.security;

import com.yu.agent.workflow.api.ApiResponse;
import com.yu.agent.workflow.domain.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Development-only issuer for locally signed tokens. It is only registered when
 * agent.auth.mode=local and agent.auth.dev-token-enabled=true.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnExpression("'${agent.auth.mode:local}' == 'local' and '${agent.auth.dev-token-enabled:false}' == 'true'")
public class DevTokenController {
    private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);

    private final JwtEncoder encoder;
    private final String issuer;
    private final String rolesClaim;
    private final String audience;
    private final long ttlSeconds;

    public DevTokenController(JwtEncoder encoder,
                              @Value("${agent.auth.issuer:}") String issuer,
                              @Value("${agent.auth.roles-claim:roles}") String rolesClaim,
                              @Value("${agent.auth.audience:}") String audience,
                              @Value("${agent.auth.dev-token-ttl-seconds:3600}") long ttlSeconds) {
        this.encoder = encoder;
        this.issuer = issuer == null || issuer.isBlank() ? "agent-workflow-local" : issuer;
        this.rolesClaim = rolesClaim;
        this.audience = audience;
        this.ttlSeconds = Math.max(60, Math.min(ttlSeconds, 3600));
    }

    @PostMapping("/dev-token")
    public ApiResponse<Map<String, Object>> issue(@Valid @RequestBody DevTokenRequest request) {
        UserRole role = UserRole.parse(request.role());
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(request.subject())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds));
        if (audience != null && !audience.isBlank()) {
            claims.audience(List.of(audience));
        }
        withRoles(claims, rolesClaim, List.of(role.name()));
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        log.warn("Issued local development token subject={} role={}", request.subject(), role);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("tokenType", "Bearer");
        data.put("subject", request.subject());
        data.put("role", role.name());
        data.put("expiresIn", ttlSeconds);
        data.put("issuer", issuer);
        return ApiResponse.ok(data, request.traceId() == null ? UUID.randomUUID().toString() : request.traceId());
    }

    private void withRoles(JwtClaimsSet.Builder claims, String path, List<String> roles) {
        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            claims.claim(path, roles);
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put(parts[i], child);
            cursor = child;
        }
        cursor.put(parts[parts.length - 1], roles);
        claims.claim(parts[0], root.get(parts[0]));
    }

    public record DevTokenRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String subject,
                                  @NotBlank String role,
                                  String traceId) {
    }
}
