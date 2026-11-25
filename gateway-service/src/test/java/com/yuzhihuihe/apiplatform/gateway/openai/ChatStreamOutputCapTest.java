package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 流式路径受到与预留所依据的同一个有效输出上限的约束。
 *
 * <p>供应商已被告知 {@code max_tokens = effectiveCap}，但网关不能依赖这一点：无视该限制的
 * 供应商会生成没人付过费的 token。因此一旦收到的输出达到预留容量，上游流就会被切断，而到
 * 那一刻为止实际产出的内容才参与结算。没有这一点，流式路径就会成为预留不变式上仅剩的
 * 一个漏洞。
 */
class ChatStreamOutputCapTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("12000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("12000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("12000000-0000-4000-8000-000000000004");
    private static final UUID MODEL_ID = UUID.fromString("12000000-0000-4000-8000-000000000005");
    private static final UUID CHANNEL_ID = UUID.fromString("12000000-0000-4000-8000-000000000006");
    private static final String MODEL = "text-pro";
    private static final long CAP = 20L;

    private QuotaService quota;
    private RequestLimitService limits;
    private RecordingUsageWriter usage;
    private UnboundedAdapter adapter;
    private List<String> accountingCalls;

    @BeforeEach
    void setUp() {
        quota = mock(QuotaService.class);
        limits = mock(RequestLimitService.class);
        accountingCalls = new CopyOnWriteArrayList<>();
        usage = new RecordingUsageWriter(accountingCalls);
        adapter = new UnboundedAdapter();

        when(limits.acquire(any())).thenAnswer(invocation -> {
            RequestLimitService.LimitCommand command = invocation.getArgument(0);
            return Mono.just(new RequestLimitService.LimitLease(
                    UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                    command.channelId(), 100, 10, RequestLimitService.Store.REDIS));
        });
        when(limits.rebindChannel(any(), any())).thenAnswer(invocation -> {
            RequestLimitService.LimitLease lease = invocation.getArgument(0);
            return Mono.just(new RequestLimitService.LimitLease(
                    lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(),
                    invocation.getArgument(1), lease.rpmLimit(), lease.concurrencyLimit(),
                    lease.store()));
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
                        invocation.getArgument(0), 12, 12, 0, "SETTLED", invocation.getArgument(2))));
        when(quota.release(any(), any())).thenReturn(Mono.empty());
    }

    /**
     * 撞上预留上限时切断上游，并且**如实告知这是一次截断**。
     *
     * <p>适配器愿意产出一百个分片，而客户端只买到 {@code max_tokens=20} 的容量；每一块的内容
     * 约 7 个 token，因此第三块就会把容量顶满。此前的行为是切断之后照旧补 {@code [DONE]}——
     * 客户端读到「这段回答完整结束了」，而它其实被预算切断了。现在的契约是：最后一块带
     * {@code finish_reason=length}，并且**没有** {@code [DONE]}。
     */
    @Test
    void theUpstreamStreamIsCutOffAtTheReservedOutputCap() throws Exception {
        ObjectNode body = request(true);
        body.put("max_tokens", CAP);

        List<String> emitted = new ChatCompletionServiceHarness()
                .stream(body)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(emitted).isNotNull().isNotEmpty();
        long contentChunks = emitted.stream()
                .filter(chunk -> chunk.contains("\"choices\":[{"))
                .count();
        assertThat(contentChunks).isLessThan(UnboundedAdapter.CHUNKS);
        assertThat(contentChunks).isPositive();
        assertThat(adapter.cancelled).as("the upstream provider stream was aborted at the cap")
                .isTrue();
        assertTruncatedTerminal(emitted);
    }

    @Test
    void whatWasProducedUpToTheCapIsWhatSettles() {
        ObjectNode body = request(true);
        body.put("max_tokens", CAP);

        new ChatCompletionServiceHarness().stream(body).collectList().block(Duration.ofSeconds(5));

        ArgumentCaptor<QuotaService.TokenUsage> charged =
                ArgumentCaptor.forClass(QuotaService.TokenUsage.class);
        verify(quota, timeout(2_000)).settle(any(), charged.capture(), eq(QuotaService.UsageQuality.ESTIMATED));
        assertThat(charged.getValue().outputTokens())
                .as("the settled output cannot exceed the effective cap the reservation holds")
                .isLessThanOrEqualTo(CAP);
        assertThat(charged.getValue().totalTokens()).isLessThanOrEqualTo(12 + CAP);
    }

    @Test
    void aClientLimitBelowThePlanCapIsWhatBoundsTheStream() throws Exception {
        ObjectNode body = request(true);
        body.put("max_tokens", 1L);

        List<String> emitted = new ChatCompletionServiceHarness()
                .stream(body)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(emitted).isNotNull();
        // 只有一个 token 的容量：第一块就把容量顶满，因此只交付一块。
        long contentChunks = emitted.stream()
                .filter(chunk -> chunk.contains("\"choices\":[{"))
                .count();
        assertThat(contentChunks).isLessThanOrEqualTo(1);
        assertThat(adapter.cancelled).isTrue();
        assertTruncatedTerminal(emitted);
    }

    /**
     * 截断的流必须同时满足两件事，缺一件客户端就会把半截回答当成完整交付：
     * 最后一块自报 {@code length}，且**不发** {@code [DONE]}。
     *
     * <p>刻意**不**发失败帧：截断与故障是两回事。转换层若看到 error 会报
     * {@code response.failed}，把「命中长度上限」说成「上游坏了」，而正确表达是
     * {@code response.incomplete} + {@code max_output_tokens}。
     */
    private static void assertTruncatedTerminal(List<String> emitted) throws Exception {
        assertThat(emitted).noneMatch(value -> "[DONE]".equals(value));
        assertThat(emitted).noneMatch(value -> value.contains("\"error\""));

        JsonNode lastContent = lastContentChunk(emitted);
        assertThat(lastContent.path("choices").get(0).path("finish_reason").asText())
                .isEqualTo("length");
    }

    /**
     * 截断时不再发 {@code [DONE]}，但 {@code include_usage} 要的用量块照旧在最后发出去。
     *
     * <p>用量块是客户端的结算信息，去掉它会让客户端不知道这次扣了多少；被去掉的只有
     * 「完整结束」这一个标记。
     */
    @Test
    void aTruncatedStreamStillDeliversTheRequestedUsageChunkButNeverDone() {
        ObjectNode body = request(true);
        body.put("max_tokens", 10L);
        body.putObject("stream_options").put("include_usage", true);

        List<String> emitted = new ChatCompletionServiceHarness()
                .stream(body)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(emitted).isNotNull();
        assertThat(emitted).noneMatch(value -> "[DONE]".equals(value));
        assertThat(emitted).noneMatch(value -> value.contains("\"error\""));
        // 用量块在场，且它是最后一帧。
        assertThat(emitted.getLast()).contains("\"usage\"");
        assertThat(lastContentChunk(emitted).path("choices").get(0)
                .path("finish_reason").asText()).isEqualTo("length");
    }

    private static JsonNode lastContentChunk(List<String> emitted) {
        try {
            for (int index = emitted.size() - 1; index >= 0; index--) {
                JsonNode candidate = JSON.readTree(emitted.get(index));
                if (candidate.path("choices").isArray() && !candidate.path("choices").isEmpty()) {
                    return candidate;
                }
            }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        throw new AssertionError("no content chunk was emitted");
    }

    private final class ChatCompletionServiceHarness {
        private ChatCompletionService service(Integer packageMaxOutputTokens) {
            ChatCompletionService.GatewayCatalog catalog =
                    (context, publicModel, routingModel, capability) -> Mono.just(
                            new ChatCompletionService.PreparedRequest(
                                    MODEL_ID, publicModel, MODEL_ID, routingModel, 8_192,
                                    RequestLimitService.Thresholds.all(100),
                                    RequestLimitService.Thresholds.all(10),
                                    RequestLimitService.PackageDailyLimit.unlimited(),
                                    RequestLimitService.PackageTokenLimit.unlimited(),
                                    List.of(target()), packageMaxOutputTokens));
            return new ChatCompletionService(
                    catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                    new ChannelRouter(), new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults()),
                    List.of(adapter), JSON, Duration.ofMinutes(2), Duration.ofSeconds(10));
        }

        Flux<String> stream(ObjectNode body) {
            return service(null).stream(apiKey(), body, "req_stream_cap_" + UUID.randomUUID())
                    .doOnNext(accountingCalls::add);
        }
    }

    private static ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of(MODEL));
    }

    private static ChatCompletionService.RouteTarget target() {
        ChannelRouter.RouteCandidate candidate = new ChannelRouter.RouteCandidate(
                CHANNEL_ID, "MOCK", MODEL, "upstream-model",
                Set.of(ChannelRouter.Capability.TEXT), 100, 100,
                true, true, true, true, true);
        ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                CHANNEL_ID, UUID.randomUUID(), "MOCK", "http://127.0.0.1:8089", "secret",
                5_000, "upstream-model");
        return new ChatCompletionService.RouteTarget(candidate, channel);
    }

    private static ObjectNode request(boolean stream) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", MODEL);
        request.put("stream", stream);
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return request;
    }

    private static ProviderAdapter.ProviderChunk chunk(int index) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", "chatcmpl-cap");
        body.put("object", "chat.completion.chunk");
        body.put("created", 100);
        body.put("model", "upstream-model");
        body.putArray("choices").addObject().put("index", 0)
                .putObject("delta").put("content", "0123456789");
        return new ProviderAdapter.ProviderChunk(body, null);
    }

    /** 一个乐于流出一百个分片、无视任何输出上限的供应商。 */
    private static final class UnboundedAdapter implements ProviderAdapter {
        private static final int CHUNKS = 100;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<JsonNode> requestBodies = new ArrayList<>();

        @Override
        public String providerType() {
            return "MOCK";
        }

        @Override
        public Set<ChannelRouter.Capability> capabilities() {
            return Set.of(ChannelRouter.Capability.TEXT);
        }

        @Override
        public Mono<ProviderResponse> complete(RoutedRequest request) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Flux<ProviderChunk> stream(RoutedRequest request) {
            requestBodies.add(request.body());
            return Flux.range(0, CHUNKS)
                    .map(UnboundedAdapter::chunk)
                    .doOnCancel(() -> cancelled.set(true));
        }

        private static ProviderChunk chunk(int index) {
            return ChatStreamOutputCapTest.chunk(index);
        }

        @Override
        public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
            return Mono.error(new UnsupportedOperationException());
        }
    }

    private static final class RecordingUsageWriter implements ChatCompletionService.UsageLogWriter {
        private final List<ChatCompletionService.UsageEntry> entries = new CopyOnWriteArrayList<>();
        private final List<String> accountingCalls;

        private RecordingUsageWriter(List<String> accountingCalls) {
            this.accountingCalls = accountingCalls;
        }

        @Override
        public Mono<Void> write(ChatCompletionService.UsageEntry entry) {
            entries.add(entry);
            accountingCalls.add("log");
            return Mono.empty();
        }
    }
}
