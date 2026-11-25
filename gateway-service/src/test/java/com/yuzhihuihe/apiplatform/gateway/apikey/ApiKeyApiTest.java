package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import com.yuzhihuihe.apiplatform.gateway.security.ConsoleTokenValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Testcontainers
@Import(ApiKeyApiTest.ProbeController.class)
@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "spring.data.redis.timeout=100ms",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret",
                // 关掉内容安全。本类测的是 API key 的认证与缓存，而内容安全是数据面
                // 上的另一道闸：它要问平台侧要策略与账号安全状态，测试环境里没有那个
                // 上游，于是每个 /v1/* 请求都以 content_safety_unavailable（503）结束。
                // 只打控制台接口的那几个用例不经过这道闸，因此一直是绿的。
                "content-safety.enabled=false"
        })
class ApiKeyApiTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private final AtomicReference<String> cachedPayload = new AtomicReference<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try {
            execute("""
                    CREATE SCHEMA IF NOT EXISTS platform;
                    CREATE TABLE IF NOT EXISTS platform.app_user(
                        id uuid PRIMARY KEY,
                        tenant_id uuid NOT NULL,
                        status varchar(20) NOT NULL
                    );
                    CREATE OR REPLACE VIEW platform.gateway_user_status AS
                    SELECT tenant_id, id, status FROM platform.app_user;
                    GRANT USAGE ON SCHEMA platform TO PUBLIC;
                    GRANT SELECT ON platform.gateway_user_status TO PUBLIC;
                    """);
        } catch (Exception error) {
            throw new IllegalStateException("test_platform_user_schema_setup_failed", error);
        }
    }

    @BeforeEach
    void resetData() throws Exception {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            return Mono.just(switch (token) {
                case "other-user-token" -> jwt(token, TENANT_ID, OTHER_USER_ID);
                case "other-tenant-token" -> jwt(token, OTHER_TENANT_ID, USER_ID);
                default -> jwt(token, TENANT_ID, USER_ID);
            });
        });
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        cachedPayload.set(null);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(anyString())).thenAnswer(
                invocation -> Mono.justOrEmpty(cachedPayload.get()));
        when(redisValues.set(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            cachedPayload.set(invocation.getArgument(1));
            return Mono.just(true);
        });
        when(redisTemplate.delete(anyString())).thenAnswer(invocation -> {
            cachedPayload.set(null);
            return Mono.just(1L);
        });
        // 同一个 Redis 还被**限流**路径使用，而那条路走的是 Lua 脚本，不是 opsForValue：
        //   DefaultRequestLimitService → RedisRequestLimitStore.isAvailable() 用的是
        //   hasKey(...)，acquire 用的是 execute(ACQUIRE, ...)（返回 1 表示放行）。
        // Mockito 对未桩的方法返回 null，于是每个 /v1/* 请求都会在限流阶段以
        // NullPointerException 失败、再被映射成 503 dependency_unavailable——
        // 本类里所有走数据面的用例因此全红，而只打控制台接口的用例照样绿。
        // 按真实语义把这两个调用补上，缓存用例要测的东西不受影响。
        // 账号安全状态：CLEARED 才放行（见字段上的说明）。
        // 这是本类所有数据面用例拿到 503 的唯一原因——限流路径并不需要额外桩。
        when(accountEnforcement.current(any(), any())).thenReturn(Mono.just(
                new com.yuzhihuihe.apiplatform.gateway.safety.AccountEnforcementService
                        .Enforcement("CLEARED", null, null)));
        execute("TRUNCATE gateway.api_key, gateway.package_instance_model_quota, "
                + "gateway.package_instance, gateway.consumed_event CASCADE");
        execute("TRUNCATE platform.app_user");
        execute("""
                INSERT INTO platform.app_user(id, tenant_id, status)
                VALUES ('71000000-0000-4000-8000-000000000001',
                        '61000000-0000-4000-8000-000000000001', 'ACTIVE')
                """);
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user,
                    status, starts_at, expires_at, activated_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000001',
                    '41000000-0000-4000-8000-000000000001',
                    '51000000-0000-4000-8000-000000000001',
                    '61000000-0000-4000-8000-000000000001',
                    '71000000-0000-4000-8000-000000000001',
                    'verified-identity-key',
                    '91000000-0000-4000-8000-000000000001',
                    'STARTER', 'Starter', 1900, 'CNY',
                    '[{"modelCode":"text-basic","modelTokenQuota":null,"capabilities":["TEXT"]}]',
                    5000000, 1, 20, 100000, 3, 30, 20971520, false,
                    'ACTIVE', now(), now() + interval '30 days', now())
                """);
    }

    @org.springframework.beans.factory.annotation.Autowired
    WebTestClient webTestClient;

    @org.springframework.beans.factory.annotation.Autowired
    ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    ApiKeyService apiKeyService;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    ReactiveValueOperations<String, String> redisValues;

    /**
     * 账号安全状态是数据面上的另一道闸：{@code SafetyEnforcementWebFilter} 对每个带
     * API key 的 {@code /v1/*} 请求都要问它一次，只有 {@code CLEARED} 才放行；
     * 查不到状态就回 503 content_safety_unavailable。它本身没有任何开关，
     * 而测试环境里没有平台侧的策略上游——本类里所有走数据面的用例因此拿到 503，
     * 只打控制台接口的用例不受影响。这里把它桩成 CLEARED，与被测行为无关。
     */
    @MockitoBean
    com.yuzhihuihe.apiplatform.gateway.safety.AccountEnforcementService accountEnforcement;

    @MockitoBean
    ConsoleTokenValidator consoleTokenValidator;

    @Test
    void authenticatedUserCanManageOwnKeys() {
        consoleClient().get().uri("/console/gateway/api-keys")
                .exchange().expectStatus().isOk()
                .expectBody().json("[]");
        consoleClient().get().uri("/console/gateway/api-keys/eligible-models")
                .exchange().expectStatus().isOk()
                .expectBody().json("[\"text-basic\"]");
        JsonNode created = consoleClient().post().uri("/console/gateway/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Console\",\"allowedModels\":[\"text-basic\"]}")
                .exchange().expectStatus().isCreated()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.path("secret").asText()).startsWith("sk-yzh-");
        String id = created.path("id").asText();
        consoleClient().get().uri("/console/gateway/api-keys")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo(id)
                .jsonPath("$[0].secret").doesNotExist();
        consoleClient().patch().uri("/console/gateway/api-keys/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"DISABLED\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("DISABLED");
        consoleClient().post().uri("/console/gateway/api-keys/{id}/reset", id)
                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_sms_proof");
        consoleClient().delete().uri("/console/gateway/api-keys/{id}", id)
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void ordinaryManagementCannotSeeCountOrMutateManagedKeys() throws Exception {
        UUID managedId = UUID.fromString("91000000-0000-4000-8000-000000000001");
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at,
                    managed, secret_ciphertext, secret_key_version)
                VALUES (
                    '91000000-0000-4000-8000-000000000001',
                    '61000000-0000-4000-8000-000000000001',
                    '71000000-0000-4000-8000-000000000001',
                    '81000000-0000-4000-8000-000000000001',
                    'Managed', 'sk-yzh-managed', 'managed-key-hash',
                    '["text-basic"]', 'ACTIVE', now() + interval '30 days',
                    true, decode(repeat('01', 29), 'hex'), 1)
                """);

        assertThat(apiKeyService.list(TENANT_ID, USER_ID).collectList().block()).isEmpty();
        assertThat(createKey("Ordinary", "text-basic").path("id").asText())
                .isNotEqualTo(managedId.toString());
        assertThatThrownBy(() -> apiKeyService.changeStatus(
                TENANT_ID, USER_ID, managedId, "DISABLED").block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("api_key_not_found");
        assertThatThrownBy(() -> apiKeyService.revoke(
                TENANT_ID, USER_ID, managedId).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("api_key_not_found");
        assertThat(singleString("SELECT status FROM gateway.api_key WHERE id = '"
                + managedId + "'" )).isEqualTo("ACTIVE");
    }

    @Test
    void returnsSecretOnceAndStoresOnlyPepperedDigest() throws Exception {
        JsonNode created = createKey("Development", "text-basic");
        String secret = created.path("secret").asText();

        assertThat(secret).startsWith("sk-yzh-").hasSizeGreaterThanOrEqualTo(50);
        assertThat(singleString("SELECT key_hash FROM gateway.api_key"))
                .hasSize(64)
                .isNotEqualTo(secret);

        String listed = objectMapper.writeValueAsString(
                apiKeyService.list(TENANT_ID, USER_ID).collectList().block());
        assertThat(listed).doesNotContain(secret).doesNotContain("key_hash");
    }

    @Test
    void enforcesActivePackageKeyLimitAndAllowedModelSubset() throws Exception {
        createKey("First", "text-basic");

        assertThatThrownBy(() -> apiKeyService.create(TENANT_ID, USER_ID,
                new ApiKeyService.CreateApiKeyRequest(
                        "Second", List.of("text-basic"), null)).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("api_key_limit_exceeded");

        execute("DELETE FROM gateway.api_key");
        assertThatThrownBy(() -> apiKeyService.create(TENANT_ID, USER_ID,
                new ApiKeyService.CreateApiKeyRequest(
                        "Forbidden", List.of("text-pro"), null)).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("api_key_model_not_allowed");
    }

    @Test
    void disabledAndRevokedKeysCannotAuthenticate() throws Exception {
        JsonNode created = createKey("Runtime", "text-basic");
        String id = created.path("id").asText();
        String secret = created.path("secret").asText();

        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isOk()
                .expectBody().returnResult();

        apiKeyService.changeStatus(TENANT_ID, USER_ID,
                UUID.fromString(id), "DISABLED").block();
        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isUnauthorized()
                .expectBody().returnResult();

        apiKeyService.revoke(TENANT_ID, USER_ID, UUID.fromString(id)).block();
        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isUnauthorized()
                .expectBody().returnResult();
    }

    @Test
    void disabledKeyCanBeReEnabledAndAuthenticateAgain() throws Exception {
        JsonNode created = createKey("Re-enabled", "text-basic");
        String id = created.path("id").asText();
        String secret = created.path("secret").asText();

        apiKeyService.changeStatus(TENANT_ID, USER_ID,
                UUID.fromString(id), "DISABLED").block();
        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isUnauthorized()
                .expectBody().returnResult();

        assertThat(apiKeyService.changeStatus(TENANT_ID, USER_ID,
                UUID.fromString(id), "ACTIVE").block().status()).isEqualTo("ACTIVE");
        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isOk()
                .expectBody().returnResult();
    }

    @Test
    void disabledUserCannotAuthenticateAnOtherwiseActiveKey() throws Exception {
        JsonNode created = createKey("Disabled user", "text-basic");
        String secret = created.path("secret").asText();

        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isOk();
        execute("UPDATE platform.app_user SET status = 'DISABLED'");

        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void isolatesListStatusChangesAndRevocationByTenantAndUser() throws Exception {
        JsonNode created = createKey("Owned", "text-basic");
        String id = created.path("id").asText();

        assertThat(apiKeyService.list(TENANT_ID, OTHER_USER_ID).collectList().block()).isEmpty();
        assertThat(apiKeyService.list(OTHER_TENANT_ID, USER_ID).collectList().block()).isEmpty();

        assertThatThrownBy(() -> apiKeyService.changeStatus(
                TENANT_ID, OTHER_USER_ID, UUID.fromString(id), "DISABLED").block())
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> apiKeyService.revoke(
                OTHER_TENANT_ID, USER_ID, UUID.fromString(id)).block())
                .isInstanceOf(ResponseStatusException.class);

        List<ApiKeyService.ApiKeyView> owned = apiKeyService
                .list(TENANT_ID, USER_ID).collectList().block();
        assertThat(owned).singleElement().satisfies(key -> {
            assertThat(key.id()).hasToString(id);
            assertThat(key.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void createsAndAuthenticatesWalletKeyWithoutUsableActivePackage() throws Exception {
        execute("UPDATE gateway.package_instance SET status = 'REVOKED'");

        assertThat(apiKeyService.eligibleModels(TENANT_ID, USER_ID, "TOKEN").collectList().block())
                .contains("text-basic");
        JsonNode created = createKey("Wallet only", "text-basic");

        assertThat(created.path("expiresAt").isNull()).isTrue();
        assertThat(singleString("SELECT package_instance_id::text FROM gateway.api_key")).isNull();
        apiClient(created.path("secret").asText()).get().uri("/v1/context")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.packageInstanceId").doesNotExist()
                .jsonPath("$.allowedModels[0]").isEqualTo("text-basic")
                .jsonPath("$.walletEligible").isEqualTo(true);
    }

    @Test
    void serializesConcurrentCreationAtPackageKeyLimit() throws Exception {
        ApiKeyService.CreateApiKeyRequest first = new ApiKeyService.CreateApiKeyRequest(
                "Concurrent first", List.of("text-basic"), null);
        ApiKeyService.CreateApiKeyRequest second = new ApiKeyService.CreateApiKeyRequest(
                "Concurrent second", List.of("text-basic"), null);

        var outcomes = Mono.zip(
                        apiKeyService.create(TENANT_ID, USER_ID, first)
                                .materialize().subscribeOn(Schedulers.parallel()),
                        apiKeyService.create(TENANT_ID, USER_ID, second)
                                .materialize().subscribeOn(Schedulers.parallel()))
                .block(Duration.ofSeconds(15));

        assertThat(outcomes).isNotNull();
        assertThat(List.of(outcomes.getT1(), outcomes.getT2()).stream()
                .filter(signal -> signal.isOnNext()).count()).isEqualTo(1);
        assertThat(List.of(outcomes.getT1(), outcomes.getT2()).stream()
                .filter(signal -> signal.isOnError()).count()).isEqualTo(1);
        Throwable failure = List.of(outcomes.getT1(), outcomes.getT2()).stream()
                .filter(signal -> signal.isOnError())
                .findFirst().orElseThrow().getThrowable();
        assertThat(failure)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("api_key_limit_exceeded");
        assertThat(count("gateway.api_key")).isEqualTo(1);
    }

    @Test
    void cacheInvalidationFailureFailsDisableAndStaleCacheCannotAuthenticate() throws Exception {
        JsonNode created = createKey("Cached", "text-basic");
        String id = created.path("id").asText();
        String secret = created.path("secret").asText();
        String stale = objectMapper.writeValueAsString(Map.of(
                "apiKeyId", id,
                "userId", USER_ID,
                "allowedModels", Set.of("text-basic"),
                "status", "ACTIVE"));
        when(redisValues.get(anyString())).thenReturn(Mono.just(stale));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

        assertThatThrownBy(() -> apiKeyService.changeStatus(
                TENANT_ID, USER_ID, UUID.fromString(id), "DISABLED").block())
                .isInstanceOf(RuntimeException.class);
        apiClient(secret).get().uri("/v1/probe").exchange().expectStatus().isUnauthorized()
                .expectBody().returnResult();
    }

    @Test
    void cacheInvalidationFailureFailsRevocation() throws Exception {
        JsonNode created = createKey("Revoke cached", "text-basic");
        when(redisTemplate.delete(anyString())).thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

        assertThatThrownBy(() -> apiKeyService.revoke(
                TENANT_ID, USER_ID, UUID.fromString(created.path("id").asText())).block())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void redisCacheContainsOnlyAuthenticationMetadata() throws Exception {
        JsonNode created = createKey("Cache payload", "text-basic");

        apiClient(created.path("secret").asText()).get().uri("/v1/probe")
                .exchange().expectStatus().isOk()
                .expectBody().returnResult();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisValues).set(anyString(), payload.capture(), any(Duration.class));
        JsonNode cached = objectMapper.readTree(payload.getValue());
        List<String> fields = new ArrayList<>();
        cached.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "apiKeyId", "userId", "packageInstanceId",
                "allowedModels", "walletEligible", "status");
        assertThat(cached.path("walletEligible").asBoolean()).isTrue();
        assertThat(cached.path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void cacheReadTimeoutFallsBackWithoutWritingToKnownUnavailableRedis() throws Exception {
        JsonNode created = createKey("Delayed cache", "text-basic");
        when(redisValues.get(anyString())).thenReturn(
                Mono.delay(Duration.ofSeconds(3)).then(Mono.empty()));

        long startedAt = System.nanoTime();
        apiClient(created.path("secret").asText()).get().uri("/v1/probe")
                .exchange().expectStatus().isOk().expectBody().returnResult();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        verify(redisValues, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void validCacheHitRefreshesKeyPermissionsFromDatabase() throws Exception {
        JsonNode created = createKey("Cache hit", "text-basic");
        String secret = created.path("secret").asText();

        apiClient(secret).get().uri("/v1/context")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.allowedModels[0]").isEqualTo("text-basic");
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = allowed_models ||
                    '{"modelCode":"multimodal-basic","modelTokenQuota":null,
                      "capabilities":["TEXT","IMAGE","FILE"]}'::jsonb;
                UPDATE gateway.api_key
                SET allowed_models = allowed_models || '"multimodal-basic"'::jsonb;
                """);

        byte[] body = apiClient(secret).get().uri("/v1/context")
                .exchange().expectStatus().isOk().expectBody()
                .returnResult().getResponseBody();
        List<String> models = new ArrayList<>();
        objectMapper.readTree(body).path("allowedModels")
                .forEach(model -> models.add(model.asText()));
        assertThat(models).containsExactlyInAnyOrder("text-basic", "multimodal-basic");
    }

    @Test
    void cacheHitKeepsKeyPermissionsAfterPackageModelsChange() throws Exception {
        JsonNode created = createKey("Package cache boundary", "text-basic");
        String secret = created.path("secret").asText();
        apiClient(secret).get().uri("/v1/context")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.allowedModels[0]").isEqualTo("text-basic");
        execute("""
                UPDATE gateway.package_instance
                SET allowed_models = '[{"modelCode":"text-pro","capabilities":["TEXT"]}]'::jsonb
                """);

        apiClient(secret).get().uri("/v1/context")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.allowedModels[0]").isEqualTo("text-basic");
    }

    private JsonNode createKey(String name, String model) throws Exception {
        ApiKeyService.CreatedApiKey created = apiKeyService.create(
                TENANT_ID, USER_ID,
                new ApiKeyService.CreateApiKeyRequest(name, List.of(model), null)).block();
        return objectMapper.valueToTree(created);
    }

    private WebTestClient consoleClient() {
        return consoleClient("console-token");
    }

    private WebTestClient consoleClient(String token) {
        return webTestClient.mutate().defaultHeader(
                HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
    }

    private WebTestClient apiClient(String secret) {
        return webTestClient.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secret).build();
    }

    private Jwt jwt(String token, UUID tenantId, UUID userId) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String singleString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static int count(String table) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/v1/probe")
        String probe() {
            return "ok";
        }

        @GetMapping("/v1/context")
        ApiKeyService.ApiKeyContext context(ServerWebExchange exchange) {
            return exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        }
    }
}
