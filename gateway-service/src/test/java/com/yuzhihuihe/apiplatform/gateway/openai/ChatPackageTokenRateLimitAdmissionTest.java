package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.limit.DailyRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.DefaultPackageTokenRateLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.DefaultRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.PostgresPackageTokenRateLimitStore;
import com.yuzhihuihe.apiplatform.gateway.limit.PostgresRequestLimitStore;
import com.yuzhihuihe.apiplatform.gateway.limit.RedisPackageTokenRateLimitStore;
import com.yuzhihuihe.apiplatform.gateway.limit.RedisRequestLimitStore;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelCircuitRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 控制台以 {@code tokens_per_minute}（积分/分钟）售卖的积分速率，限制了一个套餐每分钟可消耗的积分。
 * 消耗量只有在请求结算之后才可知，因此该上限只能通过把已结算的扣费回填进一个准入可见的桶里
 * 来强制执行：本测试针对真实的 PostgreSQL 和真实的 Redis 跑通整个闭环
 * （结算 → 记录 → 下一个请求被拒绝），并证明一个只售出很小每分钟额度的套餐
 * 不会在同一分钟内服务第二个请求。
 */
@Testcontainers
class ChatPackageTokenRateLimitAdmissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("99000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("99000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("99000000-0000-4000-8000-000000000003");
    private static final UUID KEY_ID = UUID.fromString("99000000-0000-4000-8000-000000000004");
    private static final UUID SECOND_KEY_ID = UUID.fromString("99000000-0000-4000-8000-000000000005");
    private static final UUID WALLET_KEY_ID = UUID.fromString("99000000-0000-4000-8000-000000000006");
    /** 该套餐售出的每分钟全部额度，以及一个请求结算下来的用量。 */
    private static final long SOLD_CREDITS_PER_MINUTE = 30;
    /**
     * 直接读取已记录的桶，因为该记录是刻意 fire-and-forget 的：
     * 限流计数器绝不能延迟、也绝不能导致一个实际已被服务的请求的结算失败。
     */
    private static final String CREDIT_BUCKET_KEY =
            "gateway:limit:{gateway-limits}:credits:package:" + PACKAGE_ID;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static DatabaseClient database;
    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static RequestLimitService limitService;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE gateway.channel SET last_health_status = 'UNHEALTHY';
                    UPDATE gateway.channel SET last_health_status = 'HEALTHY'
                    WHERE id = '84000000-0000-4000-8000-000000000002';
                    INSERT INTO gateway.package_instance(
                        id, grant_event_id, grant_request_id, tenant_id, user_id,
                        package_plan_version_id, package_code, package_name, price_amount,
                        allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                        tokens_per_minute, max_concurrency, duration_days, status,
                        starts_at, expires_at, activated_at, daily_request_limit, identity_key)
                    VALUES (
                        '99000000-0000-4000-8000-000000000003',
                        '99000000-0000-4000-8000-000000000007',
                        '99000000-0000-4000-8000-000000000008',
                        '99000000-0000-4000-8000-000000000001',
                        '99000000-0000-4000-8000-000000000002',
                        '99000000-0000-4000-8000-000000000009',
                        'FREE', 'Free', 0,
                        '[{"modelCode":"text-basic"}]',
                        1000000, 2, 100, 30, 10, 30, 'ACTIVE',
                        now(), now() + interval '30 days', now(), NULL,
                        '99000000-0000-4000-8000-000000000002');
                    INSERT INTO gateway.api_key(
                        id, tenant_id, user_id, package_instance_id, name,
                        key_prefix, key_hash, allowed_models, status)
                    VALUES
                        ('99000000-0000-4000-8000-000000000004',
                         '99000000-0000-4000-8000-000000000001',
                         '99000000-0000-4000-8000-000000000002',
                         '99000000-0000-4000-8000-000000000003',
                         'tpm-key-a', 'sk-yzh-tpm-a', 'tpm-key-a-hash',
                         '["text-basic"]', 'ACTIVE'),
                        ('99000000-0000-4000-8000-000000000005',
                         '99000000-0000-4000-8000-000000000001',
                         '99000000-0000-4000-8000-000000000002',
                         '99000000-0000-4000-8000-000000000003',
                         'tpm-key-b', 'sk-yzh-tpm-b', 'tpm-key-b-hash',
                         '["text-basic"]', 'ACTIVE'),
                        ('99000000-0000-4000-8000-000000000006',
                         '99000000-0000-4000-8000-000000000001',
                         '99000000-0000-4000-8000-000000000002',
                         NULL,
                         'wallet-key', 'sk-yzh-wallet', 'wallet-key-hash',
                         '["text-basic"]', 'ACTIVE');
                    """);
        }
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        database = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        // 预热两个响应式连接池：冷启动的 Lettuce 连接可能超出限流器的 Redis
        // 超时，从而表现为一个不相关的 dependency_unavailable。
        redis.execute(connection -> connection.serverCommands().flushAll())
                .blockLast(Duration.ofSeconds(20));
        database.sql("SELECT 1 AS warm").map((row, metadata) -> 1).one()
                .block(Duration.ofSeconds(20));
        limitService = new DefaultRequestLimitService(
                new RedisRequestLimitStore(redis), new PostgresRequestLimitStore(database),
                2, Duration.ofSeconds(2), null,
                DailyRequestLimitService.unlimited(),
                new DefaultPackageTokenRateLimitService(
                        new RedisPackageTokenRateLimitStore(redis),
                        new PostgresPackageTokenRateLimitStore(database),
                        Duration.ofSeconds(2)));
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetBuckets() {
        redis.execute(connection -> connection.serverCommands().flushAll())
                .blockLast(Duration.ofSeconds(20));
    }

    @Test
    void refusesTheRequestBeyondTheSoldCreditsPerMinuteBeforeThePaidUpstreamCall() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        assertSoftly(softly -> {
            completeQuietly(service, keyA(), "req_tpm_1");

            softly.assertThat(awaitRecordedCredits())
                    .as("the charge the package actually settled must reach the admission bucket")
                    .isGreaterThanOrEqualTo(SOLD_CREDITS_PER_MINUTE);

            Throwable refusal = refusalOf(() ->
                    service.complete(keyA(), request(), "req_tpm_2").block(Duration.ofSeconds(10)));
            softly.assertThat(refusal)
                    .as("a package that already consumed its whole minute must refuse the next"
                            + " request instead of admitting it")
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded");
                    });
            softly.assertThat(adapter.completeCalls.get())
                    .as("the request beyond the sold credit rate must never reach the paid"
                            + " upstream call")
                    .isEqualTo(1);
        });
    }

    @Test
    void theCreditRateFollowsThePackageInstanceRatherThanTheApiKey() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        completeQuietly(service, keyA(), "req_tpm_4");
        awaitRecordedCredits();

        assertThatThrownBy(() -> service.complete(keyB(), request(), "req_tpm_5")
                .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded"));
        assertThat(adapter.completeCalls.get()).isEqualTo(1);
    }

    @Test
    void answersItsOwnRateLimitEnvelopeSoAClientCanTellTheCapsApart() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);
        WebTestClient web = client(service);

        completeQuietly(service, keyA(), "req_tpm_6");
        awaitRecordedCredits();

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("rate_limit_error")
                .jsonPath("$.error.code").isEqualTo("tokens_per_minute_exceeded");
        assertThat(adapter.completeCalls.get()).isEqualTo(1);
    }

    @Test
    void packagesThatSellNoCreditRateKeepThePreviousBehaviour() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        completeQuietly(service, walletKey(), "req_tpm_7");
        completeQuietly(service, walletKey(), "req_tpm_8");

        assertThat(adapter.completeCalls.get())
                .as("without a package there is no sold credit rate to enforce")
                .isEqualTo(2);
        assertThat(recordedCredits()).isZero();
    }

    /**
     * 流式入口与一次性补全入口是相同的准入和相同的结算尾部，
     * 因此积分速率必须以同样的方式约束它：同一分钟内的第二个流式请求
     * 在到达供应商之前就被拒绝。
     */
    @Test
    void theStreamEntryPointIsBoundedByTheSameSoldCreditRate() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        streamQuietly(service, "req_tpm_stream_1");
        assertThat(awaitRecordedCredits())
                .as("a streamed settlement must feed the same credit bucket")
                .isGreaterThanOrEqualTo(SOLD_CREDITS_PER_MINUTE);

        Throwable refusal = refusalOf(() -> streamQuietly(service, "req_tpm_stream_2"));

        assertThat(refusal).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded");
        });
        assertThat(adapter.streamCalls.get())
                .as("the streamed request beyond the sold credit rate never reached the vendor")
                .isEqualTo(1);
    }

    private static void streamQuietly(ChatCompletionService service, String requestId) {
        service.stream(keyA(), streamRequest(), requestId)
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private static void completeQuietly(
            ChatCompletionService service, ApiKeyService.ApiKeyContext key, String requestId) {
        service.complete(key, request(), requestId).block(Duration.ofSeconds(10));
    }

    private static Throwable refusalOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    /**
     * 该记录在结算尾部执行，且不会阻塞响应，因此这里对一个真实 key
     * 做有时限的轮询，而不是假定它已经写入。
     */
    private static long awaitRecordedCredits() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        long recorded = 0;
        while (System.nanoTime() < deadline) {
            recorded = recordedCredits();
            if (recorded >= SOLD_CREDITS_PER_MINUTE) {
                return recorded;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return recorded;
            }
        }
        return recorded;
    }

    private static long recordedCredits() {
        String credits = redis.<String, String>opsForHash().get(CREDIT_BUCKET_KEY, "credits")
                .block(Duration.ofSeconds(5));
        return credits == null ? 0 : Long.parseLong(credits);
    }

    private static ChatCompletionService service(CountingAdapter adapter) {
        QuotaService quota = mock(QuotaService.class);
        when(quota.reserve(any())).thenAnswer(invocation -> {
            QuotaService.ReserveCommand command = invocation.getArgument(0);
            return Mono.just(new QuotaService.Reservation(
                    command.reservationId(), command.requestId(), PACKAGE_ID, command.modelCode(),
                    command.tokens(), "RESERVED", command.expiresAt()));
        });
        when(quota.settle(any(), any(), any())).thenAnswer(invocation -> Mono.just(
                new QuotaService.Settlement(
                        invocation.getArgument(0), SOLD_CREDITS_PER_MINUTE, SOLD_CREDITS_PER_MINUTE,
                        0, "SETTLED", invocation.getArgument(2))));
        when(quota.release(any(), any())).thenReturn(Mono.empty());
        DatabaseGatewayCatalog catalog = new DatabaseGatewayCatalog(
                database, new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults()),
                new CredentialCipher(""), JSON);
        return new ChatCompletionService(
                catalog, entry -> Mono.empty(), quota, limitService, new TokenEstimator(3, 1.25),
                new ChannelRouter(), new ChannelCircuitRegistry(CircuitBreakerRegistry.ofDefaults()),
                List.of(adapter), JSON, Duration.ofMinutes(2));
    }

    private static WebTestClient client(ChatCompletionService service) {
        return WebTestClient.bindToController(new ChatCompletionController(service))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, keyA());
                    return chain.filter(exchange);
                })
                .configureClient()
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static ApiKeyService.ApiKeyContext keyA() {
        return new ApiKeyService.ApiKeyContext(
                KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));
    }

    private static ApiKeyService.ApiKeyContext keyB() {
        return new ApiKeyService.ApiKeyContext(
                SECOND_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));
    }

    private static ApiKeyService.ApiKeyContext walletKey() {
        return new ApiKeyService.ApiKeyContext(
                WALLET_KEY_ID, TENANT_ID, USER_ID, null, Set.of("text-basic"));
    }

    private static ObjectNode request() {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", "text-basic");
        body.put("stream", false);
        body.put("max_tokens", 20);
        body.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return body;
    }

    private static ObjectNode streamRequest() {
        ObjectNode body = request();
        body.put("stream", true);
        return body;
    }

    private static final class CountingAdapter implements ProviderAdapter {

        private final AtomicInteger completeCalls = new AtomicInteger();
        private final AtomicInteger streamCalls = new AtomicInteger();

        @Override
        public String providerType() {
            return "BAILIAN";
        }

        @Override
        public Set<ChannelRouter.Capability> capabilities() {
            return Set.of(ChannelRouter.Capability.TEXT);
        }

        @Override
        public Mono<ProviderResponse> complete(RoutedRequest request) {
            completeCalls.incrementAndGet();
            ObjectNode body = JSON.createObjectNode();
            body.put("id", "chatcmpl-tpm");
            body.put("object", "chat.completion");
            body.put("created", 100);
            body.put("model", "qwen3.7-flash");
            ObjectNode choice = body.putArray("choices").addObject();
            choice.put("index", 0).put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", "hello");
            body.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20)
                    .put("total_tokens", 30);
            return Mono.just(new ProviderResponse(
                    body, new ProviderAdapter.TokenUsage(10, 20, 30)));
        }

        /**
         * 一个内容分片和一个用量分片：足以让流式路径完成结算，
         * 而这正是积分速率的来源。
         */
        @Override
        public Flux<ProviderChunk> stream(RoutedRequest request) {
            streamCalls.incrementAndGet();
            ObjectNode content = JSON.createObjectNode();
            content.put("id", "chatcmpl-tpm-stream");
            content.put("object", "chat.completion.chunk");
            content.put("created", 100);
            content.put("model", "qwen3.7-flash");
            content.putArray("choices").addObject().put("index", 0)
                    .putObject("delta").put("content", "hello");
            ObjectNode done = JSON.createObjectNode();
            done.put("id", "chatcmpl-tpm-stream");
            done.put("object", "chat.completion.chunk");
            done.put("created", 100);
            done.put("model", "qwen3.7-flash");
            ObjectNode doneChoice = done.putArray("choices").addObject();
            doneChoice.put("index", 0);
            doneChoice.putObject("delta");
            doneChoice.put("finish_reason", "stop");
            return Flux.just(
                    new ProviderAdapter.ProviderChunk(content, null),
                    new ProviderAdapter.ProviderChunk(
                            done, new ProviderAdapter.TokenUsage(10, 20, 30)));
        }

        @Override
        public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
            return Mono.error(new UnsupportedOperationException("credit_rate_file_not_used"));
        }
    }
}
