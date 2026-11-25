package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
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
                "security.internal.hmac-secret=test-internal-secret",
                "quota.recovery.initial-delay=1h"
        })
class PackageRefundServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVE_PACKAGE_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVE_GRANT_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID QUEUED_PACKAGE_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID QUEUED_GRANT_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

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
    }

    @BeforeEach
    void reset() throws Exception {
        execute("TRUNCATE gateway.package_refund_hold, gateway.package_lifecycle_event, "
                + "gateway.quota_ledger, gateway.quota_reservation, gateway.api_key, "
                + "gateway.package_instance_model_quota, gateway.package_instance, "
                + "gateway.consumed_event CASCADE");
        seedPackage(ACTIVE_PACKAGE_ID, ACTIVE_GRANT_ID, "ACTIVE", 10_000, 250);
        seedPackage(QUEUED_PACKAGE_ID, QUEUED_GRANT_ID, "QUEUED", 5_000, 0);
    }

    @Autowired
    PackageRefundService service;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void queuedAndIdleActivePackagesProduceStableReadySnapshots() {
        var queued = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:queued", QUEUED_GRANT_ID))
                .block(Duration.ofSeconds(10));
        var active = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:active", ACTIVE_GRANT_ID))
                .block(Duration.ofSeconds(10));

        assertThat(queued).isNotNull();
        assertThat(queued.status()).isEqualTo("READY");
        assertThat(queued.originalStatus()).isEqualTo("QUEUED");
        assertThat(queued.usedTokens()).isZero();
        assertThat(queued.remainingTokens()).isEqualTo(5_000L);
        assertThat(active).isNotNull();
        assertThat(active.status()).isEqualTo("READY");
        assertThat(active.originalStatus()).isEqualTo("ACTIVE");
        assertThat(active.usedTokens()).isEqualTo(250L);
        assertThat(active.remainingTokens()).isEqualTo(9_750L);
        assertThat(active.snapshotAt()).isNotNull();
    }

    @Test
    void revocationUsesTheReadyHoldForTheGrantWhenBusinessNumbersDiffer() throws Exception {
        String holdBusinessNo = "refund-hold:provider-success";
        String revocationBusinessNo = "refund-revoke:provider-success";
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at,
                    managed, secret_ciphertext, secret_key_version)
                VALUES
                    ('82000000-0000-4000-8000-000000000001',
                     '61000000-0000-4000-8000-000000000001',
                     '71000000-0000-4000-8000-000000000001',
                     '91000000-0000-4000-8000-000000000001',
                     'Refund managed', 'sk-refund-managed', 'refund-managed-hash',
                     '[]', 'ACTIVE', now() + interval '30 days',
                     true, decode(repeat('01', 29), 'hex'), 1),
                    ('82000000-0000-4000-8000-000000000002',
                     '61000000-0000-4000-8000-000000000001',
                     '71000000-0000-4000-8000-000000000001',
                     '91000000-0000-4000-8000-000000000002',
                     'Queued managed', 'sk-queued-managed', 'queued-managed-hash',
                     '[]', 'ACTIVE', now() + interval '30 days',
                     true, decode(repeat('02', 29), 'hex'), 1)
                """);
        service.prepare(new PackageRefundService.PrepareRefundCommand(
                        holdBusinessNo, ACTIVE_GRANT_ID))
                .block(Duration.ofSeconds(10));
        var event = new PackageRefundService.PackageRefundedEvent(
                UUID.randomUUID(), "PACKAGE_REFUNDED_V1", revocationBusinessNo,
                new PackageRefundService.PackageRefundedPayload(
                        "ref_provider_success", revocationBusinessNo,
                        ACTIVE_GRANT_ID, USER_ID));

        var revoked = service.revoke(event).block(Duration.ofSeconds(10));
        var replay = service.revoke(event).block(Duration.ofSeconds(10));

        assertThat(revoked).isNotNull();
        assertThat(revoked.packageInstanceId()).isEqualTo(ACTIVE_PACKAGE_ID);
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.activatedPackageInstanceId()).isEqualTo(QUEUED_PACKAGE_ID);
        assertThat(revoked.activatedPackageStatus()).isEqualTo("ACTIVE");
        assertThat(replay).isEqualTo(revoked);
        assertThat(count("gateway.package_refund_hold WHERE business_no = '"
                + holdBusinessNo + "' AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("gateway.package_lifecycle_event WHERE business_no = '"
                + revocationBusinessNo + "' AND event_type = 'REFUND_REVOKE'")).isEqualTo(1);
        assertThat(count("gateway.api_key WHERE id = "
                + "'82000000-0000-4000-8000-000000000001' AND status = 'REVOKED'"))
                .isEqualTo(1);
        assertThat(count("gateway.api_key WHERE id = "
                + "'82000000-0000-4000-8000-000000000002' AND status = 'ACTIVE' "
                + "AND package_instance_id = '91000000-0000-4000-8000-000000000002'"))
                .isEqualTo(1);
    }

    @Test
    void waitingSnapshotBecomesReadyOnlyAfterExistingReservationSettles() throws Exception {
        seedReservation();

        var waiting = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:waiting", ACTIVE_GRANT_ID))
                .block(Duration.ofSeconds(10));
        assertThat(waiting).isNotNull();
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat((Object) waiting.totalTokens()).isNull();
        assertThat((Object) waiting.usedTokens()).isNull();
        assertThat((Object) waiting.remainingTokens()).isNull();
        assertThat(waiting.snapshotAt()).isNull();

        execute("DELETE FROM gateway.quota_reservation");
        execute("UPDATE gateway.package_instance SET reserved_tokens = 0, used_tokens = 1250 "
                + "WHERE id = '" + ACTIVE_PACKAGE_ID + "'");

        var ready = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:waiting", ACTIVE_GRANT_ID))
                .block(Duration.ofSeconds(10));
        assertThat(ready).isNotNull();
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.usedTokens()).isEqualTo(1_250L);
        assertThat(ready.remainingTokens()).isEqualTo(8_750L);
    }

    @Test
    void replayIsStableChangedPayloadConflictsAndReleaseIsIdempotent() {
        var command = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:replay", ACTIVE_GRANT_ID);
        var first = service.prepare(command).block(Duration.ofSeconds(10));
        var replay = service.prepare(command).block(Duration.ofSeconds(10));
        Throwable mismatch = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:replay", QUEUED_GRANT_ID))
                .materialize().block(Duration.ofSeconds(10)).getThrowable();

        assertThat(replay).isEqualTo(first);
        assertThat(mismatch).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_hold_payload_mismatch");

        var release = new PackageRefundService.ReleaseRefundCommand(
                "refund-hold:replay", ACTIVE_GRANT_ID);
        double releasedBefore = counter("gateway.refund.release", "RELEASED");
        double replayBefore = counter("gateway.refund.release", "REPLAY");
        service.release(release).block(Duration.ofSeconds(10));
        service.release(release).block(Duration.ofSeconds(10));
        assertThat(count("gateway.package_refund_hold WHERE status = 'RELEASED'"))
                .isEqualTo(1);
        assertThat(counter("gateway.refund.release", "RELEASED") - releasedBefore)
                .isEqualTo(1);
        assertThat(counter("gateway.refund.release", "REPLAY") - replayBefore)
                .isEqualTo(1);
        Throwable releaseMismatch = service.release(
                        new PackageRefundService.ReleaseRefundCommand(
                                release.businessNo(), QUEUED_GRANT_ID))
                .materialize().block(Duration.ofSeconds(10)).getThrowable();
        assertThat(releaseMismatch).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_hold_payload_mismatch");
    }

    @Test
    void existingBusinessNumberRejectsChangedUnresolvableGrantAsPayloadMismatch() {
        var command = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:missing-grant-mismatch", ACTIVE_GRANT_ID);
        service.prepare(command).block(Duration.ofSeconds(10));

        Throwable mismatch = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        command.businessNo(),
                        UUID.fromString("51000000-0000-4000-8000-000000000099")))
                .materialize().block(Duration.ofSeconds(10)).getThrowable();

        assertThat(mismatch).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_hold_payload_mismatch");
    }

    @Test
    void expiredActivePackageAndWaitingHoldAreNotPromotedToReady() throws Exception {
        execute("UPDATE gateway.package_instance SET expires_at = now() - interval '1 second' "
                + "WHERE id = '" + ACTIVE_PACKAGE_ID + "'");
        Throwable expired = service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:expired-active", ACTIVE_GRANT_ID))
                .materialize().block(Duration.ofSeconds(10)).getThrowable();
        assertThat(expired).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_not_refundable");

        execute("UPDATE gateway.package_instance SET expires_at = now() + interval '1 day' "
                + "WHERE id = '" + ACTIVE_PACKAGE_ID + "'");
        seedReservation();
        var command = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:expires-while-waiting", ACTIVE_GRANT_ID);
        assertThat(service.prepare(command).block(Duration.ofSeconds(10)).status())
                .isEqualTo("WAITING");
        execute("DELETE FROM gateway.quota_reservation");
        execute("UPDATE gateway.package_instance SET reserved_tokens = 0, "
                + "expires_at = now() - interval '1 second' WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'");

        Throwable waitingExpired = service.prepare(command)
                .materialize().block(Duration.ofSeconds(10)).getThrowable();
        assertThat(waitingExpired).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_not_refundable");
    }

    @Test
    void releasedHoldIsNotReturnedAsPrepareSnapshotAndReplayMetricsAreExclusive() {
        var prepare = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:terminal-replay", ACTIVE_GRANT_ID);
        service.prepare(prepare).block(Duration.ofSeconds(10));
        double releasedBefore = counter("gateway.refund.release", "RELEASED");
        double replayBefore = counter("gateway.refund.release", "REPLAY");
        var release = new PackageRefundService.ReleaseRefundCommand(
                prepare.businessNo(), prepare.grantRequestId());

        service.release(release).block(Duration.ofSeconds(10));
        service.release(release).block(Duration.ofSeconds(10));

        assertThat(counter("gateway.refund.release", "RELEASED") - releasedBefore).isEqualTo(1);
        assertThat(counter("gateway.refund.release", "REPLAY") - replayBefore).isEqualTo(1);
        Throwable terminalReplay = service.prepare(prepare)
                .materialize().block(Duration.ofSeconds(10)).getThrowable();
        assertThat(terminalReplay).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_hold_not_open");
    }

    @Test
    void invalidCommandsRecordOneRejectedOutcomePerInvocation() {
        double prepareBefore = counter("gateway.refund.prepare", "REJECTED");
        double releaseBefore = counter("gateway.refund.release", "REJECTED");
        double revokeBefore = counter("gateway.refund.revoke", "REJECTED");

        assertThatThrownBy(() -> service.prepare(null).block(Duration.ofSeconds(10)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_prepare_request");
        assertThatThrownBy(() -> service.release(null).block(Duration.ofSeconds(10)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_release_request");
        assertThatThrownBy(() -> service.revoke(new PackageRefundService.PackageRefundedEvent(
                        UUID.randomUUID(), "PACKAGE_GRANTED_V1", "refund-revoke:invalid", null))
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_package_refunded_event");

        assertThat(counter("gateway.refund.prepare", "REJECTED") - prepareBefore).isEqualTo(1);
        assertThat(counter("gateway.refund.release", "REJECTED") - releaseBefore).isEqualTo(1);
        assertThat(counter("gateway.refund.revoke", "REJECTED") - revokeBefore).isEqualTo(1);
    }

    @Test
    void terminalPackageIsRejectedAndConcurrentPrepareCreatesOneHold() throws Exception {
        for (String status : List.of("EXPIRED", "EXHAUSTED", "REVOKED")) {
            execute("UPDATE gateway.package_instance SET status = '" + status
                    + "', ended_at = now() WHERE id = '" + ACTIVE_PACKAGE_ID + "'");
            Throwable terminal = service.prepare(new PackageRefundService.PrepareRefundCommand(
                            "refund-hold:terminal:" + status.toLowerCase(), ACTIVE_GRANT_ID))
                    .materialize().block(Duration.ofSeconds(10)).getThrowable();
            assertThat(terminal).as(status)
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("package_not_refundable");
            execute("UPDATE gateway.package_instance SET status = 'ACTIVE', ended_at = NULL "
                    + "WHERE id = '" + ACTIVE_PACKAGE_ID + "'");
        }

        var command = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:concurrent", QUEUED_GRANT_ID);
        var results = Mono.zip(
                        service.prepare(command).subscribeOn(Schedulers.parallel()),
                        service.prepare(command).subscribeOn(Schedulers.parallel()))
                .block(Duration.ofSeconds(20));
        assertThat(results).isNotNull();
        assertThat(results.getT1()).isEqualTo(results.getT2());
        assertThat(count("gateway.package_refund_hold WHERE business_no = 'refund-hold:concurrent'"))
                .isEqualTo(1);
    }

    @Test
    void metricsUseOnlyBoundedOutcomeTags() {
        service.prepare(new PackageRefundService.PrepareRefundCommand(
                        "refund-hold:metrics", ACTIVE_GRANT_ID))
                .block(Duration.ofSeconds(10));
        service.release(new PackageRefundService.ReleaseRefundCommand(
                        "refund-hold:metrics", ACTIVE_GRANT_ID))
                .block(Duration.ofSeconds(10));

        assertThat(meterRegistry.get("gateway.refund.prepare")
                .tag("outcome", "READY").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("gateway.refund.release")
                .tag("outcome", "RELEASED").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("gateway.refund."))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue()))
                .noneMatch(tag -> tag.contains("refund-hold:metrics")
                        || tag.contains(USER_ID.toString()));
    }

    private void seedReservation() throws Exception {
        execute("UPDATE gateway.package_instance SET reserved_tokens = 1000 WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'");
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '81000000-0000-4000-8000-000000000001',
                    '61000000-0000-4000-8000-000000000001',
                    '71000000-0000-4000-8000-000000000001',
                    '91000000-0000-4000-8000-000000000001',
                    'Refund test', 'sk-refund-test', 'refund-test-hash', '["text-standard"]',
                    'ACTIVE', now() + interval '30 days');
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id,
                    package_instance_id, model_code, reserved_tokens,
                    package_reserved_tokens, expires_at)
                VALUES (
                    'a1000000-0000-4000-8000-000000000001', 'refund-reservation',
                    '61000000-0000-4000-8000-000000000001',
                    '71000000-0000-4000-8000-000000000001',
                    '81000000-0000-4000-8000-000000000001',
                    '91000000-0000-4000-8000-000000000001',
                    'text-standard', 1000, 1000, now() + interval '5 minutes');
                """);
    }

    private void seedPackage(
            UUID packageId, UUID grantRequestId, String status, long total, long used)
            throws Exception {
        String dates = "QUEUED".equals(status)
                ? "NULL, NULL, NULL"
                : "now() - interval '1 day', now() + interval '29 days', now() - interval '1 day'";
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    package_plan_version_id, package_code, package_name, price_amount,
                    currency, allowed_models, total_token_quota, reserved_tokens, used_tokens,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, expires_at, activated_at,
                    identity_key, max_file_size_bytes)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s',
                    '80000000-0000-4000-8000-000000000001', 'PAID', 'Paid', 1900,
                    'CNY', '[]', %d, 0, %d, 3, 60, 100000, 3, 30,
                    '%s', %s, 'identity-refund', 20971520)
                """.formatted(
                packageId, UUID.randomUUID(), grantRequestId, TENANT_ID, USER_ID,
                total, used, status, dates));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            for (String item : List.of(sql.split(";"))) {
                if (!item.isBlank()) {
                    statement.execute(item);
                }
            }
        }
    }

    private static long count(String from) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + from)) {
            result.next();
            return result.getLong(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private double counter(String name, String outcome) {
        return meterRegistry.counter(name, "outcome", outcome).count();
    }

}
