package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.yuzhihuihe.apiplatform.gateway.limit.DefaultDailyRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.DefaultRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.PostgresDailyRequestLimitStore;
import com.yuzhihuihe.apiplatform.gateway.limit.PostgresRequestLimitStore;
import com.yuzhihuihe.apiplatform.gateway.limit.RedisDailyRequestLimitStore;
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
 * 已售出的 {@code daily_request_limit} 在准入时即被强制执行，且在任何付费上游调用之前，
 * 与每分钟限流完全一样：相同的 429 响应封装、相同的分钟租约释放，而且一旦上限用尽，
 * 任何请求都不会到达供应商。
 */
@Testcontainers
class ChatDailyRequestLimitAdmissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("93000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("93000000-0000-4000-8000-000000000003");
    private static final UUID KEY_ID = UUID.fromString("93000000-0000-4000-8000-000000000004");
    private static final UUID SECOND_KEY_ID = UUID.fromString("93000000-0000-4000-8000-000000000008");
    private static final int SOLD_DAILY_REQUEST_LIMIT = 2;

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
                        starts_at, expires_at, activated_at, identity_key, daily_request_limit)
                    VALUES (
                        '93000000-0000-4000-8000-000000000003',
                        '93000000-0000-4000-8000-000000000005',
                        '93000000-0000-4000-8000-000000000006',
                        '93000000-0000-4000-8000-000000000001',
                        '93000000-0000-4000-8000-000000000002',
                        '93000000-0000-4000-8000-000000000007',
                        'FREE', 'Free', 0,
                        '[{"modelCode":"text-basic"}]',
                        1000000, 2, 100, 100000, 10, 30, 'ACTIVE',
                        now(), now() + interval '30 days', now(),
                        '93000000-0000-4000-8000-000000000002', 2);
                    INSERT INTO gateway.api_key(
                        id, tenant_id, user_id, package_instance_id, name,
                        key_prefix, key_hash, allowed_models, status)
                    VALUES
                        ('93000000-0000-4000-8000-000000000004',
                         '93000000-0000-4000-8000-000000000001',
                         '93000000-0000-4000-8000-000000000002',
                         '93000000-0000-4000-8000-000000000003',
                         'daily-key-a', 'sk-yzh-daily-a', 'daily-key-a-hash',
                         '["text-basic"]', 'ACTIVE'),
                        ('93000000-0000-4000-8000-000000000008',
                         '93000000-0000-4000-8000-000000000001',
                         '93000000-0000-4000-8000-000000000002',
                         '93000000-0000-4000-8000-000000000003',
                         'daily-key-b', 'sk-yzh-daily-b', 'daily-key-b-hash',
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
        // 预热两个响应式连接池：一条冷的 Lettuce 连接可能超出限流器的 Redis 超时，
        // 从而表现为一个看似无关的 dependency_unavailable。
        redis.execute(connection -> connection.serverCommands().flushAll())
                .blockLast(Duration.ofSeconds(20));
        database.sql("SELECT 1 AS warm").map((row, metadata) -> 1).one()
                .block(Duration.ofSeconds(20));
        limitService = new DefaultRequestLimitService(
                new RedisRequestLimitStore(redis), new PostgresRequestLimitStore(database),
                2, Duration.ofSeconds(2), null,
                new DefaultDailyRequestLimitService(
                        new RedisDailyRequestLimitStore(redis),
                        new PostgresDailyRequestLimitStore(database),
                        Duration.ofSeconds(2)));
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetDailyCounter() {
        redis.execute(connection -> connection.serverCommands().flushAll())
                .blockLast(Duration.ofSeconds(20));
    }

    @Test
    void rejectsTheRequestBeyondTheSoldDailyCapBeforeThePaidUpstreamCall() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        completeQuietly(service, keyA(), "req_daily_1");
        completeQuietly(service, keyA(), "req_daily_2");

        assertThatThrownBy(() -> service.complete(keyA(), request(), "req_daily_3")
                .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded");
                    assertThat(OpenAiErrorMapper.descriptor(error)).satisfies(descriptor -> {
                        assertThat(descriptor.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(descriptor.type()).isEqualTo("rate_limit_error");
                        assertThat(descriptor.code()).isEqualTo("daily_request_limit_exceeded");
                    });
                });
        assertThat(adapter.completeCalls.get())
                .as("the request beyond the sold daily cap must never reach the paid upstream call")
                .isEqualTo(SOLD_DAILY_REQUEST_LIMIT);
    }

    @Test
    void theDailyCapFollowsThePackageInstanceRatherThanTheApiKey() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        completeQuietly(service, keyA(), "req_daily_4");
        completeQuietly(service, keyA(), "req_daily_5");

        assertThatThrownBy(() -> service.complete(keyB(), request(), "req_daily_6")
                .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded"));
        assertThat(adapter.completeCalls.get()).isEqualTo(SOLD_DAILY_REQUEST_LIMIT);
    }

    @Test
    void answersTheSameRateLimitEnvelopeAsTheMinuteLimiter() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);
        WebTestClient web = client(service);

        completeQuietly(service, keyA(), "req_daily_7");
        completeQuietly(service, keyA(), "req_daily_8");

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("rate_limit_error")
                .jsonPath("$.error.code").isEqualTo("daily_request_limit_exceeded");
        assertThat(adapter.completeCalls.get()).isEqualTo(SOLD_DAILY_REQUEST_LIMIT);
    }

    /**
     * 流式入口与补全入口经由同一道门准入。
     *
     * <p>{@code completePreparedInternal} 与 {@code openPreparedStreamInternal} 都在预留之前、
     * 在任何供应商调用之前获取租约——因而也就获取了已售出的每日上限。这里专门为流式路径
     * 钉住这一点，因为只在两个入口中的一个上强制执行的上限算不上上限：客户本可以以流式
     * 绕过其补全请求已被拒绝的每日额度。
     */
    @Test
    void rejectsTheStreamBeyondTheSoldDailyCapBeforeThePaidUpstreamCall() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        streamQuietly(service, keyA(), "req_daily_stream_1");
        streamQuietly(service, keyA(), "req_daily_stream_2");

        assertThatThrownBy(() -> service.openStream(
                        keyA(), streamRequest(), "req_daily_stream_3")
                .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded");
                    assertThat(OpenAiErrorMapper.descriptor(error).code())
                            .isEqualTo("daily_request_limit_exceeded");
                });
        assertThat(adapter.streamCalls.get())
                .as("the stream beyond the sold daily cap must never reach the paid upstream call")
                .isEqualTo(SOLD_DAILY_REQUEST_LIMIT);
        assertThat(adapter.completeCalls.get()).isZero();
    }

    @Test
    void theDailyCapIsSharedBetweenCompletionsAndStreams() {
        CountingAdapter adapter = new CountingAdapter();
        ChatCompletionService service = service(adapter);

        completeQuietly(service, keyA(), "req_daily_mixed_1");
        streamQuietly(service, keyA(), "req_daily_mixed_2");

        assertThatThrownBy(() -> service.openStream(
                        keyA(), streamRequest(), "req_daily_mixed_3")
                .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded"));
        assertThat(adapter.completeCalls.get()).isEqualTo(1);
        assertThat(adapter.streamCalls.get()).isEqualTo(1);
    }

    private static void completeQuietly(
            ChatCompletionService service, ApiKeyService.ApiKeyContext key, String requestId) {
        service.complete(key, request(), requestId).block(Duration.ofSeconds(10));
    }

    private static void streamQuietly(
            ChatCompletionService service, ApiKeyService.ApiKeyContext key, String requestId) {
        service.stream(key, streamRequest(), requestId)
                .collectList()
                .block(Duration.ofSeconds(10));
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
                        invocation.getArgument(0), 10, 20, 0, "SETTLED", invocation.getArgument(2))));
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
            body.put("id", "chatcmpl-daily");
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

        @Override
        public Flux<ProviderChunk> stream(RoutedRequest request) {
            streamCalls.incrementAndGet();
            ObjectNode body = JSON.createObjectNode();
            body.put("id", "chatcmpl-daily-stream");
            body.put("object", "chat.completion.chunk");
            body.put("created", 100);
            body.put("model", "qwen3.7-flash");
            body.putArray("choices").addObject().put("index", 0)
                    .putObject("delta").put("content", "hello");
            return Flux.just(new ProviderChunk(
                    body, new ProviderAdapter.TokenUsage(10, 20, 30)));
        }

        @Override
        public Mono<ProviderFileRef> bindFile(FileObject file, Channel channel) {
            return Mono.error(new UnsupportedOperationException("daily_cap_file_not_used"));
        }
    }
}
