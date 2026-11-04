package com.yu.agent.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.*;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;

@Component
@Primary
@ConditionalOnProperty(name="agent.runtime.enabled",havingValue="true")
public class RuntimeModelRouter implements ToolCallingGateway {
    private final RuntimeSettings settings;
    private final RuntimeLedger ledger;
    private final ObjectMapper json;
    private final SpringAiToolCallingGateway primary,backup;
    public RuntimeModelRouter(RuntimeSettings settings,RuntimeLedger ledger,ObjectMapper json) {
        this.settings=settings;this.ledger=ledger;this.json=json;
        primary=adapter(settings.primary);backup=settings.backup==null?null:adapter(settings.backup);
    }
    private SpringAiToolCallingGateway adapter(RuntimeSettings.Model config) {
        var api=OpenAiApi.builder().baseUrl(config.baseUrl()).apiKey(config.apiKey()).completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(new RuntimeModelHttp(config.baseUrl(),settings.attemptSeconds))).build();
        var options=OpenAiChatOptions.builder().model(config.name()).temperature(0.0).maxTokens(config.maxOutput()).internalToolExecutionEnabled(false)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .extraBody("api.deepseek.com".equals(URI.create(config.baseUrl()).getHost())?Map.of("thinking",Map.of("type","disabled")):Map.of()).build();
        var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return new SpringAiToolCallingGateway(model,json);
    }
    @Override public ToolCallingResponse complete(List<Map<String,Object>> messages,List<Map<String,Object>> tools) {
        RunScope scope=RunScope.current();if(scope==null)throw RunScope.error("RUN_CONTEXT_REQUIRED");
        long estimated;
        try {
            int bytes=json.writeValueAsBytes(Map.of("messages",messages,"tools",tools)).length;
            if(bytes>80000)throw RunScope.error("MODEL_CONTEXT_BUDGET");
            // Conservative byte-based reservation, explicitly NOT a provider tokenizer measurement.
            estimated=bytes+1024L+32L*messages.size();
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw RunScope.error("MODEL_INVALID_MESSAGES");}
        try {return attempt(scope,settings.primary,primary,messages,tools,estimated,"default",false);}
        catch(BusinessException ex) {
            if(backup==null || !settings.backup.tenants().contains(scope.tenant)
                    || !Set.of("MODEL_TRANSIENT","MODEL_RATE_LIMITED","MODEL_TIMEOUT").contains(ex.code()))throw ex;
            scope.reauthorize();
            return attempt(scope,settings.backup,backup,messages,tools,estimated,"fallback:"+ex.code(),true);
        }
    }
    private ToolCallingResponse attempt(RunScope scope,RuntimeSettings.Model model,SpringAiToolCallingGateway adapter,
                                       List<Map<String,Object>> messages,List<Map<String,Object>> tools,long estimated,String reason,boolean fallback) {
        scope.check();var reservation=ledger.reserve(scope.id,model,estimated,reason,fallback);
        ToolCallingResponse result;
        try {result=adapter.complete(messages,tools);}
        catch(RuntimeException ex){
            String code=ex instanceof BusinessException b?b.code():"MODEL_UNAVAILABLE";
            ledger.settle(reservation,Map.of(),code);throw ex;
        }
        ledger.settle(reservation,result.metadata(),"OK");scope.check();
        var metadata=new LinkedHashMap<>(result.metadata());metadata.put("runId",scope.id);metadata.put("attemptId",reservation.id());
        metadata.put("modelAlias",model.alias());metadata.put("routeReason",reason);metadata.put("priceVersion",model.priceVersion());
        return new ToolCallingResponse(result.content(),result.toolCalls(),result.assistantMessage(),metadata);
    }
}
