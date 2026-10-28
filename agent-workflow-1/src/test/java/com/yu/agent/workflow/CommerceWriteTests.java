package com.yu.agent.workflow;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.commerce.InMemoryCommerceRepository;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.tools.AddressUpdateTool;
import com.yu.agent.workflow.tools.PaymentQueryTool;
import com.yu.agent.workflow.tools.RefundTool;
import com.yu.agent.workflow.tools.ShipmentCreateTool;
import com.yu.agent.workflow.tools.ToolContext;
import com.yu.agent.workflow.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceWriteTests {
    private final InMemoryCommerceRepository commerce = new InMemoryCommerceRepository();
    private final ToolContext supervisor = new ToolContext("manager", UserRole.SUPERVISOR, "task-write-1", "trace-write-1");

    @Test
    void refundWritesPaymentStateAndReplaysIdempotently() {
        RefundTool tool = new RefundTool(commerce);

        ToolResult first = tool.execute(Map.of("orderId", "ORD-1001", "amount", 20), supervisor);

        assertThat(first.success()).isTrue();
        assertThat(first.data()).containsEntry("verified", true).containsEntry("status", "SUCCEEDED");
        assertThat(commerce.refunds("ORD-1001")).hasSize(1);
        assertThat(commerce.order("ORD-1001").refundableAmount()).isEqualByComparingTo("179.00");
        assertThat(commerce.paymentByOrder("ORD-1001").orElseThrow().refundedAmount())
                .isEqualByComparingTo("20.00");

        ToolResult replay = tool.execute(Map.of("orderId", "ORD-1001", "amount", 20), supervisor);

        assertThat(replay.data()).containsEntry("replayed", true);
        assertThat(commerce.refunds("ORD-1001")).hasSize(1);
        assertThat(commerce.order("ORD-1001").refundableAmount()).isEqualByComparingTo("179.00");
    }

    @Test
    void refundCannotExceedTheAvailablePaidAmount() {
        assertThatThrownBy(() -> new RefundTool(commerce)
                .execute(Map.of("orderId", "ORD-1001", "amount", 199.01), supervisor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds refundable amount");
    }

    @Test
    void refundIsRejectedForAnUnpaidOrder() {
        assertThatThrownBy(() -> new RefundTool(commerce)
                .execute(Map.of("orderId", "ORD-1009", "amount", 10), supervisor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not refundable");
    }

    @Test
    void addressUpdateIsRejectedAfterShipment() {
        assertThatThrownBy(() -> new AddressUpdateTool(commerce)
                .execute(Map.of("orderId", "ORD-1001", "newAddress", "上海市新地址 1 号"), supervisor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("before shipment");
    }

    @Test
    void addressUpdateWritesTheOrderBeforeShipment() {
        ToolResult result = new AddressUpdateTool(commerce)
                .execute(Map.of("orderId", "ORD-1003", "newAddress", "上海市新地址 1 号"), supervisor);

        assertThat(result.data()).containsEntry("verified", true);
        assertThat(commerce.order("ORD-1003").shippingAddress()).isEqualTo("上海市新地址 1 号");
        assertThat(commerce.order("ORD-1003").items()).hasSize(2);
    }

    @Test
    void shipmentCreationWritesLogisticsStateAndReplaysIdempotently() {
        ShipmentCreateTool tool = new ShipmentCreateTool(commerce);

        ToolResult created = tool.execute(Map.of("orderId", "ORD-1003", "carrier", "SF Express"), supervisor);

        assertThat(created.data()).containsEntry("verified", true).containsEntry("status", "IN_TRANSIT");
        assertThat(commerce.order("ORD-1003").status()).isEqualTo("SHIPPED");
        assertThat(commerce.shipmentByOrder("ORD-1003").orElseThrow().trackingNo()).startsWith("MEM");

        ToolResult replay = tool.execute(Map.of("orderId", "ORD-1003", "carrier", "SF Express"), supervisor);

        assertThat(replay.data()).containsEntry("replayed", true);
    }

    @Test
    void shipmentCreationIsRejectedForAnUnpaidOrder() {
        assertThatThrownBy(() -> new ShipmentCreateTool(commerce)
                .execute(Map.of("orderId", "ORD-1009"), supervisor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be shipped");
    }

    @Test
    void paymentQueryReturnsPaymentAndRefundHistory() {
        ToolResult result = new PaymentQueryTool(commerce)
                .execute(Map.of("orderId", "ORD-1005"), new ToolContext("cs", UserRole.CUSTOMER_SERVICE, "task-read", "trace-read"));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("paymentId", "PAY-1005")
                .containsEntry("status", "PARTIALLY_REFUNDED")
                .containsEntry("refundableAmount", new java.math.BigDecimal("400.00"));
        assertThat((java.util.List<?>) result.data().get("refunds")).hasSize(1);
    }
}
