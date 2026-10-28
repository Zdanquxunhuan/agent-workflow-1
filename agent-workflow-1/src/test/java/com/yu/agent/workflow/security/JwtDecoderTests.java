package com.yu.agent.workflow.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderTests {
    private static final String SECRET = "unit-test-secret-0123456789-0123456789";
    private static final String ISSUER = "agent-workflow-local";
    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void acceptsTokenSignedWithConfiguredSecretAndIssuer() {
        JwtDecoder decoder = decoder();

        var jwt = decoder.decode(token(encoder(SECRET), ISSUER, Instant.now().plusSeconds(300)));

        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER_SERVICE");
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtDecoder decoder = decoder();

        assertThatThrownBy(() -> decoder.decode(
                token(encoder("another-secret-0123456789-0123456789"), ISSUER, Instant.now().plusSeconds(300))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtDecoder decoder = decoder();

        assertThatThrownBy(() -> decoder.decode(
                token(encoder(SECRET), ISSUER, Instant.now().minusSeconds(300))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnexpectedIssuer() {
        JwtDecoder decoder = decoder();

        assertThatThrownBy(() -> decoder.decode(
                token(encoder(SECRET), "https://evil.example", Instant.now().plusSeconds(300))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void oidcModeWithoutDiscoveryOrJwkSetFailsClosed() {
        assertThatThrownBy(() -> configuration.jwtDecoder("oidc", "", "", "", SECRET, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oidc-issuer-uri");
    }

    @Test
    void localModeRejectsShortSecret() {
        assertThatThrownBy(() -> configuration.jwtDecoder("local", "", "", "", "too-short", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private JwtDecoder decoder() {
        return configuration.jwtDecoder("local", ISSUER, "", "", SECRET, "");
    }

    private JwtEncoder encoder(String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    }

    private String token(JwtEncoder encoder, String issuer, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("alice")
                .issuedAt(expiresAt.minusSeconds(600))
                .expiresAt(expiresAt)
                .claim("roles", List.of("CUSTOMER_SERVICE"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
