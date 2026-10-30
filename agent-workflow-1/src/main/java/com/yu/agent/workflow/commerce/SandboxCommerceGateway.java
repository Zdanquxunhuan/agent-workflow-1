package com.yu.agent.workflow.commerce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Deterministic local gateway used for Docker demos and contract tests.
 * Replace with a real payment/carrier adapter before enabling production writes.
 */
@Component
@ConditionalOnProperty(name = "agent.commerce.gateway", havingValue = "sandbox", matchIfMissing = true)
public class SandboxCommerceGateway implements CommerceGateway {
    @Override
    public GatewayResult execute(CommerceCommand command) {
        String ref = "SANDBOX-" + command.commandType() + "-" + shortId(command.commandId());
        if ("CREATE_SHIPMENT".equals(command.commandType())) {
            return new GatewayResult(ref, "TRK" + Math.abs(command.commandId().hashCode()));
        }
        return new GatewayResult(ref, null);
    }

    private String shortId(String value) {
        String compact = UUID.nameUUIDFromBytes(value.getBytes()).toString().replace("-", "");
        return compact.substring(0, 12).toUpperCase();
    }
}
