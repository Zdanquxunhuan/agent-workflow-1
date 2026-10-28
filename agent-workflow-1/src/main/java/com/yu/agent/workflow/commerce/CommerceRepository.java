package com.yu.agent.workflow.commerce;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Business system of record used by the tools. Implementations must enforce
 * business rules and idempotency; tools only validate transport-level input.
 */
public interface CommerceRepository {
    String backend();

    boolean ready();

    OrderDetail order(String orderId);

    Optional<PaymentDetail> paymentByOrder(String orderId);

    List<RefundRecord> refunds(String orderId);

    Optional<ShipmentDetail> shipmentByOrder(String orderId);

    Optional<TicketDetail> ticket(String ticketId);

    RefundRecord createRefund(String orderId, BigDecimal amount, String reason,
                              String actor, String taskId, String idempotencyKey);

    AddressChange updateAddress(String orderId, String newAddress,
                                String actor, String taskId, String idempotencyKey);

    ShipmentDetail createShipment(String orderId, String carrier,
                                  String actor, String taskId, String idempotencyKey);
}
