package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.commerce.CommerceCommand;
import com.yu.agent.workflow.commerce.CommerceGateway;
import com.yu.agent.workflow.commerce.HttpBusinessClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class HttpBusinessClientTests {
    HttpServer server;
    HttpBusinessClient client;
    AtomicReference<String> requestBody = new AtomicReference<>();
    String response = "";
    int status = 200;
    CommerceCommand command = new CommerceCommand("cmd-1","BUSINESS_HTTP","REFUND","local-1",
            "{\"orderId\":\"ORD-3001\",\"amount\":12.50}","stable-key-1",1);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Key")).isEqualTo("test-service-key-at-least-24-characters");
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        client = new HttpBusinessClient(RestClient.builder(), new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:"+server.getAddress().getPort(), "test-service-key-at-least-24-characters",1);
    }
    @AfterEach void stop() { server.stop(0); }
    String receipt(String amount,String status) {
        return "{\"externalRef\":\"business-ref-1\",\"idempotencyKey\":\"stable-key-1\",\"orderId\":\"ORD-3001\",\"amount\":"+amount+",\"status\":\""+status+"\"}";
    }
    @Test void sendsStableKeyAndKeepsAcceptancePending() {
        response=receipt("12.50","PENDING");
        assertThat(client.submit(command).status()).isEqualTo("PENDING");
        assertThat(requestBody.get()).contains("stable-key-1","ORD-3001","12.5");
    }
    @Test void mismatchedAmountCannotSettleARefund() {
        response=receipt("125.00","SUCCEEDED");
        assertThatThrownBy(()->client.query(command)).isInstanceOf(CommerceGateway.GatewayTimeoutException.class).hasMessageContaining("did not match");
    }
    @Test void unknownReceiptStatusIsUncertain() {
        response=receipt("12.50","ACCEPTED_BUT_UNDOCUMENTED");
        assertThatThrownBy(()->client.query(command)).isInstanceOf(CommerceGateway.GatewayTimeoutException.class);
    }
    @Test void provider404IsNotFoundRatherThanSuccess() {
        status=404; response="{}";
        assertThat(client.query(command).status()).isEqualTo("NOT_FOUND");
    }
    @Test void providerConflictIsAConfirmedBusinessRejection() {
        status=409; response="{}";
        assertThat(client.submit(command).status()).isEqualTo("FAILED");
    }
    @Test void readFailureDoesNotFallBackToDemoData() {
        status=503; response="{}";
        assertThatThrownBy(()->client.order("ORD-3001")).isInstanceOf(BusinessException.class).hasMessageContaining("query failed");
    }
}
