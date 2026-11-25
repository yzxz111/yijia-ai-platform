package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceCalculator;
import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceService;
import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionService;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 最坏情况超出客户预付余额承受能力的请求，会在调用任何供应商之前被拒绝。
 *
 * <p>这是预留不变式中属于准入的那一半。只有当无法覆盖的最坏情况会被拒绝时，预留最坏情况
 * 才能真正保护平台：如果网关先调用供应商、事后再发现余额不足，供应商成本就已经产生了。
 * 这里被测的 QuotaService 是真实的那个，钱包会以平台拒绝它的方式拒绝这笔预留。
 */
class ChatUncoverableRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("11000000-0000-4000-8000-000000000002");
    private static final UUID KEY_ID = UUID.fromString("11000000-0000-4000-8000-000000000003");
    private static final UUID PACKAGE_ID = UUID.fromString("11000000-0000-4000-8000-000000000004");
    private static final UUID MODEL_ID = UUID.fromString("11000000-0000-4000-8000-000000000005");
    private static final UUID CHANNEL_ID = UUID.fromString("11000000-0000-4000-8000-000000000006");
    private static final String MODEL = "text-pro";

    private static final ModelBillingPriceCalculator.Price PRICE =
            new ModelBillingPriceCalculator.Price(
                    MODEL, 1_350, 4_050, "retail-v2", 45L, 2_025L, 4_050L);

    private QuotaReservationRepository repository;
    private ModelBillingPriceService prices;
    private WalletUsageClient wallet;
    private QuotaService quota;
    private RequestLimitService limits;
    private RecordingUsageWriter usage;
    private RecordingAdapter adapter;
    private List<String> accountingCalls;

    @BeforeEach
    void setUp() {
        repository = mock(QuotaReservationRepository.class);
        prices = mock(ModelBillingPriceService.class);
        wallet = mock(WalletUsageClient.class);
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        quota = Mockito.spy(new QuotaService(
                repository, mock(PackageActivationService.class), transactions, prices, wallet));

        // 套餐只覆盖任何请求中的 500 个 token，其余部分必须对客户的钱包做预留 ——
        // 这正是余额检查实际发生的情形。
        QuotaReservationRepository.LockedPackage lockedPackage =
                new QuotaReservationRepository.LockedPackage(
                        PACKAGE_ID, USER_ID, 500, 0, 0, Instant.now().plusSeconds(3600), "TOKEN");
        doReturn(Mono.empty()).when(repository).lockExisting(any(), any());
        doReturn(Mono.empty()).when(repository).idempotencyLock(any(), any());
        doReturn(Mono.empty()).when(repository).advisoryLock(any());
        doReturn(Mono.just(false)).when(repository).hasOpenRefundHold(any(QuotaService.ReserveCommand.class));
        doReturn(Mono.just(lockedPackage)).when(repository).lockActivePackage(any());
        doReturn(Mono.empty()).when(repository).lockModelQuota(any(), any());
        doReturn(Mono.empty()).when(repository).addReservation(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
        doReturn(Mono.empty()).when(repository).addWalletReservation(
                any(), org.mockito.ArgumentMatchers.anyLong(), any());
        doReturn(Mono.just(PRICE)).when(prices).current(MODEL);
        // 这些测试关注的是网关申请的那笔预留，而不是结算它，因此结算尾部允许止于
        // “reservation not found”，而不必在这里完整编排；到那时预留已经被申请过了。
        doReturn(Mono.empty()).when(repository).findReservation(any());

        limits = mock(RequestLimitService.class);
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

        accountingCalls = new CopyOnWriteArrayList<>();
        usage = new RecordingUsageWriter(accountingCalls);
        adapter = new RecordingAdapter();
    }

    @Test
    void completionRefusesAnUncoverableRequestWithPaymentRequiredAndNeverCallsTheProvider() {
        refusingWallet();
        ChatCompletionService service = service(8_192, null);
        ObjectNode body = request(false);
        body.put("max_tokens", 4_000);

        var signal = service.complete(apiKey(), body, "req_uncoverable_completion")
                .materialize()
                .block(Duration.ofSeconds(3));

        assertThat(signal).isNotNull();
        assertThat(signal.isOnError()).isTrue();
        assertThat(signal.getThrowable())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("insufficient_balance");
        ResponseStatusException refusal = (ResponseStatusException) signal.getThrowable();
        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(adapter.completeCalls).isZero();
        assertThat(adapter.streamCalls).isZero();
        assertThat(adapter.requestBodies).isEmpty();
        assertThat(accountingCalls).doesNotContain("settle");
    }

    @Test
    void streamRefusesAnUncoverableRequestWithPaymentRequiredAndNeverCallsTheProvider() {
        refusingWallet();
        ChatCompletionService service = service(8_192, null);
        ObjectNode body = request(true);
        body.put("max_tokens", 4_000);

        var signal = service.openStream(apiKey(), body, "req_uncoverable_stream")
                .materialize()
                .block(Duration.ofSeconds(3));

        assertThat(signal).isNotNull();
        assertThat(signal.isOnError()).isTrue();
        assertThat(signal.getThrowable())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("insufficient_balance");
        assertThat(((ResponseStatusException) signal.getThrowable()).getStatusCode())
                .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(adapter.streamCalls).isZero();
        assertThat(adapter.completeCalls).isZero();
    }

    @Test
    void theRefusalIsAnOpenAiPaymentRequiredErrorNotAnInternalOne() {
        ResponseStatusException refusal = new ResponseStatusException(
                HttpStatus.PAYMENT_REQUIRED, "insufficient_balance");

        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(refusal);

        assertThat(descriptor.status()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(descriptor.code()).isEqualTo("insufficient_balance");
        assertThat(descriptor.type()).isEqualTo("quota_error");
    }

    @Test
    void aCoverableRequestReservesTheWorstCaseOfPromptPlusMaximumOutput() {
        coveringWallet();
        ChatCompletionService service = service(8_192, 2_000);
        ObjectNode body = request(false);
        body.remove(List.of("max_tokens", "max_completion_tokens"));

        service.complete(apiKey(), body, "req_coverable").materialize().block(Duration.ofSeconds(3));

        ArgumentCaptor<QuotaService.ReserveCommand> captured =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);
        org.mockito.Mockito.verify(quota).reserve(captured.capture());
        QuotaService.ReserveCommand command = captured.getValue();
        // 客户端未设上限，而套餐方案出售的是 2 000 token 的输出上限：约束值就是套餐方案的上限，
        // 且两种 token 数量都按全额预留。
        assertThat(command.outputTokens()).isEqualTo(2_000);
        assertThat(command.inputTokens()).isEqualTo(expectedPromptTokens(body));
        assertThat(command.tokens()).isEqualTo(command.inputTokens() + 2_000);
        assertThat(expectedPromptTokens(body)).isPositive();
    }

    @Test
    void theClientLimitBoundsTheReservationBelowThePlanCap() {
        coveringWallet();
        ChatCompletionService service = service(8_192, 2_000);
        ObjectNode body = request(false);
        body.put("max_tokens", 300);

        service.complete(apiKey(), body, "req_client_bounded").materialize().block(Duration.ofSeconds(3));

        ArgumentCaptor<QuotaService.ReserveCommand> captured =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);
        org.mockito.Mockito.verify(quota).reserve(captured.capture());
        assertThat(captured.getValue().outputTokens()).isEqualTo(300);
    }

    @Test
    void aRequestWithoutALimitIsBoundedByTheDefaultNotByTheSystemCeiling() {
        coveringWallet();
        // 套餐方案不设输出上限，客户端也不设：约束值是网关自己的默认值，
        // 而**不是**系统上限。系统上限现在是 100 万，若"什么都没要"也按它预留，
        // 每个默认请求都会冻结 100 万 token 的额度。
        ChatCompletionService service =
                service((int) ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS, null);
        ObjectNode body = request(false);
        body.remove(List.of("max_tokens", "max_completion_tokens"));

        service.complete(apiKey(), body, "req_ceiling_bounded").materialize().block(Duration.ofSeconds(3));

        ArgumentCaptor<QuotaService.ReserveCommand> captured =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);
        org.mockito.Mockito.verify(quota).reserve(captured.capture());
        assertThat(captured.getValue().outputTokens())
                .isEqualTo(ReservationCeiling.DEFAULT_MAX_OUTPUT_TOKENS);
    }

    /**
     * 客户端显式要 100 万时，预留就是 100 万——这是"客户端真能要到 100 万"的准入这一半。
     *
     * <p>它同时钉住另一件事：这个数字来自**客户端**，`ceiling_source` 因而必须报
     * {@code client}，而不是任何一层配置。
     */
    @Test
    void aClientAskingForTheSystemCeilingGetsReservedForAllOfIt() {
        coveringWallet();
        ChatCompletionService service =
                service((int) ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS, null);
        ObjectNode body = request(false);
        body.put("max_tokens", ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);

        service.complete(apiKey(), body, "req_one_million").materialize().block(Duration.ofSeconds(3));

        ArgumentCaptor<QuotaService.ReserveCommand> captured =
                ArgumentCaptor.forClass(QuotaService.ReserveCommand.class);
        org.mockito.Mockito.verify(quota).reserve(captured.capture());
        assertThat(captured.getValue().outputTokens())
                .isEqualTo(ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);
        assertThat(adapter.completeCalls).isEqualTo(1);
        assertThat(adapter.requestBodies.getFirst().path("max_tokens").asLong())
                .isEqualTo(ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS);
    }

    // ------------------------------------------------------------------------------------------

    private long expectedPromptTokens(JsonNode body) {
        String messages = body.path("messages").toString();
        return new TokenEstimator(3, 1.25)
                .estimateTextTokens(messages.codePointCount(0, messages.length()));
    }

    private void refusingWallet() {
        when(wallet.reserve(any(WalletUsageClient.WalletRequest.class)))
                .thenReturn(Mono.error(new WalletUsageClient.WalletUsageClientException(
                        "wallet_insufficient_balance", 422)));
    }

    private void coveringWallet() {
        when(wallet.reserve(any(WalletUsageClient.WalletRequest.class)))
                .thenAnswer(invocation -> {
                    WalletUsageClient.WalletRequest request = invocation.getArgument(0);
                    return Mono.just(new WalletUsageClient.WalletResponse(
                            request.reservationId(), request.requestId(), request.estimatedAmount(),
                            null, "RESERVED", request.modelCode(), request.priceVersion()));
                });
    }

    private ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of(MODEL));
    }

    /**
     * @param packageMaxOutputTokens 套餐方案出售的输出上限；套餐方案不出售时为 null
     */
    private ChatCompletionService service(int modelMaxOutputTokens, Integer packageMaxOutputTokens) {
        ChatCompletionService.GatewayCatalog catalog =
                (context, publicModel, routingModel, capability) -> Mono.just(
                        new ChatCompletionService.PreparedRequest(
                                MODEL_ID, publicModel, MODEL_ID, routingModel, modelMaxOutputTokens,
                                RequestLimitService.Thresholds.all(100),
                                RequestLimitService.Thresholds.all(10),
                                RequestLimitService.PackageDailyLimit.unlimited(),
                                RequestLimitService.PackageTokenLimit.unlimited(),
                                List.of(target()), packageMaxOutputTokens));
        return new ChatCompletionService(
                catalog, usage, quota, limits, new TokenEstimator(3, 1.25),
                new ChannelRouter(), new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults()),
                List.of(adapter), JSON, Duration.ofMinutes(2), Duration.ofSeconds(30));
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

    /** 记录每一次供应商调用，使“供应商从未被调用”这一断言可被检验。 */
    private static final class RecordingAdapter implements ProviderAdapter {
        private final List<JsonNode> requestBodies = new ArrayList<>();
        private int completeCalls;
        private int streamCalls;

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
            completeCalls++;
            requestBodies.add(request.body());
            ObjectNode body = JSON.createObjectNode();
            body.put("id", "chatcmpl-recording");
            body.put("object", "chat.completion");
            body.put("created", 100);
            body.put("model", "upstream-model");
            body.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop")
                    .putObject("message").put("role", "assistant").put("content", "hello");
            body.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20)
                    .put("total_tokens", 30);
            return Mono.just(new ProviderResponse(
                    body, new TokenUsage(10, 20, 30)));
        }

        @Override
        public Flux<ProviderChunk> stream(RoutedRequest request) {
            streamCalls++;
            requestBodies.add(request.body());
            return Flux.empty();
        }

        @Override
        public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
            return Mono.error(new UnsupportedOperationException());
        }
    }
}
