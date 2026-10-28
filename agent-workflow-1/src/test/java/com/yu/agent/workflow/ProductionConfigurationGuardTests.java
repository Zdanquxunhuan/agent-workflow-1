package com.yu.agent.workflow;

import com.yu.agent.workflow.config.ProductionConfigurationGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTests {
    @Test
    void acceptsJdbcOidcConfigurationWithoutDemoSettings() {
        assertThatCode(() -> guard("jdbc", "oidc", "https://idp.example/realms/app", "",
                "https://idp.example/realms/app", "workflow-agent", "roles", false, false,
                "workflow", "strong-secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMemoryPersistenceAndLocalAuthentication() {
        assertThatThrownBy(() -> guard("memory", "local", "", "", "", "", "roles", true, true,
                "workflow", "workflow_dev_only"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.workflow.persistence must be jdbc")
                .hasMessageContaining("agent.auth.mode must be oidc")
                .hasMessageContaining("agent.auth.dev-token-enabled must be false")
                .hasMessageContaining("agent.commerce.seed-demo-data must be false")
                .hasMessageContaining("mysql.password must be set to a non-default secret");
    }

    @Test
    void rejectsJwkSetWithoutIssuerOrAudience() {
        assertThatThrownBy(() -> guard("jdbc", "oidc", "", "https://idp.example/jwks",
                "", "", "roles", false, false, "workflow", "strong-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.auth.issuer is required")
                .hasMessageContaining("agent.auth.audience is required");
    }

    private static ProductionConfigurationGuard guard(String persistence, String authMode, String oidcIssuerUri,
                                                      String jwkSetUri, String issuer, String audience,
                                                      String rolesClaim, boolean devTokenEnabled, boolean seedDemoData,
                                                      String mysqlUser, String mysqlPassword) {
        return new ProductionConfigurationGuard(persistence, authMode, oidcIssuerUri, jwkSetUri, issuer, audience,
                rolesClaim, devTokenEnabled, seedDemoData, mysqlUser, mysqlPassword);
    }
}
