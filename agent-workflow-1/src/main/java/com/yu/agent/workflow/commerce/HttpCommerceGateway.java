package com.yu.agent.workflow.commerce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="agent.commerce.gateway", havingValue="http")
public class HttpCommerceGateway implements CommerceGateway {
    private final HttpBusinessClient client;
    public HttpCommerceGateway(HttpBusinessClient client) { this.client = client; }
    public boolean supportsStatusQuery() { return true; }
    public GatewayResult query(CommerceCommand command) { requireRefund(command); return client.query(command); }
    public GatewayResult execute(CommerceCommand command) { requireRefund(command); return client.submit(command); }
    private void requireRefund(CommerceCommand command) {
        if (!"REFUND".equals(command.commandType()) || !"BUSINESS_HTTP".equals(command.channel())) {
            throw new IllegalArgumentException("HTTP integration only permits the configured refund contract");
        }
    }
}
