package com.yu.agent.workflow.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Real HTTP transport. Never falls back to demo tables on failure. */
@Component
@ConditionalOnProperty(name="agent.commerce.gateway", havingValue="http")
public class HttpBusinessClient {
    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpBusinessClient(RestClient.Builder builder, ObjectMapper mapper,
                              @Value("${agent.commerce.http.base-url:}") String baseUrl,
                              @Value("${agent.commerce.http.api-key:}") String apiKey,
                              @Value("${agent.commerce.http.timeout-seconds:2}") long timeout) {
        if (baseUrl.isBlank() || apiKey.length() < 24) throw new IllegalStateException("HTTP business integration requires base URL and service key (24+ characters)");
        this.mapper = mapper;
        Duration duration = Duration.ofSeconds(Math.max(1, timeout));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(duration).build());
        factory.setReadTimeout(duration);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).defaultHeader("X-Api-Key", apiKey).build();
    }

    public boolean ready() {
        try { return client.get().uri("/health").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful(); }
        catch (RestClientException ex) { return false; }
    }

    public OrderDetail order(String id) { return read("/business/v1/orders/{id}", id, OrderDetail.class).orElseThrow(() -> BusinessException.of("NOT_FOUND", "External order not found")); }
    public Optional<PaymentDetail> payment(String id) { return read("/business/v1/orders/{id}/payment", id, PaymentDetail.class); }
    public Optional<ShipmentDetail> shipment(String id) { return read("/business/v1/orders/{id}/shipment", id, ShipmentDetail.class); }

    private <T> Optional<T> read(String path, String id, Class<T> type) {
        try {
            T body = client.get().uri(path, id).retrieve().body(type);
            if (body == null) throw BusinessException.of("DOWNSTREAM_UNAVAILABLE", "External business returned an empty response");
            return Optional.of(body);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return Optional.empty();
            throw BusinessException.of("DOWNSTREAM_UNAVAILABLE", "External business query failed (HTTP " + ex.getStatusCode().value() + ")");
        } catch (RestClientException ex) {
            throw BusinessException.of("DOWNSTREAM_UNAVAILABLE", "External business query timed out or is unavailable");
        }
    }

    public CommerceGateway.GatewayResult submit(CommerceCommand command) {
        try {
            Map<String, Object> payload = payload(command);
            Receipt receipt = client.post().uri("/business/v1/refunds").body(Map.of(
                    "idempotencyKey", command.idempotencyKey(), "orderId", payload.get("orderId"),
                    "amount", payload.get("amount"))).retrieve().body(Receipt.class);
            return verify(command, receipt);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 400 || status == 409) return new CommerceGateway.GatewayResult(null, null, "FAILED", "Business rejected refund (HTTP " + status + ")");
            throw new CommerceGateway.GatewayTimeoutException("Business refund response unavailable (HTTP " + status + "); query by idempotency key");
        } catch (RestClientException ex) {
            throw new CommerceGateway.GatewayTimeoutException("Business refund submission timed out; do not assume failure");
        }
    }

    public CommerceGateway.GatewayResult query(CommerceCommand command) {
        try {
            Receipt receipt = client.get().uri("/business/v1/refunds/{key}", command.idempotencyKey()).retrieve().body(Receipt.class);
            return verify(command, receipt);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return new CommerceGateway.GatewayResult(null, null, "NOT_FOUND", "No ledger entry for this idempotency key");
            throw new CommerceGateway.GatewayTimeoutException("Business status query unavailable (HTTP " + ex.getStatusCode().value() + ")");
        } catch (RestClientException ex) {
            throw new CommerceGateway.GatewayTimeoutException("Business status query timed out");
        }
    }

    private CommerceGateway.GatewayResult verify(CommerceCommand command, Receipt receipt) {
        Map<String, Object> expected = payload(command);
        if (receipt == null || receipt.amount() == null || receipt.externalRef() == null || receipt.externalRef().isBlank()
                || !command.idempotencyKey().equals(receipt.idempotencyKey()) || !expected.get("orderId").equals(receipt.orderId())
                || new BigDecimal(expected.get("amount").toString()).compareTo(receipt.amount()) != 0
                || !Set.of("PENDING", "SUCCEEDED", "FAILED").contains(receipt.status() == null ? "" : receipt.status())) {
            throw new CommerceGateway.GatewayTimeoutException("External receipt did not match refund identity/amount/status; manual reconciliation required");
        }
        return new CommerceGateway.GatewayResult(receipt.externalRef(), null, receipt.status(), "External ledger status: " + receipt.status());
    }

    private Map<String, Object> payload(CommerceCommand command) {
        try { return mapper.readValue(command.payloadJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Invalid stored refund payload", ex); }
    }

    public record Receipt(String externalRef, String idempotencyKey, String orderId, BigDecimal amount, String status) {}
}
