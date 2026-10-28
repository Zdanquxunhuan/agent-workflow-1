package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.RefundRecord;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class RefundTool extends AbstractTool {
    private static final BigDecimal MAX_REFUND = new BigDecimal("100000");

    private final CommerceRepository commerce;

    public RefundTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "refund.create"; }
    public boolean readOnly() { return false; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.SUPERVISOR, UserRole.ADMIN); }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId", "amount", "reason");
        String orderId = requiredText(arguments, "orderId").toUpperCase();
        BigDecimal amount = amount(arguments.get("amount"));
        String reason = optionalText(arguments, "reason", "客服发起的退款");

        RefundRecord refund = commerce.createRefund(orderId, amount, reason,
                context.userId(), context.taskId(),
                idempotencyKey(context, orderId, amount.toPlainString()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", refund.refundId());
        data.put("orderId", refund.orderId());
        data.put("amount", refund.amount());
        data.put("status", refund.status());
        data.put("gatewayRefundId", refund.gatewayRefundId() == null ? "" : refund.gatewayRefundId());
        data.put("replayed", refund.replayed());
        data.put("verified", true);
        data.put("simulation", "memory".equals(commerce.backend()));
        data.put("backend", commerce.backend());
        String message = refund.replayed() ? "Refund already accepted (idempotent replay)"
                : ("SUCCEEDED".equals(refund.status())
                    ? "Refund completed" : "Refund accepted and queued for payment gateway");
        return ToolResult.ok(message, data);
    }

    private BigDecimal amount(Object value) {
        if (!(value instanceof Number number)) {
            throw BusinessException.of("INVALID_ARGUMENT", "amount must be a number");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw BusinessException.of("INVALID_ARGUMENT", "amount must be a valid number");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(MAX_REFUND) > 0) {
            throw BusinessException.of("INVALID_ARGUMENT", "amount must be between 0 and 100000");
        }
        return amount;
    }
}
