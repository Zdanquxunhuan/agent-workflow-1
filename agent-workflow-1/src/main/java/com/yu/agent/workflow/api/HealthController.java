package com.yu.agent.workflow.api;

import com.yu.agent.workflow.commerce.CommerceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final CommerceRepository commerce;

    public HealthController(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "service", "enterprise-workflow-agent",
                "commerceBackend", commerce.backend()), "health");
    }

    /** Readiness proves the business database is reachable, not just that the JVM started. */
    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<Map<String, String>>> readiness() {
        try {
            if (!commerce.ready()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error("NOT_READY", "Commerce repository is not ready", "readiness"));
            }
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UP",
                    "commerceBackend", commerce.backend()), "readiness"));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("NOT_READY", "Commerce database is unavailable", "readiness"));
        }
    }
}
