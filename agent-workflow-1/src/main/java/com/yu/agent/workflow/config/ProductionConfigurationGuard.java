package com.yu.agent.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup when the prod profile is active with demo or fail-open settings.
 * Production deployments must opt in explicitly with SPRING_PROFILES_ACTIVE=prod.
 */
@Component
@Profile("prod")
public class ProductionConfigurationGuard {
    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationGuard.class);

    public ProductionConfigurationGuard(
            @Value("${agent.workflow.persistence:memory}") String persistence,
            @Value("${agent.auth.mode:local}") String authMode,
            @Value("${agent.auth.oidc-issuer-uri:}") String oidcIssuerUri,
            @Value("${agent.auth.jwk-set-uri:}") String jwkSetUri,
            @Value("${agent.auth.issuer:}") String issuer,
            @Value("${agent.auth.audience:}") String audience,
            @Value("${agent.auth.roles-claim:}") String rolesClaim,
            @Value("${agent.auth.dev-token-enabled:false}") boolean devTokenEnabled,
            @Value("${agent.commerce.seed-demo-data:false}") boolean seedDemoData,
            @Value("${mysql.user:}") String mysqlUser,
            @Value("${mysql.password:}") String mysqlPassword) {
        List<String> violations = new ArrayList<>();
        if (!"jdbc".equalsIgnoreCase(persistence)) {
            violations.add("agent.workflow.persistence must be jdbc (was " + persistence + ")");
        }
        if (!"oidc".equalsIgnoreCase(authMode)) {
            violations.add("agent.auth.mode must be oidc (was " + authMode + ")");
        }
        if (devTokenEnabled) {
            violations.add("agent.auth.dev-token-enabled must be false");
        }
        if (seedDemoData) {
            violations.add("agent.commerce.seed-demo-data must be false");
        }
        if (isBlank(oidcIssuerUri) && isBlank(jwkSetUri)) {
            violations.add("agent.auth.oidc-issuer-uri or agent.auth.jwk-set-uri is required");
        }
        if (!isBlank(jwkSetUri) && isBlank(issuer)) {
            violations.add("agent.auth.issuer is required when only a JWK set URI is configured");
        }
        if (isBlank(audience)) {
            violations.add("agent.auth.audience is required in production");
        }
        if (isBlank(rolesClaim)) {
            violations.add("agent.auth.roles-claim must not be blank");
        }
        if (isBlank(mysqlUser)) {
            violations.add("mysql.user must not be blank");
        }
        if (isBlank(mysqlPassword) || "workflow_dev_only".equals(mysqlPassword)) {
            violations.add("mysql.password must be set to a non-default secret");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", violations));
        }
        log.info("Production configuration guard passed (jdbc persistence, OIDC authentication, demo data disabled)");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
