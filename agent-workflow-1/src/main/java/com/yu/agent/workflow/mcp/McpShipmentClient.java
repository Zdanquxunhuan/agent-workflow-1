package com.yu.agent.workflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** MCP JSON-only, stateless Streamable HTTP profile; not a general SSE/session client. */
@Component
@ConditionalOnProperty(name="agent.mcp.shipment.enabled", havingValue="true")
public class McpShipmentClient {
    private final McpSettings settings;
    private final ObjectMapper mapper;
    private final McpHttp http=new McpHttp();
    private final McpContract contract;
    private final McpCredentialProvider credentials;
    private final Semaphore slots=new Semaphore(8);
    private final AtomicInteger failures=new AtomicInteger();
    private volatile long openUntil;
    public McpShipmentClient(McpSettings settings, ObjectMapper mapper) {
        this.settings=settings;
        this.mapper=mapper.copy().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        this.contract=new McpContract(this.mapper);
        this.credentials=new McpCredentialProvider(settings,http,this.mapper);
    }
    public Map<String,Object> track(String order, ToolContext context, String callId) {
        if (!settings.tenant.equals(context.tenantId())) throw McpHttp.failure("MCP_ACCESS_DENIED");
        if (System.nanoTime()<openUntil) throw McpHttp.failure("MCP_CIRCUIT_OPEN");
        if (!slots.tryAcquire()) throw McpHttp.failure("MCP_BUSY");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        try {
            String token=credentials.token(context,deadline);
            var initialized=rpc("initialize",Map.of("protocolVersion",McpSettings.PROTOCOL,"capabilities",Map.of(),
                    "clientInfo",Map.of("name","workflow-agent","version","8.0")),token,deadline,UUID.randomUUID().toString());
            if (!McpSettings.PROTOCOL.equals(initialized.path("protocolVersion").asText())
                    || !initialized.path("capabilities").path("tools").isObject()) throw McpHttp.failure("MCP_PROTOCOL_ERROR");
            // Initialization instructions and remote descriptions are deliberately never inserted into model context.
            var notification=post(Map.of("jsonrpc","2.0","method","notifications/initialized"),token,deadline);
            if(notification.statusCode()!=202 || notification.body().length!=0) throw McpHttp.failure("MCP_PROTOCOL_ERROR");
            contract.discovered(rpc("tools/list",Map.of(),token,deadline,UUID.randomUUID().toString()));
            var arguments=mapper.valueToTree(Map.of("orderId",order));
            McpContract.validate(contract.tool.path("inputSchema"),arguments);
            JsonNode result=rpc("tools/call",Map.of("name",contract.tool.path("name").asText(),"arguments",arguments),token,deadline,callId);
            if(result.has("isError") && !result.path("isError").isBoolean()) throw McpHttp.failure("MCP_PROTOCOL_ERROR");
            if(result.path("isError").asBoolean()) {
                String code=result.path("structuredContent").path("code").asText();
                if(!Set.of("MCP_ACCESS_DENIED","MCP_NOT_FOUND","MCP_RATE_LIMITED","MCP_TIMEOUT",
                        "MCP_BACKEND_UNAVAILABLE","MCP_INVALID_ARGUMENT","MCP_TOOL_BLOCKED").contains(code)) code="MCP_TOOL_FAILED";
                throw McpHttp.failure(code);
            }
            JsonNode data=result.path("structuredContent");
            McpContract.validate(contract.tool.path("outputSchema"),data);
            var content=result.path("content");
            if(!content.isArray() || content.size()!=1 || !"text".equals(content.path(0).path("type").asText())
                    || !data.equals(mapper.readTree(content.path(0).path("text").asText()))) throw McpHttp.failure("MCP_CONTRACT_MISMATCH");
            if(!order.equals(data.path("orderId").asText())) throw McpHttp.failure("MCP_CONTRACT_MISMATCH");
            for(String field:List.of("shippedAt","deliveredAt","observedAt")) {
                String value=data.path(field).asText(); if(!value.isEmpty()) Instant.parse(value);
            }
            for(var event:data.path("events")) Instant.parse(event.path("occurredAt").asText());
            failures.set(0);
            return mapper.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        } catch(BusinessException ex) {
            if(Set.of("MCP_UNAVAILABLE","MCP_TIMEOUT","MCP_BACKEND_UNAVAILABLE","MCP_PROTOCOL_ERROR","MCP_CONTRACT_MISMATCH").contains(ex.code())) {
                if(failures.incrementAndGet()>=3) {openUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);failures.set(0);}
            } else failures.set(0);
            throw ex;
        } catch(Exception ex) {throw McpHttp.failure("MCP_CONTRACT_MISMATCH");}
        finally {slots.release();}
    }
    private JsonNode rpc(String method, Map<String,?> params, String token, long deadline, String id) {
        var response=post(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params),token,deadline);
        if(response.statusCode()!=200 || !response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().equalsIgnoreCase("application/json"))
            throw McpHttp.failure("MCP_PROTOCOL_ERROR");
        try {
            var body=mapper.readTree(response.body());
            if(body==null || !body.isObject() || !"2.0".equals(body.path("jsonrpc").asText())
                    || !body.path("id").isTextual() || !id.equals(body.path("id").asText())
                    || body.has("error") || !body.path("result").isObject()) throw McpHttp.failure("MCP_PROTOCOL_ERROR");
            return body.path("result");
        } catch(BusinessException ex) {throw ex;}
        catch(Exception ex) {throw McpHttp.failure("MCP_PROTOCOL_ERROR");}
    }
    private java.net.http.HttpResponse<byte[]> post(Map<String,?> body, String token, long deadline) {
        final String json;
        try {json=mapper.writeValueAsString(body);} catch(Exception ex) {throw McpHttp.failure("MCP_PROTOCOL_ERROR");}
        var response=http.post(settings.endpoint,Map.of("Content-Type","application/json","Accept","application/json, text/event-stream",
                "Authorization","Bearer "+token,"MCP-Protocol-Version",McpSettings.PROTOCOL),json,deadline);
        switch(response.statusCode()) {
            case 401,403 -> throw McpHttp.failure("MCP_ACCESS_DENIED");
            case 429 -> throw McpHttp.failure("MCP_RATE_LIMITED");
            case 500,502,503 -> throw McpHttp.failure("MCP_UNAVAILABLE");
            case 504 -> throw McpHttp.failure("MCP_TIMEOUT");
        }
        if(response.headers().firstValue("Mcp-Session-Id").isPresent()) throw McpHttp.failure("MCP_PROTOCOL_ERROR");
        return response;
    }
}
