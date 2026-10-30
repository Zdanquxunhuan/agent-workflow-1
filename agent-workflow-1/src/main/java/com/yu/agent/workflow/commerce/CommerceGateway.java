package com.yu.agent.workflow.commerce;

/**
 * Adapter boundary for the real payment/carrier systems. A production
 * implementation must send the command idempotency key downstream, use
 * bounded timeouts and distinguish accepted / succeeded / failed / unknown.
 */
public interface CommerceGateway {
    GatewayResult execute(CommerceCommand command);

    default boolean supportsStatusQuery() { return false; }

    default GatewayResult query(CommerceCommand command) { throw new UnsupportedOperationException("No status-query contract"); }

    record GatewayResult(String externalRef, String trackingNo, String status, String message) {
        public GatewayResult(String externalRef, String trackingNo) { this(externalRef, trackingNo, "SUCCEEDED", "Business operation confirmed"); }
    }

    /** The downstream result is unknown; the command must not be retried blindly. */
    final class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) {
            super(message);
        }
    }
}
