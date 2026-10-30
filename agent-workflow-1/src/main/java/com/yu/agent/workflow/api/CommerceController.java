package com.yu.agent.workflow.api;

import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.OrderDetail;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.security.AuthenticatedUser;
import com.yu.agent.workflow.security.JwtUserResolver;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Internal verification endpoint: shows the persisted business state behind a tool call. */
@RestController
@RequestMapping("/api/v1/commerce")
public class CommerceController {
    private final CommerceRepository commerce;
    private final JwtUserResolver users;

    public CommerceController(CommerceRepository commerce, JwtUserResolver users) {
        this.commerce = commerce;
        this.users = users;
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<Map<String, Object>> order(@PathVariable String orderId,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        if (user.role() == UserRole.AGENT) {
            throw BusinessException.of("FORBIDDEN", "Role is not allowed to read order data");
        }
        OrderDetail order = commerce.order(orderId.toUpperCase());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        commerce.paymentByOrder(order.orderId()).ifPresent(payment -> data.put("payment", payment));
        commerce.shipmentByOrder(order.orderId()).ifPresent(shipment -> data.put("shipment", shipment));
        data.put("refunds", commerce.refunds(order.orderId()));
        data.put("source", commerce.backend());
        String actualTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        return ApiResponse.ok(data, actualTraceId);
    }
}
