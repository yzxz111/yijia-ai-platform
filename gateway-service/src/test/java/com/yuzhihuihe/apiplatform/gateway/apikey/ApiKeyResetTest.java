package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.GatewayServiceApplication;
import com.yuzhihuihe.apiplatform.gateway.security.ConsoleTokenValidator;
import com.yuzhihuihe.apiplatform.gateway.sms.PlatformSmsClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Testcontainers
@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "spring.data.redis.timeout=100ms",
                "security.api-key.pepper=reset-test-pepper",
                "security.internal.hmac-secret=reset-test-internal-secret"
        })
class ApiKeyResetTest {

    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-000000000011");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000011");
    private final Map<String, String> cache = new ConcurrentHashMap<>();

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
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
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
        execute("TRUNCATE platform.app_user");
        execute("""
                INSERT INTO platform.app_user(id, tenant_id, status)
                VALUES ('71000000-0000-4000-8000-000000000011',
                        '61000000-0000-4000-8000-000000000011', 'ACTIVE')
                """);
        execute("DROP TRIGGER IF EXISTS fail_api_key_reset_audit "
                + "ON gateway.admin_operation_audit");
        execute("DROP FUNCTION IF EXISTS gateway.fail_api_key_reset_audit()");
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(jwt()));
        when(consoleTokenValidator.validate(anyString())).thenReturn(Mono.just(true));
        cache.clear();
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(anyString())).thenAnswer(invocation ->
                Mono.justOrEmpty(cache.get(invocation.getArgument(0))));
        when(redisValues.set(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            cache.put(invocation.getArgument(0), invocation.getArgument(1));
            return Mono.just(true);
        });
        when(redisTemplate.delete(anyString())).thenAnswer(invocation ->
                Mono.just(cache.remove(invocation.getArgument(0)) == null ? 0L : 1L));
        execute("TRUNCATE gateway.api_key, gateway.package_instance_model_quota, "
                + "gateway.package_instance, gateway.admin_operation_audit, "
                + "gateway.consumed_event CASCADE");
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user,
                    status, starts_at, expires_at, activated_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000011',
                    '41000000-0000-4000-8000-000000000011',
                    '51000000-0000-4000-8000-000000000011',
                    '61000000-0000-4000-8000-000000000011',
                    '71000000-0000-4000-8000-000000000011',
                    'reset-test-identity', '91000000-0000-4000-8000-000000000011',
                    'STARTER', 'Starter', 1900, 'CNY',
                    '[{"modelCode":"text-basic","modelTokenQuota":null,"capabilities":["TEXT"]}]',
                    5000000, 2, 20, 100000, 3, 30, 20971520, false,
                    'ACTIVE', now(), now() + interval '30 days', now())
                """);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    ApiKeyService apiKeyService;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    ConsoleTokenValidator consoleTokenValidator;

    @MockitoBean
    PlatformSmsClient platformSmsClient;

    @MockitoBean
    ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    ReactiveValueOperations<String, String> redisValues;

    @Test
    void rejectsUnknownKeyBeforeConsumingProof() throws Exception {
        String proof = "valid-proof-for-unknown-key";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.empty());

        reset(UUID.randomUUID(), proof, 404);

        verify(platformSmsClient, never()).consumeApiKeyResetProof(
                any(), any(), any(UUID.class), anyString());
    }

    @Test
    void rejectsManagedKeyBeforeConsumingProof() throws Exception {
        UUID managedId = UUID.fromString("91000000-0000-4000-8000-000000000011");
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at,
                    managed, secret_ciphertext, secret_key_version)
                VALUES (
                    '91000000-0000-4000-8000-000000000011',
                    '61000000-0000-4000-8000-000000000011',
                    '71000000-0000-4000-8000-000000000011',
                    '81000000-0000-4000-8000-000000000011',
                    'Managed reset boundary', 'sk-yzh-managed', 'managed-reset-hash',
                    '["text-basic"]', 'ACTIVE', now() + interval '30 days',
                    true, decode(repeat('01', 29), 'hex'), 1)
                """);

        reset(managedId, "unused-managed-proof", 404);

        verify(platformSmsClient, never()).consumeApiKeyResetProof(
                any(), any(), any(UUID.class), anyString());
        assertThat(singleString("SELECT status FROM gateway.api_key WHERE id = '"
                + managedId + "'" )).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsRevokedAndForeignKeysBeforeConsumingProof() throws Exception {
        UUID revokedKeyId = UUID.fromString(createKey().path("id").asText());
        UUID foreignKeyId = UUID.fromString(createKey().path("id").asText());
        apiKeyService.revoke(TENANT_ID, USER_ID, revokedKeyId).block();
        execute("UPDATE gateway.api_key SET tenant_id = '" + UUID.randomUUID()
                + "', user_id = '" + UUID.randomUUID() + "' WHERE id = '" + foreignKeyId + "'");

        reset(revokedKeyId, "proof-for-revoked-key", 404);
        reset(foreignKeyId, "proof-for-foreign-key", 404);

        verify(platformSmsClient, never()).consumeApiKeyResetProof(
                any(), any(), any(UUID.class), anyString());
    }

    @Test
    void retriesPendingResetWithStableOperationIdAndCompletesOnce() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        String proof = "retryable-proof-never-stored";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.error(new PlatformSmsClient.PlatformSmsClientException(
                        "platform_sms_http_status", 503)))
                .thenReturn(Mono.empty());

        reset(keyId, proof, 503);

        assertThat(singleString("SELECT status FROM gateway.api_key_reset_intent"))
                .isEqualTo("PENDING");
        String operationId = singleString(
                "SELECT operation_id::text FROM gateway.api_key_reset_intent");
        String fingerprint = singleString(
                "SELECT proof_fingerprint FROM gateway.api_key_reset_intent");
        assertThat(fingerprint).matches("[0-9a-f]{64}").isNotEqualTo(proof);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNotNull();

        JsonNode retried = reset(keyId, proof, 200);

        ArgumentCaptor<UUID> operations = ArgumentCaptor.forClass(UUID.class);
        verify(platformSmsClient, org.mockito.Mockito.times(2)).consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), operations.capture(), eq(proof));
        assertThat(operations.getAllValues()).containsOnly(UUID.fromString(operationId));
        assertThat(singleString("SELECT status FROM gateway.api_key_reset_intent"))
                .isEqualTo("COMPLETED");
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET' AND resource_id = '" + keyId + "'"))
                .isEqualTo(1L);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNull();
        assertThat(apiKeyService.authenticate(retried.path("secret").asText()).block()).isNotNull();
    }

    @Test
    void mapsVerifiedPlatformInternalServerErrorToRetryableServiceUnavailable() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        String proof = "proof-for-platform-internal-error";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.error(new PlatformSmsClient.PlatformSmsClientException(
                        "platform_sms_http_status", 500)));

        reset(keyId, proof, 503);

        assertThat(singleString("SELECT status FROM gateway.api_key_reset_intent"))
                .isEqualTo("PENDING");
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNotNull();
    }

    @Test
    void removesRejectedIntentSoDifferentInvalidProofsDoNotAccumulate() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), anyString()))
                .thenReturn(Mono.error(new PlatformSmsClient.PlatformSmsClientException(
                        "platform_sms_http_status", 422)));

        reset(keyId, "first-invalid-proof", 422);
        reset(keyId, "second-invalid-proof", 422);

        verify(platformSmsClient, org.mockito.Mockito.times(2)).consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), anyString());
        assertThat(singleLong("SELECT count(*) FROM gateway.api_key_reset_intent "
                + "WHERE api_key_id = '" + keyId + "' AND status = 'PENDING'"))
                .isZero();
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET' AND resource_id = '" + keyId + "'"))
                .isZero();
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNotNull();
    }

    @Test
    void allowsOnlyOnePendingResetWhenDifferentProofsRaceForTheSameKey() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        String firstProof = "first-concurrent-reset-proof";
        String secondProof = "second-concurrent-reset-proof";
        AtomicInteger proofConsumptions = new AtomicInteger();
        CountDownLatch firstReachedPlatform = new CountDownLatch(1);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), anyString()))
                .thenAnswer(invocation -> {
                    if (proofConsumptions.incrementAndGet() == 1) {
                        firstReachedPlatform.countDown();
                        return Mono.fromFuture(releaseFirst);
                    }
                    return Mono.empty();
                });

        CompletableFuture<ApiKeyService.ResetApiKey> first = apiKeyService
                .reset(TENANT_ID, USER_ID, keyId, firstProof)
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture();
        Object secondOutcome = null;
        try {
            assertThat(firstReachedPlatform.await(10, TimeUnit.SECONDS)).isTrue();
            secondOutcome = awaitOutcome(apiKeyService
                    .reset(TENANT_ID, USER_ID, keyId, secondProof)
                    .subscribeOn(Schedulers.boundedElastic())
                    .toFuture());
        } finally {
            releaseFirst.complete(null);
        }
        Object firstOutcome = awaitOutcome(first);

        assertThat(firstOutcome).isInstanceOf(ApiKeyService.ResetApiKey.class);
        assertThat(secondOutcome).isInstanceOfSatisfying(
                ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(proofConsumptions).hasValue(1);
        assertThat(singleLong("SELECT count(*) FROM gateway.api_key_reset_intent")).isEqualTo(1L);
        assertThat(singleLong("SELECT count(*) FROM gateway.api_key_reset_intent "
                + "WHERE status = 'COMPLETED'")).isEqualTo(1L);
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET' AND resource_id = '" + keyId + "'"))
                .isEqualTo(1L);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNull();
        assertThat(apiKeyService.authenticate(
                ((ApiKeyService.ResetApiKey) firstOutcome).secret()).block()).isNotNull();
    }

    @Test
    void reusesOneOperationWhenTheSameProofRacesForTheSameKey() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        String proof = "same-concurrent-reset-proof";
        AtomicInteger proofConsumptions = new AtomicInteger();
        List<UUID> operationIds = new CopyOnWriteArrayList<>();
        CountDownLatch firstReachedPlatform = new CountDownLatch(1);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenAnswer(invocation -> {
                    operationIds.add(invocation.getArgument(2));
                    if (proofConsumptions.incrementAndGet() == 1) {
                        firstReachedPlatform.countDown();
                        return Mono.fromFuture(releaseFirst);
                    }
                    return Mono.empty();
                });

        CompletableFuture<ApiKeyService.ResetApiKey> first = apiKeyService
                .reset(TENANT_ID, USER_ID, keyId, proof)
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture();
        Object secondOutcome = null;
        try {
            assertThat(firstReachedPlatform.await(10, TimeUnit.SECONDS)).isTrue();
            secondOutcome = awaitOutcome(apiKeyService
                    .reset(TENANT_ID, USER_ID, keyId, proof)
                    .subscribeOn(Schedulers.boundedElastic())
                    .toFuture());
        } finally {
            releaseFirst.complete(null);
        }
        Object firstOutcome = awaitOutcome(first);

        assertThat(secondOutcome).isInstanceOf(ApiKeyService.ResetApiKey.class);
        assertThat(firstOutcome).isInstanceOfSatisfying(
                ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(operationIds).hasSize(2).containsOnly(operationIds.getFirst());
        assertThat(proofConsumptions).hasValue(2);
        assertThat(singleLong("SELECT count(*) FROM gateway.api_key_reset_intent")).isEqualTo(1L);
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET' AND resource_id = '" + keyId + "'"))
                .isEqualTo(1L);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNull();
        assertThat(apiKeyService.authenticate(
                ((ApiKeyService.ResetApiKey) secondOutcome).secret()).block()).isNotNull();
    }

    @Test
    void retriesSameOperationAfterLateAuditFailureRollsBackRotation() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        String oldPrefix = created.path("keyPrefix").asText();
        String proof = "proof-retried-after-audit-failure";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.empty());
        execute("""
                CREATE FUNCTION gateway.fail_api_key_reset_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'API_KEY_RESET' THEN
                        RAISE EXCEPTION 'forced_api_key_reset_audit_failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        execute("""
                CREATE TRIGGER fail_api_key_reset_audit
                BEFORE INSERT ON gateway.admin_operation_audit
                FOR EACH ROW EXECUTE FUNCTION gateway.fail_api_key_reset_audit()
                """);

        reset(keyId, proof, 503);

        UUID operationId = UUID.fromString(singleString(
                "SELECT operation_id::text FROM gateway.api_key_reset_intent"));
        assertThat(singleString("SELECT status FROM gateway.api_key_reset_intent"))
                .isEqualTo("PENDING");
        assertThat(singleString("SELECT key_prefix FROM gateway.api_key WHERE id = '"
                + keyId + "'"))
                .isEqualTo(oldPrefix);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNotNull();
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET'"))
                .isZero();
        execute("DROP TRIGGER fail_api_key_reset_audit ON gateway.admin_operation_audit");
        execute("DROP FUNCTION gateway.fail_api_key_reset_audit()");

        JsonNode retried = reset(keyId, proof, 200);

        ArgumentCaptor<UUID> operations = ArgumentCaptor.forClass(UUID.class);
        verify(platformSmsClient, org.mockito.Mockito.times(2)).consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), operations.capture(), eq(proof));
        assertThat(operations.getAllValues()).containsOnly(operationId);
        assertThat(singleString("SELECT status FROM gateway.api_key_reset_intent"))
                .isEqualTo("COMPLETED");
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET' AND resource_id = '" + keyId + "'"))
                .isEqualTo(1L);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNull();
        assertThat(apiKeyService.authenticate(retried.path("secret").asText()).block()).isNotNull();
    }

    @Test
    void rejectsMissingAndConsumedProofWithoutRotatingAgain() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();

        assertThatThrownBy(() -> apiKeyService
                .reset(TENANT_ID, USER_ID, keyId, null).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(422));
        verify(platformSmsClient, never()).consumeApiKeyResetProof(
                any(), any(), any(UUID.class), anyString());
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNotNull();

        String proof = "single-use-proof";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.empty());
        JsonNode first = reset(keyId, proof, 200);

        JsonNode completedRetry = reset(keyId, proof, 422);

        verify(platformSmsClient).consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof));
        assertThat(completedRetry.path("secret").isMissingNode()).isTrue();
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET'"))
                .isEqualTo(1L);
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNull();
        assertThat(apiKeyService.authenticate(first.path("secret").asText()).block()).isNotNull();
    }

    @Test
    void finalLockRejectsRevocationAfterProofConsumptionWithoutRotating() throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldHash = singleString(
                "SELECT key_hash FROM gateway.api_key WHERE id = '" + keyId + "'");
        String proof = "proof-consumed-before-concurrent-revocation";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.fromCallable(() -> {
                    execute("UPDATE gateway.api_key SET status = 'REVOKED', revoked_at = now() "
                            + "WHERE id = '" + keyId + "'");
                    return true;
                }).then());

        reset(keyId, proof, 404);

        assertThat(singleString("SELECT status FROM gateway.api_key_reset_intent"))
                .isEqualTo("PENDING");
        assertThat(singleString("SELECT key_hash FROM gateway.api_key WHERE id = '" + keyId + "'"))
                .isEqualTo(oldHash);
        assertThat(singleLong("SELECT count(*) FROM gateway.admin_operation_audit "
                + "WHERE action = 'API_KEY_RESET'"))
                .isZero();
    }

    @Test
    void atomicallyRotatesOwnedKeyInvalidatesOldAndNewCachesAndAuditsOnlyMetadata()
            throws Exception {
        JsonNode created = createKey();
        UUID keyId = UUID.fromString(created.path("id").asText());
        String oldSecret = created.path("secret").asText();
        String oldPrefix = created.path("keyPrefix").asText();
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNotNull();
        assertThat(cache).hasSize(1);
        clearInvocations(redisTemplate);
        String proof = "valid-proof-not-for-audit";
        when(platformSmsClient.consumeApiKeyResetProof(
                eq(TENANT_ID), eq(USER_ID), any(UUID.class), eq(proof)))
                .thenReturn(Mono.empty());

        JsonNode reset = reset(keyId, proof, 200);

        String newSecret = reset.path("secret").asText();
        String newPrefix = reset.path("keyPrefix").asText();
        assertThat(newSecret).startsWith("sk-yzh-").isNotEqualTo(oldSecret);
        assertThat(newPrefix).isNotEqualTo(oldPrefix);
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, org.mockito.Mockito.times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).hasSize(2).doesNotHaveDuplicates();
        assertThat(apiKeyService.authenticate(oldSecret).block()).isNull();
        assertThat(apiKeyService.authenticate(newSecret).block()).isNotNull();
        assertThat(singleLong("SELECT count(*) FROM gateway.api_key WHERE id = '" + keyId + "'"))
                .isEqualTo(1L);
        String audit = singleString("SELECT detail::text FROM gateway.admin_operation_audit "
                + "WHERE resource_id = '" + keyId + "'");
        assertThat(audit).contains(keyId.toString(), oldPrefix, newPrefix)
                .doesNotContain(oldSecret, newSecret, proof);
    }

    private JsonNode createKey() throws Exception {
        ApiKeyService.CreatedApiKey created = apiKeyService.create(
                TENANT_ID, USER_ID,
                new ApiKeyService.CreateApiKeyRequest(
                        "Resettable", List.of("text-basic"), null)).block();
        return objectMapper.valueToTree(created);
    }

    private JsonNode reset(UUID keyId, String proof, int expectedStatus) throws Exception {
        try {
            ApiKeyService.ResetApiKey result = apiKeyService
                    .reset(TENANT_ID, USER_ID, keyId, proof).block();
            assertThat(expectedStatus).isEqualTo(HttpStatus.OK.value());
            return objectMapper.valueToTree(result);
        } catch (ResponseStatusException error) {
            assertThat(error.getStatusCode().value()).isEqualTo(expectedStatus);
            return objectMapper.createObjectNode();
        }
    }

    private static Jwt jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("reset-user-token").header("alg", "none")
                .subject(USER_ID.toString()).claim("tenant_id", TENANT_ID.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String singleString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private long singleLong(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private Object awaitOutcome(CompletableFuture<ApiKeyService.ResetApiKey> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            return error.getCause();
        } catch (Exception error) {
            return error;
        }
    }
}
