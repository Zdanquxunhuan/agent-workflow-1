package com.yu.business;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class BusinessController {
    private final BusinessLedger ledger;
    private final JdbcTemplate jdbc;
    private final long timeoutAfterAccept;
    private final ApplicationAvailability availability;

    public BusinessController(BusinessLedger ledger, JdbcTemplate jdbc,
                               @Value("${business.timeout-after-accept-ms:6000}") long timeoutAfterAccept,
                               ApplicationAvailability availability) {
        this.ledger = ledger; this.jdbc = jdbc; this.timeoutAfterAccept = timeoutAfterAccept;
        this.availability = availability;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Business initialization not complete");
        }
        jdbc.queryForObject("select 1", Integer.class);
        return Map.of("status", "UP", "synthetic", true);
    }

    @GetMapping("/business/v1/orders/{id}")
    public Map<String, Object> order(@PathVariable String id) { return ledger.order(id); }

    @GetMapping("/business/v1/orders/{id}/payment")
    public Map<String, Object> payment(@PathVariable String id) { return ledger.payment(id); }

    @PostMapping("/business/v1/refunds")
    public ResponseEntity<Map<String, Object>> refund(@Valid @RequestBody RefundRequest request) throws InterruptedException {
        // submit() commits before response delay: this exercises a real unknown-outcome window.
        Map<String, Object> result = ledger.submit(request.idempotencyKey(), request.orderId(), request.amount());
        if (Boolean.TRUE.equals(result.get("newlyCreated")) && "TIMEOUT_AFTER_ACCEPT".equals(result.get("behavior"))) {
            Thread.sleep(timeoutAfterAccept);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping("/business/v1/refunds/{key}")
    public Map<String, Object> refund(@PathVariable String key) { return ledger.refund(key); }

    @PostMapping("/business/v1/operations/refunds/{key}/settle")
    public Map<String, Object> settle(@PathVariable String key, @Valid @RequestBody SettlementRequest request) {
        return ledger.settle(key, request.status(), request.reason());
    }

    public record RefundRequest(@NotBlank @Size(max=160) String idempotencyKey,
                                @NotBlank @Pattern(regexp="ORD-[0-9]{4}") String orderId,
                                @NotNull @DecimalMin("0.01") @Digits(integer=6,fraction=2) BigDecimal amount) {}
    public record SettlementRequest(@NotBlank @Pattern(regexp="SUCCEEDED|FAILED") String status,
                                     @NotBlank @Size(min=5,max=500) String reason) {}
}
