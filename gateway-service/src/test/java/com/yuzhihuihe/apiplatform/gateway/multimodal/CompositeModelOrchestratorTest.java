package com.yuzhihuihe.apiplatform.gateway.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionService;
import com.yuzhihuihe.apiplatform.gateway.openai.CompletionDispatchService;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Sinks;

class CompositeModelOrchestratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ApiKeyService.ApiKeyContext API_KEY =
            new ApiKeyService.ApiKeyContext(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Set.of("multimodal-basic"));

    @Test
    void normalizesTextAndDispatchesWithSeparatePublicAndRoutingAliases() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        ObjectNode normalizedBody = source.deepCopy();
        normalizedBody.withArray("messages").get(0).path("content");
        MultimodalRequestNormalizer.NormalizedRequest normalized =
                new MultimodalRequestNormalizer.NormalizedRequest(
                        normalizedBody,
                        MultimodalInputClassifier.InputProfile.TEXT,
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT));
        CompositeModelPolicyService.CompositePolicy policy =
                new CompositeModelPolicyService.CompositePolicy(
                        "multimodal-basic", "text-basic", "vision-basic", "file-basic",
                        "vision-basic", "multimodal-v1");
        ChatCompletionService.CompletionResult expected =
                new ChatCompletionService.CompletionResult(
                        JSON.createObjectNode().put("model", "multimodal-basic"), "req_test");
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(normalizer.normalize(source, API_KEY)).thenReturn(Mono.just(normalized));
        when(chat.completeRouted(
                eq(API_KEY), any(), eq("req_test"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any(), eq(admission)))
                .thenReturn(Mono.just(expected));

        ChatCompletionService.CompletionResult actual = orchestrator
                .complete(API_KEY, source, "req_test", policy).block();

        assertThat(actual).isEqualTo(expected);
        verify(normalizer).normalize(source, API_KEY);
        verify(chat).completeRouted(
                eq(API_KEY), eq(normalized.body()), eq("req_test"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any(), eq(admission));
    }

    @Test
    void blocksNormalizedDocumentTextBeforeRoutingItUpstream() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        ContentSafetyOrchestrator safety = mock(ContentSafetyOrchestrator.class);
        CompositeModelOrchestrator orchestrator = new CompositeModelOrchestrator(
                normalizer, chat, properties("multimodal-v1"), safety);
        ObjectNode source = request();
        ObjectNode normalizedBody = request();
        ((ObjectNode) normalizedBody.withArray("messages").get(0))
                .put("content", "BEGIN_UNTRUSTED_DOCUMENT\nunsafe extracted text");
        MultimodalRequestNormalizer.NormalizedRequest normalized =
                new MultimodalRequestNormalizer.NormalizedRequest(
                        normalizedBody, MultimodalInputClassifier.InputProfile.TEXT_FILE,
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT));
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic")).thenReturn(Mono.just(admission));
        when(chat.releasePublicAdmission(admission)).thenReturn(Mono.empty());
        when(normalizer.normalize(source, API_KEY)).thenReturn(Mono.just(normalized));
        when(safety.inspectNormalizedInput(API_KEY, normalized.body(), "req_extracted"))
                .thenReturn(Mono.just(ContentSafetyOrchestrator.Decision.block(
                        "content_safety_blocked", "POLICY")));

        assertThatThrownBy(() -> orchestrator
                        .complete(API_KEY, source, "req_extracted", policy()).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("content_safety_blocked");
                });
        verify(safety).inspectNormalizedInput(API_KEY, normalized.body(), "req_extracted");
        verify(chat, never()).completeRouted(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("publicAdmissionRejections")
    void rejectsCompletionAdmissionBeforePreprocessing(
            HttpStatus status, String reason) {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        CompositeModelPolicyService.CompositePolicy policy = policy();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.error(new ResponseStatusException(status, reason)));

        assertThatThrownBy(() -> orchestrator.complete(
                        API_KEY, source, "req_admission_complete", policy).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(status);
                    assertThat(error.getReason()).isEqualTo(reason);
                });

        verify(normalizer, never()).normalize(any(), any());
    }

    @ParameterizedTest
    @MethodSource("publicAdmissionRejections")
    void rejectsStreamAdmissionBeforePreprocessing(
            HttpStatus status, String reason) {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request().put("stream", true);
        CompositeModelPolicyService.CompositePolicy policy = policy();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.error(new ResponseStatusException(status, reason)));

        assertThatThrownBy(() -> orchestrator.openStream(
                        API_KEY, source, "req_admission_stream", policy).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(status);
                    assertThat(error.getReason()).isEqualTo(reason);
                });

        verify(normalizer, never()).normalize(any(), any());
    }

    @Test
    void releasesPublicAdmissionWhenCompletionPreprocessingFails() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(chat.releasePublicAdmission(admission)).thenReturn(Mono.empty());
        when(normalizer.normalize(source, API_KEY))
                .thenReturn(Mono.error(new IllegalStateException("extract failed")));

        assertThatThrownBy(() -> orchestrator.complete(
                        API_KEY, source, "req_preprocess_failure", policy()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("extract failed");

        verify(chat).releasePublicAdmission(admission);
        verify(chat, never()).completeRouted(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void releasesPublicAdmissionWhenStreamPreprocessingIsCancelled() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request().put("stream", true);
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(chat.releasePublicAdmission(admission)).thenReturn(Mono.empty());
        when(normalizer.normalize(source, API_KEY)).thenReturn(Mono.never());

        var subscription = orchestrator.openStream(
                API_KEY, source, "req_preprocess_cancel", policy()).subscribe();
        subscription.dispose();

        verify(chat, timeout(1_000)).releasePublicAdmission(admission);
        verify(chat, never()).openRoutedStream(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotReleaseAgainWhenHandedOffCompletionSetupFails() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(normalizer.normalize(source, API_KEY))
                .thenReturn(Mono.just(normalizedText(source)));
        when(chat.completeRouted(
                eq(API_KEY), eq(source), eq("req_handoff_complete"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any(), eq(admission)))
                .thenReturn(Mono.error(new IllegalStateException("catalog failed")));

        assertThatThrownBy(() -> orchestrator.complete(
                        API_KEY, source, "req_handoff_complete", policy()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("catalog failed");

        verify(chat, never()).releasePublicAdmission(any());
    }

    @Test
    void doesNotReleaseAgainWhenHandedOffStreamSetupFails() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request().put("stream", true);
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(normalizer.normalize(source, API_KEY))
                .thenReturn(Mono.just(normalizedText(source)));
        when(chat.openRoutedStream(
                eq(API_KEY), eq(source), eq("req_handoff_stream"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any(), eq(admission)))
                .thenReturn(Mono.error(new IllegalStateException("activation failed")));

        assertThatThrownBy(() -> orchestrator.openStream(
                        API_KEY, source, "req_handoff_stream", policy()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("activation failed");

        verify(chat, never()).releasePublicAdmission(any());
    }

    @Test
    void releasesCompletionAdmissionWhenCancelledBeforeChatSubscriptionIsEstablished()
            throws InterruptedException {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        ChatCompletionService.PublicAdmission admission = admission();
        BlockingSubscriptionMono<ChatCompletionService.CompletionResult> chatExecution =
                new BlockingSubscriptionMono<>();
        BaseSubscriber<ChatCompletionService.CompletionResult> subscriber = new BaseSubscriber<>() { };
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(chat.releasePublicAdmission(admission)).thenReturn(Mono.empty());
        when(normalizer.normalize(source, API_KEY))
                .thenReturn(Mono.just(normalizedText(source)));
        when(chat.completeRouted(
                eq(API_KEY), eq(source), eq("req_pre_subscribe_cancel_complete"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any(), eq(admission)))
                .thenReturn(chatExecution);

        Thread subscriptionThread = new Thread(() -> orchestrator.complete(
                API_KEY, source, "req_pre_subscribe_cancel_complete", policy()).subscribe(subscriber));
        subscriptionThread.start();
        try {
            assertThat(chatExecution.awaitSubscribeEntered()).isTrue();
            assertThat(chatExecution.hasSignalledOnSubscribe()).isFalse();

            subscriber.cancel();

            verify(chat, timeout(1_000)).releasePublicAdmission(admission);
            verify(chat).releasePublicAdmission(admission);
        } finally {
            chatExecution.allowOnSubscribe();
            subscriptionThread.join(1_000);
        }
        assertThat(subscriptionThread.isAlive()).isFalse();
    }

    @Test
    void releasesStreamAdmissionWhenCancelledBeforeChatSubscriptionIsEstablished()
            throws InterruptedException {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request().put("stream", true);
        ChatCompletionService.PublicAdmission admission = admission();
        BlockingSubscriptionMono<Flux<String>> chatExecution = new BlockingSubscriptionMono<>();
        BaseSubscriber<Flux<String>> subscriber = new BaseSubscriber<>() { };
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(chat.releasePublicAdmission(admission)).thenReturn(Mono.empty());
        when(normalizer.normalize(source, API_KEY))
                .thenReturn(Mono.just(normalizedText(source)));
        when(chat.openRoutedStream(
                eq(API_KEY), eq(source), eq("req_pre_subscribe_cancel_stream"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any(), eq(admission)))
                .thenReturn(chatExecution);

        Thread subscriptionThread = new Thread(() -> orchestrator.openStream(
                API_KEY, source, "req_pre_subscribe_cancel_stream", policy()).subscribe(subscriber));
        subscriptionThread.start();
        try {
            assertThat(chatExecution.awaitSubscribeEntered()).isTrue();
            assertThat(chatExecution.hasSignalledOnSubscribe()).isFalse();

            subscriber.cancel();

            verify(chat, timeout(1_000)).releasePublicAdmission(admission);
            verify(chat).releasePublicAdmission(admission);
        } finally {
            chatExecution.allowOnSubscribe();
            subscriptionThread.join(1_000);
        }
        assertThat(subscriptionThread.isAlive()).isFalse();
    }

    @Test
    void rejectsExtractorVersionMismatchBeforeNormalizingCompletion() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        MultimodalRequestNormalizer.NormalizedRequest normalized =
                new MultimodalRequestNormalizer.NormalizedRequest(
                        source, MultimodalInputClassifier.InputProfile.TEXT,
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT));
        CompositeModelPolicyService.CompositePolicy policy =
                new CompositeModelPolicyService.CompositePolicy(
                        "multimodal-basic", "text-basic", "vision-basic", "file-basic",
                        "vision-basic", "multimodal-v2");
        ChatCompletionService.CompletionResult result =
                new ChatCompletionService.CompletionResult(
                        JSON.createObjectNode().put("model", "multimodal-basic"), "req_mismatch");
        when(normalizer.normalize(source, API_KEY)).thenReturn(Mono.just(normalized));
        when(chat.completeRouted(
                eq(API_KEY), any(), eq("req_mismatch"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any()))
                .thenReturn(Mono.just(result));

        assertThatThrownBy(() -> orchestrator
                .complete(API_KEY, source, "req_mismatch", policy).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("multimodal_route_unavailable");
                });

        verify(normalizer, never()).normalize(any(), any());
        verify(chat, never()).admitPublic(any(), any());
        verify(chat, never()).completeRouted(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsExtractorVersionMismatchBeforeNormalizingStream() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request().put("stream", true);
        MultimodalRequestNormalizer.NormalizedRequest normalized =
                new MultimodalRequestNormalizer.NormalizedRequest(
                        source, MultimodalInputClassifier.InputProfile.TEXT,
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT));
        CompositeModelPolicyService.CompositePolicy policy =
                new CompositeModelPolicyService.CompositePolicy(
                        "multimodal-basic", "text-basic", "vision-basic", "file-basic",
                        "vision-basic", "multimodal-v2");
        when(normalizer.normalize(source, API_KEY)).thenReturn(Mono.just(normalized));
        when(chat.openRoutedStream(
                eq(API_KEY), any(), eq("req_stream_mismatch"),
                eq("multimodal-basic"), eq("text-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT)), any()))
                .thenReturn(Mono.just(Flux.just("[DONE]")));

        assertThatThrownBy(() -> orchestrator
                .openStream(API_KEY, source, "req_stream_mismatch", policy).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("multimodal_route_unavailable");
                });

        verify(normalizer, never()).normalize(any(), any());
        verify(chat, never()).admitPublic(any(), any());
        verify(chat, never()).openRoutedStream(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("profileRoutes")
    void routesEachInputProfileThroughItsConfiguredComponent(
            MultimodalInputClassifier.InputProfile profile,
            Set<ChannelRouter.Capability> capabilities,
            String expectedRoutingModel) {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request();
        MultimodalRequestNormalizer.NormalizedRequest normalized =
                new MultimodalRequestNormalizer.NormalizedRequest(
                        source, profile, "multimodal-basic", "ignored", capabilities);
        CompositeModelPolicyService.CompositePolicy policy =
                new CompositeModelPolicyService.CompositePolicy(
                        "multimodal-basic", "text-route", "image-route", "file-route",
                        "mixed-route", "multimodal-v1");
        ChatCompletionService.CompletionResult expected =
                new ChatCompletionService.CompletionResult(
                        JSON.createObjectNode().put("model", "multimodal-basic"), "req_profile");
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(normalizer.normalize(source, API_KEY)).thenReturn(Mono.just(normalized));
        when(chat.completeRouted(
                eq(API_KEY), any(), eq("req_profile"),
                eq("multimodal-basic"), eq(expectedRoutingModel),
                eq(capabilities), any(), eq(admission)))
                .thenReturn(Mono.just(expected));

        assertThat(orchestrator.complete(API_KEY, source, "req_profile", policy).block())
                .isEqualTo(expected);
    }

    @Test
    void loadsOnlyAValidatedCompositePolicyWithEnabledComponents() {
        CompositeModelPolicyService.PolicyStore store =
                mock(CompositeModelPolicyService.PolicyStore.class);
        when(store.load("multimodal-basic")).thenReturn(Mono.just(
                new CompositeModelPolicyService.PolicyRow("COMPOSITE", """
                        {"textModel":"text-basic","imageModel":"vision-basic",
                         "fileModel":"file-basic","mixedFinalModel":"vision-basic",
                         "extractorVersion":"multimodal-v1"}
                        """)));
        when(store.enabledAliases(Set.of("text-basic", "vision-basic", "file-basic")))
                .thenReturn(Mono.just(Set.of("text-basic", "vision-basic", "file-basic")));
        CompositeModelPolicyService service =
                new CompositeModelPolicyService(store, JSON);

        CompositeModelPolicyService.ModelRouting routing =
                service.resolve("multimodal-basic").block();

        assertThat(routing.strategy())
                .isEqualTo(CompositeModelPolicyService.RoutingStrategy.COMPOSITE);
        assertThat(routing.compositePolicy().textModel()).isEqualTo("text-basic");
        assertThat(routing.compositePolicy().mixedFinalModel()).isEqualTo("vision-basic");
    }

    @Test
    void rejectsUnknownPolicyKeysAndUnavailableComponentsWithoutLeakingPolicy() {
        CompositeModelPolicyService.PolicyStore store =
                mock(CompositeModelPolicyService.PolicyStore.class);
        when(store.load("multimodal-basic")).thenReturn(Mono.just(
                new CompositeModelPolicyService.PolicyRow("COMPOSITE", """
                        {"textModel":"text-basic","imageModel":"vision-basic",
                         "fileModel":"file-basic","mixedFinalModel":"vision-basic",
                         "extractorVersion":"multimodal-v1","unexpected":"secret"}
                        """)));
        CompositeModelPolicyService service =
                new CompositeModelPolicyService(store, JSON);

        assertThatThrownBy(() -> service.resolve("multimodal-basic").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("multimodal_route_unavailable");
                    assertThat(error.getMessage()).doesNotContain("unexpected").doesNotContain("secret");
                });
    }

    @Test
    void dispatchesDirectAndCompositeRequestsWithoutChangingTheDirectPath() {
        CompositeModelPolicyService policies = mock(CompositeModelPolicyService.class);
        CompositeModelOrchestrator orchestrator = mock(CompositeModelOrchestrator.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompletionDispatchService dispatch =
                new CompletionDispatchService(policies, orchestrator, chat);
        ObjectNode directBody = request().put("model", "text-basic");
        ObjectNode compositeBody = request();
        ApiKeyService.ApiKeyContext directKey = new ApiKeyService.ApiKeyContext(
                API_KEY.apiKeyId(), API_KEY.tenantId(), API_KEY.userId(),
                API_KEY.packageInstanceId(), Set.of("text-basic"));
        ChatCompletionService.CompletionResult directResult =
                new ChatCompletionService.CompletionResult(
                        JSON.createObjectNode().put("model", "text-basic"), "req_direct");
        ChatCompletionService.CompletionResult compositeResult =
                new ChatCompletionService.CompletionResult(
                        JSON.createObjectNode().put("model", "multimodal-basic"), "req_composite");
        CompositeModelPolicyService.CompositePolicy policy =
                new CompositeModelPolicyService.CompositePolicy(
                        "multimodal-basic", "text-basic", "vision-basic", "file-basic",
                        "vision-basic", "multimodal-v1");
        when(policies.resolve("text-basic"))
                .thenReturn(Mono.just(CompositeModelPolicyService.ModelRouting.direct()));
        when(policies.resolve("multimodal-basic"))
                .thenReturn(Mono.just(CompositeModelPolicyService.ModelRouting.composite(policy)));
        when(chat.complete(directKey, directBody, "req_direct"))
                .thenReturn(Mono.just(directResult));
        when(orchestrator.complete(API_KEY, compositeBody, "req_composite", policy))
                .thenReturn(Mono.just(compositeResult));

        assertThat(dispatch.complete(directKey, directBody, "req_direct").block())
                .isEqualTo(directResult);
        assertThat(dispatch.complete(API_KEY, compositeBody, "req_composite").block())
                .isEqualTo(compositeResult);

        verify(chat).complete(directKey, directBody, "req_direct");
        verify(orchestrator).complete(API_KEY, compositeBody, "req_composite", policy);
        verify(chat, never()).complete(API_KEY, compositeBody, "req_composite");
    }

    @Test
    void finishesImagePreprocessingBeforeOpeningTheRoutedStream() {
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        CompositeModelOrchestrator orchestrator =
                new CompositeModelOrchestrator(normalizer, chat, properties("multimodal-v1"));
        ObjectNode source = request().put("stream", true);
        MultimodalRequestNormalizer.NormalizedRequest normalized =
                new MultimodalRequestNormalizer.NormalizedRequest(
                        source, MultimodalInputClassifier.InputProfile.TEXT_IMAGE_FILE,
                        "multimodal-basic", "vision-basic",
                        Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE));
        CompositeModelPolicyService.CompositePolicy policy =
                new CompositeModelPolicyService.CompositePolicy(
                        "multimodal-basic", "text-basic", "vision-basic", "file-basic",
                        "vision-basic", "multimodal-v1");
        Sinks.One<MultimodalRequestNormalizer.NormalizedRequest> preprocessing = Sinks.one();
        ChatCompletionService.PublicAdmission admission = admission();
        when(chat.admitPublic(API_KEY, "multimodal-basic"))
                .thenReturn(Mono.just(admission));
        when(normalizer.normalize(source, API_KEY)).thenReturn(preprocessing.asMono());
        when(chat.openRoutedStream(
                eq(API_KEY), any(), eq("req_stream"),
                eq("multimodal-basic"), eq("vision-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE)),
                any(), eq(admission)))
                .thenReturn(Mono.just(Flux.just("chunk", "[DONE]")));
        AtomicReference<Flux<String>> opened = new AtomicReference<>();

        orchestrator.openStream(API_KEY, source, "req_stream", policy).subscribe(opened::set);

        assertThat(opened).hasNullValue();
        verify(chat, never()).openRoutedStream(any(), any(), any(), any(), any(), any(), any(), any());

        preprocessing.tryEmitValue(normalized).orThrow();

        assertThat(opened.get().collectList().block()).containsExactly("chunk", "[DONE]");
        verify(chat).openRoutedStream(
                eq(API_KEY), eq(normalized.body()), eq("req_stream"),
                eq("multimodal-basic"), eq("vision-basic"),
                eq(Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE)),
                any(), eq(admission));
    }

    private static ObjectNode request() {
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        body.putArray("messages").addObject()
                .put("role", "user").put("content", "summarize");
        return body;
    }

    private static MultimodalProperties properties(String extractorVersion) {
        MultimodalProperties properties = new MultimodalProperties();
        properties.setExtractorVersion(extractorVersion);
        return properties;
    }

    private static MultimodalRequestNormalizer.NormalizedRequest normalizedText(ObjectNode body) {
        return new MultimodalRequestNormalizer.NormalizedRequest(
                body, MultimodalInputClassifier.InputProfile.TEXT,
                "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT));
    }

    private static CompositeModelPolicyService.CompositePolicy policy() {
        return new CompositeModelPolicyService.CompositePolicy(
                "multimodal-basic", "text-basic", "vision-basic", "file-basic",
                "vision-basic", "multimodal-v1");
    }

    private static ChatCompletionService.PublicAdmission admission() {
        RequestLimitService.Thresholds rpm =
                new RequestLimitService.Thresholds(60, 60, 60, Integer.MAX_VALUE, 60);
        RequestLimitService.Thresholds concurrency =
                new RequestLimitService.Thresholds(10, 10, 10, Integer.MAX_VALUE, 10);
        ChatCompletionService.PreparedPublicRequest prepared =
                new ChatCompletionService.PreparedPublicRequest(
                        UUID.randomUUID(), "multimodal-basic", 32_768, rpm, concurrency);
        RequestLimitService.LimitLease lease = new RequestLimitService.LimitLease(
                UUID.randomUUID(), API_KEY.apiKeyId(), API_KEY.userId(),
                "multimodal-basic", UUID.randomUUID(), 60, 10,
                RequestLimitService.Store.REDIS);
        return new ChatCompletionService.PublicAdmission(prepared, lease);
    }

    private static final class BlockingSubscriptionMono<T> extends Mono<T> {

        private final CountDownLatch subscribeEntered = new CountDownLatch(1);
        private final CountDownLatch allowOnSubscribe = new CountDownLatch(1);
        private final AtomicBoolean onSubscribeSignalled = new AtomicBoolean();

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            subscribeEntered.countDown();
            try {
                allowOnSubscribe.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Operators.error(actual, error);
                return;
            }
            onSubscribeSignalled.set(true);
            actual.onSubscribe(Operators.emptySubscription());
        }

        private boolean awaitSubscribeEntered() throws InterruptedException {
            return subscribeEntered.await(1, TimeUnit.SECONDS);
        }

        private boolean hasSignalledOnSubscribe() {
            return onSubscribeSignalled.get();
        }

        private void allowOnSubscribe() {
            allowOnSubscribe.countDown();
        }
    }

    private static Stream<Arguments> publicAdmissionRejections() {
        return Stream.of(
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"),
                Arguments.of(HttpStatus.UNPROCESSABLE_ENTITY, "active_package_required"),
                Arguments.of(HttpStatus.UNPROCESSABLE_ENTITY, "quota_exhausted"));
    }

    private static Stream<Arguments> profileRoutes() {
        return Stream.of(
                Arguments.of(
                        MultimodalInputClassifier.InputProfile.TEXT,
                        Set.of(ChannelRouter.Capability.TEXT), "text-route"),
                Arguments.of(
                        MultimodalInputClassifier.InputProfile.TEXT_IMAGE,
                        Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE),
                        "image-route"),
                Arguments.of(
                        MultimodalInputClassifier.InputProfile.TEXT_FILE,
                        Set.of(ChannelRouter.Capability.TEXT), "file-route"),
                Arguments.of(
                        MultimodalInputClassifier.InputProfile.TEXT_IMAGE_FILE,
                        Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE),
                        "mixed-route"));
    }
}
