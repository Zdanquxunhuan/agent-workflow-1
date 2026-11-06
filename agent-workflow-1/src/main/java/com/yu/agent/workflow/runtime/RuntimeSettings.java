package com.yu.agent.workflow.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.runtime.enabled", havingValue = "true")
public class RuntimeSettings {
    public record Model(String alias, String baseUrl, String apiKey, String name, Set<String> tenants,
                        BigDecimal inputPrice, BigDecimal outputPrice, String priceVersion, int maxOutput) {
        @Override
        public String toString() {
            return alias + ":" + name;
        }

        public long cost(long input, long output) {
            // Price is currency / million tokens; result is millionths of currency.
            return inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output)))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
        }
    }

    public final Model primary, backup;
    public final String currency;
    public final long requestMicros, dailyMicros, maxTokens;
    public final int seconds, attemptSeconds, maxAttempts, maxTools, maxActive;

    public RuntimeSettings(Environment env) {
        if (!"jdbc".equals(env.getProperty("agent.workflow.persistence")) || !env.getProperty("agent.deepseek.enabled", Boolean.class, false)
                || !"spring-ai".equals(env.getProperty("agent.deepseek.gateway")))
            throw new IllegalStateException("V9 requires JDBC, enabled model integration and spring-ai gateway");
        currency = env.getProperty("agent.runtime.currency", "USD");
        if (!currency.matches("[A-Z]{3}")) throw new IllegalStateException("Invalid budget currency");
        seconds = number(env, "deadline-seconds", 60, 5, 120);
        if (env.getProperty("agent.workflow.recovery.stale-seconds", Long.class, 120L) <= seconds + 5L)
            throw new IllegalStateException("Workflow stale recovery must exceed the runtime deadline plus 5 seconds");
        if (env.matchesProfiles("prod", "production") && (env.getProperty("agent.runtime.allow-local-http", Boolean.class, false)
                || !env.getProperty("mysql.url", "").toLowerCase(Locale.ROOT).matches(".*[?&]sslmode=verify_identity(&.*)?$")))
            throw new IllegalStateException("Production runtime requires verified MySQL TLS and forbids local HTTP mode");
        attemptSeconds = number(env, "attempt-seconds", 20, 1, 60);
        maxAttempts = number(env, "max-attempts", 6, 1, 8);
        maxTools = number(env, "max-tools", 8, 1, 8);
        maxActive = number(env, "max-active-per-user", 2, 1, 8);
        maxTokens = number(env, "max-tokens", 100000, 1000, 1000000);
        requestMicros = money(env, "request-budget");
        dailyMicros = money(env, "daily-budget");
        if (dailyMicros < requestMicros) throw new IllegalStateException("Daily budget must cover one request budget");
        primary = model(env, false);
        backup = env.getProperty("agent.runtime.backup.enabled", Boolean.class, false) ? model(env, true) : null;
    }

    private static int number(Environment e, String key, int fallback, int min, int max) {
        int value = e.getProperty("agent.runtime." + key, Integer.class, fallback);
        if (value < min || value > max) throw new IllegalStateException("Invalid runtime limit: " + key);
        return value;
    }

    private static long money(Environment e, String key) {
        long value = new BigDecimal(e.getRequiredProperty("agent.runtime." + key)).movePointRight(6).longValueExact();
        if (value <= 0 || value > 1_000_000_000_000L) throw new IllegalStateException("Invalid runtime budget: " + key);
        return value;
    }

    private static Model model(Environment e, boolean backup) {
        String prefix = backup ? "agent.runtime.backup." : "agent.deepseek.";
        String pricePrefix = backup ? "agent.runtime.backup." : "agent.runtime.primary.";
        String url = e.getRequiredProperty(prefix + "base-url").replaceAll("/+$", "");
        URI uri = URI.create(url);
        boolean local = e.getProperty("agent.runtime.allow-local-http", Boolean.class, false) && !e.matchesProfiles("prod", "production");
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
            throw new IllegalStateException("Model endpoint must use fixed HTTPS");
        String key = e.getRequiredProperty(prefix + "api-key"), name = e.getRequiredProperty(prefix + "model");
        if (key.isBlank() || "replace-me".equals(key) || name.isBlank() || name.length() > 128)
            throw new IllegalStateException("Model credentials/name required");
        Set<String> tenants = new HashSet<>();
        for (String t : e.getRequiredProperty(prefix + "allowed-tenants").split(",")) {
            if (!t.trim().matches("[A-Za-z0-9_-]{1,80}"))
                throw new IllegalStateException("Explicit model tenant allowlist required");
            tenants.add(t.trim());
        }
        BigDecimal input = new BigDecimal(e.getRequiredProperty(pricePrefix + "input-per-million"));
        BigDecimal output = new BigDecimal(e.getRequiredProperty(pricePrefix + "output-per-million"));
        String revision = e.getRequiredProperty(pricePrefix + "price-version");
        if (input.signum() < 0 || output.signum() < 0 || input.compareTo(new BigDecimal("10000")) > 0 || output.compareTo(new BigDecimal("10000")) > 0
                || input.scale() > 8 || output.scale() > 8 || !revision.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalStateException("Invalid versioned model pricing");
        int max = e.getProperty(backup ? prefix + "max-output-tokens" : "agent.deepseek.max-output-tokens", Integer.class, 1200);
        if (max < 1 || max > 16000) throw new IllegalStateException("Invalid maximum output tokens");
        return new Model(backup ? "backup" : "primary", url, key, name, Set.copyOf(tenants), input, output, revision, max);
    }
}
