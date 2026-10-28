package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.OrderDetail;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class OrderQueryTool extends AbstractTool {
    private final CommerceRepository commerce;

    public OrderQueryTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "order.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() {
        return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN);
    }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId");
        String id = requiredText(arguments, "orderId").toUpperCase();
        OrderDetail order = commerce.order(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("status", order.status());
        data.put("currency", order.currency());
        data.put("amount", order.totalAmount());
        data.put("paidAmount", order.paidAmount());
        data.put("refundedAmount", order.refundedAmount());
        data.put("refundableAmount", order.refundableAmount());
        data.put("customer", order.customerId());
        data.put("customerName", order.customerName());
        data.put("shippingAddress", order.shippingAddress());
        data.put("items", order.items());
        data.put("paymentStatus", commerce.paymentByOrder(id).map(payment -> payment.status()).orElse("NONE"));
        data.put("shipmentStatus", commerce.shipmentByOrder(id).map(shipment -> shipment.status())
                .orElse("NOT_SHIPPED"));
        data.put("source", commerce.backend());
        return ToolResult.ok("Order found", data);
    }
}
