package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
@Import(RechargeOrderServiceTest.FixedClockConfiguration.class)
class RechargeOrderServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_TENANT_USER_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000005");
    private static final UUID OPTION_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000006");
    private static final UUID DISABLED_OPTION_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000007");
    private static final UUID OTHER_TENANT_OPTION_ID =
            UUID.fromString("84000000-0000-4000-8000-000000000008");
    private static final Instant FIXED_NOW = Instant.parse("2032-05-06T07:08:09Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RechargeOrderService service;

    @Autowired
    RechargeOrderExpiryJob expiryJob;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetData() {
        jdbc.update("TRUNCATE platform.app_user RESTART IDENTITY CASCADE");
        insertUser(TENANT_ID, USER_ID, "recharge.owner@example.test");
        insertUser(TENANT_ID, OTHER_USER_ID, "recharge.other@example.test");
        insertUser(OTHER_TENANT_ID, OTHER_TENANT_USER_ID, "recharge.tenant@example.test");
        insertOption(OPTION_ID, TENANT_ID, 1_000, true, 10);
        insertOption(DISABLED_OPTION_ID, TENANT_ID, 3_000, false, 20);
        insertOption(OTHER_TENANT_OPTION_ID, OTHER_TENANT_ID, 5_000, true, 10);
    }

    @Test
    void acceptsInclusiveCnyAmountBoundaries() {
        RechargeOrderService.RechargeOrderView minimum = create(
                USER_ID, "minimum", 100, null).order();
        RechargeOrderService.RechargeOrderView maximum = create(
                USER_ID, "maximum", 5_000_000, null).order();

        assertThat(minimum.paidAmount()).isEqualTo(100);
        assertThat(maximum.paidAmount()).isEqualTo(5_000_000);
        assertThat(minimum.currency()).isEqualTo("CNY");
    }

    @Test
    void creationAndReplayEmitOnlyBoundedOutcomeMetrics() {
        double createdBefore = counter(
                "platform.wallet.recharge.created", "outcome", "CREATED");
        double replayBefore = counter(
                "platform.wallet.recharge.created", "outcome", "REPLAY");

        create(USER_ID, "metric-create", 1_000, null);
        create(USER_ID, "metric-create", 1_000, null);

        assertThat(counter("platform.wallet.recharge.created", "outcome", "CREATED"))
                .isEqualTo(createdBefore + 1);
        assertThat(counter("platform.wallet.recharge.created", "outcome", "REPLAY"))
                .isEqualTo(replayBefore + 1);
    }

    @Test
    void createdMetricIsEmittedOnlyAfterTheOuterTransactionCommits() {
        double createdBefore = counter(
                "platform.wallet.recharge.created", "outcome", "CREATED");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            create(USER_ID, "metric-rollback", 1_000, null);
            assertThat(counter("platform.wallet.recharge.created", "outcome", "CREATED"))
                    .isEqualTo(createdBefore);
            status.setRollbackOnly();
        });

        assertThat(counter("platform.wallet.recharge.created", "outcome", "CREATED"))
                .isEqualTo(createdBefore);

        transactions.executeWithoutResult(status -> {
            create(USER_ID, "metric-commit", 1_000, null);
            assertThat(counter("platform.wallet.recharge.created", "outcome", "CREATED"))
                    .isEqualTo(createdBefore);
        });

        assertThat(counter("platform.wallet.recharge.created", "outcome", "CREATED"))
                .isEqualTo(createdBefore + 1);
    }

    @Test
    void rejectsAmountsOutsideInclusiveRangeWithoutInsertion() {
        assertFailure(
                () -> create(USER_ID, "below", 99, null),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_recharge_amount");
        assertFailure(
                () -> create(USER_ID, "above", 5_000_001, null),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_recharge_amount");

        assertThat(orderCount()).isZero();
    }

    @Test
    void acceptsAnEnabledTenantOptionOnlyWhenTheAmountMatches() {
        RechargeOrderService.RechargeOrderView result = create(
                USER_ID, "fixed-option", 1_000, OPTION_ID).order();

        assertThat(result.optionId()).isEqualTo(OPTION_ID);
        assertThat(result.paidAmount()).isEqualTo(1_000);
    }

    @Test
    void rejectsUnavailableOptionsBeforeInsertion() {
        assertUnavailable("disabled", 3_000, DISABLED_OPTION_ID);
        assertUnavailable("missing", 1_000, UUID.randomUUID());
        assertUnavailable("cross-tenant", 5_000, OTHER_TENANT_OPTION_ID);
        assertUnavailable("mismatch", 1_001, OPTION_ID);

        assertThat(orderCount()).isZero();
    }

    @Test
    void allowsCustomAmountsIndependentlyOfFixedOptions() {
        jdbc.update("UPDATE platform.recharge_option SET enabled = false WHERE tenant_id = ?", TENANT_ID);

        RechargeOrderService.RechargeOrderView result = create(
                USER_ID, "custom", 4_321, null).order();

        assertThat(result.optionId()).isNull();
        assertThat(result.paidAmount()).isEqualTo(4_321);
    }

    @Test
    void requiresANonblankIdempotencyKeyOfAtMost128Utf8Bytes() {
        for (String invalid : new String[] {null, "", " \t ", "i".repeat(129), "\u4e2d".repeat(43)}) {
            assertFailure(
                    () -> create(USER_ID, invalid, 100, null),
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_idempotency_key");
        }

        RechargeOrderService.CreationOutcome accepted = create(
                USER_ID, "i".repeat(128), 100, null);
        assertThat(accepted.created()).isTrue();
    }

    @Test
    void identicalReplayReturnsTheOriginalOrderAndMarksTheOutcomeAsReplayed() {
        RechargeOrderService.CreationOutcome first = create(
                USER_ID, "same-payload", 1_000, OPTION_ID);
        RechargeOrderService.CreationOutcome replay = create(
                USER_ID, "same-payload", 1_000, OPTION_ID);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.order()).isEqualTo(first.order());
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void reusedKeyRejectsAChangedAmountOrOptionPayload() {
        create(USER_ID, "changed-amount", 1_000, OPTION_ID);
        create(USER_ID, "changed-option", 1_000, OPTION_ID);

        assertFailure(
                () -> create(USER_ID, "changed-amount", 1_001, null),
                HttpStatus.CONFLICT, "recharge_idempotency_payload_mismatch");
        assertFailure(
                () -> create(USER_ID, "changed-option", 1_000, null),
                HttpStatus.CONFLICT, "recharge_idempotency_payload_mismatch");
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    void idempotencyIsScopedByUserAndAllReadsRemainTenantSafe() {
        RechargeOrderService.RechargeOrderView owner = create(
                USER_ID, "shared-key", 100, null).order();
        RechargeOrderService.RechargeOrderView otherUser = create(
                OTHER_USER_ID, "shared-key", 100, null).order();
        RechargeOrderService.RechargeOrderView otherTenant = service.createWithOutcome(
                OTHER_TENANT_ID, OTHER_TENANT_USER_ID, "shared-key",
                new RechargeOrderService.CreateRechargeCommand(100, null)).order();

        assertThat(owner.rechargeNo()).isNotEqualTo(otherUser.rechargeNo());
        assertThat(owner.rechargeNo()).isNotEqualTo(otherTenant.rechargeNo());
        assertFailure(
                () -> service.detail(TENANT_ID, OTHER_USER_ID, owner.rechargeNo()),
                HttpStatus.NOT_FOUND, "recharge_order_not_found");
        assertFailure(
                () -> service.detail(OTHER_TENANT_ID, OTHER_TENANT_USER_ID, owner.rechargeNo()),
                HttpStatus.NOT_FOUND, "recharge_order_not_found");
        assertFailure(
                () -> service.detail(TENANT_ID, USER_ID, "rch_missing"),
                HttpStatus.NOT_FOUND, "recharge_order_not_found");
    }

    @Test
    void createsPendingZeroBonusOrderWithStableNumberAndFifteenMinuteExpiry() {
        RechargeOrderService.RechargeOrderView result = create(
                USER_ID, "creation-shape", 12_345, null).order();

        assertThat(result.rechargeNo()).startsWith("rch_").hasSize(36);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.paidAmount()).isEqualTo(12_345);
        assertThat(result.bonusAmount()).isZero();
        assertThat(result.creditedAmount()).isEqualTo(12_345);
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(result.updatedAt()).isEqualTo(FIXED_NOW);
        assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));
        assertThat(result.paidAt()).isNull();
        assertThat(result.closedAt()).isNull();
    }

    @Test
    void ownedListIsPagedAndDeterministicallyNewestFirst() {
        RechargeOrderService.RechargeOrderView first = create(
                USER_ID, "list-first", 100, null).order();
        RechargeOrderService.RechargeOrderView second = create(
                USER_ID, "list-second", 200, null).order();
        create(OTHER_USER_ID, "not-owned", 300, null);
        jdbc.update("""
                UPDATE platform.recharge_order
                SET created_at = ?, updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW.plusSeconds(1)),
                Timestamp.from(FIXED_NOW.plusSeconds(1)), second.rechargeNo());

        RechargeOrderService.PageResult<RechargeOrderService.RechargeOrderView> page =
                service.list(TENANT_ID, USER_ID, 0, 1, null, null);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.items()).extracting(RechargeOrderService.RechargeOrderView::rechargeNo)
                .containsExactly(second.rechargeNo());
        assertThat(service.detail(TENANT_ID, USER_ID, first.rechargeNo())).isEqualTo(first);
    }

    @Test
    void expiredPendingRechargeIsClosedWhenRead() {
        RechargeOrderService.RechargeOrderView expired = create(
                USER_ID, "read-expired", 100, null).order();
        jdbc.update("""
                UPDATE platform.recharge_order
                SET created_at = ?, expires_at = ?, updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW.minus(Duration.ofMinutes(30))),
                Timestamp.from(FIXED_NOW.minusSeconds(1)),
                Timestamp.from(FIXED_NOW), expired.rechargeNo());

        RechargeOrderService.RechargeOrderView detail = service.detail(
                TENANT_ID, USER_ID, expired.rechargeNo());

        assertThat(detail.status()).isEqualTo("CLOSED");
        assertThat(detail.closedAt()).isEqualTo(FIXED_NOW);
        assertThat(service.list(TENANT_ID, USER_ID, 0, 20, null, null).items())
                .extracting(RechargeOrderService.RechargeOrderView::status)
                .containsExactly("CLOSED");
    }

    @Test
    void expiryJobClosesExpiredPendingRechargeOrdersInBoundedBatch() {
        RechargeOrderService.RechargeOrderView expired = create(
                USER_ID, "job-expired", 100, null).order();
        jdbc.update("""
                UPDATE platform.recharge_order
                SET created_at = ?, expires_at = ?, updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW.minus(Duration.ofMinutes(30))),
                Timestamp.from(FIXED_NOW.minusSeconds(1)),
                Timestamp.from(FIXED_NOW), expired.rechargeNo());

        assertThat(expiryJob.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.recharge_order WHERE recharge_no = ?
                """, String.class, expired.rechargeNo())).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("""
                SELECT closed_at FROM platform.recharge_order WHERE recharge_no = ?
                """, OffsetDateTime.class, expired.rechargeNo()).toInstant())
                .isEqualTo(FIXED_NOW);
    }

    @Test
    void payableEligibilityRejectsPaidClosedFailedAndExpiredOrders() {
        RechargeOrderService.RechargeOrderView payable = create(
                USER_ID, "payable", 100, null).order();
        RechargeOrderService.RechargeOrderView paid = create(
                USER_ID, "paid", 100, null).order();
        RechargeOrderService.RechargeOrderView closed = create(
                USER_ID, "closed", 100, null).order();
        RechargeOrderService.RechargeOrderView failed = create(
                USER_ID, "failed", 100, null).order();
        RechargeOrderService.RechargeOrderView expired = create(
                USER_ID, "expired", 100, null).order();
        jdbc.update("""
                UPDATE platform.recharge_order
                SET status = 'PAID', paid_at = ?, updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW), Timestamp.from(FIXED_NOW), paid.rechargeNo());
        jdbc.update("""
                UPDATE platform.recharge_order
                SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW), Timestamp.from(FIXED_NOW), closed.rechargeNo());
        jdbc.update("""
                UPDATE platform.recharge_order SET status = 'FAILED', updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW), failed.rechargeNo());
        jdbc.update("""
                UPDATE platform.recharge_order
                SET created_at = ?, expires_at = ?, updated_at = ?
                WHERE recharge_no = ?
                """, Timestamp.from(FIXED_NOW.minus(Duration.ofMinutes(30))),
                Timestamp.from(FIXED_NOW.minus(Duration.ofMinutes(15))),
                Timestamp.from(FIXED_NOW), expired.rechargeNo());

        assertThat(service.requirePayable(TENANT_ID, USER_ID, payable.rechargeNo()).rechargeNo())
                .isEqualTo(payable.rechargeNo());
        for (String rechargeNo : new String[] {
                paid.rechargeNo(), closed.rechargeNo(), failed.rechargeNo(), expired.rechargeNo()}) {
            assertFailure(
                    () -> service.requirePayable(TENANT_ID, USER_ID, rechargeNo),
                    HttpStatus.CONFLICT, "recharge_order_not_payable");
        }
    }

    @Test
    void listRejectsUnboundedPaginationAndUnapprovedSortInput() {
        assertFailure(
                () -> service.list(TENANT_ID, USER_ID, -1, 20, null, null),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination");
        assertFailure(
                () -> service.list(TENANT_ID, USER_ID, 0, 101, null, null),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination");
        assertFailure(
                () -> service.list(TENANT_ID, USER_ID, 0, 20, "paid_amount desc; drop table", "asc"),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_recharge_sort");
        assertFailure(
                () -> service.list(TENANT_ID, USER_ID, 0, 20, "createdAt", "sideways"),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sort_direction");
    }

    private RechargeOrderService.CreationOutcome create(
            UUID userId, String key, long amount, UUID optionId) {
        return service.createWithOutcome(TENANT_ID, userId, key,
                new RechargeOrderService.CreateRechargeCommand(amount, optionId));
    }

    private void assertUnavailable(String key, long amount, UUID optionId) {
        assertFailure(
                () -> create(USER_ID, key, amount, optionId),
                HttpStatus.UNPROCESSABLE_ENTITY, "recharge_option_unavailable");
    }

    private void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            HttpStatus status,
            String reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(status.value());
            assertThat(error.getReason()).isEqualTo(reason);
        });
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM platform.recharge_order", Integer.class);
    }

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private void insertUser(UUID tenantId, UUID userId, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Recharge User', 'ACTIVE')
                """, userId, tenantId, email);
    }

    private void insertOption(UUID id, UUID tenantId, long amount, boolean enabled, int order) {
        jdbc.update("""
                INSERT INTO platform.recharge_option(
                    id, tenant_id, amount, enabled, display_order)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, amount, enabled, order);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedRechargeClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
