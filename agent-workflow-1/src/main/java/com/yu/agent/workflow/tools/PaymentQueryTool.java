package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.OrderDetail;
import com.yu.agent.workflow.commerce.PaymentDetail;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PaymentQueryTool extends AbstractTool {
    private final CommerceRepository commerce;

    public PaymentQueryTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "payment.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() {
        return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN);
    }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId");
        String id = requiredText(arguments, "orderId").toUpperCase();
        OrderDetail order = commerce.order(id);
        PaymentDetail payment = commerce.paymentByOrder(id)
                .orElseThrow(() -> BusinessException.of("NOT_FOUND", "Payment does not exist for order: " + id));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", payment.paymentId());
        data.put("orderId", id);
        data.put("orderStatus", order.status());
        data.put("channel", payment.channel());
        data.put("status", payment.status());
        data.put("amount", payment.amount());
        data.put("refundedAmount", payment.refundedAmount());
        data.put("refundableAmount", order.refundableAmount());
        data.put("gatewayTxnId", payment.gatewayTxnId() == null ? "" : payment.gatewayTxnId());
        data.put("paidAt", payment.paidAt() == null ? "" : payment.paidAt().toString());
        data.put("refunds", commerce.refunds(id));
        data.put("source", commerce.backend());
        return ToolResult.ok("Payment found", data);
    }
}
