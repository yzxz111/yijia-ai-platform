package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionService;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CompositeModelOrchestrator {

    private static final ChatCompletionService.TargetRequestPreparer IDENTITY_PREPARER =
            (body, target, adapter) -> Mono.just(body);

    private final MultimodalRequestNormalizer normalizer;
    private final ChatCompletionService chat;
    private final MultimodalProperties properties;
    private final ContentSafetyOrchestrator safety;

    public CompositeModelOrchestrator(
            MultimodalRequestNormalizer normalizer,
            ChatCompletionService chat,
            MultimodalProperties properties) {
        this(normalizer, chat, properties, null);
    }

    @Autowired
    public CompositeModelOrchestrator(
            MultimodalRequestNormalizer normalizer,
            ChatCompletionService chat,
            MultimodalProperties properties,
            ContentSafetyOrchestrator safety) {
        this.normalizer = normalizer;
        this.chat = chat;
        this.properties = properties;
        this.safety = safety;
    }

    public Mono<ChatCompletionService.CompletionResult> complete(
            ApiKeyService.ApiKeyContext apiKey,
            ObjectNode body,
            String requestId,
            CompositeModelPolicyService.CompositePolicy policy) {
        return complete(apiKey, Mono.just(body), requestId, policy);
    }

    public Mono<ChatCompletionService.CompletionResult> complete(
            ApiKeyService.ApiKeyContext apiKey,
            Mono<ObjectNode> body,
            String requestId,
            CompositeModelPolicyService.CompositePolicy policy) {
        validateExtractorVersion(policy);
        return Mono.defer(() -> {
            AtomicBoolean handedOff = new AtomicBoolean();
            return chat.admitPublic(apiKey, policy.publicModel())
                    .flatMap(admission -> Mono.usingWhen(
                            Mono.just(admission),
                            ignored -> body.flatMap(source ->
                                            normalizeAndRoute(source, apiKey, requestId, policy))
                                    .flatMap(routed -> {
                                        Mono<ChatCompletionService.CompletionResult> execution =
                                                chat.completeRouted(
                                                        apiKey, routed.normalized().body(), requestId,
                                                        routed.normalized().publicModel(),
                                                        routed.routingModel(),
                                                        routed.normalized().finalCapabilities(),
                                                        IDENTITY_PREPARER, admission);
                                        return execution.doOnSubscribe(
                                                subscription -> handedOff.set(true));
                                    }),
                            ignored -> releaseUnlessHandedOff(ignored, handedOff),
                            (ignored, error) -> releaseUnlessHandedOff(ignored, handedOff),
                            ignored -> releaseUnlessHandedOff(ignored, handedOff)));
        });
    }

    public Mono<Flux<String>> openStream(
            ApiKeyService.ApiKeyContext apiKey,
            ObjectNode body,
            String requestId,
            CompositeModelPolicyService.CompositePolicy policy) {
        return openStream(apiKey, Mono.just(body), requestId, policy);
    }

    public Mono<Flux<String>> openStream(
            ApiKeyService.ApiKeyContext apiKey,
            Mono<ObjectNode> body,
            String requestId,
            CompositeModelPolicyService.CompositePolicy policy) {
        validateExtractorVersion(policy);
        return Mono.defer(() -> {
            AtomicBoolean handedOff = new AtomicBoolean();
            return chat.admitPublic(apiKey, policy.publicModel())
                    .flatMap(admission -> Mono.usingWhen(
                            Mono.just(admission),
                            ignored -> body.flatMap(source ->
                                            normalizeAndRoute(source, apiKey, requestId, policy))
                                    .flatMap(routed -> {
                                        Mono<Flux<String>> execution = chat.openRoutedStream(
                                                apiKey, routed.normalized().body(), requestId,
                                                routed.normalized().publicModel(), routed.routingModel(),
                                                routed.normalized().finalCapabilities(),
                                                IDENTITY_PREPARER, admission);
                                        return execution.doOnSubscribe(
                                                subscription -> handedOff.set(true));
                                    }),
                            ignored -> releaseUnlessHandedOff(ignored, handedOff),
                            (ignored, error) -> releaseUnlessHandedOff(ignored, handedOff),
                            ignored -> releaseUnlessHandedOff(ignored, handedOff)));
        });
    }

    private Mono<RoutedNormalization> normalizeAndRoute(
            ObjectNode body,
            ApiKeyService.ApiKeyContext apiKey,
            String requestId,
            CompositeModelPolicyService.CompositePolicy policy) {
        return normalizer.normalize(body, apiKey)
                .flatMap(normalized -> inspectExtractedInput(normalized, apiKey, requestId)
                        .thenReturn(new RoutedNormalization(
                                normalized, routingModel(normalized, policy))));
    }

    private Mono<Void> inspectExtractedInput(
            MultimodalRequestNormalizer.NormalizedRequest normalized,
            ApiKeyService.ApiKeyContext apiKey,
            String requestId) {
        if (safety == null || normalized.profile() == MultimodalInputClassifier.InputProfile.TEXT
                || normalized.profile() == MultimodalInputClassifier.InputProfile.TEXT_IMAGE) {
            return Mono.empty();
        }
        return safety.inspectNormalizedInput(apiKey, normalized.body(), requestId)
                .flatMap(decision -> decision.blocked()
                        ? Mono.error(new ResponseStatusException(
                                "content_safety_unavailable".equals(decision.code())
                                        ? HttpStatus.SERVICE_UNAVAILABLE
                                        : HttpStatus.UNPROCESSABLE_ENTITY,
                                decision.code()))
                        : Mono.empty());
    }

    private Mono<Void> releaseUnlessHandedOff(
            ChatCompletionService.PublicAdmission admission, AtomicBoolean handedOff) {
        return handedOff.get() ? Mono.empty() : chat.releasePublicAdmission(admission);
    }

    private void validateExtractorVersion(
            CompositeModelPolicyService.CompositePolicy policy) {
        if (policy == null
                || policy.extractorVersion() == null
                || policy.extractorVersion().isBlank()
                || !policy.extractorVersion().equals(properties.getExtractorVersion())) {
            throw routeUnavailable();
        }
    }

    private String routingModel(
            MultimodalRequestNormalizer.NormalizedRequest normalized,
            CompositeModelPolicyService.CompositePolicy policy) {
        if (policy == null || !policy.publicModel().equals(normalized.publicModel())) {
            throw routeUnavailable();
        }
        return switch (normalized.profile()) {
            case TEXT -> policy.textModel();
            case TEXT_IMAGE -> policy.imageModel();
            case TEXT_FILE -> policy.fileModel();
            case TEXT_IMAGE_FILE -> policy.mixedFinalModel();
        };
    }

    private ResponseStatusException routeUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "multimodal_route_unavailable");
    }

    private record RoutedNormalization(
            MultimodalRequestNormalizer.NormalizedRequest normalized,
            String routingModel) { }
}
