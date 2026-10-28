package com.yu.agent.workflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.yu.agent.workflow.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               JwtUserResolver userResolver,
                                               ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health", "/api/v1/health/**",
                                "/actuator/health", "/actuator/info", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/dev-token").permitAll()
                        .requestMatchers("/api/v1/integrations/reviews", "/api/v1/integrations/reviews/**").hasAnyRole("SUPERVISOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/tasks/*/approve",
                                "/api/v1/tasks/*/reject",
                                "/api/v1/tasks/*/resume").hasAnyRole("SUPERVISOR", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(userResolver)))
                        .authenticationEntryPoint((request, response, ex) -> writeError(objectMapper, request,
                                response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                                "Authentication required"))
                        .accessDeniedHandler((request, response, ex) -> writeError(objectMapper, request,
                                response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied")));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(JwtUserResolver userResolver) {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopes.convert(jwt));
            userResolver.roles(jwt).forEach(role ->
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            return authorities;
        });
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${agent.auth.mode:local}") String mode,
                          @Value("${agent.auth.issuer:}") String issuer,
                          @Value("${agent.auth.oidc-issuer-uri:}") String oidcIssuerUri,
                          @Value("${agent.auth.jwk-set-uri:}") String jwkSetUri,
                          @Value("${agent.auth.local-secret:}") String localSecret,
                          @Value("${agent.auth.audience:}") String audience) {
        if ("oidc".equalsIgnoreCase(mode)) {
            if (hasText(oidcIssuerUri)) {
                log.info("JWT validation uses OIDC issuer {}", oidcIssuerUri);
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(oidcIssuerUri).build();
                // Re-apply validators so a configured audience is enforced after discovery.
                decoder.setJwtValidator(validator(hasText(issuer) ? issuer : oidcIssuerUri, audience));
                return decoder;
            }
            if (hasText(jwkSetUri)) {
                log.info("JWT validation uses JWK set {} issuer={}", jwkSetUri, issuer);
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
                decoder.setJwtValidator(validator(issuer, audience));
                return decoder;
            }
            throw new IllegalStateException(
                    "agent.auth.mode=oidc requires agent.auth.oidc-issuer-uri or agent.auth.jwk-set-uri");
        }
        if (!"local".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Unsupported agent.auth.mode: " + mode);
        }
        byte[] secret = localSecret == null ? new byte[0] : localSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("agent.auth.local-secret must be at least 32 bytes for HS256");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(validator(hasText(issuer) ? issuer : "agent-workflow-local", audience));
        return decoder;
    }

    @Bean
    @ConditionalOnExpression("'${agent.auth.mode:local}' == 'local' and '${agent.auth.dev-token-enabled:false}' == 'true'")
    JwtEncoder devJwtEncoder(@Value("${agent.auth.local-secret:}") String localSecret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(localSecret.getBytes(StandardCharsets.UTF_8)));
    }

    @Bean
    ApplicationRunner authenticationModeWarning(@Value("${agent.auth.mode:local}") String mode,
                                                @Value("${agent.auth.dev-token-enabled:false}") boolean devTokenEnabled) {
        return args -> {
            if (!"oidc".equalsIgnoreCase(mode)) {
                log.warn("agent.auth.mode={} uses a shared HS256 secret; use OIDC/JWK validation in production", mode);
            }
            if (devTokenEnabled) {
                log.warn("Local dev token endpoint is ENABLED at POST /api/v1/auth/dev-token; never enable it in production");
            }
        };
    }

    private OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(hasText(issuer) ? JwtValidators.createDefaultWithIssuer(issuer) : JwtValidators.createDefault());
        if (hasText(audience)) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(audience)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void writeError(ObjectMapper objectMapper, HttpServletRequest request,
                                   HttpServletResponse response, int status, String code,
                                   String message) throws IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message, traceId));
    }
}
