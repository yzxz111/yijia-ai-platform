package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public final class OutputSafetyInspector {
    private final ContentSafetyOrchestrator orchestrator;

    public OutputSafetyInspector(ContentSafetyOrchestrator orchestrator) { this.orchestrator = orchestrator; }

    public Mono<Decision> inspect(ApiKeyService.ApiKeyContext context, JsonNode response, String requestId) {
        return inspectText(context, assistantText(response), requestId);
    }

    public Mono<Decision> inspectText(ApiKeyService.ApiKeyContext context, String text, String requestId) {
        return orchestrator.inspectOutput(context, text, requestId)
                .map(result -> new Decision(result.blocked(), result.code()));
    }

    private String assistantText(JsonNode response) {
        if (response == null) return "";
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) return content.asText();
        StringBuilder value = new StringBuilder();
        content.forEach(part -> {
            if (part.path("text").isTextual()) value.append(part.path("text").asText());
        });
        if (value.isEmpty()) {
            response.path("output").forEach(message -> message.path("content").forEach(part -> {
                if (part.path("text").isTextual()) value.append(part.path("text").asText());
            }));
        }
        return value.toString();
    }

    public record Decision(boolean blocked, String code) { }
}
