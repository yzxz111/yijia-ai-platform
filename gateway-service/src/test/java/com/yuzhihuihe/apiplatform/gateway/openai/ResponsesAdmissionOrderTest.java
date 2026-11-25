package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileService;
import com.yuzhihuihe.apiplatform.gateway.file.ProviderFileBindingService;
import com.yuzhihuihe.apiplatform.gateway.file.RemoteImageFetcher;
import com.yuzhihuihe.apiplatform.gateway.file.ResponsesRequestPreparer;
import com.yuzhihuihe.apiplatform.gateway.multimodal.CompositeModelOrchestrator;
import com.yuzhihuihe.apiplatform.gateway.multimodal.CompositeModelPolicyService;
import com.yuzhihuihe.apiplatform.gateway.multimodal.MultimodalProperties;
import com.yuzhihuihe.apiplatform.gateway.multimodal.MultimodalRequestNormalizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class ResponsesAdmissionOrderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final String PUBLIC_MODEL = "multimodal-basic";
    private static final ApiKeyService.ApiKeyContext API_KEY =
            new ApiKeyService.ApiKeyContext(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Set.of(PUBLIC_MODEL));
    private static final CompositeModelPolicyService.CompositePolicy POLICY =
            new CompositeModelPolicyService.CompositePolicy(
                    PUBLIC_MODEL, "text-basic", "vision-basic", "file-basic",
                    "vision-basic", "multimodal-v1");

    @ParameterizedTest
    @EnumSource(RequestMode.class)
    void rejectsCompositeAdmissionBeforeSubscribingToResponsesFiles(RequestMode mode) {
        AtomicInteger fileSubscriptions = new AtomicInteger();
        FileService files = mock(FileService.class);
        when(files.requireUsable(eq(API_KEY), eq("file_owned")))
                .thenReturn(Mono.defer(() -> {
                    fileSubscriptions.incrementAndGet();
                    return Mono.just(file());
                }));
        ResponsesRequestPreparer preparer = new ResponsesRequestPreparer(
                JSON, mock(FileContentValidator.class), mock(RemoteImageFetcher.class), files,
                20 * 1024 * 1024L, 8, 20 * 1024 * 1024L);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        when(chat.admitPublic(API_KEY, PUBLIC_MODEL)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded")));
        MultimodalRequestNormalizer normalizer = mock(MultimodalRequestNormalizer.class);
        CompositeModelOrchestrator orchestrator = new CompositeModelOrchestrator(
                normalizer, chat, new MultimodalProperties());
        CompositeModelPolicyService policies = mock(CompositeModelPolicyService.class);
        when(policies.resolve(PUBLIC_MODEL)).thenReturn(Mono.just(
                CompositeModelPolicyService.ModelRouting.composite(POLICY)));
        CompletionDispatchService dispatcher =
                new CompletionDispatchService(policies, orchestrator, chat);
        ResponsesService service = new ResponsesService(
                preparer, dispatcher, mock(ProviderFileBindingService.class),
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> mode.invoke(service, request(mode)).block(Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("rate_limit_exceeded");
                });

        assertThat(fileSubscriptions).hasValue(0);
        verify(chat).admitPublic(API_KEY, PUBLIC_MODEL);
        verify(files, never()).requireUsable(any(), any());
        verify(normalizer, never()).normalize(any(), any());
    }

    private static ObjectNode request(RequestMode mode) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", PUBLIC_MODEL).put("stream", mode == RequestMode.STREAM);
        body.putArray("input").addObject()
                .put("role", "user").putArray("content")
                .addObject().put("type", "input_file").put("file_id", "file_owned");
        return body;
    }

    private static FileObjectRepository.FileObject file() {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_owned", API_KEY.tenantId(), API_KEY.userId(),
                "files/owned", "owned.pdf", "0".repeat(64), "application/pdf", 10,
                "READY", NOW.plusSeconds(3600), null, NOW);
    }

    private enum RequestMode {
        COMPLETE {
            @Override
            Mono<?> invoke(ResponsesService service, ObjectNode body) {
                return service.complete(API_KEY, body, "req_complete");
            }
        },
        STREAM {
            @Override
            Mono<?> invoke(ResponsesService service, ObjectNode body) {
                return service.openStream(API_KEY, body, "req_stream");
            }
        };

        abstract Mono<?> invoke(ResponsesService service, ObjectNode body);
    }
}
