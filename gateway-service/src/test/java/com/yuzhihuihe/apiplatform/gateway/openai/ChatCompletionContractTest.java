package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.BailianAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.DeepSeekAdapter;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

class ChatCompletionContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID MODEL_ID = UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final UUID ROUTING_MODEL_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000008");
    private static final UUID CHANNEL_A = UUID.fromString("10000000-0000-4000-8000-000000000006");
    private static final UUID CHANNEL_B = UUID.fromString("10000000-0000-4000-8000-000000000007");

    private ApiKeyService.ApiKeyContext apiKey;
    private QuotaService quota;
    private RequestLimitService limits;
    private RecordingUsageWriter usage;
    private ChannelCircuitRegistry circuits;
    private List<String> accountingCalls;

    @BeforeEach
    void setUp() {
        apiKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));
        quota = mock(QuotaService.class);
        limits = mock(RequestLimitService.class);
        accountingCalls = new CopyOnWriteArrayList<>();
        usage = new RecordingUsageWriter(accountingCalls);
        circuits = new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults());

        when(limits.acquire(any())).thenAnswer(invocation -> {
            RequestLimitService.LimitCommand command = invocation.getArgument(0);
            return Mono.just(new RequestLimitService.LimitLease(
                    UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                    command.channelId(), 100, 10, RequestLimitService.Store.REDIS));
        });
        when(limits.acquirePublic(any())).thenAnswer(invocation -> {
            RequestLimitService.LimitCommand command = invocation.getArgument(0);
            return Mono.just(new RequestLimitService.LimitLease(
                    UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                    command.channelId(), 100, 10, RequestLimitService.Store.REDIS));
        });
        when(limits.rebindChannel(any(), any())).thenAnswer(invocation -> {
            RequestLimitService.LimitLease lease = invocation.getArgument(0);
            UUID channelId = invocation.getArgument(1);
            return Mono.just(new RequestLimitService.LimitLease(
                    lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(),
                    channelId, lease.rpmLimit(), lease.concurrencyLimit(), lease.store()));
        });
        when(limits.activateRoute(any(), any(), any(Integer.class), any(Integer.class)))
                .thenAnswer(invocation -> {
                    RequestLimitService.LimitLease lease = invocation.getArgument(0);
                    UUID channelId = invocation.getArgument(1);
                    return Mono.just(new RequestLimitService.LimitLease(
                            lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(),
                            channelId, lease.rpmLimit(), lease.concurrencyLimit(), lease.store()));
                });
        when(limits.release(any())).thenReturn(Mono.empty());
        when(quota.reserve(any())).thenAnswer(invocation -> {
            QuotaService.ReserveCommand command = invocation.getArgument(0);
            return Mono.just(new QuotaService.Reservation(
                    command.reservationId(), command.requestId(), PACKAGE_ID, command.modelCode(),
                    command.tokens(), "RESERVED", command.expiresAt()));
        });
        when(quota.settle(any(), any(), any())).thenAnswer(invocation -> Mono.just(
                new QuotaService.Settlement(
                        invocation.getArgument(0), 30, 30, 0, "SETTLED", invocation.getArgument(2)))
                .doOnSubscribe(ignored -> accountingCalls.add("settle")));
        when(quota.release(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void returnsOpenAiCompatibleUsageAndRequestId() {
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));
        ChatCompletionController controller = new ChatCompletionController(service);
        WebTestClient web = client(controller);

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("x-request-id", "req_[0-9a-f]{32}")
                .expectBody()
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.model").isEqualTo("text-basic")
                .jsonPath("$.usage.total_tokens").isEqualTo(30);

        verify(quota).settle(any(), eq(new QuotaService.TokenUsage(10, 20, 30)),
                eq(QuotaService.UsageQuality.ACTUAL));
        assertThat(usage.entries).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("SUCCEEDED");
            assertThat(entry.usage().totalTokens()).isEqualTo(30);
            assertThat(entry.upstreamModel()).isEqualTo("upstream-model");
        });
    }

    @Test
    void chatFailsClosedWithoutReleasingAMismatchedVideoReservation() {
        doAnswer(invocation -> {
            QuotaService.ReserveCommand command = invocation.getArgument(0);
            return Mono.just(new QuotaService.Reservation(
                    command.reservationId(), command.requestId(), PACKAGE_ID, command.modelCode(),
                    command.tokens(), "RESERVED", command.expiresAt(), "VIDEO_SECOND"));
        }).when(quota).reserve(any());
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_chat_video_dimension_mismatch")
                .block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("usage_dimension_mismatch");
                });

        verify(quota, never()).release(any(), any());
        verify(quota, never()).settle(any(), any(), any());
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void controllerUsesTheCompletionDispatcherForCompositeRequests() {
        apiKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        CompletionDispatchService dispatch = mock(CompletionDispatchService.class);
        ObjectNode body = request(false);
        body.put("model", "multimodal-basic");
        ObjectNode response = (ObjectNode) response(10, 20).body().deepCopy();
        response.put("model", "multimodal-basic");
        when(dispatch.complete(eq(apiKey), any(), any())).thenReturn(Mono.just(
                new ChatCompletionService.CompletionResult(response, "req_dispatch")));

        client(new ChatCompletionController(dispatch)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("multimodal-basic");

        verify(dispatch).complete(eq(apiKey), any(), any());
    }

    @Test
    void subtractsProviderAttemptTimeFromTheStoredPlatformLatency() {
        StubAdapter adapter = StubAdapter.complete("MOCK",
                Mono.delay(Duration.ofMillis(100)).thenReturn(response(10, 20)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.complete(apiKey, request(false), "req_platform_latency")
                .block(Duration.ofSeconds(3));

        assertThat(usage.entries).singleElement().satisfies(entry -> {
            assertThat(entry.durationMs()).isGreaterThanOrEqualTo(80);
            assertThat(entry.platformAddedLatencyMs()).isBetween(0L, entry.durationMs() - 1);
        });
    }

    @Test
    void includesResponseCriticalSettlementAndLeaseReleaseInPlatformLatency() {
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));
        service.complete(apiKey, request(false), "req_post_processing_warmup")
                .block(Duration.ofSeconds(3));
        usage.entries.clear();

        service.complete(apiKey, request(false), "req_post_processing_baseline")
                .block(Duration.ofSeconds(3));
        long baseline = usage.entries.getFirst().platformAddedLatencyMs();
        usage.entries.clear();

        when(quota.settle(any(), any(), any())).thenAnswer(invocation ->
                Mono.delay(Duration.ofMillis(60)).map(ignored -> new QuotaService.Settlement(
                        invocation.getArgument(0), 30, 30, 0, "SETTLED", invocation.getArgument(2))));
        when(limits.release(any())).thenReturn(Mono.delay(Duration.ofMillis(60)).then());

        service.complete(apiKey, request(false), "req_post_processing_latency")
                .block(Duration.ofSeconds(3));

        assertThat(usage.entries).singleElement().satisfies(entry ->
                assertThat(entry.platformAddedLatencyMs()).isGreaterThanOrEqualTo(baseline + 80));
    }

    @Test
    void subtractsProviderStreamTimeWhenTheClientCancels() throws Exception {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.never());
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        var subscription = service.stream(apiKey, request(true), "req_cancelled_platform_latency")
                .subscribe();
        Thread.sleep(120);
        subscription.dispose();

        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.outcome()).isEqualTo("CANCELLED");
        assertThat(entry.durationMs()).isGreaterThanOrEqualTo(80);
        assertThat(entry.platformAddedLatencyMs()).isLessThan(entry.durationMs() - 40);
    }

    @Test
    void emitsJsonSseChunksAndDoneWithStableMetadata() throws Exception {
        ProviderAdapter.ProviderChunk first = chunk("chatcmpl-1", 100, "hello", null);
        ProviderAdapter.ProviderChunk usageChunk = chunk(
                "chatcmpl-1", 100, null, new ProviderAdapter.TokenUsage(10, 20, 30));
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(first, usageChunk));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));
        ChatCompletionController controller = new ChatCompletionController(service);
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);

        List<String> data = client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(data).hasSize(3);
        JsonNode firstJson = JSON.readTree(data.get(0));
        JsonNode usageJson = JSON.readTree(data.get(1));
        assertThat(firstJson.path("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(firstJson.path("id").asText()).startsWith("chatcmpl-")
                .isEqualTo(usageJson.path("id").asText());
        assertThat(firstJson.path("created").asLong()).isPositive()
                .isEqualTo(usageJson.path("created").asLong());
        assertThat(data.get(2)).isEqualTo("[DONE]");
        verify(quota, timeout(1_000)).settle(any(), any(), eq(QuotaService.UsageQuality.ACTUAL));
    }

    @Test
    void theStreamPathAlsoReservesEveryCompletionWhileForwardingThePerCompletionLimit()
            throws Exception {
        ProviderAdapter.ProviderChunk contentChunk = chunk("chatcmpl-stream-n", 100, "hello", null);
        ProviderAdapter.ProviderChunk usageChunk = chunk(
                "chatcmpl-stream-n", 100, null, new ProviderAdapter.TokenUsage(10, 20, 30));
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(contentChunk, usageChunk));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));
        ObjectNode body = request(true);
        body.put("max_tokens", 64);
        body.put("n", ProviderAdapter.MAX_COMPLETIONS);
        ArgumentCaptor<QuotaService.ReserveCommand> reservation =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(3));

        JsonNode forwarded = adapter.requestBodies.getFirst();
        assertThat(forwarded.path("max_tokens").asInt(-1)).isEqualTo(64);
        verify(quota).reserve(reservation.capture());
        assertThat(reservation.getValue().outputTokens())
                .isEqualTo(ProviderAdapter.MAX_COMPLETIONS * 64L);
    }

    @Test
    void streamingSettlementPreservesStructuredLoggingContext() {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-context", 100, "hello", new ProviderAdapter.TokenUsage(10, 20, 30))));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_stream_context")
                .contextWrite(context -> context
                        .put("traceId", "trace-stream-context")
                        .put("apiKeyPrefix", "sk-yzh-streamctx"))
                .collectList()
                .block(Duration.ofSeconds(3));

        usage.awaitEntry();
        assertThat(usage.traceId.get()).isEqualTo("trace-stream-context");
        assertThat(usage.apiKeyPrefix.get()).isEqualTo("sk-yzh-streamctx");
    }

    @Test
    void normalizesStableMetadataAcrossEveryStreamChunk() throws Exception {
        ObjectNode firstData = (ObjectNode) chunk("provider-a", 100, "hello", null).data();
        firstData.put("object", "vendor.chunk");
        ObjectNode secondData = (ObjectNode) chunk("provider-b", 200, "world", null).data();
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(firstData, null),
                new ProviderAdapter.ProviderChunk(secondData, null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<JsonNode> chunks = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000020")
                .filter(value -> !"[DONE]".equals(value))
                .map(value -> {
                    try {
                        return JSON.readTree(value);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .collectList().block(Duration.ofSeconds(3));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(node -> node.path("id").asText()).containsOnly(
                "chatcmpl-00000000000000000000000000000020");
        assertThat(chunks).extracting(node -> node.path("created").asLong()).containsOnly(
                chunks.getFirst().path("created").asLong());
        assertThat(chunks).extracting(node -> node.path("object").asText()).containsOnly(
                "chat.completion.chunk");
        assertThat(chunks).extracting(node -> node.path("model").asText()).containsOnly("text-basic");
    }

    @Test
    void includeUsageAddsOneFinalUsageChunkBeforeDoneWhenProviderOmitsUsage() throws Exception {
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-no-usage", 100, "hello", null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> data = service.stream(
                        apiKey, body, "req_00000000000000000000000000000021")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(data).hasSize(3);
        JsonNode usageChunk = JSON.readTree(data.get(1));
        assertThat(usageChunk.path("choices").isArray()).isTrue();
        assertThat(usageChunk.path("choices").isEmpty()).isTrue();
        assertThat(usageChunk.path("usage").path("total_tokens").asLong()).isPositive();
        assertThat(data.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void includeUsageReplacesAllProviderUsageWithOneNormalizedFinalChunk() throws Exception {
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);
        ProviderAdapter.TokenUsage known = new ProviderAdapter.TokenUsage(10, 20, 30);
        ObjectNode contentData = (ObjectNode) chunk("provider-content", 100, "hello", known).data();
        ObjectNode usageData = (ObjectNode) chunk("provider-usage", 200, null, known).data();
        usageData.put("vendor_marker", "must-not-leak");
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(contentData, known),
                new ProviderAdapter.ProviderChunk(usageData, known)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> data = service.stream(
                        apiKey, body, "req_00000000000000000000000000000033")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(data).hasSize(3);
        JsonNode contentChunk = JSON.readTree(data.get(0));
        JsonNode usageChunk = JSON.readTree(data.get(1));
        assertThat(contentChunk.has("usage")).isFalse();
        assertThat(usageChunk.path("choices").isEmpty()).isTrue();
        assertThat(usageChunk.path("usage").path("total_tokens").asLong()).isEqualTo(30);
        assertThat(usageChunk.has("vendor_marker")).isFalse();
        assertThat(data.getLast()).isEqualTo("[DONE]");
    }

    /**
     * 生产帧序回归（2026-10-16）：上游天翼云在**长度截断**时多发的那一帧不得再杀掉整条流。
     *
     * <p>下面是生产实测抓到的真实帧序（{@code deepseek-v4.1-flash}，{@code max_tokens=200}）：
     *
     * <pre>
     * [内容块 ...]
     * {"id":"...","object":"chat.completion.chunk","model":"deepseek-v4.1-flash","choices":[]}
     * {"id":"...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}
     * {"id":"...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{}}],"usage":{...}}
     * [DONE]
     * </pre>
     *
     * <p>第二帧既没有 choices 元素、也没有 usage。此前 {@code validateStreamChunk} 要求「空 choices
     * 必须带 usage」，于是它被判成协议违规，整条**已经在产出内容**的流被收尾成失败帧：客户端拿到
     * 半个回答、没有 {@code [DONE]}，而 {@code finish_reason=length} 就在紧接的下一帧里、
     * 网关在读到它之前就抛了，所以这次截断从未被标记过。生产上 chat-deep 因此在 6 次调用里失败。
     *
     * <p>本用例钉住的是「客户端最终看到什么」：正文照旧、截断标记照旧、{@code [DONE]} 照旧。
     */
    @Test
    void toleratesTheEmptyChoicesFrameTheUpstreamEmitsOnLengthTruncation() throws Exception {
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);

        ObjectNode content = (ObjectNode) chunk("provider-content", 100, "TCP 三次握手", null).data();

        // 上游的长度分隔帧：空 choices，且**没有** usage。
        ObjectNode lengthSeparator = JSON.createObjectNode();
        lengthSeparator.put("id", "provider-separator");
        lengthSeparator.put("object", "chat.completion.chunk");
        lengthSeparator.put("created", 200);
        lengthSeparator.put("model", "upstream-model");
        lengthSeparator.putArray("choices");

        // 结束帧：网关正是靠这一帧才知道「被截断了」。
        ObjectNode finish = JSON.createObjectNode();
        finish.put("id", "provider-finish");
        finish.put("object", "chat.completion.chunk");
        finish.put("created", 300);
        finish.put("model", "upstream-model");
        finish.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta");
        ((ObjectNode) finish.withArray("choices").get(0)).put("finish_reason", "length");

        // 用量尾帧：空 choices + usage，一直是合法形态。
        ProviderAdapter.TokenUsage known = new ProviderAdapter.TokenUsage(7, 11, 18);
        ObjectNode usageTail = (ObjectNode) chunk("provider-usage", 400, null, known).data();

        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(content, null),
                new ProviderAdapter.ProviderChunk(lengthSeparator, null),
                new ProviderAdapter.ProviderChunk(finish, null),
                new ProviderAdapter.ProviderChunk(usageTail, known)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> frames = service.stream(
                        apiKey, body, "req_00000000000000000000000000000040")
                .collectList().block(Duration.ofSeconds(3));

        // 先看整条响应的形状：[DONE] 恰好一次、且必须是最后一帧；没有失败帧。
        // 不按帧数断言——上游的用量尾帧会被跳过，而网关自己合成的用量尾帧仍在，
        // 于是帧数取决于 include_usage 与上游是否自带 usage，钉死它只会让用例变脆。
        assertThat(frames.stream().filter("[DONE]"::equals).count()).isEqualTo(1);
        assertThat(frames.getLast()).isEqualTo("[DONE]");
        assertThat(frames).noneMatch(frame -> frame.contains("\"error\""));

        // 再看网关转发给客户端的**内容帧**（choices 里有 delta 的那些，按出现顺序）。
        List<JsonNode> bodyFrames = frames.stream()
                .filter(frame -> !"[DONE]".equals(frame))
                .map(frame -> {
                    try {
                        return JSON.readTree(frame);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .filter(frame -> frame.path("choices").path(0).has("delta"))
                .toList();

        assertThat(bodyFrames).hasSize(2);
        assertThat(bodyFrames.get(0).path("choices").path(0).path("delta").path("content").asText())
                .isEqualTo("TCP 三次握手");
        // 截断标记必须活着到达客户端：这正是从前被吞掉的那个信号，
        // 而且它就在紧接空 choices 那一帧的下一帧里——从前网关在读到它之前就抛了。
        assertThat(bodyFrames.get(1).path("choices").path(0).path("finish_reason").asText())
                .isEqualTo("length");

        // 上游那个空 choices 的分隔帧没有被转发给客户端（它没有 delta，因此不在 bodyFrames 里）。
        assertThat(bodyFrames).noneMatch(frame -> frame.path("choices").isEmpty());
    }

    /**
     * 容忍空 {@code choices} 的配套保障：一条**只由无信息填充帧组成**的流必须仍然失败。
     *
     * <p>这是「容忍」与「放行」的分界。服务层把「空 {@code choices} 且无 {@code usage}」的纯填充帧
     * 跳过（不发给客户），于是整条流**一帧都没转发出去**，被执行器既有的 {@code empty_provider_stream}
     * 守卫接住。若既不跳过也不判，它会一路跑到 {@code [DONE]}，把一个零内容的上游故障包装成一次成功
     * ——而 {@code [DONE]} 的语义正是「这段回答完整地结束了」。这正是本次修复要消灭的假完成信号，
     * 只是方向相反。
     *
     * <p>这里刻意断言异常**在首字节之前**抛出：那样这次失败还换得了渠道，比发一帧失败帧给客户更好。
     */
    @Test
    void streamOfOnlyContentLessPaddingFailsBeforeTheFirstByte() {
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);

        // 上游的长度分隔帧形态，出现两次：空 choices、无 usage，什么都不携带。
        ObjectNode padding = JSON.createObjectNode();
        padding.put("id", "provider-padding");
        padding.put("object", "chat.completion.chunk");
        padding.put("created", 100);
        padding.put("model", "upstream-model");
        padding.putArray("choices");

        ObjectNode paddingAgain = padding.deepCopy();
        paddingAgain.put("created", 200);

        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(padding, null),
                new ProviderAdapter.ProviderChunk(paddingAgain, null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        Signal<String> first = service.stream(
                        apiKey, body, "req_00000000000000000000000000000041")
                .materialize().blockFirst(Duration.ofSeconds(3));

        // 首帧就是错误信号：没有任何东西被转发出去，因此没有 [DONE]、也没有半截内容。
        assertThat(first).isNotNull();
        assertThat(first.isOnError()).isTrue();
        assertThat(first.getThrowable())
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.reason()).isEqualTo("empty_provider_stream");
                });
    }

    /**
     * 零内容保障：转发过帧、但那些帧**既没有内容、也没有任何终止信号**时，必须在 [DONE] 之前收成失败。
     *
     * <p>它与上一条的分工是「首字节之前 / 之后」：上一条一帧都没转发出去，落在执行器的守卫上，
     * 那时还换得了渠道；这一条的帧是合法且会被转发的（{@code {"delta":{"role":"assistant"}}} 是每次流
     * 的第一帧），响应因此已经提交，换不了渠道，只能收尾成失败帧。
     *
     * <p>判据必须是「内容与终止信号**皆无**」，不能是「没有内容」——一次**合法的空回答**
     * （结束帧的 delta 是空的、{@code finish_reason=stop}）全程也可以一个字都没有，它是正常结果。
     * 按「没有内容」一刀切会把那种正常结果报成上游故障。下一条用例正是它的对照。
     */
    @Test
    void streamWithFramesButNoContentAndNoTerminalSignalFailsInsteadOfCompleting() throws Exception {
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);

        // 只有 role 的空信封帧：合法、会被转发，但客户拿不到任何东西，也没有宣告结束。
        ObjectNode roleEnvelope = JSON.createObjectNode();
        roleEnvelope.put("id", "provider-role");
        roleEnvelope.put("object", "chat.completion.chunk");
        roleEnvelope.put("created", 100);
        roleEnvelope.put("model", "upstream-model");
        roleEnvelope.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta")
                .put("role", "assistant");

        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(roleEnvelope, null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> frames = service.stream(
                        apiKey, body, "req_00000000000000000000000000000043")
                .collectList().block(Duration.ofSeconds(3));

        // 信封帧照常转发（响应已经提交），但收尾必须是失败帧，且没有 [DONE]。
        assertThat(frames.getFirst()).contains("\"role\":\"assistant\"");
        assertThat(frames).anyMatch(frame -> frame.contains("\"error\""));
        assertThat(frames).doesNotContain("[DONE]");
    }

    /**
     * 对照用例：一次**合法的空回答**不得被零内容保障误伤。
     *
     * <p>它与上一条的区别是结尾那一个 {@code finish_reason=stop} 的结束帧——内容依然是零，
     * 但上游明确宣告了「这条流结束了」。那是正常结果（模型只产出了推理过程、或上游自己截断），
     * 必须照常收尾成成功：{@code [DONE]} 要有，失败帧不能有。
     */
    @Test
    void aLegitimatelyEmptyAnswerStillCompletesBecauseItDeclaresAnEnd() throws Exception {
        ObjectNode body = request(true);
        body.putObject("stream_options").put("include_usage", true);

        ObjectNode emptyFinish = JSON.createObjectNode();
        emptyFinish.put("id", "provider-finish");
        emptyFinish.put("object", "chat.completion.chunk");
        emptyFinish.put("created", 300);
        emptyFinish.put("model", "upstream-model");
        emptyFinish.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta");
        ((ObjectNode) emptyFinish.withArray("choices").get(0)).put("finish_reason", "stop");

        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(emptyFinish, null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> frames = service.stream(
                        apiKey, body, "req_00000000000000000000000000000042")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(frames).noneMatch(frame -> frame.contains("\"error\""));
        // 成功收尾：结束帧 + 网关补的用量尾帧 + [DONE]。
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void streamWithoutIncludeUsageNeverExposesProviderUsage() throws Exception {
        ProviderAdapter.TokenUsage known = new ProviderAdapter.TokenUsage(10, 20, 30);
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("provider-content", 100, "hello", known),
                chunk("provider-usage", 200, null, known)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> data = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000034")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(data).hasSize(2);
        assertThat(JSON.readTree(data.getFirst()).has("usage")).isFalse();
        assertThat(data.getLast()).isEqualTo("[DONE]");
        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(10, 20, 30)),
                eq(QuotaService.UsageQuality.ACTUAL));
    }

    @Test
    void dropsUsageOnlyChunksEvenWhenProviderUsageIsIncomplete() throws Exception {
        ObjectNode incompleteUsage = (ObjectNode) chunk("provider-usage", 200, null, null).data();
        incompleteUsage.putObject("usage").put("prompt_tokens", 10);
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("provider-content", 100, "hello", null),
                new ProviderAdapter.ProviderChunk(incompleteUsage, null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> data = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000038")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(data).hasSize(2);
        assertThat(JSON.readTree(data.getFirst()).path("choices").isEmpty()).isFalse();
        assertThat(data.getLast()).isEqualTo("[DONE]");
        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(12, 2, 14)),
                eq(QuotaService.UsageQuality.ESTIMATED));
    }

    @Test
    void failsOverWhenUpstreamFailsBeforeFirstByte() {
        StubAdapter first = StubAdapter.stream("FIRST", Flux.error(new IllegalStateException("connect")));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(chunk("chatcmpl-2", 200, "ok", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<String> data = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000001")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(data).hasSize(2);
        assertThat(data.getFirst()).contains("\"content\":\"ok\"");
        assertThat(data.getLast()).isEqualTo("[DONE]");

        assertThat(first.streamCalls).isEqualTo(1);
        assertThat(second.streamCalls).isEqualTo(1);
        verify(limits, timeout(1_000)).acquire(any());
        verify(limits, timeout(1_000)).rebindChannel(any(), eq(CHANNEL_B));
        verify(quota, timeout(1_000)).settle(any(), any(), eq(QuotaService.UsageQuality.ESTIMATED));
    }

    @Test
    void nonStreamFailoverPreservesUnknownFailedAttemptWithoutChargingIt() {
        StubAdapter first = StubAdapter.complete(
                "FIRST", Mono.error(new IllegalStateException("connect")));
        StubAdapter second = StubAdapter.complete("SECOND", response(10, 20));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        service.complete(apiKey, request(false), "req_non_stream_stage_failover")
                .block(Duration.ofSeconds(3));

        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.stages()).hasSize(2);
        assertThat(entry.stages().get(0)).satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_A);
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.billable()).isFalse();
        });
        assertThat(entry.stages().get(1)).satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_B);
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(10, 20, 30));
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
            assertThat(stage.outcome()).isEqualTo("SUCCEEDED");
            assertThat(stage.billable()).isTrue();
        });
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(10, 20, 30));
        assertThat(entry.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
        verify(quota).settle(
                any(), eq(new QuotaService.TokenUsage(10, 20, 30)),
                eq(QuotaService.UsageQuality.ACTUAL));
    }

    @Test
    void nonStreamCircuitRejectionDoesNotCreateAnUpstreamStage() {
        ProviderAdapter.TokenUsage failedUsage = new ProviderAdapter.TokenUsage(10, 20, 30);
        StubAdapter first = StubAdapter.complete("FIRST", new ProviderAdapter.ProviderResponse(
                JSON.getNodeFactory().textNode("not-an-object"), failedUsage));
        StubAdapter second = StubAdapter.complete("SECOND", response(40, 50));
        circuits.circuitBreaker(CHANNEL_B, "text-basic").transitionToForcedOpenState();
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_non_stream_circuit_rejected_stage")
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(first.completeCalls).isEqualTo(1);
        assertThat(second.completeCalls).isZero();
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_A);
            assertThat(stage.usage()).isEqualTo(failedUsage);
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
    }

    @Test
    void boundsAllProviderAttemptsByOneTotalDeadline() {
        UUID channelC = UUID.fromString("10000000-0000-4000-8000-000000000008");
        StubAdapter first = StubAdapter.complete("FIRST", delayedFailure("first"));
        StubAdapter second = StubAdapter.complete("SECOND", delayedFailure("second"));
        StubAdapter third = StubAdapter.complete("THIRD", delayedFailure("third"));
        List<ChatCompletionService.RouteTarget> targets = List.of(
                target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND"), target(channelC, "THIRD"));
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.just(
                new ChatCompletionService.PreparedRequest(
                        MODEL_ID, publicModel, 8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), targets));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.<ProviderAdapter>of(first, second, third), JSON,
                Duration.ofMinutes(2), Duration.ofMillis(120));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000027")
                .block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error ->
                        // 超时必须是 504「尝试超时」，而不是一个笼统的 502 上游故障：
                        // 客户端对两者的正确动作不同（同幂等键重试 vs 换渠道/排查）。
                        assertThat(error.status()).isEqualTo(504));

        // **这里刻意不断言墙钟耗时。** 原来还有一条 `elapsedMillis < 250`，但它测不出
        // 「截止时间生效」：本机上单跑这个方法是 255–308ms，而同一个方法在全量套件里
        // 是绿的——差别全部来自第一次使用 Reactor 调度器、`Instant.now()` 与我无法控制的
        // 类加载开销（实测同一段逻辑首次 336ms、再次 163ms）。也就是说那条断言测的是
        // 本机预热状态，不是被测代码；它既会在快机器上放行真正的回归，又会在慢机器上
        // 制造与代码无关的红灯。
        //
        // 截止时间的不变量由下面两条**确定性**事实钉住，两者都与机器速度无关：
        //   * 总尝试次数被框住（本用例）；
        //   * 全局截止时间耗尽后连一次尝试都不再发出（见
        //     initialChannelRebindUsesTheGlobalAttemptDeadline /
        //     failoverChannelRebindUsesTheGlobalAttemptDeadline 里的 `.isZero()`）。
        assertThat(first.completeCalls + second.completeCalls + third.completeCalls).isLessThanOrEqualTo(2);
        verify(quota, timeout(1_000)).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initialChannelRebindUsesTheGlobalAttemptDeadline(boolean stream) {
        StubAdapter adapter = stream
                ? StubAdapter.stream("MOCK", Flux.just(chunk("chatcmpl-late", 100, "late", null)))
                : StubAdapter.complete("MOCK", response(10, 20));
        doAnswer(invocation -> Mono.never()).when(limits).rebindChannel(any(), any());
        ChatCompletionService service = timedService(
                List.of(target(CHANNEL_A, "MOCK")), List.of(adapter), Duration.ofMillis(300));

        Mono<Void> invocation = stream
                ? service.stream(apiKey, request(true), "req_00000000000000000000000000000049").then()
                : service.complete(apiKey, request(false), "req_00000000000000000000000000000049").then();
        long started = System.nanoTime();
        Signal<Void> signal = invocation.materialize().block(Duration.ofSeconds(1));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(signal).isNotNull();
        assertThat(signal.getThrowable()).isInstanceOfSatisfying(
                ProviderAdapter.ProviderException.class,
                error -> assertThat(error.status()).isEqualTo(504));
        assertThat(elapsedMillis).isLessThan(700);
        assertThat(adapter.completeCalls + adapter.streamCalls).isZero();
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).release(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failoverChannelRebindUsesTheGlobalAttemptDeadline(boolean stream) {
        StubAdapter first = stream
                ? StubAdapter.stream("FIRST", Flux.error(new IllegalStateException("connect")))
                : StubAdapter.complete("FIRST", Mono.error(new IllegalStateException("connect")));
        StubAdapter second = stream
                ? StubAdapter.stream("SECOND", Flux.just(chunk("chatcmpl-late", 100, "late", null)))
                : StubAdapter.complete("SECOND", response(10, 20));
        doAnswer(invocation ->
                CHANNEL_B.equals(invocation.getArgument(1))
                        ? Mono.never() : Mono.just(invocation.getArgument(0)))
                .when(limits).rebindChannel(any(), any());
        ChatCompletionService service = timedService(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second), Duration.ofMillis(300));

        Mono<Void> invocation = stream
                ? service.stream(apiKey, request(true), "req_00000000000000000000000000000050").then()
                : service.complete(apiKey, request(false), "req_00000000000000000000000000000050").then();
        long started = System.nanoTime();
        Signal<Void> signal = invocation.materialize().block(Duration.ofSeconds(1));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(signal).isNotNull();
        assertThat(signal.getThrowable()).isInstanceOfSatisfying(
                ProviderAdapter.ProviderException.class,
                error -> assertThat(error.status()).isEqualTo(504));
        assertThat(elapsedMillis).isLessThan(700);
        assertThat(first.completeCalls + first.streamCalls).isEqualTo(1);
        assertThat(second.completeCalls + second.streamCalls).isZero();
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).release(any());
    }

    @Test
    void failsOverWhenTheFirstProviderChunkCannotBeSerializedForTheClient() {
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), null)));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-serializable", 201, "ok", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<String> data = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000017")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(data).hasSize(2);
        assertThat(data.getFirst()).contains("\"content\":\"ok\"");
        assertThat(data.getLast()).isEqualTo("[DONE]");
        assertThat(first.streamCalls).isEqualTo(1);
        assertThat(second.streamCalls).isEqualTo(1);
    }

    @Test
    void failedPreFirstByteActualUsageIsProviderCostOnlyAfterSuccessfulFailover() {
        ProviderAdapter.TokenUsage failedUsage = new ProviderAdapter.TokenUsage(90, 9, 99);
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), failedUsage)));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-clean", 201, "ok", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000036")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(usage.awaitEntry()).satisfies(entry -> {
            JsonNode stages = JSON.valueToTree(entry).path("stages");
            assertThat(stages).hasSize(2);
            assertThat(stages.get(0).path("channelId").asText()).isEqualTo(CHANNEL_A.toString());
            assertThat(stages.get(0).path("usage").path("totalTokens").asLong()).isEqualTo(99);
            assertThat(stages.get(0).path("quality").asText()).isEqualTo("ACTUAL");
            assertThat(stages.get(0).path("outcome").asText()).isEqualTo("FAILED");
            assertThat(stages.get(1).path("channelId").asText()).isEqualTo(CHANNEL_B.toString());
            assertThat(stages.get(1).path("usage").path("totalTokens").asLong()).isEqualTo(13);
            assertThat(stages.get(1).path("quality").asText()).isEqualTo("ESTIMATED");
            assertThat(stages.get(1).path("outcome").asText()).isEqualTo("SUCCEEDED");
            assertThat(entry.stages().get(0).customerBillable()).isFalse();
            assertThat(entry.stages().get(0).providerBillable()).isTrue();
            assertThat(entry.stages().get(1).customerBillable()).isTrue();
            assertThat(entry.stages().get(1).providerBillable()).isTrue();
            assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 1, 13));
            assertThat(entry.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
        });
        assertThat(accountingCalls).containsExactly("settle", "log");
        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(12, 1, 13)),
                eq(QuotaService.UsageQuality.ESTIMATED));
    }

    @Test
    void failedStageLogFailureStillSettlesQuotaAndReleasesTheLease() {
        usage.failWrites = true;
        ProviderAdapter.TokenUsage failedUsage = new ProviderAdapter.TokenUsage(90, 9, 99);
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), failedUsage)));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-clean", 201, "ok", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        service.stream(apiKey, request(true), "req_failed_stage_log_recovery")
                .collectList().block(Duration.ofSeconds(3));

        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(12, 1, 13)),
                eq(QuotaService.UsageQuality.ESTIMATED));
        verify(limits, timeout(1_000)).release(any());
        assertThat(accountingCalls).contains("log", "settle");
    }

    @Test
    void finalStreamFailoverFailureReleasesCustomerQuotaAndRetainsProviderUsage() {
        ProviderAdapter.TokenUsage failedUsage = new ProviderAdapter.TokenUsage(90, 9, 99);
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), failedUsage)));
        StubAdapter second = StubAdapter.stream(
                "SECOND", Flux.error(new IllegalStateException("fallback offline")));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<Signal<String>> signals = service.stream(
                        apiKey, request(true), "req_final_stream_failover_failed")
                .materialize().collectList().block(Duration.ofSeconds(3));

        assertThat(signals.getLast().getThrowable()).hasMessageContaining("fallback offline");
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.stages()).hasSize(2);
        assertThat(entry.stages().get(0)).satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_A);
            assertThat(stage.usage()).isEqualTo(failedUsage);
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
        assertThat(entry.stages().get(1)).satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_B);
            assertThat(stage.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isFalse();
        });
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).release(any());
    }

    @Test
    void streamFailoverRebindFailureReleasesCustomerQuotaAndRetainsProviderStage() {
        ProviderAdapter.TokenUsage failedUsage = new ProviderAdapter.TokenUsage(90, 9, 99);
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), failedUsage)));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-unused", 201, "unused", null)));
        doAnswer(invocation -> CHANNEL_B.equals(invocation.getArgument(1))
                ? Mono.error(new ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"))
                : Mono.just(invocation.getArgument(0)))
                .when(limits).rebindChannel(any(), any());
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<Signal<String>> signals = service.stream(
                        apiKey, request(true), "req_stream_rebind_after_actual_failed")
                .materialize().collectList().block(Duration.ofSeconds(3));

        assertThat(signals.getLast().getThrowable()).isInstanceOf(ResponseStatusException.class);
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_A);
            assertThat(stage.usage()).isEqualTo(failedUsage);
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).release(any());
        assertThat(second.streamCalls).isZero();
    }

    @Test
    void streamCircuitRejectionDoesNotCreateAnUpstreamStage() {
        ProviderAdapter.TokenUsage failedUsage = new ProviderAdapter.TokenUsage(90, 9, 99);
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), failedUsage)));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-unused", 201, "unused", null)));
        circuits.circuitBreaker(CHANNEL_B, "text-basic").transitionToForcedOpenState();
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<Signal<String>> signals = service.stream(
                        apiKey, request(true), "req_stream_circuit_rejected_stage")
                .materialize().collectList().block(Duration.ofSeconds(3));

        assertThat(signals.getLast().getThrowable()).isInstanceOf(ResponseStatusException.class);
        assertThat(first.streamCalls).isEqualTo(1);
        assertThat(second.streamCalls).isZero();
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_A);
            assertThat(stage.usage()).isEqualTo(failedUsage);
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
    }

    @Test
    void neverFailsOverAfterFirstByteAndReleasesInterruptedStream() throws Exception {
        StubAdapter first = StubAdapter.stream("FIRST", Flux.concat(
                Flux.just(chunk("chatcmpl-3", 300, "partial", null)),
                Flux.error(new IllegalStateException("upstream interrupted"))));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(chunk("chatcmpl-4", 400, "wrong", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<String> data = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000002")
                .collectList().block(Duration.ofSeconds(3));

        // 首段之后的失败**不再**以「把未处理异常抛进已提交的响应」收场：那会让客户端看到
        // 一次连接重置（生产 A 样本记录的 ECONNRESET），既没有原因也没有终态。现在的契约是
        // 「已发出的内容 + 一帧可解析的失败帧 + 正常结束」。
        assertThat(data).hasSize(2);
        assertThat(data.getFirst()).contains("\"content\":\"partial\"");
        JsonNode failure = JSON.readTree(data.getLast());
        assertThat(failure.path("error").path("code").asText()).isEqualTo("provider_error");
        // 这个用例喂的是一个**网关无法归类**的异常（裸 IllegalStateException），因此原因码
        // 必须是 unknown_stream_failure：从前这里写的是 upstream_stream_interrupted，等于把
        // 「不知道」记成了「上游流中断」——而这条分支恰恰证明不了上游中断。
        // 真正传输层中断的那条路径由 ChatStreamFailureClassifierTest 覆盖。
        assertThat(failure.path("error").path("reason").asText())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
        assertThat(failure.path("request_id").asText())
                .isEqualTo("req_00000000000000000000000000000002");
        // **[DONE] 必须缺席。** 它的语义是「这段回答完整结束」；失败之后发它，等于把一次
        // 断流重新包装成一次成功，正是这次事故里「只承诺执行却显示完成」的成因。
        assertThat(data).doesNotContain("[DONE]");

        // 首段已发出之后不跨供应商续写：拼接另一家的答案会造成文字混合与重复计费。
        assertThat(second.streamCalls).isZero();
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        assertThat(entry.outcome()).isEqualTo("FAILED");
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isFalse();
        });
    }

    /**
     * 上游在**吐出任何内容之前**就掐断时，必须换下一条渠道，而不是把这半次请求丢掉。
     *
     * <p>这是 2026-10-16 生产数据量出来的那个洞：历史上 11 次「上游流中断」的 {@code first_token_ms}
     * 全是 {@code NULL}——上游每次都是在吐出任何内容之前就断了。而网关把「响应头已经提交」
     * 当成了「已经有内容发给客户」（{@code settlement.firstByte()} 被无条件调用，
     * 它同时关掉了换渠道的资格），于是每一次都直接放弃。
     *
     * <p>「不跨供应商续写」要防的是**文字混合**与**重复计费**，而两者都以「已经交付过内容」为前提。
     * 客户一个字都没收到时这两件事都不可能发生，因此换渠道是安全的，而且对客户端完全不可见。
     *
     * <p>这里刻意先让上游发一个只有 {@code role} 的空信封帧再断——那正是最容易骗过判断的形状：
     * 它让响应提交、也让流「过了第一个信号」，但客户什么都没看到。若判定写成「上游产出过内容」
     * 而不是「客户收到过内容」，这条用例就会退回成不换渠道。
     */
    @Test
    void failsOverWhenTheUpstreamCutsTheStreamBeforeAnyContentReachesTheClient() {
        ObjectNode roleEnvelope = JSON.createObjectNode();
        roleEnvelope.put("id", "provider-role");
        roleEnvelope.put("object", "chat.completion.chunk");
        roleEnvelope.put("created", 100);
        roleEnvelope.put("model", "upstream-model");
        roleEnvelope.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta")
                .put("role", "assistant");

        StubAdapter first = StubAdapter.stream("FIRST", Flux.concat(
                Flux.just(new ProviderAdapter.ProviderChunk(roleEnvelope, null)),
                Flux.error(new IOException("Connection reset by peer"))));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-second", 400, "第二个渠道的回答", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<String> frames = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000044")
                .collectList().block(Duration.ofSeconds(3));

        // 第二个渠道确实被用上了。
        assertThat(second.streamCalls).isEqualTo(1);
        // 客户拿到的是**完整的第二个渠道的回答**，而不是一个半截流加一帧失败帧。
        assertThat(frames).anyMatch(frame -> frame.contains("第二个渠道的回答"));
        assertThat(frames).noneMatch(frame -> frame.contains("\"error\""));
        assertThat(frames.getLast()).isEqualTo("[DONE]");
    }

    /**
     * 对照：**已经交付过内容**之后再断，仍然必须放弃，不得换渠道。
     *
     * <p>这条与上一条只差「客户有没有收到过内容」，而整个契约就卡在这一个点上：
     * 拼接另一家的答案会造成文字混合、工具 ID 重复和重复计费（交接方案四.2）。
     * 上一条证明网关敢换，这一条证明网关不乱换——两条合起来才是完整的边界。
     */
    @Test
    void doesNotFailOverOnceContentHasReachedTheClient() {
        StubAdapter first = StubAdapter.stream("FIRST", Flux.concat(
                Flux.just(chunk("chatcmpl-a", 300, "已经发出去的一半", null)),
                Flux.error(new IOException("Connection reset by peer"))));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-b", 400, "另一家的答案", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<String> frames = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000045")
                .collectList().block(Duration.ofSeconds(3));

        // 第二个渠道绝不能被用上：那正是会混合两段文字的操作。
        assertThat(second.streamCalls).isZero();
        // 已经发出的内容 + 一帧失败帧 + 没有 [DONE]。
        assertThat(frames).anyMatch(frame -> frame.contains("已经发出去的一半"));
        assertThat(frames).anyMatch(frame -> frame.contains("\"error\""));
        assertThat(frames).doesNotContain("[DONE]");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "<empty>|0|12",
            "abc|1|13",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz|20|32"
    }, delimiter = '|')
    void estimatesStreamSettlementFromContentActuallyReceived(
            String content, long expectedOutput, long expectedTotal) {
        String received = "<empty>".equals(content) ? null : content;
        ProviderAdapter.ProviderChunk providerChunk = chunk("chatcmpl-estimated", 300, received, null);
        if (received == null) {
            // 零内容这一档必须**同时**给出结束标记：它要表达的是「模型没有产出可见输出但正常结束」
            // （例如只产出了推理过程），而不是「上游什么都没说就断了」。
            // 后者是另一种故障形状，由 streamWithFramesButNoContentAndNoTerminalSignalFailsInsteadOfCompleting 覆盖。
            ObjectNode zeroContent = ((ObjectNode) providerChunk.data()).withArray("choices")
                    .addObject().put("index", 0).putObject("delta");
            zeroContent.put("finish_reason", "stop");
        }
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(providerChunk));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000019")
                .collectList().block(Duration.ofSeconds(3));

        QuotaService.TokenUsage expected = new QuotaService.TokenUsage(12, expectedOutput, expectedTotal);
        verify(quota, timeout(1_000)).settle(
                any(), eq(expected), eq(QuotaService.UsageQuality.ESTIMATED));
        assertThat(usage.awaitEntry().usage()).isEqualTo(new ProviderAdapter.TokenUsage(
                expected.inputTokens(), expected.outputTokens(), expected.totalTokens()));
    }

    @Test
    void largeProviderChunksSettleWithoutRetainingTheFullOutputForEstimation() {
        TokenEstimator boundedEstimator = new TokenEstimator(3, 1.25) {
            @Override
            public long estimate(EstimateCommand command) {
                if (command.text() != null && command.text().length() > 128) {
                    throw new AssertionError("stream_output_was_retained");
                }
                return super.estimate(command);
            }
        };
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-large", 300, "x".repeat(100_000), null)));
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.just(
                new ChatCompletionService.PreparedRequest(
                        MODEL_ID, publicModel, 8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(target(CHANNEL_A, "MOCK"))));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, boundedEstimator,
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000037")
                .collectList().block(Duration.ofSeconds(3));

        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(12, 20, 32)),
                eq(QuotaService.UsageQuality.ESTIMATED));
    }

    @Test
    void streamFailureReleasesCustomerQuotaAndRetainsKnownProviderUsage() throws Exception {
        ProviderAdapter.TokenUsage known = new ProviderAdapter.TokenUsage(10, 7, 17);
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.concat(
                Flux.just(chunk("chatcmpl-known", 300, "partial", known)),
                Flux.error(new IllegalStateException("upstream interrupted"))));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> data = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000024")
                .collectList().block(Duration.ofSeconds(3));

        // 失败帧必须带上 request_id：桌面据此把断流与中台用量记录对齐（交接方案四.5）。
        JsonNode failure = JSON.readTree(data.getLast());
        assertThat(failure.path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(failure.path("error").path("reason").asText())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
        assertThat(failure.path("request_id").asText())
                .isEqualTo("req_00000000000000000000000000000024");

        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(known);
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    /**
     * 首段之后的失败只结算**一次**，且仍然只记一条用量记录。
     *
     * <p>失败帧这条新路径同时可能被 {@code doFinally} 的取消分支与 {@code fail()} 触碰，
     * 因此「恰好一次」必须由测试钉住而不是靠推理：多结算一次就是重复扣费，少一次就是
     * 供应商已经计费而中台不留痕迹。
     */
    @Test
    void aPostFirstByteFailureSettlesExactlyOnceAndLogsOneEntry() throws Exception {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.concat(
                Flux.just(chunk("chatcmpl-once", 300, "partial", null)),
                Flux.error(new IllegalStateException("upstream interrupted"))));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000026")
                .collectList().block(Duration.ofSeconds(3));

        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.outcome()).isEqualTo("FAILED");
        // 等待一小段时间，让任何多余的异步结算有机会被发现（而不是靠时序侥幸通过）。
        Thread.sleep(150);
        verify(quota, times(0)).settle(any(), any(), any());
        verify(quota, times(1)).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits, times(1)).release(any());
        assertThat(usage.entries).singleElement();
        assertThat(entry.stages()).singleElement();
    }

    /**
     * 失败帧必须能**穿过真实 HTTP/SSE 序列化链路**到达客户端。
     *
     * <p>交接方案六.1 明确要求这一点：仅对 Flux 做内存断言，既不能证明经过 Nginx 之后
     * 不会被 HTTP/2 重置，也不能证明客户端 SDK 能解析这一帧。上面那些用例走的是
     * {@code service.stream(...)}，拿到的是**字符串**；这个用例走
     * {@code WebTestClient} + 真实控制器 + 真实 SSE 编解码，断言的是客户端实际收到的东西。
     */
    @Test
    void aPostFirstByteFailureReachesTheClientAsAParseableSseFailureFrame() throws Exception {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.concat(
                Flux.just(chunk("chatcmpl-wire", 300, "partial", null)),
                Flux.error(new IllegalStateException("upstream interrupted"))));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        List<String> data = client(new ChatCompletionController(service))
                .post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request(true))
                .exchange()
                // 首段已发出，HTTP 状态早已提交为 200；失败只能用帧来表达，不能靠状态码。
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(data).isNotNull().hasSize(2);
        assertThat(data.getFirst()).contains("\"content\":\"partial\"");

        // 客户端看到的是一个**合法 JSON 的 SSE data 帧**，不是一次连接重置。
        JsonNode failure = JSON.readTree(data.getLast());
        assertThat(failure.path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(failure.path("error").path("reason").asText())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
        assertThat(failure.path("error").path("message").isTextual()).isTrue();
        assertThat(failure.path("request_id").asText()).startsWith("req_");

        // 终态不得包含成功标记：既不半截 [DONE]，也不再是一条"完整回答"。
        assertThat(data).noneMatch(value -> "[DONE]".equals(value));
    }

    @Test
    void cancellationSettlesEstimatedUsageAfterOutput() {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.concat(
                Flux.just(chunk("chatcmpl-5", 500, "partial", null)), Flux.never()));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000003")
                .take(1)
                .blockLast(Duration.ofSeconds(3));

        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(12, 3, 15)),
                eq(QuotaService.UsageQuality.ESTIMATED));
        verify(quota, never()).release(any(), eq(QuotaService.ReleaseReason.CLIENT_CANCELLED));
        assertThat(entry.outcome()).isEqualTo("CANCELLED");
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 3, 15));
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isFalse();
        });
    }

    @Test
    void cancellationReleasesCustomerQuotaAndRetainsKnownProviderUsage() {
        ProviderAdapter.TokenUsage known = new ProviderAdapter.TokenUsage(11, 5, 16);
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.concat(
                Flux.just(chunk("chatcmpl-known-cancel", 310, "partial", known)), Flux.never()));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000025")
                .take(1).blockLast(Duration.ofSeconds(3));

        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(11, 5, 16)),
                eq(QuotaService.UsageQuality.ACTUAL));
        verify(quota, never()).release(any(), eq(QuotaService.ReleaseReason.CLIENT_CANCELLED));
        assertThat(entry.usage()).isEqualTo(known);
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(known);
            assertThat(stage.customerBillable()).isTrue();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    @Test
    void directCompleteEmptyPlanRetainsNoUsableChannelAndReleasesAccounting() {
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(disabledTarget()), List.of(adapter));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_direct_complete_empty_plan")
                .block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        this::assertDirectNoUsableChannel);

        verify(limits).acquire(any());
        verify(quota).reserve(any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(quota, never()).settle(any(), any(), any());
        verify(limits).release(any());
        assertThat(usage.awaitEntry()).satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("FAILED");
            assertThat(entry.stream()).isFalse();
        });
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void directStreamEmptyPlanRetainsNoUsableChannelAndReleasesAccounting() {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.never());
        ChatCompletionService service = service(List.of(disabledTarget()), List.of(adapter));

        assertThatThrownBy(() -> service.openStream(
                        apiKey, request(true), "req_direct_stream_empty_plan")
                .block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        this::assertDirectNoUsableChannel);

        verify(limits).acquire(any());
        verify(quota).reserve(any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(quota, never()).settle(any(), any(), any());
        verify(limits).release(any());
        assertThat(usage.awaitEntry()).satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("FAILED");
            assertThat(entry.stream()).isTrue();
        });
        assertThat(adapter.streamCalls).isZero();
    }

    @Test
    void releasesReservationWhenEveryUpstreamFailsBeforeFirstByte() {
        StubAdapter adapter = StubAdapter.complete("MOCK", Mono.error(new IllegalStateException("offline")));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000004")
                .block(Duration.ofSeconds(3)))
                .hasMessage("offline");

        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(quota, never()).settle(any(), any(), any());
    }

    @Test
    void neverRetriesProviderAfterAResponseWhenSettlementFails() {
        StubAdapter first = StubAdapter.complete("FIRST", response(10, 20));
        StubAdapter second = StubAdapter.complete("SECOND", response(10, 20));
        when(quota.settle(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("settlement unavailable")));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000005")
                .block(Duration.ofSeconds(3)))
                .hasMessage("settlement unavailable");

        assertThat(first.completeCalls).isEqualTo(1);
        assertThat(second.completeCalls).isZero();
        verify(quota, never()).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
    }

    @Test
    void failoverSuccessWithSettlementFailureDoesNotReleaseTheReservation() {
        StubAdapter first = StubAdapter.complete("FIRST", Mono.error(new IllegalStateException("connect")));
        StubAdapter second = StubAdapter.complete("SECOND", response(10, 20));
        when(quota.settle(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("settlement unavailable")));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000013")
                .block(Duration.ofSeconds(3)))
                .hasMessage("settlement unavailable");

        assertThat(second.completeCalls).isEqualTo(1);
        verify(quota, never()).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).rebindChannel(any(), eq(CHANNEL_B));
        verify(limits).release(any());
    }

    @Test
    void malformedJsonResponseReleasesCustomerQuotaAndLogsProviderUsage() {
        ProviderAdapter.TokenUsage actualUsage = new ProviderAdapter.TokenUsage(10, 20, 30);
        ProviderAdapter.ProviderResponse malformed = new ProviderAdapter.ProviderResponse(
                JSON.getNodeFactory().textNode("not-an-object"), actualUsage);
        StubAdapter first = StubAdapter.complete("FIRST", malformed);
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST")), List.of(first));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000014")
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(ProviderAdapter.ProviderException.class);

        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).release(any());
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(entry.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
        assertThat(entry.outcome()).isEqualTo("FAILED");
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.channelId()).isEqualTo(CHANNEL_A);
            assertThat(stage.usage()).isEqualTo(actualUsage);
            assertThat(stage.quality()).isEqualTo(QuotaService.UsageQuality.ACTUAL);
            assertThat(stage.outcome()).isEqualTo("FAILED");
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    /**
     * 非流式错误信封也要带受控原因码，且没有原因码时该键**缺席**（不是 null）。
     *
     * <p>两条都重要：前一条让流式与非流式对同一个上游故障给出同一种可诊断性；后一条让客户端
     * 不必区分「字段不存在」与「字段为空」——把 null 写出去，客户端会读到一个存在但无意义的
     * 原因码。
     */
    @Test
    void nonStreamErrorEnvelopeCarriesTheSafeReasonCodeAndOmitsItWhenAbsent() {
        StubAdapter failing = StubAdapter.complete("MOCK", Mono.error(
                new ProviderAdapter.ProviderException(502, "provider_stream_missing_done")));
        ChatCompletionService withReason =
                service(List.of(target(CHANNEL_A, "MOCK")), List.of(failing));

        client(new ChatCompletionController(withReason)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("provider_error")
                .jsonPath("$.error.reason").isEqualTo("provider_stream_missing_done");

        // 一个 message 不是受控标识符的异常：code 照旧，但 reason 必须缺席。
        StubAdapter unreasoned = StubAdapter.complete("MOCK", Mono.error(
                new ProviderAdapter.ProviderException(502, "provider secret")));
        ChatCompletionService withoutReason =
                service(List.of(target(CHANNEL_A, "MOCK")), List.of(unreasoned));

        client(new ChatCompletionController(withoutReason)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("\"code\":\"provider_error\"")
                        .doesNotContain("reason")
                        .doesNotContain("provider secret"));
    }

    @Test
    void rewriteFailureReleasesCustomerQuotaAndLogsKnownProviderUsage() {
        ProviderAdapter.TokenUsage actualUsage = new ProviderAdapter.TokenUsage(10, 20, 30);
        StubAdapter adapter = StubAdapter.complete("MOCK", new ProviderAdapter.ProviderResponse(
                rewriteFailingBody(), actualUsage));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("provider_error");

        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        verify(limits).release(any());
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(actualUsage);
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
        assertThat(entry.outcome()).isEqualTo("FAILED");
        assertThat(entry.httpStatus()).isEqualTo(502);
    }

    @Test
    void rewriteFailureWithoutProviderUsageReleasesCustomerQuotaAndLogsEstimate() {
        StubAdapter adapter = StubAdapter.complete("MOCK", new ProviderAdapter.ProviderResponse(
                rewriteFailingBody(), null));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000055")
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(ProviderAdapter.ProviderException.class);

        ProviderAdapter.TokenUsage estimatedUsage = new ProviderAdapter.TokenUsage(12, 2, 14);
        verify(quota, never()).settle(any(), any(), any());
        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        ChatCompletionService.UsageEntry entry = usage.awaitEntry();
        assertThat(entry.usage()).isEqualTo(new ProviderAdapter.TokenUsage(0, 0, 0));
        assertThat(entry.quality()).isEqualTo(QuotaService.UsageQuality.ESTIMATED);
        assertThat(entry.outcome()).isEqualTo("FAILED");
        assertThat(entry.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.usage()).isEqualTo(estimatedUsage);
            assertThat(stage.customerBillable()).isFalse();
            assertThat(stage.providerBillable()).isTrue();
        });
    }

    @Test
    void structurallyInvalidJsonFailsOverBeforeCircuitSuccessAccounting() {
        StubAdapter first = StubAdapter.complete("FIRST", new ProviderAdapter.ProviderResponse(
                JSON.createObjectNode(), null));
        StubAdapter second = StubAdapter.complete("SECOND", response(10, 20));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        ChatCompletionService.CompletionResult result = service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000036")
                .block(Duration.ofSeconds(3));

        assertThat(result.body().path("choices").isArray()).isTrue();
        assertThat(first.completeCalls).isEqualTo(1);
        assertThat(second.completeCalls).isEqualTo(1);
        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vendor_object", "user_role", "missing_finish_reason", "numeric_finish_reason"})
    void invalidJsonCompletionSpineFailsOverBeforeCircuitSuccessAccounting(String defect) {
        ObjectNode invalidBody = (ObjectNode) response(10, 20).body().deepCopy();
        ObjectNode choice = (ObjectNode) invalidBody.withArray("choices").get(0);
        switch (defect) {
            case "vendor_object" -> invalidBody.put("object", "vendor.completion");
            case "user_role" -> ((ObjectNode) choice.path("message")).put("role", "user");
            case "missing_finish_reason" -> choice.remove("finish_reason");
            case "numeric_finish_reason" -> choice.put("finish_reason", 1);
            default -> throw new AssertionError("unhandled defect " + defect);
        }
        StubAdapter first = StubAdapter.complete("FIRST",
                new ProviderAdapter.ProviderResponse(invalidBody, new ProviderAdapter.TokenUsage(10, 20, 30)));
        StubAdapter second = StubAdapter.complete("SECOND", response(10, 20));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        ChatCompletionService.CompletionResult result = service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000044")
                .block(Duration.ofSeconds(3));

        assertThat(result.body().path("object").asText()).isEqualTo("chat.completion");
        assertThat(first.completeCalls).isEqualTo(1);
        assertThat(second.completeCalls).isEqualTo(1);
        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void structurallyInvalidFirstStreamChunkFailsOverBeforeClientBytes() throws Exception {
        StubAdapter first = StubAdapter.stream("FIRST", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createObjectNode(), null)));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                chunk("chatcmpl-valid", 100, "fallback", null)));
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        List<String> output = service.stream(
                        apiKey, request(true), "req_00000000000000000000000000000037")
                .collectList().block(Duration.ofSeconds(3));

        assertThat(output).hasSize(2);
        assertThat(JSON.readTree(output.getFirst()).path("choices").get(0)
                .path("delta").path("content").asText()).isEqualTo("fallback");
        assertThat(output.getLast()).isEqualTo("[DONE]");
        assertThat(first.streamCalls).isEqualTo(1);
        assertThat(second.streamCalls).isEqualTo(1);
        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void synthesizesOpenAiUsageWhenTheProviderOmitsIt() {
        ObjectNode body = response(0, 0).body().deepCopy();
        body.remove("usage");
        ((ObjectNode) body.path("choices").get(0).path("message")).put("content", "abc");
        StubAdapter adapter = StubAdapter.complete(
                "MOCK", new ProviderAdapter.ProviderResponse(body, null));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        ChatCompletionService.CompletionResult result = service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000015")
                .block(Duration.ofSeconds(3));

        assertThat(result.body().path("usage").path("prompt_tokens").asLong()).isEqualTo(12);
        assertThat(result.body().path("usage").path("completion_tokens").asLong()).isEqualTo(1);
        assertThat(result.body().path("usage").path("total_tokens").asLong()).isEqualTo(13);
        verify(quota).settle(any(), eq(new QuotaService.TokenUsage(12, 1, 13)),
                eq(QuotaService.UsageQuality.ESTIMATED));
    }

    @Test
    void streamEstimationIncludesReasoningAndToolCallArguments() {
        ObjectNode data = (ObjectNode) chunk("chatcmpl-evidence", 300, null, null).data();
        ObjectNode delta = ((ObjectNode) data.withArray("choices").addObject().put("index", 0))
                .putObject("delta");
        delta.put("reasoning_content", "abc");
        delta.putArray("tool_calls").addObject().put("index", 0)
                .putObject("function").put("arguments", "def");
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(data, null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000038")
                .collectList().block(Duration.ofSeconds(3));

        verify(quota, timeout(1_000)).settle(
                any(), eq(new QuotaService.TokenUsage(12, 2, 14)),
                eq(QuotaService.UsageQuality.ESTIMATED));
    }

    @Test
    void replacesIncompleteProviderUsageWithTheSettledUsage() {
        ObjectNode body = (ObjectNode) response(10, 20).body().deepCopy();
        body.set("usage", JSON.createObjectNode());
        StubAdapter adapter = StubAdapter.complete("MOCK",
                new ProviderAdapter.ProviderResponse(body, null));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        ChatCompletionService.CompletionResult result = service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000022")
                .block(Duration.ofSeconds(3));

        assertThat(result.body().path("usage").path("prompt_tokens").asLong()).isPositive();
        assertThat(result.body().path("usage").path("completion_tokens").asLong()).isEqualTo(2);
        assertThat(result.body().path("usage").path("total_tokens").asLong()).isPositive();
    }

    @Test
    void orchestratesLimitEstimateReserveRouteAndAdapterInThatOrder() {
        List<String> calls = new CopyOnWriteArrayList<>();
        ChatCompletionService.RouteTarget target = target(CHANNEL_A, "ORDERED");
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.defer(() -> {
            calls.add("permission");
            return Mono.just(new ChatCompletionService.PreparedRequest(
                    MODEL_ID, publicModel, 8_192, RequestLimitService.Thresholds.all(100),
                    RequestLimitService.Thresholds.all(10), List.of(target)));
        });
        doAnswer(invocation -> {
            calls.add("limit");
            RequestLimitService.LimitCommand command = invocation.getArgument(0);
            return Mono.just(new RequestLimitService.LimitLease(
                    UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                    command.channelId(), 100, 10, RequestLimitService.Store.REDIS));
        }).when(limits).acquire(any());
        doAnswer(invocation -> {
            calls.add("reserve");
            QuotaService.ReserveCommand command = invocation.getArgument(0);
            return Mono.just(new QuotaService.Reservation(
                    command.reservationId(), command.requestId(), PACKAGE_ID, command.modelCode(),
                    command.tokens(), "RESERVED", command.expiresAt()));
        }).when(quota).reserve(any());
        TokenEstimator estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenAnswer(invocation -> {
            calls.add("estimate");
            return 30L;
        });
        ChannelRouter router = mock(ChannelRouter.class);
        when(router.plan(any(), any())).thenAnswer(invocation -> {
            calls.add("route");
            return List.of(target.candidate());
        });
        ProviderAdapter adapter = new ProviderAdapter() {
            @Override public String providerType() { return "ORDERED"; }
            @Override public Set<ChannelRouter.Capability> capabilities() {
                return Set.of(ChannelRouter.Capability.TEXT);
            }
            @Override public Mono<ProviderResponse> complete(RoutedRequest request) {
                calls.add("adapter");
                return Mono.just(response(10, 20));
            }
            @Override public Flux<ProviderChunk> stream(RoutedRequest request) {
                return Flux.error(new UnsupportedOperationException());
            }
            @Override public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
                return Mono.error(new UnsupportedOperationException());
            }
        };
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, estimator, router, circuits,
                List.of(adapter), JSON, Duration.ofMinutes(2));

        service.complete(apiKey, request(false), "req_00000000000000000000000000000023")
                .block(Duration.ofSeconds(3));

        assertThat(calls).containsExactly(
                "permission", "limit", "estimate", "reserve", "route", "adapter");
    }

    @Test
    void separatesPublicAccountingAliasFromTheRoutingAlias() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.RouteTarget route = target(CHANNEL_A, "MOCK");
        ChatCompletionService.GatewayCatalog catalog = (
                context, publicModel, routingModel, capabilities) -> Mono.just(
                        new ChatCompletionService.PreparedRequest(
                                MODEL_ID, publicModel, ROUTING_MODEL_ID, routingModel,
                                8_192, RequestLimitService.Thresholds.all(100),
                                RequestLimitService.Thresholds.all(10), List.of(route)));
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));
        ObjectNode request = request(false);
        request.put("model", "multimodal-basic");
        ArgumentCaptor<RequestLimitService.LimitCommand> limitCommand =
                ArgumentCaptor.forClass(RequestLimitService.LimitCommand.class);
        ArgumentCaptor<QuotaService.ReserveCommand> reserveCommand =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        ChatCompletionService.CompletionResult result = service.completeRouted(
                        compositeKey, request,
                        "req_00000000000000000000000000000053",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null)
                .block(Duration.ofSeconds(3));

        assertThat(result.body().path("model").asText()).isEqualTo("multimodal-basic");
        verify(limits).acquire(limitCommand.capture());
        verify(quota).reserve(reserveCommand.capture());
        assertThat(limitCommand.getValue().modelCode()).isEqualTo("multimodal-basic");
        assertThat(reserveCommand.getValue().modelCode()).isEqualTo("multimodal-basic");
        assertThat(adapter.modelAliases).containsExactly("text-basic");
        assertThat(usage.entries).singleElement().satisfies(entry -> {
            assertThat(entry.modelId()).isEqualTo(MODEL_ID);
            assertThat(entry.modelCode()).isEqualTo("multimodal-basic");
            assertThat(entry.routingModelId()).isEqualTo(ROUTING_MODEL_ID);
            assertThat(entry.routingModelCode()).isEqualTo("text-basic");
            assertThat(entry.composite()).isTrue();
        });
    }

    @Test
    void preadmittedCompletionReusesOnePublicLeaseAcrossRouteFailover() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.RouteTarget first = new ChatCompletionService.RouteTarget(
                target(CHANNEL_A, "FIRST").candidate(),
                target(CHANNEL_A, "FIRST").channel(), 11, 4);
        ChatCompletionService.RouteTarget second = new ChatCompletionService.RouteTarget(
                target(CHANNEL_B, "SECOND").candidate(),
                target(CHANNEL_B, "SECOND").channel(), 7, 2);
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(Mono.just(new ChatCompletionService.PreparedRequest(
                        MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "text-basic",
                        8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(first, second))));
        StubAdapter firstAdapter = StubAdapter.complete(
                "FIRST", Mono.error(new IllegalStateException("connect")));
        StubAdapter secondAdapter = StubAdapter.complete("SECOND", response(10, 20));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(firstAdapter, secondAdapter),
                JSON, Duration.ofMinutes(2));
        ObjectNode body = request(false).put("model", "multimodal-basic");

        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));
        service.completeRouted(
                        compositeKey, body, "req_preadmitted_complete",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null, admission)
                .block(Duration.ofSeconds(3));

        verify(limits).acquirePublic(any());
        verify(limits).activateRoute(admission.lease(), CHANNEL_A, 11, 4);
        verify(limits).activateRoute(argThat(lease ->
                lease.leaseId().equals(admission.lease().leaseId())
                        && lease.channelId().equals(CHANNEL_A)),
                eq(CHANNEL_B), eq(7), eq(2));
        ArgumentCaptor<RequestLimitService.LimitLease> released =
                ArgumentCaptor.forClass(RequestLimitService.LimitLease.class);
        verify(limits).release(released.capture());
        assertThat(released.getValue().leaseId()).isEqualTo(admission.lease().leaseId());
        assertThat(firstAdapter.completeCalls).isEqualTo(1);
        assertThat(secondAdapter.completeCalls).isEqualTo(1);
    }

    @Test
    void preadmittedStreamReusesAndFinalizesThePublicLease() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.RouteTarget route = new ChatCompletionService.RouteTarget(
                target(CHANNEL_B, "MOCK").candidate(),
                target(CHANNEL_B, "MOCK").channel(), 9, 3);
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(Mono.just(new ChatCompletionService.PreparedRequest(
                        MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "text-basic",
                        8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(route))));
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-preadmitted", 900, "done",
                        new ProviderAdapter.TokenUsage(10, 20, 30))));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter),
                JSON, Duration.ofMinutes(2));
        ObjectNode body = request(true).put("model", "multimodal-basic");

        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));
        service.openRoutedStream(
                        compositeKey, body, "req_preadmitted_stream",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null, admission)
                .flatMapMany(stream -> stream)
                .collectList().block(Duration.ofSeconds(3));

        verify(limits).acquirePublic(any());
        verify(limits).activateRoute(admission.lease(), CHANNEL_B, 9, 3);
        ArgumentCaptor<RequestLimitService.LimitLease> released =
                ArgumentCaptor.forClass(RequestLimitService.LimitLease.class);
        verify(limits, timeout(1_000)).release(released.capture());
        assertThat(released.getValue().leaseId()).isEqualTo(admission.lease().leaseId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preadmittedRoutedCatalogEmptyUsesCompositeRouteUnavailableEnvelope(boolean stream) {
        assertCompositeRouteUnavailableOverHttp(stream, Mono.empty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preadmittedRoutedRequestWithZeroTargetsUsesCompositeRouteUnavailableEnvelope(
            boolean stream) {
        assertCompositeRouteUnavailableOverHttp(stream, Mono.just(routedPrepared(List.of())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preadmittedRoutedRequestWithNoUsablePlanUsesCompositeRouteUnavailableEnvelope(
            boolean stream) {
        ChatCompletionService.RouteTarget base = target(CHANNEL_A, "MOCK");
        ChannelRouter.RouteCandidate candidate = base.candidate();
        ChatCompletionService.RouteTarget unavailable = new ChatCompletionService.RouteTarget(
                new ChannelRouter.RouteCandidate(
                        candidate.channelId(), candidate.providerCode(), candidate.modelCode(),
                        candidate.upstreamModel(), candidate.capabilities(), candidate.priority(),
                        candidate.weight(), false, candidate.concurrencyAvailable(),
                        candidate.circuitPermitted(), candidate.packageAllowed(),
                        candidate.apiKeyAllowed()),
                base.channel());

        assertCompositeRouteUnavailableOverHttp(
                stream, Mono.just(routedPrepared(List.of(unavailable))));
    }

    @Test
    void preadmittedCompletionReleasesOnceWhenRoutedCatalogSetupFails() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(Mono.error(new IllegalStateException("catalog unavailable")));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(), JSON, Duration.ofMinutes(2));
        ObjectNode body = request(false).put("model", "multimodal-basic");
        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));

        assertThatThrownBy(() -> service.completeRouted(
                        compositeKey, body, "req_catalog_setup_failure",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null, admission)
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("catalog unavailable");

        verify(limits).acquirePublic(any());
        verify(limits).release(admission.lease());
        verify(quota, never()).reserve(any());
    }

    @Test
    void preadmittedStreamReleasesOnceWhenRouteActivationFails() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.RouteTarget route = new ChatCompletionService.RouteTarget(
                target(CHANNEL_B, "MOCK").candidate(),
                target(CHANNEL_B, "MOCK").channel(), 1, 1);
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(Mono.just(new ChatCompletionService.PreparedRequest(
                        MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "text-basic",
                        8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(route))));
        doReturn(Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded")))
                .when(limits).activateRoute(any(), eq(CHANNEL_B), eq(1), eq(1));
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-unused", 900, "done", null)));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));
        ObjectNode body = request(true).put("model", "multimodal-basic");
        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));

        assertThatThrownBy(() -> service.openRoutedStream(
                        compositeKey, body, "req_activation_setup_failure",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null, admission)
                .block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(limits).acquirePublic(any());
        verify(limits).release(admission.lease());
        assertThat(adapter.streamCalls).isZero();
    }

    @Test
    void preadmittedCompletionCancellationDuringCatalogPreparationReleasesPublicLeaseOnce() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(Mono.never());
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(), JSON, Duration.ofMinutes(2));
        ObjectNode body = request(false).put("model", "multimodal-basic");
        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));

        Disposable execution = service.completeRouted(
                        compositeKey, body, "req_cancel_catalog_setup",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null, admission)
                .subscribe();
        verify(catalog, timeout(1_000)).prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT));
        assertThat(admission.claimed()).isTrue();
        execution.dispose();

        verify(limits, timeout(1_000)).release(admission.lease());
        verify(limits, times(1)).release(admission.lease());
        verify(quota, never()).reserve(any());
    }

    @Test
    void preadmittedStreamCancellationDuringRouteActivationReleasesReservationAndLeaseOnce() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.RouteTarget route = new ChatCompletionService.RouteTarget(
                target(CHANNEL_B, "MOCK").candidate(),
                target(CHANNEL_B, "MOCK").channel(), 9, 3);
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                compositeKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(Mono.just(new ChatCompletionService.PreparedRequest(
                        MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "text-basic",
                        8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(route))));
        doReturn(Mono.never()).when(limits).activateRoute(
                any(), eq(CHANNEL_B), eq(9), eq(3));
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.never());
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));
        ObjectNode body = request(true).put("model", "multimodal-basic");
        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));
        ArgumentCaptor<QuotaService.ReserveCommand> reserveCommand =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        Disposable execution = service.openRoutedStream(
                        compositeKey, body, "req_cancel_route_setup",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null, admission)
                .subscribe();
        verify(limits, timeout(1_000)).activateRoute(
                any(), eq(CHANNEL_B), eq(9), eq(3));
        verify(quota).reserve(reserveCommand.capture());
        assertThat(admission.claimed()).isTrue();
        execution.dispose();

        verify(quota, timeout(1_000)).release(
                reserveCommand.getValue().reservationId(),
                QuotaService.ReleaseReason.CLIENT_CANCELLED);
        verify(limits, timeout(1_000)).release(admission.lease());
        verify(limits, times(1)).release(admission.lease());
        assertThat(adapter.streamCalls).isZero();
    }

    @Test
    void failedPublicAdmissionReleaseCanBeRetriedUntilOneReleaseSucceeds() {
        ChatCompletionService service = new ChatCompletionService(
                mock(ChatCompletionService.GatewayCatalog.class), usage, quota, limits,
                new TokenEstimator(3, 1.25), new ChannelRouter(), circuits,
                List.of(), JSON, Duration.ofMinutes(2));
        RequestLimitService.LimitLease lease = new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "multimodal-basic",
                new UUID(0, 0), 60, 10, RequestLimitService.Store.REDIS);
        ChatCompletionService.PublicAdmission admission =
                new ChatCompletionService.PublicAdmission(publicPrepared(), lease);
        when(limits.release(lease)).thenReturn(
                Mono.error(new IllegalStateException("redis unavailable")), Mono.empty());

        assertThatThrownBy(() -> service.releasePublicAdmission(admission)
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");
        service.releasePublicAdmission(admission).block(Duration.ofSeconds(3));
        service.releasePublicAdmission(admission).block(Duration.ofSeconds(3));

        verify(limits, times(2)).release(lease);
    }

    @Test
    void publicAdmissionUsesOnlyTheExactOnceLimitAcquisitionPath() {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(compositeKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        RequestLimitService.LimitLease lease = new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "multimodal-basic",
                new UUID(0, 0), 60, 10, RequestLimitService.Store.REDIS);
        doReturn(Mono.just(lease)).when(limits).acquirePublic(any());
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(), JSON, Duration.ofMinutes(2));

        ChatCompletionService.PublicAdmission admission = service.admitPublic(
                compositeKey, "multimodal-basic").block(Duration.ofSeconds(3));

        assertThat(admission.lease()).isEqualTo(lease);
        ArgumentCaptor<RequestLimitService.LimitCommand> command =
                ArgumentCaptor.forClass(RequestLimitService.LimitCommand.class);
        verify(limits).acquirePublic(command.capture());
        verify(limits, never()).acquire(any());
        assertThat(command.getValue().channelId()).isEqualTo(new UUID(0, 0));
        assertThat(command.getValue().modelCode()).isEqualTo("multimodal-basic");
    }

    @Test
    void cancelledPublicAdmissionReleaseCanBeRetriedUntilOneReleaseSucceeds() {
        ChatCompletionService service = new ChatCompletionService(
                mock(ChatCompletionService.GatewayCatalog.class), usage, quota, limits,
                new TokenEstimator(3, 1.25), new ChannelRouter(), circuits,
                List.of(), JSON, Duration.ofMinutes(2));
        RequestLimitService.LimitLease lease = new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "multimodal-basic",
                new UUID(0, 0), 60, 10, RequestLimitService.Store.REDIS);
        ChatCompletionService.PublicAdmission admission =
                new ChatCompletionService.PublicAdmission(publicPrepared(), lease);
        doReturn(Mono.never(), Mono.empty()).when(limits).release(lease);

        Disposable firstRelease = service.releasePublicAdmission(admission).subscribe();
        verify(limits, timeout(1_000)).release(lease);
        firstRelease.dispose();
        service.releasePublicAdmission(admission).block(Duration.ofSeconds(3));
        service.releasePublicAdmission(admission).block(Duration.ofSeconds(3));

        verify(limits, times(2)).release(lease);
    }

    @Test
    void concurrentSuccessfulPublicAdmissionReleaseRemainsIdempotent() {
        ChatCompletionService service = new ChatCompletionService(
                mock(ChatCompletionService.GatewayCatalog.class), usage, quota, limits,
                new TokenEstimator(3, 1.25), new ChannelRouter(), circuits,
                List.of(), JSON, Duration.ofMinutes(2));
        RequestLimitService.LimitLease lease = new RequestLimitService.LimitLease(
                UUID.randomUUID(), KEY_ID, USER_ID, "multimodal-basic",
                new UUID(0, 0), 60, 10, RequestLimitService.Store.REDIS);
        ChatCompletionService.PublicAdmission admission =
                new ChatCompletionService.PublicAdmission(publicPrepared(), lease);
        when(limits.release(lease)).thenReturn(Mono.delay(Duration.ofMillis(50)).then());

        Mono.when(
                        service.releasePublicAdmission(admission),
                        service.releasePublicAdmission(admission))
                .block(Duration.ofSeconds(3));
        service.releasePublicAdmission(admission).block(Duration.ofSeconds(3));

        verify(limits, times(1)).release(lease);
    }

    @Test
    void routedStreamsExposeOnlyThePublicModelAlias() throws Exception {
        ApiKeyService.ApiKeyContext compositeKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.RouteTarget route = target(CHANNEL_A, "MOCK");
        ChatCompletionService.GatewayCatalog catalog = (
                context, publicModel, routingModel, capabilities) -> Mono.just(
                        new ChatCompletionService.PreparedRequest(
                                MODEL_ID, publicModel, ROUTING_MODEL_ID, routingModel,
                                8_192, RequestLimitService.Thresholds.all(100),
                                RequestLimitService.Thresholds.all(10), List.of(route)));
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-routed", 900, "done",
                        new ProviderAdapter.TokenUsage(10, 20, 30))));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));
        ObjectNode request = request(true);
        request.put("model", "multimodal-basic");
        request.putObject("stream_options").put("include_usage", true);

        List<String> events = service.openRoutedStream(
                        compositeKey, request,
                        "req_00000000000000000000000000000054",
                        "multimodal-basic", "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT), null)
                .flatMapMany(stream -> stream)
                .collectList().block(Duration.ofSeconds(3));

        assertThat(events).isNotEmpty().endsWith("[DONE]");
        for (String event : events) {
            if (!"[DONE]".equals(event)) {
                assertThat(JSON.readTree(event).path("model").asText())
                        .isEqualTo("multimodal-basic");
            }
        }
        assertThat(adapter.modelAliases).containsExactly("text-basic");
    }

    @Test
    void appliesTargetAwarePreparationToATrustedResponsesCompletion() {
        ObjectNode body = request(false);
        var content = ((ObjectNode) body.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "input_file").put("file_id", "file_owned");
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(
                CHANNEL_A, "MOCK", Set.of(
                        ChannelRouter.Capability.TEXT, ChannelRouter.Capability.FILE))), List.of(adapter));

        service.completePrepared(
                        apiKey, body, "req_00000000000000000000000000000051",
                        Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.FILE),
                        (source, target, provider) -> Mono.fromSupplier(() -> {
                            ObjectNode bound = (ObjectNode) source.deepCopy();
                            bound.path("messages").get(0).path("content").get(0)
                                    .path("file_id");
                            ((ObjectNode) bound.path("messages").get(0).path("content").get(0))
                                    .put("file_id", "upstream-" + target.channel().id());
                            return bound;
                        }))
                .block(Duration.ofSeconds(3));

        assertThat(adapter.requestBodies.getFirst().path("messages").get(0)
                .path("content").get(0).path("file_id").asText())
                .isEqualTo("upstream-" + CHANNEL_A);
    }

    @Test
    void appliesTargetAwarePreparationToATrustedResponsesStream() {
        ObjectNode body = request(true);
        var content = ((ObjectNode) body.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "input_file").put("file_id", "file_owned");
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-prepared", 900, "done", new ProviderAdapter.TokenUsage(10, 20, 30))));
        ChatCompletionService service = service(List.of(target(
                CHANNEL_A, "MOCK", Set.of(
                        ChannelRouter.Capability.TEXT, ChannelRouter.Capability.FILE))), List.of(adapter));

        service.openPreparedStream(
                        apiKey, body, "req_00000000000000000000000000000052",
                        Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.FILE),
                        (source, target, provider) -> Mono.fromSupplier(() -> {
                            ObjectNode bound = (ObjectNode) source.deepCopy();
                            ((ObjectNode) bound.path("messages").get(0).path("content").get(0))
                                    .put("file_id", "upstream-" + target.channel().id());
                            return bound;
                        }))
                .flatMapMany(stream -> stream)
                .collectList().block(Duration.ofSeconds(3));

        assertThat(adapter.requestBodies.getFirst().path("messages").get(0)
                .path("content").get(0).path("file_id").asText())
                .isEqualTo("upstream-" + CHANNEL_A);
    }

    @ParameterizedTest
    @CsvSource({
            "max_tokens, 500, 500",
            "max_completion_tokens, 500, 500",
            "absent, 0, 8192"
    })
    void upstreamOutputLimitMatchesTheBoundedReservation(
            String field, int clientValue, int expectedOutput) {
        // "absent" 那一行期望 8192，即网关自身的默认值 ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS。
        // 它曾经是"模型声明的上限"（这个用例里是 10000），因为那时"客户端没限制"直接落到
        // 套餐/模型/系统三层；2026-10-19 系统上限抬到 100 万之后，那样会让每个默认请求
        // 都按 100 万向供应商索取并预留，于是"没要求"改为按默认值 8192 处理。
        //
        // 这条用例真正守的不变式没有变：向供应商请求的数字，恰好就是预留所覆盖的数字。
        // 按一个上限预留、却向供应商请求另一个上限，正是让结算无法收回的原因。
        ObjectNode body = request(false);
        body.remove(List.of("max_tokens", "max_completion_tokens"));
        if (!"absent".equals(field)) {
            body.put(field, clientValue);
        }
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.just(
                new ChatCompletionService.PreparedRequest(
                        MODEL_ID, publicModel, 10_000, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(target(CHANNEL_A, "MOCK"))));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));
        ArgumentCaptor<QuotaService.ReserveCommand> reservation =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        service.complete(apiKey, body, "req_00000000000000000000000000000045")
                .block(Duration.ofSeconds(3));

        JsonNode forwarded = adapter.requestBodies.getFirst();
        assertThat(forwarded.path("max_tokens").asInt(-1))
                .isEqualTo("max_completion_tokens".equals(field) ? -1 : expectedOutput);
        assertThat(forwarded.path("max_completion_tokens").asInt(-1))
                .isEqualTo("max_completion_tokens".equals(field) ? expectedOutput : -1);
        verify(quota).reserve(reservation.capture());
        String messages = body.path("messages").toString();
        long input = new TokenEstimator(3, 1.25)
                .estimateTextTokens(messages.codePointCount(0, messages.length()));
        assertThat(reservation.getValue().tokens()).isEqualTo(input + expectedOutput);
    }

    @Test
    void theReservationCoversEveryCompletionWhileTheForwardedLimitStaysPerCompletion() {
        // OpenAI 语义：n 是向供应商请求多少个补全，max_tokens 是其中每一个的上限。
        // 必须把每个补全的上限发给供应商，而预留必须覆盖全部 n —— 只预留一个补全的输出，
        // 正是客户端可能付一个的钱却收到 n 个的原因。
        ObjectNode body = request(false);
        body.put("max_tokens", 64);
        body.put("n", ProviderAdapter.MAX_COMPLETIONS);
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));
        ArgumentCaptor<QuotaService.ReserveCommand> reservation =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        service.complete(apiKey, body, "req_00000000000000000000000000000053")
                .block(Duration.ofSeconds(3));

        JsonNode forwarded = adapter.requestBodies.getFirst();
        assertThat(forwarded.path("n").asInt(-1)).isEqualTo(ProviderAdapter.MAX_COMPLETIONS);
        assertThat(forwarded.path("max_tokens").asInt(-1)).isEqualTo(64);
        verify(quota).reserve(reservation.capture());
        assertThat(reservation.getValue().outputTokens())
                .isEqualTo(ProviderAdapter.MAX_COMPLETIONS * 64L);
        assertThat(reservation.getValue().tokens())
                .isEqualTo(reservation.getValue().inputTokens()
                        + ProviderAdapter.MAX_COMPLETIONS * 64L);
    }

    @Test
    void aRequestForOneCompletionReservesExactlyWhatAnAbsentNReserves() {
        ObjectNode absent = request(false);
        absent.put("max_tokens", 64);
        ObjectNode explicit = request(false);
        explicit.put("max_tokens", 64);
        explicit.put("n", 1);
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));
        ArgumentCaptor<QuotaService.ReserveCommand> reservations =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        service.complete(apiKey, absent, "req_00000000000000000000000000000054")
                .block(Duration.ofSeconds(3));
        service.complete(apiKey, explicit, "req_00000000000000000000000000000055")
                .block(Duration.ofSeconds(3));

        verify(quota, times(2)).reserve(reservations.capture());
        QuotaService.ReserveCommand withoutN = reservations.getAllValues().get(0);
        QuotaService.ReserveCommand withN = reservations.getAllValues().get(1);
        assertThat(withN.tokens()).isEqualTo(withoutN.tokens());
        assertThat(withN.inputTokens()).isEqualTo(withoutN.inputTokens());
        assertThat(withN.outputTokens()).isEqualTo(withoutN.outputTokens());
        assertThat(withN.outputTokens()).isEqualTo(64);
    }

    @Test
    void completionCountsAboveTheSupportedBoundAreRejectedBeforeLimitQuotaAndProvider() {
        ObjectNode invalid = request(false);
        invalid.put("n", ProviderAdapter.MAX_COMPLETIONS + 1);
        StubAdapter adapter = StubAdapter.validatingComplete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalid)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_request");

        verify(limits, never()).acquire(any());
        verify(quota, never()).reserve(any());
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void imageRequestsReserveDeterministicMediaTokensWithoutCountingRawPayload() {
        ObjectNode urlRequest = imageRequest("https://example.test/image.png");
        ObjectNode base64Request = imageRequest("data:image/png;base64," + "A".repeat(20_000));
        ObjectNode textOnly = request(false);
        var textContent = ((ObjectNode) textOnly.withArray("messages").get(0)).putArray("content");
        textContent.addObject().put("type", "text").put("text", "describe");
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(
                CHANNEL_A, "MOCK", Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE))),
                List.of(adapter));
        ArgumentCaptor<QuotaService.ReserveCommand> reservations =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);

        service.complete(apiKey, urlRequest, "req_00000000000000000000000000000046")
                .block(Duration.ofSeconds(3));
        service.complete(apiKey, base64Request, "req_00000000000000000000000000000047")
                .block(Duration.ofSeconds(3));
        service.complete(apiKey, textOnly, "req_00000000000000000000000000000048")
                .block(Duration.ofSeconds(3));

        verify(quota, times(3)).reserve(reservations.capture());
        List<QuotaService.ReserveCommand> captured = reservations.getAllValues();
        assertThat(captured.get(0).tokens()).isEqualTo(captured.get(1).tokens());
        assertThat(captured.get(0).tokens()).isGreaterThan(captured.get(2).tokens() + 1_000);
    }

    @Test
    void weightedSelectionAcquiresTheLeaseForTheSelectedFirstChannel() {
        ChatCompletionService.RouteTarget first = weightedTarget(CHANNEL_A, "FIRST", 1);
        ChatCompletionService.RouteTarget selected = weightedTarget(CHANNEL_B, "SECOND", 9);
        ChannelRouter router = mock(ChannelRouter.class);
        when(router.plan(any(), any())).thenReturn(List.of(selected.candidate(), first.candidate()));
        StubAdapter firstAdapter = StubAdapter.complete("FIRST", response(10, 20));
        StubAdapter selectedAdapter = StubAdapter.complete("SECOND", response(10, 20));
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.just(
                new ChatCompletionService.PreparedRequest(
                        MODEL_ID, publicModel, 8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(first, selected)));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                router, circuits, List.of(firstAdapter, selectedAdapter), JSON, Duration.ofMinutes(2));

        service.complete(apiKey, request(false), "req_00000000000000000000000000000035")
                .block(Duration.ofSeconds(3));

        ArgumentCaptor<RequestLimitService.LimitLease> lease =
                ArgumentCaptor.forClass(RequestLimitService.LimitLease.class);
        verify(limits).acquire(any());
        verify(limits).rebindChannel(lease.capture(), eq(CHANNEL_B));
        assertThat(selectedAdapter.completeCalls).isEqualTo(1);
        assertThat(lease.getValue().channelId()).isEqualTo(CHANNEL_A);
    }

    @Test
    void releasesReservationWhenAStreamFailoverCannotAcquireItsLimitLease() {
        StubAdapter first = StubAdapter.stream("FIRST", Flux.error(new IllegalStateException("connect")));
        StubAdapter second = StubAdapter.stream("SECOND", Flux.just(chunk("chatcmpl-6", 600, "wrong", null)));
        doAnswer(invocation -> CHANNEL_B.equals(invocation.getArgument(1))
                ? Mono.error(new ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"))
                : Mono.just(invocation.getArgument(0)))
                .when(limits).rebindChannel(any(), any());
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000006")
                .materialize().collectList().block(Duration.ofSeconds(3));

        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        assertThat(second.streamCalls).isZero();
    }

    @Test
    void releasesReservationWhenAJsonFailoverCannotAcquireItsLimitLease() {
        StubAdapter first = StubAdapter.complete("FIRST", Mono.error(new IllegalStateException("connect")));
        StubAdapter second = StubAdapter.complete("SECOND", response(10, 20));
        doAnswer(invocation -> CHANNEL_B.equals(invocation.getArgument(1))
                ? Mono.error(new ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"))
                : Mono.just(invocation.getArgument(0)))
                .when(limits).rebindChannel(any(), any());
        ChatCompletionService service = service(
                List.of(target(CHANNEL_A, "FIRST"), target(CHANNEL_B, "SECOND")),
                List.of(first, second));

        assertThatThrownBy(() -> service.complete(
                        apiKey, request(false), "req_00000000000000000000000000000008")
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(ResponseStatusException.class);

        verify(quota).release(any(), eq(QuotaService.ReleaseReason.UPSTREAM_FAILURE));
        assertThat(second.completeCalls).isZero();
    }

    @Test
    void settlementFailureStillReleasesStreamLeaseAndWritesRecoveryUsage() {
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                chunk("chatcmpl-7", 700, "complete", new ProviderAdapter.TokenUsage(10, 20, 30))));
        when(quota.settle(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("settlement unavailable")));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000009")
                .collectList().block(Duration.ofSeconds(3));

        verify(limits, timeout(1_000)).release(any());
        assertThat(usage.awaitEntry().outcome()).isEqualTo("SUCCEEDED");
        assertThat(usage.economicsSuppressed).isTrue();
    }

    @Test
    void recordsProviderFailuresInTheChannelModelCircuit() {
        StubAdapter adapter = StubAdapter.complete("MOCK", Mono.error(new IllegalStateException("offline")));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.complete(apiKey, request(false), "req_00000000000000000000000000000010")
                .materialize().block(Duration.ofSeconds(3));

        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void repeatedProviderTimeoutsOpenTheChannelModelCircuit() {
        useTwoCallCircuitWindow();
        StubAdapter adapter = StubAdapter.complete("MOCK", Mono.never());
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.just(
                new ChatCompletionService.PreparedRequest(
                        MODEL_ID, publicModel, 8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), List.of(target(CHANNEL_A, "MOCK"))));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON,
                Duration.ofMinutes(2), Duration.ofMillis(80));

        for (int attempt = 0; attempt < 2; attempt++) {
            String requestId = "req_0000000000000000000000000000002" + attempt;
            service.complete(apiKey, request(false), requestId)
                    .materialize().block(Duration.ofSeconds(2));
        }

        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(adapter.completeCalls).isEqualTo(2);
    }

    @Test
    void repeatedFirstChunkProtocolFailuresOpenTheChannelModelCircuit() {
        useTwoCallCircuitWindow();
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.just(
                new ProviderAdapter.ProviderChunk(JSON.createArrayNode(), null)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        for (int attempt = 0; attempt < 2; attempt++) {
            String requestId = "req_0000000000000000000000000000003" + attempt;
            service.stream(apiKey, request(true), requestId)
                    .materialize().collectList().block(Duration.ofSeconds(3));
        }

        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(adapter.streamCalls).isEqualTo(2);
    }

    @Test
    void clientCancellationReleasesCircuitPermissionWithoutRecordingFailure() {
        useTwoCallCircuitWindow();
        StubAdapter adapter = StubAdapter.stream("MOCK", Flux.concat(
                Flux.just(chunk("chatcmpl-cancel", 300, "partial", null)), Flux.never()));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.stream(apiKey, request(true), "req_00000000000000000000000000000032")
                .take(1).blockLast(Duration.ofSeconds(3));

        CircuitBreaker.Metrics metrics = circuits.circuitBreaker(CHANNEL_A, "text-basic").getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
        assertThat(metrics.getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void providerClientErrorsDoNotTripTheSharedChannelCircuit() {
        StubAdapter adapter = StubAdapter.complete("MOCK", Mono.error(
                new ProviderAdapter.ProviderException(400, "invalid_request")));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.complete(apiKey, request(false), "req_00000000000000000000000000000016")
                .materialize().block(Duration.ofSeconds(3));

        assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                .getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "file_url", "input_file", "file_id", "file_data"})
    void rejectsFileContentPartsBeforeCatalogLimitsAndReservation(String partType) {
        ObjectNode fileRequest = request(false);
        var content = ((ObjectNode) fileRequest.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "text").put("text", "summarize this file");
        content.addObject().put("type", partType).put("file_id", "file-1");
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));

        assertThatThrownBy(() -> service.complete(
                        apiKey, fileRequest, "req_00000000000000000000000000000017")
                .block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("invalid_chat_request");

        verify(catalog, never()).prepare(any(), any(), any(), any());
        verify(limits, never()).acquire(any());
        verify(quota, never()).reserve(any());
        assertThat(adapter.completeCalls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"text\",\"text\":\"forged\",\"file_id\":\"file-1\"}",
            "{\"type\":\"text\",\"text\":\"forged\",\"metadata\":{\"file_data\":\"payload\"}}",
            "{\"type\":\"text\",\"text\":\"forged\",\"metadata\":{\"fileUrl\":\"https://files.test/1\"}}",
            "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://images.test/1\",\"file-name\":\"secret.pdf\"}}"
    })
    void rejectsRecursiveFileFieldsRegardlessOfForgedContentType(String partJson) throws Exception {
        ObjectNode invalid = request(false);
        var content = ((ObjectNode) invalid.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "text").put("text", "inspect");
        content.add(JSON.readTree(partJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @Test
    void invalidSupportedOptionsReturn400BeforeLimitQuotaAndProvider() {
        ObjectNode invalid = request(false);
        invalid.put("top_p", "not-a-number");
        StubAdapter adapter = StubAdapter.validatingComplete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalid)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_request");

        verify(limits, never()).acquire(any());
        verify(quota, never()).reserve(any());
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void malformedStreamOptionsReturn400BeforeLimitQuotaAndProvider() {
        ObjectNode invalid = request(false);
        invalid.putObject("stream_options").put("include_usage", "yes");
        StubAdapter adapter = StubAdapter.validatingComplete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalid)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_request");

        verify(limits, never()).acquire(any());
        verify(quota, never()).reserve(any());
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void malformedToolsReturn400BeforeLimitQuotaAndProvider() {
        ObjectNode invalid = request(false);
        invalid.putArray("tools").addObject();
        StubAdapter adapter = StubAdapter.validatingComplete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalid)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_request");

        verify(limits, never()).acquire(any());
        verify(quota, never()).reserve(any());
        assertThat(adapter.completeCalls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true"})
    void nonTextualModelReturns400BeforeCatalogLimitsQuotaAndProvider(String modelJson) throws Exception {
        ObjectNode invalid = request(false);
        invalid.set("model", JSON.readTree(modelJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123",
            "\"sometimes\"",
            "{}",
            "{\"type\":\"function\"}",
            "{\"type\":\"function\",\"function\":{}}",
            "{\"type\":\"function\",\"function\":{\"name\":1}}"
    })
    void malformedToolChoiceReturns400BeforeCatalogLimitsQuotaAndProvider(String toolChoiceJson)
            throws Exception {
        ObjectNode invalid = request(false);
        invalid.set("tool_choice", JSON.readTree(toolChoiceJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"type\":\"yaml\"}",
            "{\"type\":\"json_schema\"}",
            "{\"type\":\"json_schema\",\"json_schema\":{}}",
            "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"result\",\"schema\":[]}}",
            "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"result\",\"schema\":{},\"strict\":\"yes\"}}"
    })
    void malformedResponseFormatReturns400BeforeCatalogLimitsQuotaAndProvider(String responseFormatJson)
            throws Exception {
        ObjectNode invalid = request(false);
        invalid.set("response_format", JSON.readTree(responseFormatJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[{\"type\":\"function\",\"function\":{\"name\":\"contains spaces\"}}]",
            "[{\"type\":\"function\",\"function\":{\"name\":\"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_--\"}}]"
    })
    void malformedFunctionToolsReturn400BeforeCatalogLimitsQuotaAndProvider(String toolsJson)
            throws Exception {
        ObjectNode invalid = request(false);
        invalid.set("tools", JSON.readTree(toolsJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[{}]",
            "[{\"id\":1,\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]",
            "[{\"id\":\"call_1\",\"type\":\"other\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]",
            "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{}}]",
            "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"bad name\",\"arguments\":\"{}\"}}]",
            "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":{}}}]"
    })
    void malformedMessageToolCallsReturn400BeforeCatalogLimitsQuotaAndProvider(String toolCallsJson)
            throws Exception {
        ObjectNode invalid = request(false);
        ObjectNode message = (ObjectNode) invalid.withArray("messages").get(0);
        message.put("role", "assistant");
        message.set("tool_calls", JSON.readTree(toolCallsJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"tool_call_id\":123}",
            "{\"tool_call_id\":\"\"}",
            "{\"name\":123}",
            "{\"name\":\"\"}"
    })
    void malformedMessageMetadataReturns400BeforeCatalogLimitsQuotaAndProvider(String metadataJson)
            throws Exception {
        ObjectNode invalid = request(false);
        ObjectNode message = (ObjectNode) invalid.withArray("messages").get(0);
        message.setAll((ObjectNode) JSON.readTree(metadataJson));

        assertInvalidBeforeCatalogLimitsQuotaAndProvider(invalid);
    }

    @Test
    void preservesMixedImageAndTextChatRequests() {
        ObjectNode imageRequest = request(false);
        var content = ((ObjectNode) imageRequest.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "text").put("text", "describe this image");
        content.addObject().put("type", "image_url")
                .putObject("image_url").put("url", "https://example.test/a.png");
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(target(
                CHANNEL_A, "MOCK", Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE))),
                List.of(adapter));

        service.complete(apiKey, imageRequest, "req_00000000000000000000000000000018")
                .block(Duration.ofSeconds(3));

        assertThat(adapter.completeCalls).isEqualTo(1);
    }

    @Test
    void requiresImageCapabilityOnTheSelectedMapping() {
        ObjectNode mixed = request(false);
        var content = ((ObjectNode) mixed.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", "https://example.test/a.png");
        ChatCompletionService.RouteTarget incomplete = target(CHANNEL_A, "MOCK",
                Set.of(ChannelRouter.Capability.TEXT));
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = service(List.of(incomplete), List.of(adapter));

        assertThatThrownBy(() -> service.complete(
                        apiKey, mixed, "req_00000000000000000000000000000011")
                .block(Duration.ofSeconds(3)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void streamPreflightFailureUsesTheOpenAiErrorEnvelopeInsteadOfA200Stream() {
        ChatCompletionService service = mock(ChatCompletionService.class);
        when(service.openStream(any(), any(), any())).thenReturn(Mono.error(
                new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "active_package_required")));
        ChatCompletionController controller = new ChatCompletionController(service);

        client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(true))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("package_unavailable");
    }

    @Test
    void upstreamFailureBeforeTheFirstStreamChunkUsesAnErrorResponse() {
        ChatCompletionService service = mock(ChatCompletionService.class);
        when(service.openStream(any(), any(), any())).thenReturn(Mono.just(
                Flux.error(new ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "no_usable_channel"))));
        ChatCompletionController controller = new ChatCompletionController(service);

        client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(true))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("upstream_unavailable");
    }

    @Test
    void returnsOpenAiErrorEnvelopeForUnavailablePackage() {
        ChatCompletionService service = mock(ChatCompletionService.class);
        when(service.complete(any(), any(), any())).thenReturn(Mono.error(
                new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "active_package_required")));
        ChatCompletionController controller = new ChatCompletionController(service);

        client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("No usable package")
                .jsonPath("$.error.type").isEqualTo("quota_error")
                .jsonPath("$.error.code").isEqualTo("package_unavailable")
                .jsonPath("$.request_id").value(value -> assertThat(value.toString()).startsWith("req_"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directCatalogEmptyReportsNoUsableChannel(boolean stream) {
        ChatCompletionService.GatewayCatalog catalog =
                (context, publicModel, routingModel, capabilities) -> Mono.empty();
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(), JSON, Duration.ofMinutes(2));

        assertDirectFailureReason(service, stream, "no_usable_channel");

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(stream))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("No upstream channel is available")
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("upstream_unavailable");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directZeroTargetsRetainsNoUsableChannelEnvelope(boolean stream) {
        ChatCompletionService.GatewayCatalog catalog =
                (context, publicModel, routingModel, capabilities) -> Mono.just(
                        new ChatCompletionService.PreparedRequest(
                                MODEL_ID, publicModel, ROUTING_MODEL_ID, routingModel,
                                8_192, RequestLimitService.Thresholds.all(100),
                                RequestLimitService.Thresholds.all(10), List.of()));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(), JSON, Duration.ofMinutes(2));

        assertDirectFailureReason(service, stream, "no_usable_channel");

        client(new ChatCompletionController(service)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(stream))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("No upstream channel is available")
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("upstream_unavailable");
    }

    @Test
    void dependencyFailureKeepsItsDistinctOpenAiErrorCode() {
        ChatCompletionService service = mock(ChatCompletionService.class);
        when(service.complete(any(), any(), any())).thenReturn(Mono.error(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "dependency_unavailable")));
        ChatCompletionController controller = new ChatCompletionController(service);

        client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("Dependency unavailable")
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("dependency_unavailable");
    }

    @ParameterizedTest
    @CsvSource({
            "403, model_not_allowed, model_not_allowed",
            "503, multimodal_route_unavailable, multimodal_route_unavailable",
            "503, document_converter_unavailable, document_converter_unavailable",
            "504, multimodal_preprocessing_timeout, multimodal_preprocessing_timeout"
    })
    void exposesStableCompositeErrorCodes(
            int sourceStatus, String reason, String expectedCode) {
        ChatCompletionService service = mock(ChatCompletionService.class);
        when(service.complete(any(), any(), any())).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.valueOf(sourceStatus), reason,
                        new IllegalStateException("sensitive-internal-detail"))));
        ChatCompletionController controller = new ChatCompletionController(service);

        client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(sourceStatus)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo(expectedCode)
                .jsonPath("$.error.message").value(message ->
                        assertThat(message.toString()).doesNotContain("sensitive-internal-detail"));
    }

    @Test
    void unknownFailuresRemainSanitizedInternalErrors() {
        ChatCompletionService service = mock(ChatCompletionService.class);
        when(service.complete(any(), any(), any())).thenReturn(Mono.error(
                new IllegalStateException("sensitive-provider-body")));
        ChatCompletionController controller = new ChatCompletionController(service);

        client(controller).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("internal_error")
                .jsonPath("$.error.message").isEqualTo("Internal server error");
    }

    @Test
    void apiKeyAuthenticationFailureUsesOpenAiErrorEnvelope() {
        ApiKeyService authenticator = mock(ApiKeyService.class);
        when(authenticator.authenticate(any())).thenReturn(Mono.empty());
        ChatCompletionService service = mock(ChatCompletionService.class);
        WebTestClient web = WebTestClient.bindToController(new ChatCompletionController(service))
                .webFilter(new ApiKeyWebFilter(authenticator))
                .configureClient()
                .responseTimeout(Duration.ofSeconds(3))
                .build();

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(false))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches("x-request-id", "req_[0-9a-f]{32}")
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("Invalid API key")
                .jsonPath("$.error.type").isEqualTo("authentication_error")
                .jsonPath("$.error.code").isEqualTo("invalid_api_key")
                .jsonPath("$.request_id").value(value -> assertThat(value.toString()).startsWith("req_"));
    }

    @Test
    void malformedJsonAfterAuthenticationUsesOpenAiInvalidRequestEnvelope() {
        ApiKeyService authenticator = mock(ApiKeyService.class);
        when(authenticator.authenticate("sk-valid")).thenReturn(Mono.just(apiKey));
        ChatCompletionService service = mock(ChatCompletionService.class);
        WebTestClient web = WebTestClient.bindToController(new ChatCompletionController(service))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter(new ApiKeyWebFilter(authenticator))
                .configureClient()
                .responseTimeout(Duration.ofSeconds(3))
                .build();

        web.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-valid")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueMatches("x-request-id", "req_[0-9a-f]{32}")
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_request")
                .jsonPath("$.request_id").value(value -> assertThat(value.toString()).startsWith("req_"));
    }

    @Test
    void adaptersEnforceCapabilitiesAndStripUnknownVendorFields() {
        ObjectNode body = request(false);
        body.put("vendor_secret", "must-not-leak");
        body.put("top_p", 0.8);
        ((ObjectNode) body.withArray("messages").get(0)).put("vendor_message_flag", true);
        body.putObject("stream_options").put("include_usage", true).put("vendor_trace", true);

        JsonNode forwarded = ProviderAdapter.sanitize(body, "upstream-model", false);

        assertThat(forwarded.path("model").asText()).isEqualTo("upstream-model");
        assertThat(forwarded.has("vendor_secret")).isFalse();
        assertThat(forwarded.path("messages").get(0).has("vendor_message_flag")).isFalse();
        assertThat(forwarded.path("stream_options").has("vendor_trace")).isFalse();
        assertThat(forwarded.path("top_p").asDouble()).isEqualTo(0.8);
        assertThat(DeepSeekAdapter.supportedCapabilities()).containsExactly(ChannelRouter.Capability.TEXT);
        assertThat(BailianAdapter.supportedCapabilities()).containsExactlyInAnyOrder(
                ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE, ChannelRouter.Capability.FILE);
    }

    @Test
    void upstreamErrorBodiesAreNotRetainedInGatewayErrors() throws Exception {
        HttpServer server = failingProviderServer(500, "sensitive upstream diagnostic", "text/plain");
        try {
            ProviderAdapter.ProviderException error = upstreamFailure(
                    server, "req_00000000000000000000000000000007");

            assertThat(error)
                    .hasMessage("provider_http_500")
                    .hasMessageNotContaining("sensitive upstream diagnostic");
            // 原文被丢弃的**理由**在这里：那不是 JSON 错误信封，所以一个字都不保留。
            assertThat(error.upstreamCode()).isNull();
            assertThat(error.upstreamMessage()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void upstreamJsonErrorEnvelopesAreSurfacedForDiagnosis() throws Exception {
        // 上游的 JSON 错误信封是运维唯一能读到的证据：没有它，「模型名不对」与
        // 「地址不对」在 404 上是同一句话。
        HttpServer server = failingProviderServer(404, """
                {"error":{"code":"model_not_found",
                 "message":"The model 'upstream-model' does not exist"}}
                """, "application/json");
        try {
            ProviderAdapter.ProviderException error = upstreamFailure(
                    server, "req_00000000000000000000000000000008");

            // 状态码前缀必须原样保留：管理端从异常消息里抽上游状态码。
            assertThat(error).hasMessage("provider_http_404: model_not_found: "
                    + "The model 'upstream-model' does not exist");
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.upstreamCode()).isEqualTo("model_not_found");
            assertThat(error.upstreamMessage())
                    .isEqualTo("The model 'upstream-model' does not exist");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jsonBodiesWithoutAnErrorEnvelopeAreStillNotRetained() throws Exception {
        // 能被解析成 JSON 并不等于它是错误信封：没有 code/message 的对象同样不保留，
        // 否则「只保留结构化信封」这条规则会被任意一个 JSON 应答绕过去。
        HttpServer server = failingProviderServer(500,
                "{\"note\":\"sensitive upstream diagnostic\"}", "application/json");
        try {
            ProviderAdapter.ProviderException error = upstreamFailure(
                    server, "req_00000000000000000000000000000009");

            assertThat(error)
                    .hasMessage("provider_http_500")
                    .hasMessageNotContaining("sensitive upstream diagnostic");
            assertThat(error.upstreamMessage()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteJsonProviderUsageIsTreatedAsMissing() throws Exception {
        ObjectNode providerBody = (ObjectNode) response(10, 20).body().deepCopy();
        providerBody.putObject("usage").put("total_tokens", 30);
        HttpServer server = providerServer(providerBody.toString(), "application/json");
        try {
            ProviderAdapter adapter = new DeepSeekAdapter(WebClient.builder(), JSON);
            ProviderAdapter.ProviderResponse result = adapter.complete(new ProviderAdapter.RoutedRequest(
                            "req_00000000000000000000000000000041", "text-basic", request(false),
                            providerChannel(CHANNEL_A, "DEEPSEEK", server)))
                    .block(Duration.ofSeconds(3));

            assertThat(result.usage()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteStreamProviderUsageIsTreatedAsMissing() throws Exception {
        String events = "data: {\"id\":\"chatcmpl-live\",\"object\":\"chat.completion.chunk\","
                + "\"created\":800,\"model\":\"upstream-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-live\",\"object\":\"chat.completion.chunk\","
                + "\"created\":800,\"model\":\"upstream-model\",\"choices\":[],"
                + "\"usage\":{\"total_tokens\":30}}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = providerServer(events, "text/event-stream");
        try {
            ProviderAdapter adapter = new DeepSeekAdapter(WebClient.builder(), JSON);
            List<ProviderAdapter.ProviderChunk> chunks = adapter.stream(new ProviderAdapter.RoutedRequest(
                            "req_00000000000000000000000000000042", "text-basic", request(true),
                            providerChannel(CHANNEL_A, "DEEPSEEK", server)))
                    .collectList().block(Duration.ofSeconds(3));

            assertThat(chunks).hasSize(2);
            assertThat(chunks.getLast().usage()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "10, 20, 5",
            "9223372036854775807, 1, 9223372036854775807"
    })
    void inconsistentJsonProviderUsageIsA5xxCircuitFailure(long input, long output, long total)
            throws Exception {
        ObjectNode providerBody = (ObjectNode) response(10, 20).body().deepCopy();
        providerBody.putObject("usage")
                .put("prompt_tokens", input)
                .put("completion_tokens", output)
                .put("total_tokens", total);
        HttpServer server = providerServer(providerBody.toString(), "application/json");
        try {
            ProviderAdapter adapter = new DeepSeekAdapter(WebClient.builder(), JSON);
            ChatCompletionService.RouteTarget target = new ChatCompletionService.RouteTarget(
                    new ChannelRouter.RouteCandidate(
                            CHANNEL_A, "DEEPSEEK", "text-basic", "upstream-model",
                            Set.of(ChannelRouter.Capability.TEXT), 100, 100,
                            true, true, true, true, true),
                    providerChannel(CHANNEL_A, "DEEPSEEK", server));
            ChatCompletionService service = service(List.of(target), List.of(adapter));

            Signal<ChatCompletionService.CompletionResult> signal = service.complete(
                            apiKey, request(false), "req_00000000000000000000000000000043")
                    .materialize().block(Duration.ofSeconds(3));

            assertThat(signal.getThrowable())
                    .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class,
                            error -> assertThat(error.status()).isEqualTo(502));
            assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                    .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void excessiveJsonProviderUsageFailsOverAndCountsAgainstTheCircuit() throws Exception {
        ObjectNode providerBody = (ObjectNode) response(10, 20).body().deepCopy();
        providerBody.putObject("usage")
                .put("prompt_tokens", 10)
                .put("completion_tokens", 20)
                .put("total_tokens", 31);
        HttpServer server = providerServer(providerBody.toString(), "application/json");
        try {
            ProviderAdapter first = new DeepSeekAdapter(WebClient.builder(), JSON);
            StubAdapter second = StubAdapter.complete("SECOND", response(7, 8));
            ChatCompletionService.RouteTarget firstTarget = new ChatCompletionService.RouteTarget(
                    new ChannelRouter.RouteCandidate(
                            CHANNEL_A, "DEEPSEEK", "text-basic", "upstream-model",
                            Set.of(ChannelRouter.Capability.TEXT), 100, 100,
                            true, true, true, true, true),
                    providerChannel(CHANNEL_A, "DEEPSEEK", server));
            ChatCompletionService service = service(
                    List.of(firstTarget, target(CHANNEL_B, "SECOND")), List.of(first, second));

            ChatCompletionService.CompletionResult result = service.complete(
                            apiKey, request(false), "req_00000000000000000000000000000051")
                    .block(Duration.ofSeconds(3));

            assertThat(result.body().path("usage").path("total_tokens").asLong()).isEqualTo(15);
            assertThat(second.completeCalls).isEqualTo(1);
            assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                    .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void excessiveSseProviderUsageFailsOverBeforeClientBytesAndCountsAgainstTheCircuit()
            throws Exception {
        String events = "data: {\"id\":\"chatcmpl-bad\",\"object\":\"chat.completion.chunk\","
                + "\"created\":800,\"model\":\"upstream-model\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":31}}\n\n"
                + "data: {\"id\":\"chatcmpl-bad\",\"object\":\"chat.completion.chunk\","
                + "\"created\":800,\"model\":\"upstream-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"wrong\"}}]}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = providerServer(events, "text/event-stream");
        try {
            ProviderAdapter first = new DeepSeekAdapter(WebClient.builder(), JSON);
            StubAdapter second = StubAdapter.stream("SECOND", Flux.just(
                    chunk("chatcmpl-fallback", 900, "fallback", null)));
            ChatCompletionService.RouteTarget firstTarget = new ChatCompletionService.RouteTarget(
                    new ChannelRouter.RouteCandidate(
                            CHANNEL_A, "DEEPSEEK", "text-basic", "upstream-model",
                            Set.of(ChannelRouter.Capability.TEXT), 100, 100,
                            true, true, true, true, true),
                    providerChannel(CHANNEL_A, "DEEPSEEK", server));
            ChatCompletionService service = service(
                    List.of(firstTarget, target(CHANNEL_B, "SECOND")), List.of(first, second));

            List<String> output = service.stream(
                            apiKey, request(true), "req_00000000000000000000000000000052")
                    .collectList().block(Duration.ofSeconds(3));

            assertThat(output.getFirst()).contains("\"content\":\"fallback\"");
            assertThat(output.getLast()).isEqualTo("[DONE]");
            assertThat(second.streamCalls).isEqualTo(1);
            assertThat(circuits.circuitBreaker(CHANNEL_A, "text-basic")
                    .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usageLogsNeverRetainProviderBodiesCredentialsOrTemporaryUrls() {
        String providerBody = "sensitive-provider-body";
        String credential = "sk-sensitive-credential";
        String temporaryUrl = "https://files.example.test/object?signature=sensitive";
        StubAdapter adapter = StubAdapter.complete("MOCK", Mono.error(new IllegalStateException(
                providerBody + " " + credential + " " + temporaryUrl)));
        ChatCompletionService service = service(List.of(target(CHANNEL_A, "MOCK")), List.of(adapter));

        service.complete(apiKey, request(false), "req_00000000000000000000000000000039")
                .materialize().block(Duration.ofSeconds(3));

        assertThat(usage.awaitEntry().errorSummary())
                .doesNotContain(providerBody, credential, temporaryUrl)
                .isEqualTo("IllegalStateException");
    }

    @Test
    void bailianAdapterUsesCompatibleSseProtocolAndSanitizedPayload() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> forwarded = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> sseHeader = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            forwarded.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sseHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            byte[] response = ("data: {\"id\":\"chatcmpl-live\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":800,\"model\":\"upstream-model\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-live\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":800,\"model\":\"upstream-model\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ProviderAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
            ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                    CHANNEL_A, UUID.randomUUID(), "BAILIAN",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 2_000,
                    "upstream-model");
            ObjectNode source = request(true);
            source.put("vendor_secret", "must-not-leak");

            List<ProviderAdapter.ProviderChunk> chunks = adapter.stream(
                            new ProviderAdapter.RoutedRequest(
                                    "req_00000000000000000000000000000012", "text-basic", source, channel))
                    .collectList().block(Duration.ofSeconds(3));

            assertThat(chunks).hasSize(2);
            assertThat(chunks.getLast().usage()).satisfies(usage -> {
                assertThat(usage.inputTokens()).isEqualTo(10);
                assertThat(usage.outputTokens()).isEqualTo(20);
                assertThat(usage.totalTokens()).isEqualTo(30);
                assertThat(usage.source()).isEqualTo(ProviderAdapter.UsageSource.PROVIDER_ACTUAL);
            });
            assertThat(sseHeader.get()).isEqualTo("enable");
            assertThat(forwarded.get()).contains("\"model\":\"upstream-model\"")
                    .contains("\"stream\":true")
                    .doesNotContain("vendor_secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerStreamRejectsAnUpstreamResponseWithoutDone() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = ("data: {\"id\":\"chatcmpl-live\","
                    + "\"object\":\"chat.completion.chunk\",\"created\":800,"
                    + "\"model\":\"upstream-model\",\"choices\":[]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ProviderAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
            ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                    CHANNEL_A, UUID.randomUUID(), "BAILIAN",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 2_000,
                    "upstream-model");

            assertThatThrownBy(() -> adapter.stream(new ProviderAdapter.RoutedRequest(
                            "req_00000000000000000000000000000018", "text-basic",
                            request(true), channel))
                    .collectList().block(Duration.ofSeconds(3)))
                    .isInstanceOf(ProviderAdapter.ProviderException.class)
                    .hasMessage("provider_stream_missing_done");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerStreamStopsAtDoneAndDiscardsLaterEvents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = ("data: {\"id\":\"chatcmpl-before\","
                    + "\"object\":\"chat.completion.chunk\",\"created\":800,"
                    + "\"model\":\"upstream-model\",\"choices\":[]}\n\n"
                    + "data: [DONE]\n\n"
                    + "data: {\"id\":\"chatcmpl-after\","
                    + "\"object\":\"chat.completion.chunk\",\"created\":801,"
                    + "\"model\":\"upstream-model\",\"choices\":[]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ProviderAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
            ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                    CHANNEL_A, UUID.randomUUID(), "BAILIAN",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 2_000,
                    "upstream-model");

            List<ProviderAdapter.ProviderChunk> chunks = adapter.stream(
                            new ProviderAdapter.RoutedRequest(
                                    "req_00000000000000000000000000000019", "text-basic",
                                    request(true), channel))
                    .collectList().block(Duration.ofSeconds(3));

            assertThat(chunks).singleElement().satisfies(chunk ->
                    assertThat(chunk.data().path("id").asText()).isEqualTo("chatcmpl-before"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bailianBindsAFileThroughTheCompatibleFilesApi() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> authorization =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> contentType =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> idempotencyKey =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> upload =
                new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/files", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            upload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"file-upstream-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ProviderAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
            ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                    CHANNEL_A, UUID.randomUUID(), "BAILIAN",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 2_000,
                    "upstream-model");
            byte[] content = "file-body".getBytes(StandardCharsets.UTF_8);
            ProviderAdapter.FileObject file = new ProviderAdapter.FileObject(
                    UUID.randomUUID(), "object-key", "text/plain", content.length, content);

            ProviderAdapter.ProviderFileRef bound = adapter.bindFile(file, channel)
                    .block(Duration.ofSeconds(3));

            assertThat(bound.upstreamFileId()).isEqualTo("file-upstream-1");
            assertThat(authorization.get()).isEqualTo("Bearer secret");
            assertThat(contentType.get()).startsWith("multipart/form-data;boundary=");
            assertThat(idempotencyKey.get()).isEqualTo(file.id() + "-" + channel.id());
            assertThat(upload.get()).contains("file-extract").contains("file-body");
        } finally {
            server.stop(0);
        }
    }

    private ChatCompletionService service(
            List<ChatCompletionService.RouteTarget> targets, List<ProviderAdapter> adapters) {
        return timedService(targets, adapters, Duration.ofSeconds(30));
    }

    private ChatCompletionService timedService(
            List<ChatCompletionService.RouteTarget> targets,
            List<ProviderAdapter> adapters,
            Duration totalAttemptTimeout) {
        ChatCompletionService.GatewayCatalog catalog = (context, publicModel, routingModel, capability) -> Mono.just(
                new ChatCompletionService.PreparedRequest(
                        MODEL_ID, publicModel, 8_192, RequestLimitService.Thresholds.all(100),
                        RequestLimitService.Thresholds.all(10), targets));
        return new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, adapters, JSON,
                Duration.ofMinutes(2), totalAttemptTimeout);
    }

    private HttpServer providerServer(String response, String contentType) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 一个只会失败的上游桩：用它钉住「失败时网关保留了什么、又丢掉了什么」。 */
    private HttpServer failingProviderServer(int status, String response, String contentType)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 向该桩发一次真实对话请求，返回它抛出的 {@code ProviderException}。 */
    private ProviderAdapter.ProviderException upstreamFailure(HttpServer server, String requestId) {
        ProviderAdapter adapter = new DeepSeekAdapter(WebClient.builder(), JSON);
        ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                CHANNEL_A, UUID.randomUUID(), "DEEPSEEK",
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 2_000,
                "upstream-model");
        return catchThrowableOfType(
                () -> adapter.complete(new ProviderAdapter.RoutedRequest(
                                requestId, "text-basic", request(false), channel))
                        .block(Duration.ofSeconds(3)),
                ProviderAdapter.ProviderException.class);
    }

    private ProviderAdapter.Channel providerChannel(
            UUID channelId, String providerType, HttpServer server) {
        return new ProviderAdapter.Channel(
                channelId, UUID.randomUUID(), providerType,
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 2_000,
                "upstream-model");
    }

    private void useTwoCallCircuitWindow() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .build();
        circuits = new ChannelCircuitRegistry(CircuitBreakerRegistry.of(config));
    }

    private WebTestClient client(ChatCompletionController controller) {
        return WebTestClient.bindToController(controller)
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE,
                            apiKey);
                    return chain.filter(exchange);
                })
                .configureClient()
                .responseTimeout(Duration.ofSeconds(3))
                .build();
    }

    private ChatCompletionService.RouteTarget target(UUID channelId, String providerType) {
        return target(channelId, providerType, Set.of(ChannelRouter.Capability.TEXT));
    }

    private ChatCompletionService.RouteTarget target(
            UUID channelId, String providerType, Set<ChannelRouter.Capability> capabilities) {
        ChannelRouter.RouteCandidate candidate = new ChannelRouter.RouteCandidate(
                channelId, providerType, "text-basic", "upstream-model",
                capabilities,
                channelId.equals(CHANNEL_A) ? 100 : 90, 100,
                true, true, true, true, true);
        ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                channelId, UUID.randomUUID(), providerType, "http://127.0.0.1:8089", "secret",
                5_000, "upstream-model");
        return new ChatCompletionService.RouteTarget(candidate, channel);
    }

    private ChatCompletionService.RouteTarget disabledTarget() {
        ChatCompletionService.RouteTarget base = target(CHANNEL_A, "MOCK");
        ChannelRouter.RouteCandidate candidate = base.candidate();
        return new ChatCompletionService.RouteTarget(
                new ChannelRouter.RouteCandidate(
                        candidate.channelId(), candidate.providerCode(), candidate.modelCode(),
                        candidate.upstreamModel(), candidate.capabilities(), candidate.priority(),
                        candidate.weight(), false, candidate.concurrencyAvailable(),
                        candidate.circuitPermitted(), candidate.packageAllowed(),
                        candidate.apiKeyAllowed()),
                base.channel());
    }

    private ChatCompletionService.RouteTarget weightedTarget(UUID channelId, String providerType, int weight) {
        ChannelRouter.RouteCandidate candidate = new ChannelRouter.RouteCandidate(
                channelId, providerType, "text-basic", "upstream-model",
                Set.of(ChannelRouter.Capability.TEXT), 100, weight,
                true, true, true, true, true);
        ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                channelId, UUID.randomUUID(), providerType, "http://127.0.0.1:8089", "secret",
                5_000, "upstream-model");
        return new ChatCompletionService.RouteTarget(candidate, channel);
    }

    private static ObjectNode request(boolean stream) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", "text-basic");
        request.put("stream", stream);
        request.put("max_tokens", 20);
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return request;
    }

    private static ObjectNode imageRequest(String url) {
        ObjectNode request = request(false);
        var content = ((ObjectNode) request.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "text").put("text", "describe");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", url);
        return request;
    }

    private void assertInvalidBeforeCatalogLimitsQuotaAndProvider(ObjectNode invalid) {
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        StubAdapter adapter = StubAdapter.complete("MOCK", response(10, 20));
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(adapter), JSON, Duration.ofMinutes(2));

        assertThatThrownBy(() -> service.complete(
                        apiKey, invalid, "req_00000000000000000000000000000040")
                .block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verify(catalog, never()).prepare(any(), any(), any(), any());
        verify(limits, never()).acquire(any());
        verify(quota, never()).reserve(any());
        assertThat(adapter.completeCalls).isZero();
    }

    private static ProviderAdapter.ProviderResponse response(long input, long output) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", "chatcmpl-json");
        body.put("object", "chat.completion");
        body.put("created", 100);
        body.put("model", "upstream-model");
        ObjectNode choice = body.putArray("choices").addObject();
        choice.put("index", 0).put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", "hello");
        body.putObject("usage").put("prompt_tokens", input).put("completion_tokens", output)
                .put("total_tokens", input + output);
        return new ProviderAdapter.ProviderResponse(
                body, new ProviderAdapter.TokenUsage(input, output, input + output));
    }

    private static ChatCompletionService.PreparedPublicRequest publicPrepared() {
        return new ChatCompletionService.PreparedPublicRequest(
                MODEL_ID, "multimodal-basic", 8_192,
                new RequestLimitService.Thresholds(
                        60, 60, 60, Integer.MAX_VALUE, 60),
                new RequestLimitService.Thresholds(
                        10, 10, 10, Integer.MAX_VALUE, 10));
    }

    private ChatCompletionService.PreparedRequest routedPrepared(
            List<ChatCompletionService.RouteTarget> targets) {
        return new ChatCompletionService.PreparedRequest(
                MODEL_ID, "multimodal-basic", ROUTING_MODEL_ID, "text-basic",
                8_192, RequestLimitService.Thresholds.all(100),
                RequestLimitService.Thresholds.all(10), targets);
    }

    private void assertCompositeRouteUnavailableOverHttp(
            boolean stream, Mono<ChatCompletionService.PreparedRequest> routedPreparation) {
        apiKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("multimodal-basic"));
        ChatCompletionService.GatewayCatalog catalog = mock(ChatCompletionService.GatewayCatalog.class);
        when(catalog.preparePublic(apiKey, "multimodal-basic"))
                .thenReturn(Mono.just(publicPrepared()));
        when(catalog.prepare(
                apiKey, "multimodal-basic", "text-basic",
                Set.of(ChannelRouter.Capability.TEXT)))
                .thenReturn(routedPreparation);
        ChatCompletionService service = new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), circuits, List.of(), JSON, Duration.ofMinutes(2));
        CompletionDispatchService dispatcher = mock(CompletionDispatchService.class);
        when(dispatcher.complete(eq(apiKey), any(), any())).thenAnswer(invocation ->
                service.admitPublic(apiKey, "multimodal-basic")
                        .flatMap(admission -> service.completeRouted(
                                apiKey, invocation.getArgument(1), invocation.getArgument(2),
                                "multimodal-basic", "text-basic",
                                Set.of(ChannelRouter.Capability.TEXT), null, admission)));
        when(dispatcher.openStream(eq(apiKey), any(), any())).thenAnswer(invocation ->
                service.admitPublic(apiKey, "multimodal-basic")
                        .flatMap(admission -> service.openRoutedStream(
                                apiKey, invocation.getArgument(1), invocation.getArgument(2),
                                "multimodal-basic", "text-basic",
                                Set.of(ChannelRouter.Capability.TEXT), null, admission)));
        ObjectNode body = request(stream).put("model", "multimodal-basic");

        client(new ChatCompletionController(dispatcher)).post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("Multimodal route is unavailable")
                .jsonPath("$.error.type").isEqualTo("server_error")
                .jsonPath("$.error.code").isEqualTo("multimodal_route_unavailable");

        verify(limits).acquirePublic(any());
        verify(limits).release(any());
    }

    private void assertDirectFailureReason(
            ChatCompletionService service, boolean stream, String expectedReason) {
        Mono<?> execution = stream
                ? service.openStream(apiKey, request(true), "req_direct_reason")
                : service.complete(apiKey, request(false), "req_direct_reason");

        assertThatThrownBy(() -> execution.block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo(expectedReason));
    }

    private void assertDirectNoUsableChannel(ResponseStatusException error) {
        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.getReason()).isEqualTo("no_usable_channel");
        assertThat(OpenAiErrorMapper.descriptor(error)).satisfies(descriptor -> {
            assertThat(descriptor.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(descriptor.message()).isEqualTo("No upstream channel is available");
            assertThat(descriptor.type()).isEqualTo("server_error");
            assertThat(descriptor.code()).isEqualTo("upstream_unavailable");
        });
    }

    private static ObjectNode rewriteFailingBody() {
        return new RewriteFailingObjectNode((ObjectNode) response(10, 20).body());
    }

    private static Mono<ProviderAdapter.ProviderResponse> delayedFailure(String message) {
        return Mono.delay(Duration.ofMillis(80))
                .then(Mono.error(new IllegalStateException(message)));
    }

    private static ProviderAdapter.ProviderChunk chunk(
            String id, long created, String content, ProviderAdapter.TokenUsage usage) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", id);
        body.put("object", "chat.completion.chunk");
        body.put("created", created);
        body.put("model", "upstream-model");
        var choices = body.putArray("choices");
        if (content != null) {
            choices.addObject().put("index", 0).putObject("delta").put("content", content);
        }
        if (usage != null) {
            body.putObject("usage").put("prompt_tokens", usage.inputTokens())
                    .put("completion_tokens", usage.outputTokens())
                    .put("total_tokens", usage.totalTokens());
        }
        return new ProviderAdapter.ProviderChunk(body, usage);
    }

    private static final class StubAdapter implements ProviderAdapter {
        private final String providerType;
        private final Mono<ProviderResponse> complete;
        private final Flux<ProviderChunk> stream;
        private int completeCalls;
        private int streamCalls;
        private boolean validateRequest;
        private final List<JsonNode> requestBodies = new ArrayList<>();
        private final List<String> modelAliases = new ArrayList<>();

        private StubAdapter(String providerType, Mono<ProviderResponse> complete, Flux<ProviderChunk> stream) {
            this.providerType = providerType;
            this.complete = complete;
            this.stream = stream;
        }

        static StubAdapter complete(String providerType, ProviderResponse response) {
            return complete(providerType, Mono.just(response));
        }

        static StubAdapter complete(String providerType, Mono<ProviderResponse> response) {
            return new StubAdapter(providerType, response, Flux.error(new UnsupportedOperationException()));
        }

        static StubAdapter validatingComplete(String providerType, ProviderResponse response) {
            StubAdapter adapter = complete(providerType, response);
            adapter.validateRequest = true;
            return adapter;
        }

        static StubAdapter stream(String providerType, Flux<ProviderChunk> stream) {
            return new StubAdapter(providerType, Mono.error(new UnsupportedOperationException()), stream);
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Set<ChannelRouter.Capability> capabilities() {
            return Set.of(ChannelRouter.Capability.TEXT);
        }

        @Override
        public Mono<ProviderResponse> complete(RoutedRequest request) {
            completeCalls++;
            requestBodies.add(request.body().deepCopy());
            modelAliases.add(request.modelAlias());
            if (validateRequest) {
                ProviderAdapter.sanitize(request.body(), request.channel().upstreamModel(), false);
            }
            return complete;
        }

        @Override
        public Flux<ProviderChunk> stream(RoutedRequest request) {
            streamCalls++;
            requestBodies.add(request.body().deepCopy());
            modelAliases.add(request.modelAlias());
            return stream;
        }

        @Override
        public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
            return Mono.just(new ProviderFileRef("unused"));
        }
    }

    private static final class RecordingUsageWriter implements ChatCompletionService.UsageLogWriter {
        private final List<ChatCompletionService.UsageEntry> entries = new CopyOnWriteArrayList<>();
        private final List<String> accountingCalls;
        private final AtomicReference<String> traceId = new AtomicReference<>();
        private final AtomicReference<String> apiKeyPrefix = new AtomicReference<>();
        private volatile boolean failWrites;
        private volatile boolean economicsSuppressed;

        private RecordingUsageWriter(List<String> accountingCalls) {
            this.accountingCalls = accountingCalls;
        }

        @Override
        public Mono<Void> write(ChatCompletionService.UsageEntry entry) {
            return Mono.deferContextual(context -> {
                accountingCalls.add("log");
                economicsSuppressed = context.getOrDefault("suppressUsageEconomics", false);
                if (failWrites) {
                    return Mono.error(new IllegalStateException("usage log unavailable"));
                }
                entries.add(entry);
                traceId.set(context.getOrDefault("traceId", null));
                apiKeyPrefix.set(context.getOrDefault("apiKeyPrefix", null));
                return Mono.empty();
            });
        }

        ChatCompletionService.UsageEntry awaitEntry() {
            Instant deadline = Instant.now().plusSeconds(2);
            while (entries.isEmpty() && Instant.now().isBefore(deadline)) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            assertThat(entries).isNotEmpty();
            return entries.getFirst();
        }
    }

    private static final class RewriteFailingObjectNode extends ObjectNode {

        private RewriteFailingObjectNode(ObjectNode source) {
            super(JsonNodeFactory.instance);
            setAll(source);
        }

        @Override
        public ObjectNode deepCopy() {
            throw new ProviderAdapter.ProviderException(502, "invalid_provider_response");
        }
    }
}
