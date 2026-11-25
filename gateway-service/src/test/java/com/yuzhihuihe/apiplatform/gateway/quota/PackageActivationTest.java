package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.micrometer.core.instrument.MeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret"
        })
class PackageActivationTest {

    private static final String EVENT_PATH = "/internal/events/package-granted";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // 内部事件验签依赖 Redis 防重放，测试必须提供独立实例。
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
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
    }

    @BeforeEach
    void resetPackages() throws Exception {
        execute("TRUNCATE gateway.package_instance_model_quota, gateway.package_instance, "
                + "gateway.consumed_event CASCADE");
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    QuotaService quotaService;

    @Autowired
    PackageActivationService packageActivationService;

    @Autowired
    PackageRefundService packageRefundService;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void duplicateGrantEventCreatesOneInstance() throws Exception {
        String event = eventJson(
                "40000000-0000-4000-8000-000000000001",
                "grant-001",
                "STARTER");

        deliver(event).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE");
        deliver(event).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE");

        assertThat(count("gateway.package_instance")).isEqualTo(1);
        assertThat(count("gateway.consumed_event")).isEqualTo(1);
    }

    @Test
    void secondPackageQueuesBehindActivePackage() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE");
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED");

        assertThat(statuses()).containsExactly("ACTIVE", "QUEUED");
    }

    @Test
    void freePackageCanOnlyBeGrantedOncePerVerifiedIdentity() throws Exception {
        String firstUser = "70000000-0000-4000-8000-000000000001";
        String secondRegistration = "70000000-0000-4000-8000-000000000002";
        String identityKey = "same-verified-identity-hash";

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-free-001",
                firstUser, identityKey, "FREE", true))
                .expectStatus().isOk();
        execute("UPDATE gateway.package_instance SET status = 'EXHAUSTED', ended_at = now() "
                + "WHERE package_code = 'FREE'");

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-free-002",
                secondRegistration, identityKey, "FREE", true))
                .expectStatus().isEqualTo(422);

        assertThat(count("gateway.package_instance")).isEqualTo(1);
    }

    @Test
    void generatedFreePackageCodeUsesSemanticKindForIdentityLimit() throws Exception {
        String firstUser = "70000000-0000-4000-8000-000000000001";
        String secondRegistration = "70000000-0000-4000-8000-000000000002";
        String identityKey = "same-generated-free-identity";
        String firstEvent = eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-custom-free-001",
                firstUser, identityKey, "FREE", true)
                .replace("\"packageCode\": \"FREE\"", "\"packageCode\": \"PKG_CUSTOM_FREE\"")
                .replace("\"packageName\": \"FREE\"", "\"packageName\": \"体验套餐\"");
        String secondEvent = eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-custom-free-002",
                secondRegistration, identityKey, "FREE", true)
                .replace("\"packageCode\": \"FREE\"", "\"packageCode\": \"PKG_CUSTOM_FREE\"")
                .replace("\"packageName\": \"FREE\"", "\"packageName\": \"体验套餐\"");

        deliver(firstEvent).expectStatus().isOk();
        execute("UPDATE gateway.package_instance SET status = 'EXHAUSTED', ended_at = now()");
        deliver(secondEvent).expectStatus().isEqualTo(422);

        assertThat(count("gateway.package_instance WHERE package_kind = 'FREE_CLAIM'"))
                .isEqualTo(1);
    }

    @Test
    void exhaustedPackageActivatesOldestQueuedSnapshotBeforeNewGrant() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002",
                "70000000-0000-4000-8000-000000000001", "identity-hash-001",
                "ADVANCED", false, 1_000_000L))
                .expectStatus().isOk();
        execute("""
                UPDATE gateway.package_instance
                SET used_tokens = total_token_quota
                WHERE status = 'ACTIVE'
                """);

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000003", "grant-003", "STARTER"))
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED");

        assertThat(statuses()).containsExactly("EXHAUSTED", "ACTIVE", "QUEUED");
        assertThat(count("gateway.package_instance_model_quota")).isEqualTo(1);
    }

    @Test
    void fullyReservedActivePackageRemainsActiveUntilUsageSettles() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        execute("""
                UPDATE gateway.package_instance
                SET reserved_tokens = total_token_quota
                WHERE status = 'ACTIVE'
                """);

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED");

        assertThat(statuses()).containsExactly("ACTIVE", "QUEUED");
    }

    @Test
    void rejectsQueuedSnapshotWithOversizedModelCodeBeforePersistence() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        String oversizedModel = "m".repeat(129);
        String invalidEvent = eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED")
                .replace("\"text-standard\"", "\"" + oversizedModel + "\"");

        deliver(invalidEvent).expectStatus().isEqualTo(422);

        assertThat(count("gateway.package_instance")).isEqualTo(1);
        assertThat(count("gateway.consumed_event")).isEqualTo(1);
    }

    @Test
    void existingApiKeyUsesNewPackageAfterQueuedActivation() throws Exception {
        UUID apiKeyId = UUID.fromString("90000000-0000-4000-8000-000000000001");
        UUID managedApiKeyId = UUID.fromString("90000000-0000-4000-8000-000000000002");
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        UUID oldPackageId = uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '90000000-0000-4000-8000-000000000001',
                    '60000000-0000-4000-8000-000000000001',
                    '70000000-0000-4000-8000-000000000001',
                    '%s', 'Switch test', 'sk-yzh-switch', 'switch-test-hash',
                    '["text-standard"]', 'ACTIVE', now() + interval '30 days')
                """.formatted(oldPackageId));
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at,
                    managed, secret_ciphertext, secret_key_version)
                VALUES (
                    '90000000-0000-4000-8000-000000000002',
                    '60000000-0000-4000-8000-000000000001',
                    '70000000-0000-4000-8000-000000000001',
                    '%s', 'Managed switch test', 'sk-yzh-managed', 'managed-switch-hash',
                    '["text-standard"]', 'ACTIVE', now() + interval '30 days',
                    true, decode(repeat('01', 29), 'hex'), 1)
                """.formatted(oldPackageId));
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED");
        execute("UPDATE gateway.package_instance SET used_tokens = total_token_quota "
                + "WHERE id = '" + oldPackageId + "'");

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000003", "grant-003", "STARTER"))
                .expectStatus().isOk();
        UUID newPackageId = uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");

        assertThat(uuid("SELECT package_instance_id FROM gateway.api_key WHERE id = '" + apiKeyId + "'"))
                .isEqualTo(newPackageId);
        assertThat(uuid("SELECT package_instance_id FROM gateway.api_key WHERE id = '"
                + managedApiKeyId + "'"))
                .isEqualTo(oldPackageId);
        QuotaService.Reservation reservation = quotaService.reserve(new QuotaService.ReserveCommand(
                        UUID.randomUUID(), "request-after-switch",
                        UUID.fromString("60000000-0000-4000-8000-000000000001"),
                        UUID.fromString("70000000-0000-4000-8000-000000000001"),
                        apiKeyId, "text-standard", 1_000, Instant.now().plusSeconds(60)))
                .block(Duration.ofSeconds(10));
        assertThat(reservation.packageInstanceId()).isEqualTo(newPackageId);
    }

    @Test
    void packageSwitchWritesImmutableLedgerEntry() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        UUID oldPackageId = uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();
        execute("UPDATE gateway.package_instance SET used_tokens = total_token_quota "
                + "WHERE id = '" + oldPackageId + "'");

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000003", "grant-003", "STARTER"))
                .expectStatus().isOk();
        UUID newPackageId = uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");

        assertThat(count("gateway.quota_ledger WHERE entry_type = 'SWITCH'")).isEqualTo(1);
        assertThat(uuid("SELECT source_package_instance_id FROM gateway.quota_ledger "
                + "WHERE entry_type = 'SWITCH'"))
                .isEqualTo(oldPackageId);
        assertThat(uuid("SELECT package_instance_id FROM gateway.quota_ledger "
                + "WHERE entry_type = 'SWITCH'"))
                .isEqualTo(newPackageId);
        assertThatThrownBy(() -> execute(
                "UPDATE gateway.quota_ledger SET total_token_delta = 1 WHERE entry_type = 'SWITCH'"))
                .hasMessageContaining("quota_ledger_immutable");
        assertThatThrownBy(() -> execute(
                "DELETE FROM gateway.quota_ledger WHERE entry_type = 'SWITCH'"))
                .hasMessageContaining("quota_ledger_immutable");
    }

    /**
     * 没有已终结前驱时激活排队实例，必须成功，且不写 SWITCH 台账。
     *
     * <p>ck_ledger_scope 要求 SWITCH 行必须带 source_package_instance_id，而没有前驱
     * 就没有 source。此前的实现把这个情况当成「台账写入失败」抛
     * package_switch_ledger_failed，导致整个发放事务回滚、平台侧收到 HTTP 500，
     * 套餐永远留在排队中。这个状态真实出现过：维度唯一性修复后，被另一个维度的生效
     * 实例挡住而排队至今的实例终于被激活，而它的上一个实例从未终结过。
     */
    @Test
    void activatingQueuedPackageWithoutTerminalPredecessorWritesNoSwitchLedger() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();
        // 制造「该维度有排队、无生效、且该用户全维度没有任何已终结实例」的状态。
        // 排队实例按 ck_package_instance_dates 不带任何起止时间，与线上一直排在队列里
        // 从未生效过的实例形状一致。
        execute("UPDATE gateway.package_instance SET status = 'QUEUED', starts_at = NULL, "
                + "activated_at = NULL, expires_at = NULL WHERE status = 'ACTIVE'");
        assertThat(count("gateway.package_instance WHERE status IN "
                + "('EXHAUSTED', 'EXPIRED', 'REVOKED')")).isZero();

        var result = packageActivationService
                .activateNext(UUID.fromString("70000000-0000-4000-8000-000000000001"), "TOKEN")
                .block(Duration.ofSeconds(20));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(count("gateway.package_instance WHERE status = 'ACTIVE'")).isEqualTo(1);
        // 没有前驱就没有切换可记：台账必须为空，而不是写入一行违反约束的数据。
        assertThat(count("gateway.quota_ledger WHERE entry_type = 'SWITCH'")).isZero();
    }

    /**
     * 重复投递必须推进队列 —— 这是排队实例唯一的自动激活触发。
     *
     * <p>平台对 QUEUED 结果每 5 分钟重投一次。如果重复投递只回读既有结果，
     * 排队实例就只剩「有新发放」和「另一个实例终结」两种触发，而它等待的那个实例
     * 可能属于另一个维度、永远不会终结，于是永远排下去 —— 线上正是如此。
     */
    @Test
    void duplicateDeliveryAdvancesQueueSoQueuedPackageCanActivate() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        UUID oldest = uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();
        assertThat(count("gateway.package_instance WHERE status = 'QUEUED'")).isEqualTo(1);
        // 制造「该维度有排队、无生效、无任何已终结实例」。
        execute("UPDATE gateway.package_instance SET status = 'QUEUED', starts_at = NULL, "
                + "activated_at = NULL, expires_at = NULL WHERE status = 'ACTIVE'");

        // 平台重投第二个事件（同一 eventId）：队列必须因此往前走。
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();

        assertThat(count("gateway.package_instance WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'"))
                .isEqualTo(oldest);
    }

    @Test
    void concurrentActivationSelectsOneQueuedPackageAndWritesOneSwitch() throws Exception {
        UUID userId = UUID.fromString("70000000-0000-4000-8000-000000000001");
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();
        execute("UPDATE gateway.package_instance SET used_tokens = total_token_quota "
                + "WHERE status = 'ACTIVE'");

        var results = Mono.zip(
                        packageActivationService.activateNext(userId, "TOKEN")
                                .subscribeOn(Schedulers.parallel()),
                        packageActivationService.activateNext(userId, "TOKEN")
                                .subscribeOn(Schedulers.parallel()))
                .block(Duration.ofSeconds(20));

        assertThat(results).isNotNull();
        assertThat(results.getT1()).isEqualTo(results.getT2());
        assertThat(results.getT1().status()).isEqualTo("ACTIVE");
        assertThat(count("gateway.package_instance WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("gateway.quota_ledger WHERE entry_type = 'SWITCH'")).isEqualTo(1);
    }

    @Test
    void refundedActivePackageActivatesOldestQueuedAndRebindsKeysOnce() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();
        UUID activePackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        UUID activeGrant = uuid("SELECT grant_request_id FROM gateway.package_instance WHERE id = '"
                + activePackage + "'");
        UUID queuedPackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'QUEUED'");
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '90000000-0000-4000-8000-000000000099',
                    '60000000-0000-4000-8000-000000000001',
                    '70000000-0000-4000-8000-000000000001',
                    '%s', 'Refund switch', 'sk-yzh-refund', 'refund-switch-hash',
                    '["text-standard"]', 'ACTIVE', now() + interval '30 days')
                """.formatted(activePackage));
        packageRefundService.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-revoke:active", activeGrant))
                .block(Duration.ofSeconds(10));
        String event = refundedEventJson(
                "44000000-0000-4000-8000-000000000001", "refund-001",
                "refund-revoke:active", activeGrant);
        double revokedBefore = counter("gateway.refund.revoke", "REVOKED");
        double replayBefore = counter("gateway.refund.revoke", "REPLAY");

        deliverRefund(event).expectStatus().isOk()
                .expectBody().jsonPath("$.packageInstanceId").isEqualTo(activePackage.toString())
                .jsonPath("$.status").isEqualTo("REVOKED")
                .jsonPath("$.activatedPackageInstanceId").isEqualTo(queuedPackage.toString())
                .jsonPath("$.activatedPackageStatus").isEqualTo("ACTIVE");
        deliverRefund(event).expectStatus().isOk().expectBody().returnResult();

        assertThat(counter("gateway.refund.revoke", "REVOKED") - revokedBefore)
                .isEqualTo(1);
        assertThat(counter("gateway.refund.revoke", "REPLAY") - replayBefore)
                .isEqualTo(1);
        assertThat(uuid("SELECT package_instance_id FROM gateway.api_key WHERE id = "
                + "'90000000-0000-4000-8000-000000000099'"))
                .isEqualTo(queuedPackage);
        assertThat(count("gateway.package_lifecycle_event WHERE event_type = 'REFUND_REVOKE'"))
                .isEqualTo(1);
        deliverRefund(event.replace("refund-001", "refund-002"))
                .expectStatus().isEqualTo(409)
                .expectBody().returnResult();
    }

    @Test
    void readyRefundHoldPreventsTerminalizationUntilPackageIsRevoked() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk().expectBody().returnResult();
        UUID heldPackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        UUID heldGrant = uuid("SELECT grant_request_id FROM gateway.package_instance WHERE id = '"
                + heldPackage + "'");
        packageRefundService.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-revoke:terminal-guard", heldGrant))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.package_instance SET expires_at = now() - interval '1 second' "
                + "WHERE id = '" + heldPackage + "'");

        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk().expectBody().returnResult();
        assertThat(count("gateway.package_instance WHERE id = '" + heldPackage
                + "' AND status = 'ACTIVE'"))
                .isEqualTo(1);
        UUID queuedPackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'QUEUED'");
        deliverRefund(refundedEventJson(
                "44000000-0000-4000-8000-000000000004", "refund-terminal-guard",
                "refund-revoke:terminal-guard", heldGrant))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("REVOKED")
                .jsonPath("$.activatedPackageInstanceId").isEqualTo(queuedPackage.toString());
    }

    @Test
    void refundedQueuedPackageLeavesCurrentActiveUnchanged() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000002", "grant-002", "ADVANCED"))
                .expectStatus().isOk();
        UUID activePackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        UUID queuedPackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'QUEUED'");
        UUID queuedGrant = uuid("SELECT grant_request_id FROM gateway.package_instance WHERE id = '"
                + queuedPackage + "'");
        packageRefundService.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-revoke:queued", queuedGrant))
                .block(Duration.ofSeconds(10));

        deliverRefund(refundedEventJson(
                "44000000-0000-4000-8000-000000000002", "refund-queued",
                "refund-revoke:queued", queuedGrant))
                .expectStatus().isOk()
                .expectBody().returnResult();

        assertThat(uuid("SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'"))
                .isEqualTo(activePackage);
        assertThat(count("gateway.package_instance WHERE id = '" + queuedPackage
                + "' AND status = 'REVOKED'"))
                .isEqualTo(1);
    }

    @Test
    void refundWithoutQueuedSuccessorLeavesKeysBoundToRevokedPackage() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        UUID activePackage = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        UUID activeGrant = uuid("SELECT grant_request_id FROM gateway.package_instance WHERE id = '"
                + activePackage + "'");
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '90000000-0000-4000-8000-000000000098',
                    '60000000-0000-4000-8000-000000000001',
                    '70000000-0000-4000-8000-000000000001',
                    '%s', 'Refund no successor', 'sk-yzh-refund2', 'refund-no-next-hash',
                    '["text-standard"]', 'ACTIVE', now() + interval '30 days')
                """.formatted(activePackage));
        packageRefundService.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-revoke:no-next", activeGrant))
                .block(Duration.ofSeconds(10));

        deliverRefund(refundedEventJson(
                "44000000-0000-4000-8000-000000000003", "refund-no-next",
                "refund-revoke:no-next", activeGrant))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("REVOKED")
                .jsonPath("$.activatedPackageInstanceId").doesNotExist();

        assertThat(uuid("SELECT package_instance_id FROM gateway.api_key WHERE id = "
                + "'90000000-0000-4000-8000-000000000098'"))
                .isEqualTo(activePackage);
        assertThat(count("gateway.package_instance WHERE status = 'ACTIVE'"))
                .isZero();
    }

    @Test
    void crossedRevocationEventAndBusinessIdentifiersConflict() throws Exception {
        deliver(eventJson(
                "40000000-0000-4000-8000-000000000001", "grant-001", "STARTER"))
                .expectStatus().isOk();
        UUID packageId = uuid(
                "SELECT id FROM gateway.package_instance WHERE status = 'ACTIVE'");
        UUID grantId = uuid("SELECT grant_request_id FROM gateway.package_instance WHERE id = '"
                + packageId + "'");
        packageRefundService.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-revoke:crossed-b", grantId))
                .block(Duration.ofSeconds(10));
        execute("""
                INSERT INTO gateway.package_lifecycle_event(
                    id, event_id, business_no, package_instance_id,
                    event_type, source_business_no, payload_hash)
                VALUES
                    ('45000000-0000-4000-8000-000000000001',
                     '44000000-0000-4000-8000-000000000010',
                     'refund-revoke:crossed-a', '%s',
                     'REFUND_REVOKE', 'refund-crossed-a', repeat('a', 64)),
                    ('45000000-0000-4000-8000-000000000002',
                     '44000000-0000-4000-8000-000000000011',
                     'refund-revoke:crossed-b', '%s',
                     'REFUND_REVOKE', 'refund-crossed-b', repeat('b', 64))
                """.formatted(packageId, packageId));

        deliverRefund(refundedEventJson(
                "44000000-0000-4000-8000-000000000010", "refund-crossed",
                "refund-revoke:crossed-b", grantId))
                .expectStatus().isEqualTo(409)
                .expectBody().returnResult();
    }

    @Test
    void acceptsAVideoPackageAndRejectsADimensionCapabilityMismatch() throws Exception {
        // 秒数维度的套餐只能携带具备 VIDEO 能力的权益。这是
        // 准入侧对 V40 触发器在网关表上强制执行的规则的一份副本：
        // 准入不匹配的组合会让 token 请求消耗秒数余额，或者让
        // 视频请求耗尽 token 池。
        deliver(videoEventJson(
                "40000000-0000-4000-8000-0000000000b1", "grant-video-001",
                "VIDEO_SECOND", "VIDEO"))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ACTIVE");

        // 维度决定该权益的配额落在哪一列。
        assertThat(scalar("SELECT usage_dimension FROM gateway.package_instance"))
                .isEqualTo("VIDEO_SECOND");
        assertThat(scalar("SELECT seconds_quota FROM gateway.package_instance_model_quota"))
                .isEqualTo("30");

        deliver(videoEventJson(
                "40000000-0000-4000-8000-0000000000b2", "grant-video-002",
                "TOKEN", "VIDEO"))
                .expectStatus().isEqualTo(422);
        deliver(videoEventJson(
                "40000000-0000-4000-8000-0000000000b3", "grant-video-003",
                "VIDEO_SECOND", "TEXT"))
                .expectStatus().isEqualTo(422);

        assertThat(count("gateway.package_instance")).isEqualTo(1);
    }

    /**
     * 方案 C 第 4 步：一个同时卖视频秒与音频秒的版本，必须在网关侧真的变成
     * **两份**额度，而且每个模型的自额度行写着**它自己的**单位。
     *
     * <p>这条测试盯的是三个各自都会静默出错的点：
     *
     * <ol>
     *   <li>实例上必须出现 {@code audio_seconds_quota}，否则音频秒无处安放；
     *   <li>音频别名的自额度行必须是 {@code AUDIO_SECOND}。写错成主维度
     *       （VIDEO_SECOND）不会让发放失败——它只会让之后的每一次音频预留
     *       要么以 {@code usage_dimension_mismatch} 被拒，要么拿音频的秒数
     *       去和视频秒的子额度比大小；
     *   <li>视频别名的自额度行必须**仍然**是 VIDEO_SECOND，且额度取自视频那一列。
     * </ol>
     */
    /**
     * 客户端文本通道（{@code chat-standard} / {@code chat-deep}）随"卖文本"的
     * TOKEN 版本一起发放（2026-10-09 生产故障的修复）。
     *
     * <p>V103 之后客户端目录里的文本入口**只剩这两个**，而平台的在售版本里没有它们
     * （只有 text-* / vision-* / multimodal-basic / file-basic）。不在发放时补齐，
     * 一个刚买到的文本套餐在客户端就是"模型全部不可用"——权益、路由、计费全都正常，
     * 只有目录是空的。
     *
     * <p>负对照同样重要：按秒计费的视频版本不该因此多出文本入口（它本来就不进对话
     * 目录，多两个别名只会让"这份套餐买了什么"变得含混）。
     */
    @Test
    void grantsTheClientTextChannelsTogetherWithTextEntitlement() throws Exception {
        deliver(eventJson("40000000-0000-4000-8000-0000000000d1", "grant-text-channels-001",
                "ADVANCED"))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ACTIVE");

        assertThat(scalar("""
                SELECT string_agg(element.value ->> 'modelCode', ',' ORDER BY element.ordinality)
                FROM gateway.package_instance package,
                     jsonb_array_elements(package.allowed_models)
                         WITH ORDINALITY AS element(value, ordinality)
                """)).isEqualTo("text-standard,chat-standard,chat-deep");

        // 两个通道不带自额度：留空表示可以花掉整份 token 桶（与 V106 给音频别名的
        // 处理一致），因此不该出现它们的 package_instance_model_quota 行。
        assertThat(scalar("""
                SELECT count(*) FROM gateway.package_instance_model_quota
                WHERE model_code IN ('chat-standard', 'chat-deep')
                """)).isEqualTo("0");

        // 负对照：视频版本不卖文本，一个通道都不该拿到（只看那个按秒计费的实例——
        // 上面那个文本套餐本来就有这两个通道）。
        deliver(videoEventJson(
                "40000000-0000-4000-8000-0000000000d2", "grant-text-channels-002",
                "VIDEO_SECOND", "VIDEO"))
                .expectStatus().isOk();
        assertThat(scalar("""
                SELECT count(*)
                FROM gateway.package_instance package,
                     jsonb_array_elements(package.allowed_models) element
                WHERE package.usage_dimension = 'VIDEO_SECOND'
                  AND element ->> 'modelCode' IN ('chat-standard', 'chat-deep')
                """)).isEqualTo("0");
    }

    @Test
    void mixedVideoAndAudioGrantCreatesTwoBucketsAndKeepsEachModelsOwnDimension() throws Exception {
        deliver(mixedEventJson(
                "40000000-0000-4000-8000-0000000000c1", "grant-mixed-001", 600L))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ACTIVE");

        assertThat(scalar("SELECT usage_dimension FROM gateway.package_instance"))
                .isEqualTo("VIDEO_SECOND");
        assertThat(scalar("SELECT total_token_quota FROM gateway.package_instance")).isEqualTo("100");
        assertThat(scalar("SELECT audio_seconds_quota FROM gateway.package_instance"))
                .isEqualTo("600");
        assertThat(scalar("SELECT audio_seconds_reserved FROM gateway.package_instance"))
                .isEqualTo("0");
        assertThat(scalar("SELECT audio_seconds_used FROM gateway.package_instance"))
                .isEqualTo("0");

        // 每个模型的自额度行按**它自己的**维度落库：视频别名走 token_quota 之外的
        // seconds_quota（VIDEO_SECOND），音频别名同样走 seconds_quota 但维度不同。
        // 两个不同维度的行落在同一列上是 V40/V42 的既有形状（一列"非 token 额度"），
        // 因此这里断言的是 (维度, 额度) 的配对，而不是列名。
        assertThat(scalar("""
                SELECT usage_dimension || ':' || seconds_quota
                FROM gateway.package_instance_model_quota
                WHERE model_code = 'video-basic'
                """)).isEqualTo("VIDEO_SECOND:100");
        assertThat(scalar("""
                SELECT usage_dimension || ':' || seconds_quota
                FROM gateway.package_instance_model_quota
                WHERE model_code = 'audio-asr-basic'
                """)).isEqualTo("AUDIO_SECOND:600");
    }

    /**
     * 两个方向的错配都必须在**发放**这一步被拒绝，而不是留到客户开始调用。
     *
     * <p>三种情形各自对应的真实事故：音频别名没有音频额度（每一次语音请求都以
     * {@code quota_exhausted} 结束，而客户已经付过款）、没有音频别名却带着音频额度
     * （一个无人能消费的承诺）、以及纯音频版本同时填了两份额度（"这次请求扣哪一列"
     * 出现两个都说得通的答案）。平台侧同一组规则在 PackageVersionService 与
     * 平台 V93 的 CHECK 里各拦一次；网关再拦一次是因为**能计费的一方是网关**。
     */
    @Test
    void rejectsGrantEventsWhoseAudioSecondBucketDoesNotMatchTheirModels() throws Exception {
        deliver(mixedEventJson(
                "40000000-0000-4000-8000-0000000000c2", "grant-mixed-002", null))
                .expectStatus().isEqualTo(422);
        deliver(videoEventJsonWithAudioBucket(
                "40000000-0000-4000-8000-0000000000c3", "grant-mixed-003", 600L))
                .expectStatus().isEqualTo(422);
        deliver(pureAudioEventJsonWithAudioBucket(
                "40000000-0000-4000-8000-0000000000c4", "grant-mixed-004"))
                .expectStatus().isEqualTo(422);

        assertThat(count("gateway.package_instance")).isZero();
        assertThat(count("gateway.package_instance_model_quota")).isZero();
    }

    private static final String VIDEO_MODEL_JSON = """
            {
              "modelCode": "video-basic",
              "modelTokenQuota": 100,
              "capabilities": ["TEXT", "IMAGE", "VIDEO"],
              "usageDimension": "VIDEO_SECOND"
            }""";

    private static final String AUDIO_MODEL_JSON = """
            {
              "modelCode": "audio-asr-basic",
              "modelTokenQuota": 600,
              "capabilities": ["AUDIO_TRANSCRIBE"],
              "usageDimension": "AUDIO_SECOND"
            }""";

    /**
     * 方案 C 的混合版本授予事件：一个视频别名（VIDEO_SECOND，100 秒）与一个音频
     * 别名（AUDIO_SECOND，600 秒）共存，实例级第二份额度为
     * {@code audioSecondsQuota}（为 null 时**故意**造出"有音频别名却没有音频额度"
     * 那种错配）。
     *
     * <p>两个模型的 {@code usageDimension} 是逐模型下发的——这正是方案 C 加进快照
     * 的字段，也是网关唯一能知道"这个别名按什么单位计费"的来源。
     */
    private String mixedEventJson(String eventId, String businessNo, Long audioSecondsQuota) {
        return audioBucketEventJson(
                eventId, businessNo, "100", "VIDEO_SECOND", audioSecondsQuota,
                VIDEO_MODEL_JSON + ",\n  " + AUDIO_MODEL_JSON);
    }

    /** 只有视频别名，却带着一份音频额度：一个无人能消费的承诺。 */
    private String videoEventJsonWithAudioBucket(
            String eventId, String businessNo, long audioSecondsQuota) {
        return audioBucketEventJson(
                eventId, businessNo, "100", "VIDEO_SECOND", audioSecondsQuota, VIDEO_MODEL_JSON);
    }

    /**
     * 纯音频版本（usageDimension = AUDIO_SECOND）**同时**填了第二份额度。
     * 那种版本的主额度列本身就是音频秒，再填一份会让"这次请求扣哪一列"
     * 出现两个都说得通的答案。
     */
    private String pureAudioEventJsonWithAudioBucket(String eventId, String businessNo) {
        return audioBucketEventJson(
                eventId, businessNo, "600", "AUDIO_SECOND", 600L, AUDIO_MODEL_JSON);
    }

    private String audioBucketEventJson(
            String eventId, String businessNo, String totalQuota, String usageDimension,
            Long audioSecondsQuota, String modelsJson) {
        String grantRequestId = eventId.replaceFirst("^4", "5");
        String audio = audioSecondsQuota == null ? "null" : audioSecondsQuota.toString();
        return """
                {
                  "eventId": "%s",
                  "eventType": "PACKAGE_GRANTED_V1",
                  "businessKey": "%s",
                  "payload": {
                    "grantRequestId": "%s",
                    "businessNo": "%s",
                    "tenantId": "60000000-0000-4000-8000-000000000001",
                    "userId": "70000000-0000-4000-8000-000000000001",
                    "identityKey": "identity-hash-mixed",
                    "packageVersionId": "80000000-0000-4000-8000-000000000001",
                    "packageCode": "VIDEO_AUDIO",
                    "packageName": "VIDEO_AUDIO",
                    "packageKind": "STANDARD",
                    "priceAmount": 19900,
                    "currency": "CNY",
                    "totalTokenQuota": %s,
                    "audioSecondsQuota": %s,
                    "durationDays": 30,
                    "maxApiKeys": 2,
                    "requestsPerMinute": 20,
                    "tokensPerMinute": 60,
                    "maxConcurrency": 2,
                    "dailyRequestLimit": null,
                    "maxOutputTokens": null,
                    "maxFileSizeBytes": 20971520,
                    "oncePerUser": false,
                    "usageDimension": "%s",
                    "models": [%s]
                  }
                }
                """.formatted(
                eventId, businessNo, grantRequestId, businessNo, totalQuota, audio,
                usageDimension, modelsJson);
    }

    private WebTestClient.ResponseSpec deliver(String body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        String contentSha256 = sha256(body.getBytes(StandardCharsets.UTF_8));
        return webTestClient.post().uri(EVENT_PATH)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", contentSha256)
                .header("X-Internal-Signature", signature(
                        timestamp, requestId, "POST", EVENT_PATH, contentSha256))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec deliverRefund(String body) throws Exception {
        String path = "/internal/events/package-refunded";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        UUID requestId = UUID.randomUUID();
        String contentSha256 = sha256(body.getBytes(StandardCharsets.UTF_8));
        return webTestClient.post().uri(path)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", contentSha256)
                .header("X-Internal-Signature", signature(
                        timestamp, requestId, "POST", path, contentSha256))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /**
     * 视频套餐的授予事件。{@code usageDimension} 与模型的
     * 能力是分别传入的，这样测试既能构造出不匹配，也能构造出
     * 预期的形状；配额使用该维度自己的单位（VIDEO_SECOND 为秒）。
     */
    private String videoEventJson(
            String eventId, String businessNo, String usageDimension, String capability) {
        String grantRequestId = eventId.replaceFirst("^4", "5");
        long totalQuota = "VIDEO_SECOND".equals(usageDimension) ? 60L : 5_000_000L;
        return """
                {
                  "eventId": "%s",
                  "eventType": "PACKAGE_GRANTED_V1",
                  "businessKey": "%s",
                  "payload": {
                    "grantRequestId": "%s",
                    "businessNo": "%s",
                    "tenantId": "60000000-0000-4000-8000-000000000001",
                    "userId": "70000000-0000-4000-8000-000000000001",
                    "identityKey": "identity-hash-001",
                    "packageVersionId": "80000000-0000-4000-8000-000000000001",
                    "packageCode": "VIDEO_STARTER",
                    "packageName": "VIDEO_STARTER",
                    "packageKind": "STANDARD",
                    "priceAmount": 12900,
                    "currency": "CNY",
                    "totalTokenQuota": %d,
                    "durationDays": 30,
                    "maxApiKeys": 2,
                    "requestsPerMinute": 20,
                    "tokensPerMinute": 60,
                    "maxConcurrency": 2,
                    "dailyRequestLimit": null,
                    "maxOutputTokens": null,
                    "maxFileSizeBytes": 20971520,
                    "oncePerUser": false,
                    "usageDimension": "%s",
                    "models": [{
                      "modelCode": "video-basic",
                      "modelTokenQuota": 30,
                      "capabilities": ["%s"]
                    }]
                  }
                }
                """.formatted(eventId, businessNo, grantRequestId, businessNo, totalQuota,
                usageDimension, capability);
    }

    private String refundedEventJson(
            String eventId, String refundNo, String revocationBusinessNo, UUID grantRequestId) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "PACKAGE_REFUNDED_V1",
                  "businessKey": "%s",
                  "payload": {
                    "refundNo": "%s",
                    "revocationBusinessNo": "%s",
                    "grantRequestId": "%s",
                    "userId": "70000000-0000-4000-8000-000000000001"
                  }
                }
                """.formatted(
                eventId, revocationBusinessNo, refundNo,
                revocationBusinessNo, grantRequestId);
    }

    private String eventJson(String eventId, String businessNo, String packageCode) {
        return eventJson(
                eventId,
                businessNo,
                "70000000-0000-4000-8000-000000000001",
                "identity-hash-001",
                packageCode,
                false);
    }

    private String eventJson(
            String eventId,
            String businessNo,
            String userId,
            String identityKey,
            String packageCode,
            boolean oncePerUser) {
        return eventJson(eventId, businessNo, userId, identityKey, packageCode, oncePerUser, null);
    }

    private String eventJson(
            String eventId,
            String businessNo,
            String userId,
            String identityKey,
            String packageCode,
            boolean oncePerUser,
            Long modelTokenQuota) {
        String grantRequestId = eventId.replaceFirst("^4", "5");
        long priceAmount = "FREE".equals(packageCode) ? 0L : 1_900L;
        long totalTokenQuota = "FREE".equals(packageCode) ? 100_000L : 5_000_000L;
        long maxFileSize = "FREE".equals(packageCode) ? 5L * 1024L * 1024L : 20L * 1024L * 1024L;
        String optionalLimits = "FREE".equals(packageCode)
                ? "\"dailyRequestLimit\": 20, \"maxOutputTokens\": 2000,"
                : "\"dailyRequestLimit\": null, \"maxOutputTokens\": null,";
        String modelQuota = modelTokenQuota == null ? "" : "\"modelTokenQuota\": " + modelTokenQuota + ",";
        String packageKind = "FREE".equals(packageCode) ? "FREE_CLAIM" : "STANDARD";
        return """
                {
                  "eventId": "%s",
                  "eventType": "PACKAGE_GRANTED_V1",
                  "businessKey": "%s",
                  "payload": {
                    "grantRequestId": "%s",
                    "businessNo": "%s",
                    "tenantId": "60000000-0000-4000-8000-000000000001",
                    "userId": "%s",
                    "identityKey": "%s",
                    "packageVersionId": "80000000-0000-4000-8000-000000000001",
                    "packageCode": "%s",
                    "packageName": "%s",
                    "packageKind": "%s",
                    "priceAmount": %d,
                    "currency": "CNY",
                    "totalTokenQuota": %d,
                    "durationDays": 30,
                    "maxApiKeys": 3,
                    "requestsPerMinute": 20,
                    "tokensPerMinute": 100000,
                    "maxConcurrency": 3,
                    %s
                    "maxFileSizeBytes": %d,
                    "oncePerUser": %s,
                    "models": [{
                      "modelCode": "text-standard",
                      %s
                      "capabilities": ["TEXT"]
                    }]
                  }
                }
                """.formatted(
                eventId, businessNo, grantRequestId, businessNo, userId, identityKey,
                packageCode, packageCode, packageKind, priceAmount, totalTokenQuota, optionalLimits,
                maxFileSize, oncePerUser, modelQuota);
    }

    private String signature(
            String timestamp, UUID requestId, String method, String path, String contentSha256)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + requestId + "\n" + method + "\n" + path + "\n" + contentSha256)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String table) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private UUID uuid(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getObject(1, UUID.class);
        }
    }

    /** 一行中的一列，以文本形式返回：这些断言大多想要的就是这种形状。 */
    private String scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private List<String> statuses() throws Exception {
        List<String> statuses = new ArrayList<>();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT status
                        FROM gateway.package_instance
                        ORDER BY created_at, id
                        """)) {
            while (result.next()) {
                statuses.add(result.getString(1));
            }
        }
        return statuses;
    }

    private double counter(String name, String outcome) {
        return meterRegistry.counter(name, "outcome", outcome).count();
    }
}
