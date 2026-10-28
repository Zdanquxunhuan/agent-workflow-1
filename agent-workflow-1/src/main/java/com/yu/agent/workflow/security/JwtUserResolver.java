package com.yu.agent.workflow.security;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves the effective identity and role from verified JWT claims. */
@Component
public class JwtUserResolver {
    private static final List<UserRole> PRECEDENCE = List.of(
            UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.CUSTOMER_SERVICE, UserRole.AGENT);
    private static final Set<String> KNOWN_ROLES = Arrays.stream(UserRole.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final String rolesClaim;

    public JwtUserResolver(@Value("${agent.auth.roles-claim:roles}") String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public AuthenticatedUser resolve(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw BusinessException.of("UNAUTHORIZED", "Token has no subject");
        }
        Set<String> roles = roles(jwt);
        UserRole effective = PRECEDENCE.stream()
                .filter(role -> roles.contains(role.name()))
                .findFirst()
                .orElseThrow(() -> BusinessException.of("FORBIDDEN",
                        "Token has no role recognized by this service"));
        return new AuthenticatedUser(subject, effective, Set.copyOf(roles));
    }

    public Set<String> roles(Jwt jwt) {
        return rolesFrom(claimValue(jwt, rolesClaim));
    }

    private Object claimValue(Jwt jwt, String path) {
        if (!path.contains(".")) return jwt.getClaim(path);
        Object value = jwt.getClaim(path.substring(0, path.indexOf('.')));
        for (String segment : path.substring(path.indexOf('.') + 1).split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(segment);
        }
        return value;
    }

    private Set<String> rolesFrom(Object claim) {
        Set<String> roles = new LinkedHashSet<>();
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> addRole(roles, value));
        } else if (claim != null) {
            addRole(roles, claim);
        }
        return roles;
    }

    private void addRole(Set<String> roles, Object value) {
        if (value == null) return;
        for (String candidate : value.toString().split("[\\s,]+")) {
            String normalized = candidate.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (normalized.startsWith("ROLE_")) normalized = normalized.substring("ROLE_".length());
            if (KNOWN_ROLES.contains(normalized)) roles.add(normalized);
        }
    }
}
