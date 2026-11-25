package com.yuzhihuihe.apiplatform.gateway.openai;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.multimodal.CompositeModelOrchestrator;
import com.yuzhihuihe.apiplatform.gateway.multimodal.CompositeModelPolicyService;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CompletionDispatchService {

    private final CompositeModelPolicyService policies;
    private final CompositeModelOrchestrator orchestrator;
    private final ChatCompletionService chat;

    public CompletionDispatchService(
            CompositeModelPolicyService policies,
            CompositeModelOrchestrator orchestrator,
            ChatCompletionService chat) {
        this.policies = policies;
        this.orchestrator = orchestrator;
        this.chat = chat;
    }

    public Mono<ChatCompletionService.CompletionResult> complete(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId) {
        return routing(apiKey, body).flatMap(routing -> routing.compositePolicy() == null
                ? chat.complete(apiKey, body, requestId)
                : orchestrator.complete(
                        apiKey, (ObjectNode) body, requestId, routing.compositePolicy()));
    }

    public Mono<Flux<String>> openStream(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId) {
        return routing(apiKey, body).flatMap(routing -> routing.compositePolicy() == null
                ? chat.openStream(apiKey, body, requestId)
                : orchestrator.openStream(
                        apiKey, (ObjectNode) body, requestId, routing.compositePolicy()));
    }

    public Mono<ChatCompletionService.CompletionResult> completePrepared(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            Set<ChannelRouter.Capability> capabilities,
            ChatCompletionService.TargetRequestPreparer requestPreparer) {
        return completePrepared(
                apiKey, body, requestId,
                Mono.just(new PreparedRequest(
                        (ObjectNode) body, capabilities, requestPreparer)));
    }

    public Mono<ChatCompletionService.CompletionResult> completePrepared(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode routingBody,
            String requestId,
            Mono<PreparedRequest> preparedRequest) {
        return routing(apiKey, routingBody).flatMap(routing -> routing.compositePolicy() == null
                ? preparedRequest.flatMap(prepared -> chat.completePrepared(
                        apiKey, prepared.body(), requestId,
                        prepared.capabilities(), prepared.requestPreparer()))
                : orchestrator.complete(
                        apiKey, preparedRequest.map(PreparedRequest::body),
                        requestId, routing.compositePolicy()));
    }

    public Mono<Flux<String>> openPreparedStream(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            Set<ChannelRouter.Capability> capabilities,
            ChatCompletionService.TargetRequestPreparer requestPreparer) {
        return openPreparedStream(
                apiKey, body, requestId,
                Mono.just(new PreparedRequest(
                        (ObjectNode) body, capabilities, requestPreparer)));
    }

    public Mono<Flux<String>> openPreparedStream(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode routingBody,
            String requestId,
            Mono<PreparedRequest> preparedRequest) {
        return routing(apiKey, routingBody).flatMap(routing -> routing.compositePolicy() == null
                ? preparedRequest.flatMap(prepared -> chat.openPreparedStream(
                        apiKey, prepared.body(), requestId,
                        prepared.capabilities(), prepared.requestPreparer()))
                : orchestrator.openStream(
                        apiKey, preparedRequest.map(PreparedRequest::body),
                        requestId, routing.compositePolicy()));
    }

    private Mono<CompositeModelPolicyService.ModelRouting> routing(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body) {
        if (apiKey == null || body == null || !(body instanceof ObjectNode)
                || !body.path("model").isTextual()
                || body.path("model").asText().isBlank()
                || !apiKey.allowedModels().contains(body.path("model").asText())) {
            return Mono.just(CompositeModelPolicyService.ModelRouting.direct());
        }
        return policies.resolve(body.path("model").asText());
    }

    public record PreparedRequest(
            ObjectNode body,
            Set<ChannelRouter.Capability> capabilities,
            ChatCompletionService.TargetRequestPreparer requestPreparer) {

        public PreparedRequest {
            capabilities = Set.copyOf(capabilities);
        }
    }
}
