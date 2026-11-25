package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;

/**
 * 首段之后的失败，必须能分辨是谁断的。
 *
 * <p>这些用例钉住的正是 2026-10-15 那次排查里缺的东西：三项真实复测都在 3–10 秒失败，
 * 用量表里全是 {@code ProviderException(status=502: upstream_stream_interrupted)}——
 * 一个把「上游掐断」「客户端断开」「网关自身异常」压成同一个词的兜底值。因此这里逐个断言
 * 每种原因**不会**再落到同一个码上，并且断言异常本身确实进了日志。
 */
class ChatStreamFailureClassifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID CHANNEL_ID = UUID.fromString("10000000-0000-4000-8000-000000000006");
    private static final String REQUEST_ID = "req_00000000000000000000000000000031";

    private ApiKeyService.ApiKeyContext apiKey;
    private QuotaService quota;
    private RequestLimitService limits;
    private ChannelCircuitRegistry circuits;
    private ProviderAdapter.Channel channel;

    @BeforeEach
    void setUp() {
        apiKey = new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("chat-standard"));
        quota = mock(QuotaService.class);
        limits = mock(RequestLimitService.class);
        circuits = new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults());
        channel = new ProviderAdapter.Channel(
                CHANNEL_ID, UUID.randomUUID(), "TIANYIYUN", "https://upstream.example", "secret",
                60_000, "upstream-chat");

        when(limits.acquire(any())).thenAnswer(invocation -> {
            RequestLimitService.LimitCommand command = invocation.getArgument(0);
            return Mono.just(new RequestLimitService.LimitLease(
                    UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                    command.channelId(), 100, 10, RequestLimitService.Store.REDIS));
        });
        // 首段之前的渠道绑定会 rebind 租约；不桩它就会在 bindWithinDeadline 里拿到 null，
        // 于是用例连被测的那条路径都走不到。
        when(limits.rebindChannel(any(), any())).thenAnswer(invocation -> {
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
        when(quota.release(any(), any())).thenReturn(Mono.empty());
    }

    // ---------------------------------------------------------------- 分类 --

    @Test
    void genuineTransportInterruptionKeepsTheReasonClientsAlreadyBranchOn() {
        ProviderAdapter.ProviderException classified = classify(
                new IOException("Connection reset by peer"));

        // 真·传输层中断必须走既有的上游类原因码：它本来就分得清「连不上/被掐断」与「超时」，
        // 不需要为它发明新词（客户端按 error.code 分支，本来也不依赖这个值）。
        assertThat(classified.reason()).isEqualTo("provider_unreachable");
        assertThat(classified.status()).isEqualTo(502);
    }

    @Test
    void clientAbortIsNotRecordedAsAnUpstreamFault() {
        ProviderAdapter.ProviderException classified = classify(abortedWhileWritingToClient());

        // 用户点停止、或客户端自己的看门狗断开连接都会走这一支。记成上游故障会污染渠道健康度，
        // 让「上游到底稳不稳」这个判断失去依据。
        assertThat(classified.reason())
                .isEqualTo(ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM);
        assertThat(classified.reason()).isNotEqualTo("upstream_stream_interrupted");
    }

    @Test
    void clientAbortIsRecognisedThroughAWrapper() {
        // 真实链路上失败几乎总是被包一层。这里刻意用 CompletionException 而不是
        // IOException 包装：包装层若是 IOException，整条链本身就已经是「传输层失败」，
        // 那个用例证明不了「先判客户端断开」这条顺序规则。
        ProviderAdapter.ProviderException classified = classify(
                new CompletionException(abortedWhileWritingToClient()));

        assertThat(classified.reason())
                .isEqualTo(ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM);
    }

    @Test
    void anAbortWithNoEvidenceOfTheDirectionIsNotBlamedOnTheClient() {
        // AbortedException 只说「连接在写完成之前被关闭」，它**不**自带方向：上游那条连接上
        // 也可能发生同样的事。没有下游写回的栈证据时，正确的答案是「不知道」，而不是猜一个
        // 责任方——猜错的方向会让人去查错的那一端。
        ProviderAdapter.ProviderException classified = classify(
                new AbortedException("Connection has been closed BEFORE send operation"));

        assertThat(classified.reason())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
    }

    @Test
    void aDownstreamFramePlusTheSendMessageIsEnoughEvidenceForAClientAbort() {
        // 栈上出现了下游这条路的帧，但方法名不是 send*：reactor-netty 自己的原话
        // （BEFORE send operation）此时就是那条证据，不必因为方法名对不上而丢掉一个可定的结论。
        AbortedException aborted =
                new AbortedException("Connection has been closed BEFORE send operation");
        aborted.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("reactor.netty.http.server.HttpServerResponse",
                        "writeWith", "HttpServerResponse.java", 300),
                new StackTraceElement("org.springframework.web.server.adapter.HttpWebHandlerAdapter",
                        "handle", "HttpWebHandlerAdapter.java", 120)});

        assertThat(classify(aborted).reason())
                .isEqualTo(ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM);
    }

    @Test
    void anUnexpectedGatewayFailureStopsBorrowingTheUpstreamReasonCode() {
        ProviderAdapter.ProviderException classified = classify(
                new IllegalStateException("prepared_provider_body_required"));

        // 这是本次修复的核心断言：从前这里写的就是 upstream_stream_interrupted，
        // 于是一次「网关自己没能归类」的失败被读成了已定责的上游故障。
        assertThat(classified.reason())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
        assertThat(classified.reason()).isNotEqualTo("upstream_stream_interrupted");
    }

    @Test
    void nullFailureStillProducesATerminalClassification() {
        assertThat(classify(null).reason())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
    }

    /**
     * 一个**已经带着受控原因码**的失败，不得被这个类改名成兜底值。
     *
     * <p>这条用法直接来自 2026-10-16 的生产排查：上游天翼云在长度截断时多发一帧
     * {@code choices:[]}（见 {@code ChatCompletionResponseProtocol#validateStreamChunk}），
     * 网关据此抛出 {@code invalid_provider_stream_chunk}——一个精确的原因码。但日志、
     * 用量表的 {@code error_category}、以及交给客户端的失败帧，全都写成了
     * {@code unknown_stream_failure}，也就是「网关遇到了一个自己没预料到的失败，去查这条请求的日志」。
     * 而日志里唯一有用的线索恰好被这一支抹掉了，排查只能从「上游流中断」这个词往回猜。
     *
     * <p>这个类的职责是给**没人归过类**的异常找一个诚实的名字，不是给一个已经定好类的失败改名：
     * 重新包装会同时丢掉原因码与状态码。所以判据是「它自己有没有给出受控原因码」，
     * 而不是「它是不是 ProviderException」——{@code reason()} 为 null 的那些（例如
     * {@code new ProviderException(429, "provider secret")} 这种自由文本）仍然走兜底。
     */
    @Test
    void anAlreadyClassifiedFailureKeepsItsExactReasonCode() {
        ProviderAdapter.ProviderException alreadyClassified =
                new ProviderAdapter.ProviderException(502, "invalid_provider_stream_chunk");

        ProviderAdapter.ProviderException classified = classify(alreadyClassified);

        assertThat(classified.reason()).isEqualTo("invalid_provider_stream_chunk");
        assertThat(classified.reason()).isNotEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
        // 同一个实例：不做任何包装，状态码也因此原样保留。
        assertThat(classified).isSameAs(alreadyClassified);
    }

    @Test
    void aProviderFailureWithoutASafeReasonCodeStillFallsBackToTheHonestUnknown() {
        // 自由文本经 reason() 的白名单后是 null（它是上游/凭据文本，不能被当成原因码持久化）。
        // 这种「自己也没能定类」的失败，正是兜底值存在的理由。
        ProviderAdapter.ProviderException unspecific =
                new ProviderAdapter.ProviderException(429, "provider secret");

        assertThat(unspecific.reason()).isNull();
        assertThat(classify(unspecific).reason())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
    }

    @Test
    void reasonCodesSurviveTheIdentifierWhitelistThatFeedsTheUsageTables() {
        // 原因码要写进 usage_log / usage_stage，而 ProviderException.reason() 只在它形如标识符时
        // 才接受。一个带空格或大写的码会静默变成 null，新加的区分度在数据库里就不会出现。
        for (String reason : List.of(
                ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM,
                ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE)) {
            assertThat(reason).matches("[a-z][a-z0-9_]*");
        }
    }

    // ------------------------------------------------------------ 日志细节 --

    @Test
    void describeNamesTheExceptionAndItsMessage() {
        String detail = ChatStreamFailureClassifier.describe(new AbortedException(
                "Connection has been closed BEFORE send operation"));

        assertThat(detail)
                .contains(AbortedException.class.getName())
                .contains("Connection has been closed BEFORE send operation");
    }

    @Test
    void describeFollowsTheCauseChainToTheRootCause() {
        // 根因（ClosedChannelException）没有消息，外层才有：因此两个类名都必须出现，
        // 否则日志里就会出现一条「有类名没原因」或「有原因没类名」的记录。
        String detail = ChatStreamFailureClassifier.describe(
                new IllegalStateException("write failed", new ClosedChannelException()));

        assertThat(detail)
                .contains(IllegalStateException.class.getName())
                .contains(ClosedChannelException.class.getName())
                .contains("write failed");
    }

    @Test
    void describeSkipsWrapperTypesThatCarryNoInformation() {
        String detail = ChatStreamFailureClassifier.describe(
                new CompletionException(new ClosedChannelException()));

        // CompletionException 只是一个包装层：真正有意义的是它里面那个类型。
        assertThat(detail).contains(ClosedChannelException.class.getName());
        assertThat(detail).doesNotContain("CompletionException");
    }

    @Test
    void describeBoundsUpstreamTextSoOneFailureCannotFloodTheLog() {
        String detail = ChatStreamFailureClassifier.describe(new TimeoutException("x".repeat(4_000)));

        assertThat(detail.length()).isLessThan(500);
        assertThat(detail).endsWith("…");
    }

    @Test
    void describeHandlesANullFailure() {
        assertThat(ChatStreamFailureClassifier.describe(null)).isEqualTo("none");
    }

    @Test
    void theLogLineCarriesEveryFieldOperationsNeedsToActOn() {
        String line = ChatStreamFailureClassifier.message(
                abortedWhileWritingToClient(), REQUEST_ID, 1, "chat-deep", channel,
                Instant.now().minusMillis(4_000), ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM);

        // 这条日志是本次修复的主要产物：它必须自己就够用来定责，而不是让人再去猜。
        assertThat(line).contains("request=" + REQUEST_ID);
        assertThat(line).contains("reason=" + ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM);
        assertThat(line).contains("attempt=1");
        assertThat(line).contains("model=chat-deep");
        assertThat(line).contains("channel=" + channel.id());
        assertThat(line).contains("providerType=TIANYIYUN");
        assertThat(line).contains("upstreamModel=upstream-chat");
        assertThat(line).contains("timeoutMs=60000");
        assertThat(line).contains(AbortedException.class.getName());
        // 4000ms 只是下限断言：机器慢一点不影响结论，写成等值断言就会变成一条会自己变红的测试。
        assertThat(line).containsPattern("elapsedMs=\\d{4,}");
    }

    @Test
    void theLogLineSurvivesAMissingChannel() {
        String line = ChatStreamFailureClassifier.message(
                new IllegalStateException("boom"), REQUEST_ID, 0, "chat-standard", null, null,
                ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);

        assertThat(line)
                .contains("channel=unknown")
                .contains("providerType=unknown")
                .contains("upstreamModel=unknown")
                .contains("timeoutMs=-1")
                .contains("elapsedMs=-1");
    }

    // --------------------------------------------------- 端到端：流与记账 --

    @Test
    void failureFrameCarriesTheClassifiedReasonAndStillOmitsDone() {
        RecordingUsageWriter usage = new RecordingUsageWriter();
        ChatCompletionService service = service(usage, Flux.error(new IllegalStateException("boom")));

        List<String> frames = service.stream(apiKey, request(), REQUEST_ID)
                .collectList().block(Duration.ofSeconds(3));

        assertThat(frames).hasSize(2);
        assertThat(frames.getFirst()).contains("\"content\":\"partial\"");
        JsonNode failure = read(frames.getLast());
        assertThat(failure.path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(failure.path("error").path("reason").asText())
                .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE);
        assertThat(failure.path("request_id").asText()).isEqualTo(REQUEST_ID);
        // [DONE] 的语义是「回答完整结束」，失败之后发它就是把断流重新包装成成功。
        assertThat(frames).doesNotContain("[DONE]");
    }

    @Test
    void postFirstByteFailureReachesTheUsageRowWithTheClassifiedReason() {
        ChatCompletionService.UsageEntry entry = failedStreamEntry(
                new IllegalStateException("gateway could not classify this"));

        // 用量表是事后唯一能定责的证据，因此分类必须活到这一行——否则这次修复只改了响应体，
        // 报表里依然全是同一个词。
        assertThat(entry.outcome()).isEqualTo("FAILED");
        assertThat(entry.stages()).singleElement().satisfies(stage ->
                assertThat(stage.errorCategory())
                        .isEqualTo(ChatStreamFailureClassifier.UNKNOWN_STREAM_FAILURE));
    }

    @Test
    void clientAbortReachesTheUsageRowDistinctlyFromAnUpstreamFault() {
        ChatCompletionService.UsageEntry entry = failedStreamEntry(abortedWhileWritingToClient());

        assertThat(entry.stages()).singleElement().satisfies(stage ->
                assertThat(stage.errorCategory())
                        .isEqualTo(ChatStreamFailureClassifier.CLIENT_ABORTED_STREAM));
    }

    @Test
    void aTransportInterruptionStillReadsAsAnUpstreamFaultInTheUsageRow() {
        ChatCompletionService.UsageEntry entry = failedStreamEntry(
                new IOException("Connection reset by peer"));

        assertThat(entry.stages()).singleElement().satisfies(stage ->
                assertThat(stage.errorCategory()).isEqualTo("provider_unreachable"));
    }

    // ------------------------------------------------------------- 夹具 --

    private ProviderAdapter.ProviderException classify(Throwable error) {
        return ChatStreamFailureClassifier.classify(
                error, REQUEST_ID, 0, "chat-standard", channel, Instant.now());
    }

    /**
     * 一个「确实是在往客户端写回的时候被断开」的 AbortedException。
     *
     * <p>栈帧不是装饰：分类规则要求看到下游写回这条路上的证据才敢指名客户端，
     * 而真实异常本来就带着这样的栈。手工构造一个不带栈的异常去断言「客户端断开」，
     * 测的就不是真实链路上的行为了。
     */
    private static AbortedException abortedWhileWritingToClient() {
        AbortedException aborted =
                new AbortedException("Connection has been closed BEFORE send operation");
        aborted.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("reactor.netty.http.server.HttpServerOperations",
                        "send", "HttpServerOperations.java", 210),
                new StackTraceElement("reactor.netty.http.server.HttpServerResponse",
                        "sendSubscribe", "HttpServerResponse.java", 260),
                new StackTraceElement("org.springframework.http.server.reactive.ReactorHttpHandlerAdapter",
                        "apply", "ReactorHttpHandlerAdapter.java", 100)});
        return aborted;
    }

    /**
     * 跑一条「首段已发出、随后失败」的真实流，取回它写下的用量记录。
     *
     * <p>刻意不 mock 掉结算链路：这条链路里「失败最终被记成了什么」正是被测对象。
     */
    private ChatCompletionService.UsageEntry failedStreamEntry(Throwable failure) {
        RecordingUsageWriter usage = new RecordingUsageWriter();
        ChatCompletionService service = service(usage, Flux.error(failure));

        service.stream(apiKey, request(), REQUEST_ID).collectList().block(Duration.ofSeconds(3));

        return usage.awaitEntry();
    }

    private ChatCompletionService service(
            RecordingUsageWriter usage, Flux<ProviderAdapter.ProviderChunk> failure) {
        ChatCompletionService.GatewayCatalog catalog =
                (context, publicModel, routingModel, capability) -> Mono.just(
                        new ChatCompletionService.PreparedRequest(
                                UUID.randomUUID(), publicModel, UUID.randomUUID(), routingModel,
                                8_192, RequestLimitService.Thresholds.all(100),
                                RequestLimitService.Thresholds.all(10),
                                List.of(target(routingModel))));
        return new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25), new ChannelRouter(),
                circuits, List.of(new FailingStreamAdapter(failure)), JSON,
                Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    /**
     * 候选渠道必须与请求解析出来的路由模型码同名：路由器的匹配条件是
     * {@code request.modelCode().equals(candidate.modelCode())}，写死一个不同的别名会让
     * 计划为空、请求在 503 {@code no_usable_channel} 上就结束了，根本走不到被测的那条路径。
     */
    private ChatCompletionService.RouteTarget target(String routingModel) {
        ChannelRouter.RouteCandidate candidate = new ChannelRouter.RouteCandidate(
                CHANNEL_ID, channel.providerType(), routingModel, channel.upstreamModel(),
                Set.of(ChannelRouter.Capability.TEXT), 100, 100, true, true, true, true, true);
        return new ChatCompletionService.RouteTarget(candidate, channel);
    }

    private static JsonNode request() {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", "chat-standard");
        body.put("stream", true);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return body;
    }

    private static JsonNode read(String frame) {
        try {
            return JSON.readTree(frame);
        } catch (Exception exception) {
            throw new AssertionError("frame is not JSON: " + frame, exception);
        }
    }

    private static ProviderAdapter.ProviderChunk chunk(String content) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", "chatcmpl-x");
        body.put("object", "chat.completion.chunk");
        body.put("created", 1_800_000_000L);
        body.put("model", "upstream-chat");
        body.putArray("choices").addObject().put("index", 0)
                .putObject("delta").put("content", content);
        return new ProviderAdapter.ProviderChunk(body, null);
    }

    /** 先交付一段内容（让响应提交、firstByte 记下时间），再按用例给定的方式失败。 */
    private static final class FailingStreamAdapter implements ProviderAdapter {
        private final Flux<ProviderChunk> upstream;

        private FailingStreamAdapter(Flux<ProviderChunk> failure) {
            this.upstream = Flux.concat(Flux.just(chunk("partial")), failure);
        }

        @Override
        public String providerType() {
            return "TIANYIYUN";
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
            return upstream;
        }

        @Override
        public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
            return Mono.empty();
        }
    }

    private static final class RecordingUsageWriter implements ChatCompletionService.UsageLogWriter {
        private final List<ChatCompletionService.UsageEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> write(ChatCompletionService.UsageEntry entry) {
            entries.add(entry);
            return Mono.empty();
        }

        private ChatCompletionService.UsageEntry awaitEntry() {
            Instant deadline = Instant.now().plusSeconds(3);
            while (entries.isEmpty() && Instant.now().isBefore(deadline)) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            assertThat(entries).as("a failed stream must still write its usage row").isNotEmpty();
            return entries.getFirst();
        }
    }
}
