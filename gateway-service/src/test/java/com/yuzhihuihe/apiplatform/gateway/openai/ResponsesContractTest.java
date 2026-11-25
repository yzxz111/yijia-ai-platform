package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.ProviderFileBindingService;
import com.yuzhihuihe.apiplatform.gateway.file.ResponsesRequestPreparer;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.safety.OutputSafetyInspector;
import com.yuzhihuihe.apiplatform.gateway.safety.SafetyStreamScanner;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ResponsesContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final ApiKeyService.ApiKeyContext API_KEY = new ApiKeyService.ApiKeyContext(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            UUID.fromString("20000000-0000-4000-8000-000000000001"),
            UUID.fromString("30000000-0000-4000-8000-000000000001"),
            UUID.fromString("40000000-0000-4000-8000-000000000001"),
            Set.of("file-basic"));

    @Test
    void replacesOwnedFileIdsWithTheSelectedChannelsProviderBinding() {
        ProviderFileBindingService bindings = mock(ProviderFileBindingService.class);
        ResponsesService service = service(bindings);
        FileObjectRepository.FileObject file = file();
        ObjectNode chatBody = request(false);
        ResponsesRequestPreparer.PreparedResponseRequest prepared =
                new ResponsesRequestPreparer.PreparedResponseRequest(
                        chatBody, List.of(file),
                        Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.FILE), false);
        ProviderAdapter adapter = mock(ProviderAdapter.class);
        ChatCompletionService.RouteTarget target = target();
        when(bindings.bind(file, adapter, target.channel())).thenReturn(Mono.just("upstream-file-7"));

        ObjectNode providerBody = (ObjectNode) service.prepareProviderBody(
                prepared, chatBody, target, adapter).block(Duration.ofSeconds(1));

        assertThat(providerBody.path("messages").get(0).path("content").get(0)
                .path("file_id").asText()).isEqualTo("upstream-file-7");
    }

    @Test
    void mapsChatCompletionOutputToAResponsesEnvelope() {
        ResponsesService service = service(mock(ProviderFileBindingService.class));

        ObjectNode response = (ObjectNode) service.toResponse(
                new ChatCompletionService.CompletionResult(chatResponse(), "req_responses"));

        assertThat(response.path("object").asText()).isEqualTo("response");
        assertThat(response.path("status").asText()).isEqualTo("completed");
        assertThat(response.path("output").get(0).path("content").get(0)
                .path("text").asText()).isEqualTo("answer");
        assertThat(response.path("usage").path("total_tokens").asLong()).isEqualTo(30);
    }

    @Test
    void routesPreparedResponsesThroughTheCompletionDispatcher() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                API_KEY.apiKeyId(), API_KEY.tenantId(), API_KEY.userId(),
                API_KEY.packageInstanceId(), Set.of("multimodal-basic"));
        ResponsesRequestPreparer preparer = mock(ResponsesRequestPreparer.class);
        CompletionDispatchService dispatch = mock(CompletionDispatchService.class);
        ObjectNode chatBody = request(false);
        chatBody.put("model", "multimodal-basic");
        ResponsesRequestPreparer.PreparedResponseRequest prepared =
                new ResponsesRequestPreparer.PreparedResponseRequest(
                        chatBody, List.of(), Set.of(ChannelRouter.Capability.TEXT), false);
        ObjectNode chatResponse = chatResponse();
        chatResponse.put("model", "multimodal-basic");
        ObjectNode responsesBody = JSON.createObjectNode().put("model", "multimodal-basic");
        when(preparer.prepare(eq(compositeKey), any())).thenReturn(Mono.just(prepared));
        when(dispatch.completePrepared(
                eq(compositeKey), eq(responsesBody), eq("req_dispatch"), any()))
                .thenAnswer(invocation -> invocation
                        .<Mono<CompletionDispatchService.PreparedRequest>>getArgument(3)
                        .map(request -> {
                            assertThat(request.body()).isSameAs(chatBody);
                            assertThat(request.capabilities())
                                    .containsExactly(ChannelRouter.Capability.TEXT);
                            return new ChatCompletionService.CompletionResult(
                                    chatResponse, "req_dispatch");
                        }));
        ResponsesService service = new ResponsesService(
                preparer, dispatch, mock(ProviderFileBindingService.class),
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));

        ObjectNode result = (ObjectNode) service.complete(
                compositeKey, responsesBody, "req_dispatch").block();

        assertThat(result.path("model").asText()).isEqualTo("multimodal-basic");
        verify(dispatch).completePrepared(
                eq(compositeKey), eq(responsesBody), eq("req_dispatch"), any());
    }

    @Test
    void exposesNormalAndStreamingResponsesEndpoints() {
        ResponsesService service = mock(ResponsesService.class);
        when(service.complete(eq(API_KEY), any(), eq("req_responses_test")))
                .thenReturn(Mono.just(responseEnvelope()));
        when(service.openStream(eq(API_KEY), any(), eq("req_responses_test")))
                .thenReturn(Mono.just(Flux.just(
                        "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}",
                        "{\"type\":\"response.completed\"}")));
        WebTestClient web = responsesClient(service);

        web.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"file-basic\",\"input\":\"hello\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("response")
                .jsonPath("$.status").isEqualTo("completed");

        web.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"file-basic\",\"input\":\"hello\",\"stream\":true}")
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("response.output_text.delta")
                        .contains("response.completed"));
    }

    @Test
    void malformedJsonUsesOpenAiInvalidRequestEnvelope() {
        ResponsesService service = mock(ResponsesService.class);
        WebTestClient web = WebTestClient.bindToController(new ResponsesController(service))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, API_KEY);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_responses_test");
                    return chain.filter(exchange);
                }).build();

        web.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("x-request-id", "req_responses_test")
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_request")
                .jsonPath("$.request_id").isEqualTo("req_responses_test");
    }

    @Test
    void compositeFailuresUseTheSharedStableErrorContract() {
        ResponsesService service = mock(ResponsesService.class);
        when(service.complete(any(), any(), any())).thenReturn(Mono.error(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "multimodal_route_unavailable",
                        new IllegalStateException("sensitive-policy-json"))));

        responsesClient(service).post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("multimodal_route_unavailable")
                .jsonPath("$.error.message").value(message ->
                        assertThat(message.toString()).doesNotContain("sensitive-policy-json"));
    }

    @Test
    void providerFailureBeforeFirstResponseEventUsesHttpErrorEnvelope() {
        ResponsesRequestPreparer preparer = mock(ResponsesRequestPreparer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        ResponsesRequestPreparer.PreparedResponseRequest prepared =
                new ResponsesRequestPreparer.PreparedResponseRequest(
                        request(true), List.of(), Set.of(ChannelRouter.Capability.TEXT), true);
        when(preparer.prepare(eq(API_KEY), any())).thenReturn(Mono.just(prepared));
        when(chat.openPreparedStream(eq(API_KEY), any(), eq("req_responses_test"), any(), any()))
                .thenReturn(Mono.just(Flux.error(
                        new ProviderAdapter.ProviderException(502, "provider_unavailable"))));
        ResponsesService service = new ResponsesService(
                preparer, chat, mock(ProviderFileBindingService.class),
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));
        WebTestClient web = WebTestClient.bindToController(new ResponsesController(service))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, API_KEY);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_responses_test");
                    return chain.filter(exchange);
                }).build();

        web.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"file-basic\",\"input\":\"hello\",\"stream\":true}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("provider_error")
                .jsonPath("$.request_id").isEqualTo("req_responses_test");
    }

    @Test
    void providerRateLimitBeforeFirstResponseEventPreservesHttp429() {
        ResponsesRequestPreparer preparer = mock(ResponsesRequestPreparer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        ResponsesRequestPreparer.PreparedResponseRequest prepared =
                new ResponsesRequestPreparer.PreparedResponseRequest(
                        request(true), List.of(), Set.of(ChannelRouter.Capability.TEXT), true);
        when(preparer.prepare(eq(API_KEY), any())).thenReturn(Mono.just(prepared));
        when(chat.openPreparedStream(eq(API_KEY), any(), eq("req_responses_test"), any(), any()))
                .thenReturn(Mono.just(Flux.error(
                        new ProviderAdapter.ProviderException(429, "provider_rate_limited"))));
        ResponsesService service = new ResponsesService(
                preparer, chat, mock(ProviderFileBindingService.class),
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));
        WebTestClient web = WebTestClient.bindToController(new ResponsesController(service))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, API_KEY);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_responses_test");
                    return chain.filter(exchange);
                }).build();

        web.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"file-basic\",\"input\":\"hello\",\"stream\":true}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("rate_limit_error")
                .jsonPath("$.error.code").isEqualTo("provider_error")
                .jsonPath("$.request_id").isEqualTo("req_responses_test");
    }

    @Test
    void providerAuthenticationFailureBeforeFirstResponseEventUsesHttp502() {
        ResponsesRequestPreparer preparer = mock(ResponsesRequestPreparer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        ResponsesRequestPreparer.PreparedResponseRequest prepared =
                new ResponsesRequestPreparer.PreparedResponseRequest(
                        request(true), List.of(), Set.of(ChannelRouter.Capability.TEXT), true);
        when(preparer.prepare(eq(API_KEY), any())).thenReturn(Mono.just(prepared));
        when(chat.openPreparedStream(eq(API_KEY), any(), eq("req_responses_test"), any(), any()))
                .thenReturn(Mono.just(Flux.error(
                        new ProviderAdapter.ProviderException(401, "provider_authentication_failed"))));
        ResponsesService service = new ResponsesService(
                preparer, chat, mock(ProviderFileBindingService.class),
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));
        WebTestClient web = WebTestClient.bindToController(new ResponsesController(service))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, API_KEY);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_responses_test");
                    return chain.filter(exchange);
                }).build();

        web.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"file-basic\",\"input\":\"hello\",\"stream\":true}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("provider_error");
    }

    /**
     * 聊天侧的失败帧必须变成 {@code response.failed}，并且**压过** {@code response.completed}。
     *
     * <p>这正是交接方案四.2 点名的那个转换层缺口：错误帧没有
     * {@code choices[0].delta.content}，所以旧实现把它当普通块丢掉，再因为聊天流正常
     * EOF 而追加 {@code response.completed} —— 一次断流于是被重新包装成一次成功，
     * 客户端只看到「任务完成」而没有任何产出。
     */
    @Test
    void chatFailureFrameBecomesResponseFailedAndSuppressesCompleted() {
        ResponsesService service = streamingServiceForChatStream(Flux.just(
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"file-basic\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"half an answer\"}}]}",
                "{\"error\":{\"message\":\"Upstream provider request failed\","
                        + "\"type\":\"server_error\",\"code\":\"provider_error\","
                        + "\"reason\":\"provider_stream_missing_done\"},"
                        + "\"request_id\":\"req_responses_stream_failure\"}",
                "[DONE]"));

        String body = streamBody(service);

        assertThat(body).contains("response.output_text.delta")
                .contains("half an answer")
                .contains("response.failed")
                .contains("provider_stream_missing_done")
                .contains("req_responses_stream_failure")
                // **[DONE] 与 response.completed 都必须缺席。** 前者是聊天侧的「完整结束」
                // 标记，后者是 Responses 的「成功终态」；失败之后发出任何一个都是在说谎。
                .doesNotContain("response.completed")
                .doesNotContain("[DONE]");
    }

    @Test
    void responseFailedIsNotEmittedForACleanStream() {
        ResponsesService service = streamingServiceForChatStream(Flux.just(
                "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"file-basic\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"complete answer\"}}]}",
                "[DONE]"));

        String body = streamBody(service);

        assertThat(body).contains("response.completed")
                .contains("complete answer")
                .doesNotContain("response.failed");
    }

    @Test
    void prefixMetadataChunksAreIgnoredWithoutFailingTheStream() {
        ResponsesService service = streamingServiceForChatStream(Flux.just(
                "{\"id\":\"chatcmpl-3\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"file-basic\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\"}}]}",
                "{\"id\":\"chatcmpl-3\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"file-basic\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,"
                        + "\"total_tokens\":3}}",
                "[DONE]"));

        String body = streamBody(service);

        assertThat(body).contains("response.completed").doesNotContain("response.failed");
    }

    /**
     * 聊天侧被输出上限切断时，Responses 必须报 {@code response.incomplete}，不能报 completed。
     *
     * <p>与失败帧同一个道理：把只写了一半的回答说成 {@code completed}，客户端会把一个只做了
     * 一半的任务当成做完了。注意截断**不带失败帧**——命中长度上限不是上游故障，因此终态是
     * {@code incomplete} + {@code max_output_tokens}，而不是 {@code failed}。
     */
    @Test
    void truncatedChatStreamBecomesResponseIncompleteInsteadOfCompleted() throws Exception {
        ResponsesService service = streamingServiceForChatStream(Flux.just(
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"file-basic\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"half an answer\"}}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"file-basic\",\"choices\":[{\"index\":0,\"delta\":{},"
                        + "\"finish_reason\":\"length\"}]}"));

        String body = streamBody(service);

        assertThat(body).contains("half an answer")
                .contains("response.incomplete")
                .contains("max_output_tokens")
                .doesNotContain("response.completed")
                .doesNotContain("response.failed");
    }

    private static String streamBody(ResponsesService service) {
        return responsesClient(service).post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"file-basic\",\"input\":\"hello\",\"stream\":true}")
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();
    }

    /**
     * 一个会走真实 {@link ResponsesService#openStream} 转换链的实例：只有
     * {@code ResponsesRequestPreparer} 与聊天服务被替换掉，事件翻译本身是真的。
     *
     * <p>{@code isFailureFrame} 用「帧里有没有 error 对象」这一条真实判据打桩，而不是
     * 恒真——否则这些用例会在错误帧检测本身失效时依然通过。
     */
    private static ResponsesService streamingServiceForChatStream(Flux<String> chatStream) {
        ResponsesRequestPreparer preparer = mock(ResponsesRequestPreparer.class);
        ChatCompletionService chat = mock(ChatCompletionService.class);
        ResponsesRequestPreparer.PreparedResponseRequest prepared =
                new ResponsesRequestPreparer.PreparedResponseRequest(
                        request(true), List.of(), Set.of(ChannelRouter.Capability.TEXT), true);
        when(preparer.prepare(eq(API_KEY), any())).thenReturn(Mono.just(prepared));
        when(chat.openPreparedStream(eq(API_KEY), any(), eq("req_responses_test"), any(), any()))
                .thenReturn(Mono.just(chatStream));
        when(chat.isFailureFrame(any())).thenAnswer(invocation -> {
            JsonNode value = invocation.getArgument(0);
            return value != null && value.isObject() && value.path("error").isObject();
        });
        when(chat.isTruncatedFrame(any())).thenAnswer(invocation -> {
            JsonNode value = invocation.getArgument(0);
            return value != null && "length".equals(
                    value.path("choices").path(0).path("finish_reason").asText(""));
        });
        return new ResponsesService(
                preparer, chat, mock(ProviderFileBindingService.class),
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WebTestClient responsesClient(ResponsesService service) {
        OutputSafetyInspector outputSafety = mock(OutputSafetyInspector.class);
        SafetyStreamScanner streamSafety = mock(SafetyStreamScanner.class);
        when(outputSafety.inspect(any(), any(), any()))
                .thenReturn(Mono.just(new OutputSafetyInspector.Decision(false, "allowed")));
        when(streamSafety.scan(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return WebTestClient.bindToController(
                        new ResponsesController(service, outputSafety, streamSafety))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, API_KEY);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_responses_test");
                    return chain.filter(exchange);
                }).build();
    }

    private static ResponsesService service(ProviderFileBindingService bindings) {
        return new ResponsesService(
                mock(ResponsesRequestPreparer.class), mock(ChatCompletionService.class), bindings,
                JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ObjectNode request(boolean stream) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", "file-basic");
        body.put("stream", stream);
        body.putArray("messages").addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_file").put("file_id", "file_owned");
        return body;
    }

    private static ObjectNode chatResponse() {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", "chatcmpl-1").put("object", "chat.completion")
                .put("created", NOW.getEpochSecond()).put("model", "file-basic");
        body.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop")
                .putObject("message").put("role", "assistant").put("content", "answer");
        body.putObject("usage").put("prompt_tokens", 10)
                .put("completion_tokens", 20).put("total_tokens", 30);
        return body;
    }

    private static ObjectNode responseEnvelope() {
        return JSON.createObjectNode().put("id", "resp_1")
                .put("object", "response").put("status", "completed");
    }

    private static FileObjectRepository.FileObject file() {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_owned", API_KEY.tenantId(), API_KEY.userId(),
                "files/" + UUID.randomUUID(), "owned.pdf", "0".repeat(64),
                "application/pdf", 10, "READY", NOW.plusSeconds(3600), null, NOW);
    }

    private static ChatCompletionService.RouteTarget target() {
        UUID channelId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                channelId, UUID.randomUUID(), "BAILIAN", "https://dashscope.example/v1",
                "secret", 5_000, "qwen-long");
        ChannelRouter.RouteCandidate candidate = new ChannelRouter.RouteCandidate(
                channelId, "bailian", "file-basic", "qwen-long",
                Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.FILE),
                100, 100, true, true, true, true, true);
        return new ChatCompletionService.RouteTarget(candidate, channel);
    }
}
