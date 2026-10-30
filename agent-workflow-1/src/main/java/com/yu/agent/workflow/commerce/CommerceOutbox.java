package com.yu.agent.workflow.commerce;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Transactional outbox for payment and logistics commands. */
public interface CommerceOutbox {
    List<CommerceCommand> claim(String owner, int limit, Duration lease);

    void completeRefund(CommerceCommand command, String owner, String gatewayRefundId);

    void completeShipment(CommerceCommand command, String owner, String trackingNo);

    void completeAddressChange(CommerceCommand command, String owner, String externalRef);

    void retryLater(CommerceCommand command, String owner, Instant nextAttemptAt, String error);

    void markFailed(CommerceCommand command, String owner, String error);

    void markUnknown(CommerceCommand command, String owner, String error);

    default void awaitConfirmation(CommerceCommand command, String owner, String externalRef) {
        throw new UnsupportedOperationException("No asynchronous confirmation support");
    }

    default void markManualReview(CommerceCommand command, String owner, String reason) {
        markUnknown(command, owner, reason);
    }
}
