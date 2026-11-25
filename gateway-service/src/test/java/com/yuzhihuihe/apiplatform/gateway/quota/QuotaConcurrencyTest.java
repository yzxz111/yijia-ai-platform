package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;

import reactor.core.publisher.Flux;
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
class QuotaConcurrencyTest {

    private static final UUID TENANT_ID = UUID.fromString("62000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID API_KEY_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVE_PACKAGE_ID = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final String MODEL = "text-pro";

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
    }

    @BeforeEach
    void resetData() throws Exception {
        stubWalletClient();
        execute("TRUNCATE gateway.usage_log, gateway.quota_ledger, gateway.quota_reservation, "
                + "gateway.api_key, gateway.package_instance_model_quota, gateway.package_instance, "
                + "gateway.consumed_event CASCADE");
        seedPackage(ACTIVE_PACKAGE_ID, "ACTIVE", 10_000, 10_000, Instant.now().minusSeconds(60), 1);
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, expires_at)
                VALUES (
                    '82000000-0000-4000-8000-000000000001',
                    '62000000-0000-4000-8000-000000000001',
                    '72000000-0000-4000-8000-000000000001',
                    '92000000-0000-4000-8000-000000000001',
                    'Quota test', 'sk-yzh-test', 'quota-test-hash', '["text-pro"]',
                    'ACTIVE', now() + interval '30 days')
                """);
    }

    @Autowired
    QuotaService quotaService;

    @Autowired
    ReservationRecoveryJob recoveryJob;

    @Autowired
    QuotaReservationRepository reservationRepository;

    @Autowired
    PackageRefundService packageRefundService;

    @Autowired
    DatabaseClient databaseClient;

    @MockitoBean
    WalletUsageClient walletClient;

    @Test
    void concurrentReservationsUseWalletAfterPackageQuotaIsReserved() throws Exception {
        List<reactor.core.publisher.Signal<QuotaService.Reservation>> results = Flux.range(0, 20)
                .flatMap(index -> quotaService.reserve(command("concurrent-" + index, 1_000))
                        .materialize()
                        .subscribeOn(Schedulers.parallel()), 20)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(results).isNotNull();
        assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnNext)).hasSize(20);
        assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnError)).isEmpty();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isEqualTo(10_000);
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation")).isEqualTo(20);
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE billing_mode = 'WALLET_ONLY'"))
                .isEqualTo(10);
    }

    @Test
    void openRefundHoldBlocksNewReservationsWithSpecificReason() throws Exception {
        execute("""
                INSERT INTO gateway.package_refund_hold(
                    id, business_no, package_instance_id, grant_request_id,
                    original_status, status, payload_hash)
                VALUES (
                    'b1000000-0000-4000-8000-000000000001', 'refund-hold:quota',
                    '92000000-0000-4000-8000-000000000001',
                    '52000000-0000-4000-8000-000000000001',
                    'ACTIVE', 'WAITING', repeat('a', 64))
                """);

        Throwable failure = quotaService.reserve(command("refund-blocked", 1_000))
                .materialize().block(Duration.ofSeconds(10)).getThrowable();

        assertThat(failure).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_refund_in_progress");
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation")).isZero();
    }

    @Test
    void reservationCommittedBeforeHoldIsSettledIntoStableSnapshot() throws Exception {
        QuotaService.Reservation reservation = quotaService.reserve(
                        command("refund-before-hold", 1_000))
                .block(Duration.ofSeconds(10));
        UUID grantRequestId = UUID.fromString(text(
                "SELECT grant_request_id::text FROM gateway.package_instance WHERE id = '"
                        + ACTIVE_PACKAGE_ID + "'"));
        var command = new PackageRefundService.PrepareRefundCommand(
                "refund-hold:after-reservation", grantRequestId);

        var waiting = packageRefundService.prepare(command).block(Duration.ofSeconds(10));
        assertThat(waiting.status()).isEqualTo("WAITING");

        quotaService.settle(
                        reservation.id(), new QuotaService.TokenUsage(600, 400, 1_000),
                        QuotaService.UsageQuality.ACTUAL)
                .block(Duration.ofSeconds(10));
        var ready = packageRefundService.prepare(command).block(Duration.ofSeconds(10));

        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.usedTokens()).isEqualTo(1_000);
        assertThat(ready.remainingTokens()).isEqualTo(9_000);
    }

    @Test
    void concurrentRetryOfSameReservationIsIdempotent() throws Exception {
        QuotaService.ReserveCommand command = command("same-reservation", 1_000);

        var results = Mono.zip(
                        quotaService.reserve(command).materialize().subscribeOn(Schedulers.parallel()),
                        quotaService.reserve(command).materialize().subscribeOn(Schedulers.parallel()))
                .block(Duration.ofSeconds(20));

        assertThat(results).isNotNull();
        assertThat(results.getT1().isOnNext()).isTrue();
        assertThat(results.getT2().isOnNext()).isTrue();
        assertThat(results.getT1().get().id()).isEqualTo(results.getT2().get().id());
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation")).isEqualTo(1);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isEqualTo(1_000);
    }

    @Test
    void modelSubQuotaUsesWalletForUncoveredTokens() throws Exception {
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 2_000");
        quotaService.reserve(command("model-first", 1_500)).block(Duration.ofSeconds(10));

        QuotaService.Reservation second = quotaService.reserve(command("model-second", 600))
                .block(Duration.ofSeconds(10));

        assertThat(second).isNotNull();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance_model_quota"))
                .isEqualTo(2_000);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance"))
                .isEqualTo(2_000);
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE billing_mode = 'HYBRID'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentReservationsUseWalletAfterModelSubQuotaIsReserved() throws Exception {
        execute("UPDATE gateway.package_instance SET total_token_quota = 20_000");
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 10_000");

        List<reactor.core.publisher.Signal<QuotaService.Reservation>> results = Flux.range(0, 20)
                .flatMap(index -> quotaService.reserve(command("model-concurrent-" + index, 1_000))
                        .materialize()
                        .subscribeOn(Schedulers.parallel()), 20)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(results).isNotNull();
        assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnNext)).hasSize(20);
        assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnError)).isEmpty();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isEqualTo(10_000);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance_model_quota")).isEqualTo(10_000);
        assertThat(value("SELECT count(*) FROM gateway.quota_reservation WHERE billing_mode = 'WALLET_ONLY'"))
                .isEqualTo(10);
    }

    @Test
    void repeatedSettlementIsIdempotentAndReleasesDifference() throws Exception {
        QuotaService.Reservation reservation = quotaService.reserve(command("settle-once", 2_000))
                .block(Duration.ofSeconds(10));
        QuotaService.TokenUsage usage = new QuotaService.TokenUsage(700, 500, 1_200);

        QuotaService.Settlement first = quotaService.settle(
                reservation.id(), usage, QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));
        QuotaService.Settlement duplicate = quotaService.settle(
                reservation.id(), usage, QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));

        assertThat(duplicate).isEqualTo(first);
        assertThat(first.chargedTokens()).isEqualTo(1_200);
        assertThat(first.uncoveredTokens()).isZero();
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(1_200);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT used_tokens FROM gateway.package_instance_model_quota")).isEqualTo(1_200);
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger")).isEqualTo(2);
    }

    @Test
    void repeatedSettlementPreservesCappedModelCharge() throws Exception {
        execute("UPDATE gateway.package_instance SET total_token_quota = 3_000");
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 1_000");
        QuotaService.Reservation reservation = quotaService.reserve(command("settle-model-cap", 1_000))
                .block(Duration.ofSeconds(10));
        QuotaService.TokenUsage usage = new QuotaService.TokenUsage(1_200, 800, 2_000);

        QuotaService.Settlement first = quotaService.settle(
                reservation.id(), usage, QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));
        QuotaService.Settlement duplicate = quotaService.settle(
                reservation.id(), usage, QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));

        assertThat(first.chargedTokens()).isEqualTo(2_000);
        assertThat(first.modelChargedTokens()).isEqualTo(1_000);
        assertThat(duplicate).isEqualTo(first);
        assertThat(value("SELECT used_tokens FROM gateway.package_instance_model_quota")).isEqualTo(1_000);
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger")).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateSettlementCreatesOneTerminalLedgerEntry() throws Exception {
        QuotaService.Reservation reservation = quotaService.reserve(command("settle-concurrently", 2_000))
                .block(Duration.ofSeconds(10));
        QuotaService.TokenUsage usage = new QuotaService.TokenUsage(700, 500, 1_200);

        var results = Mono.zip(
                        quotaService.settle(reservation.id(), usage, QuotaService.UsageQuality.ACTUAL)
                                .subscribeOn(Schedulers.parallel()),
                        quotaService.settle(reservation.id(), usage, QuotaService.UsageQuality.ACTUAL)
                                .subscribeOn(Schedulers.parallel()))
                .block(Duration.ofSeconds(20));

        assertThat(results).isNotNull();
        assertThat(results.getT1()).isEqualTo(results.getT2());
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(1_200);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger WHERE entry_type = 'SETTLE'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentSettlementAndReleaseProduceExactlyOneTerminalOutcome() throws Exception {
        QuotaService.Reservation reservation = quotaService.reserve(command("settle-release-race", 1_000))
                .block(Duration.ofSeconds(10));

        var results = Flux.merge(
                        quotaService.settle(
                                        reservation.id(), new QuotaService.TokenUsage(600, 400, 1_000),
                                        QuotaService.UsageQuality.ACTUAL)
                                .materialize()
                                .subscribeOn(Schedulers.parallel()),
                        quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                                .materialize()
                                .subscribeOn(Schedulers.parallel()))
                .map(signal -> (reactor.core.publisher.Signal<?>) signal)
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(results).isNotNull();
        assertThat(results.stream().filter(signal -> !signal.isOnError())).hasSize(1);
        assertThat(results.stream().filter(reactor.core.publisher.Signal::isOnError)).hasSize(1)
                .allSatisfy(signal -> assertThat(signal.getThrowable())
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("reservation_not_"));
        assertThat(text("SELECT status FROM gateway.quota_reservation"))
                .isIn("SETTLED", "RELEASED");
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger WHERE entry_type <> 'RESERVE'"))
                .isEqualTo(1);
    }

    @Test
    void upstreamFailureReleaseIsIdempotent() throws Exception {
        QuotaService.Reservation reservation = quotaService.reserve(command("release-once", 1_000))
                .block(Duration.ofSeconds(10));

        quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .block(Duration.ofSeconds(10));
        quotaService.release(reservation.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .block(Duration.ofSeconds(10));

        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance_model_quota")).isZero();
        assertThat(text("SELECT status FROM gateway.quota_reservation")).isEqualTo("RELEASED");
        assertThat(value("SELECT count(*) FROM gateway.quota_ledger")).isEqualTo(2);
    }

    @Test
    void overReservationUsageConsumesRemainingWithoutNegativeBalance() throws Exception {
        execute("UPDATE gateway.package_instance SET total_token_quota = 2_000");
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 2_000");
        QuotaService.Reservation reservation = quotaService.reserve(command("over-reserve", 1_000))
                .block(Duration.ofSeconds(10));

        QuotaService.Settlement result = quotaService.settle(
                reservation.id(), new QuotaService.TokenUsage(1_500, 1_000, 2_500),
                QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));

        assertThat(result.chargedTokens()).isEqualTo(2_000);
        assertThat(result.uncoveredTokens()).isEqualTo(500);
        assertThat(value("SELECT used_tokens FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isEqualTo(2_000);
        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isEqualTo("EXHAUSTED");
        assertThat(value("SELECT uncovered_tokens FROM gateway.quota_reservation")).isEqualTo(500);
    }

    @Test
    void recoverySettlesKnownUsageAndExpiresUnknownUsage() throws Exception {
        QuotaService.Reservation known = quotaService.reserve(command("recover-known", 1_000))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation failed = quotaService.reserve(command("recover-failed", 500))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation unknown = quotaService.reserve(command("recover-unknown", 400))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");
        insertUsage(known, 600, true, "SUCCEEDED");
        insertUsage(failed, 0, false, "FAILED");

        int recovered = recoveryJob.recoverOnce().block(Duration.ofSeconds(20));

        assertThat(recovered).isEqualTo(2);
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '" + known.id() + "'"))
                .isEqualTo("SETTLED");
        assertThat(text("SELECT usage_quality FROM gateway.quota_reservation WHERE id = '" + known.id() + "'"))
                .isEqualTo("ESTIMATED");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '" + failed.id() + "'"))
                .isEqualTo("EXPIRED");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '" + unknown.id() + "'"))
                .isEqualTo("RESERVED");
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isEqualTo(400);
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(600);
    }

    @Test
    void recoveryBatchPrioritizesTerminalEvidenceOverOlderUnknownReservations() throws Exception {
        QuotaService.Reservation unknown = quotaService.reserve(command("recover-priority-unknown", 400))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation known = quotaService.reserve(command("recover-priority-known", 600))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '2 minutes' "
                + "WHERE id = '" + unknown.id() + "'");
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute' "
                + "WHERE id = '" + known.id() + "'");
        insertUsage(known, 600, false, "SUCCEEDED");
        ReservationRecoveryJob oneAtATime = new ReservationRecoveryJob(
                reservationRepository, quotaService, databaseClient, 1, "2h");

        int recovered = oneAtATime.recoverOnce().block(Duration.ofSeconds(20));

        assertThat(recovered).isEqualTo(1);
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '" + known.id() + "'"))
                .isEqualTo("SETTLED");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '" + unknown.id() + "'"))
                .isEqualTo("RESERVED");
    }

    @Test
    void recoveryEstimatesUsageAfterFirstByteAndForZeroTokenSuccess() throws Exception {
        QuotaService.Reservation failedAfterFirstByte = quotaService.reserve(
                        command("recover-failed-after-first-byte", 700))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation cancelledAfterFirstByte = quotaService.reserve(
                        command("recover-cancelled-after-first-byte", 600))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation succeededWithoutUsage = quotaService.reserve(
                        command("recover-succeeded-without-usage", 500))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");
        insertUsage(failedAfterFirstByte, 0, 0, false, "FAILED", 25L);
        insertUsage(cancelledAfterFirstByte, 0, 0, false, "CANCELLED", 30L);
        insertUsage(succeededWithoutUsage, 0, 0, false, "SUCCEEDED", null);

        int recovered = recoveryJob.recoverOnce().block(Duration.ofSeconds(20));

        assertEstimatedSettlement(failedAfterFirstByte, 700);
        assertEstimatedSettlement(cancelledAfterFirstByte, 600);
        assertEstimatedSettlement(succeededWithoutUsage, 500);
        assertThat(recovered).isEqualTo(3);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(1_800);
    }

    @Test
    void recoveryReleasesPreFirstByteFailureEvenWhenUsageWasRecorded() throws Exception {
        QuotaService.Reservation failedBeforeFirstByte = quotaService.reserve(
                        command("recover-failed-before-first-byte", 700))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");
        insertUsage(failedBeforeFirstByte, 400, 600, false, "FAILED", null);

        int recovered = recoveryJob.recoverOnce().block(Duration.ofSeconds(20));

        assertThat(recovered).isEqualTo(1);
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + failedBeforeFirstByte.id() + "'")).isEqualTo("EXPIRED");
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isZero();
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
    }

    @Test
    void recoveryNormalizesIncompletePostFirstByteTokenBreakdown() throws Exception {
        QuotaService.Reservation failedAfterFirstByte = quotaService.reserve(
                        command("recover-incomplete-after-first-byte", 500))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");
        insertUsage(failedAfterFirstByte, 600, 0, false, "FAILED", 25L);

        int recovered = recoveryJob.recoverOnce().block(Duration.ofSeconds(20));

        assertThat(recovered).isEqualTo(1);
        assertEstimatedSettlement(failedAfterFirstByte, 600);
        assertThat(value("SELECT actual_input_tokens FROM gateway.quota_reservation WHERE id = '"
                + failedAfterFirstByte.id() + "'")).isEqualTo(600);
    }

    @Test
    void recoveryNormalizesOverflowingPostFirstByteTokenBreakdown() throws Exception {
        QuotaService.Reservation failedAfterFirstByte = quotaService.reserve(
                        command("recover-overflow-after-first-byte", 500))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");
        insertUsage(failedAfterFirstByte, Long.MAX_VALUE, 1, 0, false, "FAILED", 25L);

        int recovered = recoveryJob.recoverOnce().block(Duration.ofSeconds(20));

        assertThat(recovered).isEqualTo(1);
        assertEstimatedSettlement(failedAfterFirstByte, 500);
        assertThat(value("SELECT actual_input_tokens FROM gateway.quota_reservation WHERE id = '"
                + failedAfterFirstByte.id() + "'")).isZero();
        assertThat(value("SELECT actual_output_tokens FROM gateway.quota_reservation WHERE id = '"
                + failedAfterFirstByte.id() + "'")).isZero();
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(500);
        assertThat(text("SELECT status FROM gateway.package_instance")).isEqualTo("ACTIVE");
    }

    @Test
    void exhaustedPackageActivatesOldestQueuedPackage() throws Exception {
        execute("UPDATE gateway.package_instance SET total_token_quota = 1_000");
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 1_000");
        UUID queued = UUID.fromString("92000000-0000-4000-8000-000000000002");
        seedPackage(queued, "QUEUED", 5_000, 1_000, null, 2);
        QuotaService.Reservation reservation = quotaService.reserve(command("switch-package", 1_000))
                .block(Duration.ofSeconds(10));

        quotaService.settle(reservation.id(), new QuotaService.TokenUsage(600, 400, 1_000),
                QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));

        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isEqualTo("EXHAUSTED");
        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '" + queued + "'"))
                .isEqualTo("ACTIVE");
        assertThat(value("SELECT count(*) FROM gateway.package_instance_model_quota "
                + "WHERE package_instance_id = '" + queued + "'")).isEqualTo(1);
    }

    @Test
    void fullyReservedCapacityDoesNotExhaustPackageBeforeReservationsSettle() throws Exception {
        execute("UPDATE gateway.package_instance SET total_token_quota = 2_000");
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 2_000");
        UUID queued = UUID.fromString("92000000-0000-4000-8000-000000000003");
        seedPackage(queued, "QUEUED", 5_000, 1_000, null, 3);
        QuotaService.Reservation consumed = quotaService.reserve(command("reserved-consumed", 1_000))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation released = quotaService.reserve(command("reserved-released", 1_000))
                .block(Duration.ofSeconds(10));

        quotaService.settle(consumed.id(), new QuotaService.TokenUsage(600, 400, 1_000),
                QuotaService.UsageQuality.ACTUAL).block(Duration.ofSeconds(10));
        quotaService.release(released.id(), QuotaService.ReleaseReason.UPSTREAM_FAILURE)
                .block(Duration.ofSeconds(10));

        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isEqualTo("ACTIVE");
        assertThat(text("SELECT status FROM gateway.package_instance WHERE id = '" + queued + "'"))
                .isEqualTo("QUEUED");
        assertThat(value("SELECT used_tokens FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isEqualTo(1_000);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance WHERE id = '"
                + ACTIVE_PACKAGE_ID + "'")).isZero();
    }

    @Test
    void malformedTerminalRecoveryEvidenceSettlesWithinTheAuthorizedReservation() throws Exception {
        QuotaService.Reservation malformed = quotaService.reserve(command("recover-malformed", 500))
                .block(Duration.ofSeconds(10));
        QuotaService.Reservation recoverable = quotaService.reserve(command("recover-after-malformed", 500))
                .block(Duration.ofSeconds(10));
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute'");
        insertUsage(malformed, 10, 5, false, "SUCCEEDED");
        insertUsage(recoverable, 0, 0, false, "FAILED");

        int recovered = recoveryJob.recoverOnce().block(Duration.ofSeconds(20));

        assertThat(recovered).isEqualTo(2);
        assertEstimatedSettlement(malformed, 500);
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + recoverable.id() + "'")).isEqualTo("EXPIRED");
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isEqualTo(500);
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isZero();
    }

    @Test
    void settlementTakesUserAdvisoryLockBeforePackageRowLock() throws Exception {
        execute("UPDATE gateway.package_instance SET total_token_quota = 1_000");
        execute("UPDATE gateway.package_instance_model_quota SET token_quota = 1_000");
        QuotaService.Reservation reservation = quotaService.reserve(command("lock-order", 1_000))
                .block(Duration.ofSeconds(10));

        try (var advisoryHolder = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            advisoryHolder.setAutoCommit(false);
            try (var statement = advisoryHolder.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('" + USER_ID + "', 0))");
            }
            var settlement = quotaService.settle(
                            reservation.id(), new QuotaService.TokenUsage(600, 400, 1_000),
                            QuotaService.UsageQuality.ACTUAL)
                    .subscribeOn(Schedulers.parallel())
                    .toFuture();
            try {
                awaitAdvisoryWaiter();
                assertPackageRowLockAvailable();
            } finally {
                advisoryHolder.rollback();
            }
            assertThat(settlement.get(10, TimeUnit.SECONDS).status()).isEqualTo("SETTLED");
        }
    }

    @Test
    void newReservationTakesIdempotencyLockBeforePackageRowLock() throws Exception {
        QuotaService.ReserveCommand command = command("reservation-lock-order", 1_000);

        try (var advisoryHolder = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            advisoryHolder.setAutoCommit(false);
            try (var statement = advisoryHolder.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('quota-reservation:' || ?, 0))")) {
                statement.setString(1, command.reservationId().toString());
                statement.execute();
            }
            var reservation = quotaService.reserve(command)
                    .subscribeOn(Schedulers.parallel())
                    .toFuture();
            try {
                awaitAdvisoryWaiter();
                assertPackageRowLockAvailable();
                assertThat(reservation).isNotDone();
            } finally {
                advisoryHolder.rollback();
            }
            assertThat(reservation.get(10, TimeUnit.SECONDS).status()).isEqualTo("RESERVED");
        }
    }

    @Test
    void rejectsOverflowingUsageBreakdownWithoutChangingReservation() throws Exception {
        QuotaService.Reservation reservation = quotaService.reserve(command("overflow-usage", 1_000))
                .block(Duration.ofSeconds(10));

        var signal = quotaService.settle(
                        reservation.id(),
                        new QuotaService.TokenUsage(Long.MAX_VALUE, 1, Long.MAX_VALUE),
                        QuotaService.UsageQuality.ACTUAL)
                .materialize().block(Duration.ofSeconds(10));

        assertThat(signal.isOnError()).isTrue();
        assertThat(signal.getThrowable()).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_settlement_request");
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("RESERVED");
        assertThat(value("SELECT reserved_tokens FROM gateway.package_instance")).isEqualTo(1_000);
        assertThat(value("SELECT used_tokens FROM gateway.package_instance")).isZero();
    }

    private QuotaService.ReserveCommand command(String requestId, long tokens) {
        return new QuotaService.ReserveCommand(
                UUID.randomUUID(), requestId, TENANT_ID, USER_ID, API_KEY_ID,
                MODEL, tokens, Instant.now().plusSeconds(60));
    }

    private void stubWalletClient() {
        when(walletClient.reserve(any(WalletUsageClient.WalletRequest.class)))
                .thenAnswer(invocation -> {
                    WalletUsageClient.WalletRequest request = invocation.getArgument(0);
                    return Mono.just(new WalletUsageClient.WalletResponse(
                            request.reservationId(), request.requestId(), request.estimatedAmount(),
                            null, "RESERVED", request.modelCode(), request.priceVersion()));
                });
        when(walletClient.settle(any(UUID.class), any(WalletUsageClient.WalletSettlement.class)))
                .thenAnswer(invocation -> {
                    UUID reservationId = invocation.getArgument(0);
                    WalletUsageClient.WalletSettlement settlement = invocation.getArgument(1);
                    return Mono.just(new WalletUsageClient.WalletResponse(
                            reservationId, null, 0, settlement.settledAmount(),
                            "SETTLED", null, null));
                });
        when(walletClient.release(any(UUID.class)))
                .thenAnswer(invocation -> Mono.just(new WalletUsageClient.WalletResponse(
                        invocation.getArgument(0), null, 0, null, "RELEASED", null, null)));
    }

    private void seedPackage(
            UUID id, String status, long totalQuota, long modelQuota,
            Instant activatedAt, int order) throws Exception {
        String starts = activatedAt == null ? "NULL" : "now() - interval '1 minute'";
        String expires = activatedAt == null ? "NULL" : "now() + interval '30 days'";
        String activated = activatedAt == null ? "NULL" : "now() - interval '1 minute'";
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    identity_key, package_plan_version_id, package_code, package_name,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, max_file_size_bytes, once_per_user,
                    status, starts_at, expires_at, activated_at, created_at)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s',
                    'quota-identity', '%s', 'ADVANCED', 'Advanced',
                    5900, 'CNY', '[{"modelCode":"text-pro","modelTokenQuota":%d,"capabilities":["TEXT"]}]', %d,
                    3, 20, 100000, 3, 30, 20971520, false,
                    '%s', %s, %s, %s, now() + interval '%d second')
                """.formatted(
                id, UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, USER_ID, UUID.randomUUID(),
                modelQuota, totalQuota, status, starts, expires, activated, order));
        if ("ACTIVE".equals(status)) {
            execute("""
                    INSERT INTO gateway.package_instance_model_quota(
                        id, package_instance_id, model_code, token_quota)
                    VALUES ('%s', '%s', '%s', %d)
                    """.formatted(UUID.randomUUID(), id, MODEL, modelQuota));
        }
    }

    private void insertUsage(
            QuotaService.Reservation reservation,
            long totalTokens,
            boolean estimated,
            String outcome)
            throws Exception {
        insertUsage(reservation, totalTokens, totalTokens, estimated, outcome);
    }

    private void insertUsage(
            QuotaService.Reservation reservation,
            long inputTokens,
            long totalTokens,
            boolean estimated,
            String outcome) throws Exception {
        insertUsage(reservation, inputTokens, totalTokens, estimated, outcome, null);
    }

    private void insertUsage(
            QuotaService.Reservation reservation,
            long inputTokens,
            long totalTokens,
            boolean estimated,
            String outcome,
            Long firstTokenMs) throws Exception {
        insertUsage(reservation, inputTokens, 0, totalTokens, estimated, outcome, firstTokenMs);
    }

    private void insertUsage(
            QuotaService.Reservation reservation,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            boolean estimated,
            String outcome,
            Long firstTokenMs) throws Exception {
        String firstToken = firstTokenMs == null ? "NULL" : firstTokenMs.toString();
        execute("""
                INSERT INTO gateway.usage_log(
                    id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                    package_instance_id, model_code, input_tokens, output_tokens,
                    total_tokens, usage_estimated, first_token_ms, duration_ms, http_status, outcome)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    %d, %d, %d, %s, %s, 100, 200, '%s')
                """.formatted(
                UUID.randomUUID(), reservation.requestId(), reservation.id(), TENANT_ID, USER_ID,
                API_KEY_ID, reservation.packageInstanceId(), MODEL,
                inputTokens, outputTokens, totalTokens, estimated, firstToken, outcome));
    }

    private void assertEstimatedSettlement(QuotaService.Reservation reservation, long expectedTokens)
            throws Exception {
        assertThat(text("SELECT status FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("SETTLED");
        assertThat(text("SELECT usage_quality FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo("ESTIMATED");
        assertThat(value("SELECT actual_total_tokens FROM gateway.quota_reservation WHERE id = '"
                + reservation.id() + "'")).isEqualTo(expectedTokens);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long value(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void awaitAdvisoryWaiter() throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (value("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("settlement did not wait for the user advisory lock");
    }

    private static void assertPackageRowLockAvailable() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT id FROM gateway.package_instance WHERE id = '"
                        + ACTIVE_PACKAGE_ID + "' FOR UPDATE NOWAIT");
            } finally {
                connection.rollback();
            }
        }
    }
}
