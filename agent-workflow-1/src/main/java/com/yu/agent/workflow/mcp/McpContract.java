package com.yu.agent.workflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

/** Validator for the reviewed local schema subset. Remote schemas never become executable policy. */
final class McpContract {
    final JsonNode tool;
    McpContract(ObjectMapper mapper) {
        try (var stream=new ClassPathResource("mcp/shipment-v1.json").getInputStream()) { tool=mapper.readTree(stream); }
        catch (Exception ex) { throw new IllegalStateException("Shipment MCP contract cannot be loaded", ex); }
    }
    void discovered(JsonNode listing) {
        var tools=listing.path("tools");
        if (listing.has("nextCursor") || !tools.isArray() || tools.size()!=1) fail();
        var remote=tools.path(0);
        for (String field : new String[]{"name","inputSchema","outputSchema"})
            if (!tool.path(field).equals(remote.path(field))) fail();
        for (String flag : new String[]{"readOnlyHint","destructiveHint","idempotentHint","openWorldHint"})
            if (!tool.path("annotations").path(flag).equals(remote.path("annotations").path(flag))) fail();
    }
    static void validate(JsonNode schema, JsonNode value) {
        switch(schema.path("type").asText()) {
            case "object" -> {
                if (!value.isObject()) fail();
                for (var required:schema.path("required")) if (!value.has(required.asText())) fail();
                var fields=value.fields();
                while(fields.hasNext()) {
                    var field=fields.next(); var property=schema.path("properties").get(field.getKey());
                    if (property==null) fail();
                    validate(property,field.getValue());
                }
            }
            case "array" -> {
                if (!value.isArray() || value.size()>schema.path("maxItems").asInt(Integer.MAX_VALUE)) fail();
                for(var item:value) validate(schema.path("items"),item);
            }
            case "string" -> {
                if (!value.isTextual()) fail();
                String text=value.asText(); int length=text.codePointCount(0,text.length());
                if (length<schema.path("minLength").asInt(0) || length>schema.path("maxLength").asInt(Integer.MAX_VALUE)
                        || schema.has("pattern") && !text.matches(schema.path("pattern").asText())) fail();
            }
            case "integer" -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<schema.path("minimum").asLong(Long.MIN_VALUE)) fail();
            }
            case "boolean" -> { if (!value.isBoolean()) fail(); }
            default -> fail();
        }
    }
    static void fail() { throw McpHttp.failure("MCP_CONTRACT_MISMATCH"); }
}
