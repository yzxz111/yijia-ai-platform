package com.yuzhihuihe.apiplatform.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.reporting.ReportingServiceTestFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AdminAnalyticsServiceTest {

    private static final UUID USER_ID = UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final Instant NOW = Instant.parse("2026-08-15T04:00:00Z");
    private static final Instant CURRENT_MONTH_FROM = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant CURRENT_MONTH_TO = Instant.parse("2026-08-31T16:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private AdminAnalyticsService service;

    @BeforeAll
    static void migrate() {
        JdbcTemplate bootstrap = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        bootstrap.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        service = new AdminAnalyticsService(
                new AdminAnalyticsRepository(jdbc),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ReportingServiceTestFactory.reportQuery(jdbc));
        jdbc.update("DELETE FROM platform.refund");
        jdbc.update("DELETE FROM platform.payment_transaction");
        jdbc.update("DELETE FROM platform.customer_order");
        jdbc.update("DELETE FROM platform.user_role");
        jdbc.update("DELETE FROM platform.app_user");
    }

    @Test
    void aggregatesOnlyFinalPaymentAndRefundFactsAcrossSixShanghaiMonths() {
        seedUsers();
        UUID februaryOrder = seedOrderAndPayment(
                "february", 2_000, "SUCCEEDED", Instant.parse("2026-02-15T08:00:00Z"));
        UUID aprilOrder = seedOrderAndPayment(
                "april", 1_000, "SUCCEEDED", Instant.parse("2026-04-10T08:00:00Z"));
        UUID currentStartOrder = seedOrderAndPayment(
                "current-start", 6_000, "SUCCEEDED", CURRENT_MONTH_FROM);
        UUID currentOrder = seedOrderAndPayment(
                "current", 4_000, "SUCCEEDED", Instant.parse("2026-08-12T08:00:00Z"));
        seedOrderAndPayment(
                "failed-current", 90_000, "FAILED", Instant.parse("2026-08-13T08:00:00Z"));
        seedOrderAndPayment("next-month", 7_000, "SUCCEEDED", CURRENT_MONTH_TO);

        seedRefund("negative-may", februaryOrder, 2_000, "COMPLETED",
                Instant.parse("2026-05-10T08:00:00Z"));
        seedRefund("zero-april", aprilOrder, 1_000, "COMPLETED",
                Instant.parse("2026-04-20T08:00:00Z"));
        seedRefund("current-start", currentStartOrder, 500, "COMPLETED", CURRENT_MONTH_FROM);
        seedRefund("current", currentOrder, 1_500, "COMPLETED",
                Instant.parse("2026-08-14T08:00:00Z"));
        seedRefund("pending-current", currentOrder, 5_000, "PROCESSING",
                Instant.parse("2026-08-14T09:00:00Z"));
        seedRefund("next-month", currentOrder, 700, "COMPLETED", CURRENT_MONTH_TO);

        ReportingServiceTestFactory.aggregation(
                jdbc, new DataSourceTransactionManager(jdbc.getDataSource()))
                .recomputeDirtyDates(20);

        AdminAnalyticsService.OperatingAnalytics result = service.analytics(6);

        assertThat(result.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.months()).isEqualTo(6);
        assertThat(result.summary()).isEqualTo(
                new AdminAnalyticsService.OperatingSummary(4, 3, 10_000, 2_000, 8_000, 5, 6, 3, 2));
        assertThat(result.monthly()).containsExactly(
                new AdminAnalyticsService.MonthlyOperating(LocalDate.of(2026, 3, 1), 1, 0, 0, 0),
                new AdminAnalyticsService.MonthlyOperating(LocalDate.of(2026, 4, 1), 0, 1_000, 1_000, 0),
                new AdminAnalyticsService.MonthlyOperating(LocalDate.of(2026, 5, 1), 0, 0, 2_000, -2_000),
                new AdminAnalyticsService.MonthlyOperating(LocalDate.of(2026, 6, 1), 1, 0, 0, 0),
                new AdminAnalyticsService.MonthlyOperating(LocalDate.of(2026, 7, 1), 0, 0, 0, 0),
                new AdminAnalyticsService.MonthlyOperating(LocalDate.of(2026, 8, 1), 1, 10_000, 2_000, 8_000));
    }

    @Test
    void rejectsAnyMonthCountOtherThanSix() {
        assertThatThrownBy(() -> service.analytics(5))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("months_must_be_6");
    }

    private void seedUsers() {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status, auth_version, created_at)
                VALUES (?, ?, 'analytics-1@example.com', 'hash', 'One', 'ACTIVE', 0, ?),
                       (?, ?, 'analytics-2@example.com', 'hash', 'Two', 'ACTIVE', 0, ?),
                       (?, ?, 'analytics-3@example.com', 'hash', 'Three', 'DISABLED', 0, ?),
                       (?, ?, 'analytics-4@example.com', 'hash', 'Four', 'ACTIVE', 0, ?)
                """,
                USER_ID, USER_ID, timestamp("2026-02-10T08:00:00Z"),
                userId(2), userId(2), timestamp("2026-03-10T08:00:00Z"),
                userId(3), userId(3), timestamp("2026-06-10T08:00:00Z"),
                userId(4), userId(4), Timestamp.from(CURRENT_MONTH_FROM));
    }

    private UUID seedOrderAndPayment(String suffix, long amount, String status, Instant paidAt) {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        // created_at **必须显式写出来**，不能留给数据库默认的 now()。
        //
        // 本测试把"现在"钉在 NOW（2026-08-15），统计窗口因此是 2026 年 8 月；
        // 而数据库的 now() 走的是真实墙上时钟。用默认值会让按 created_at 计数的
        // current_month_orders 与 current_month_invalid_or_refunded 随真实日期漂出
        // 窗口——它们只在作者写这个测试的那一个月里恰好是对的，之后一直读到 0。
        // 这里把六笔订单的 created_at 统一钉在 NOW：本测试要故意分散到各个月份的
        // 维度是 **paid_at**，不是 created_at。
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, status, payment_provider, paid_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAID', 'MOCK', ?, ?)
                """, orderId, "order-analytics-" + suffix, USER_ID, USER_ID, VERSION_ID,
                amount, Timestamp.from(paidAt), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, payment_product, amount, status, paid_at)
                VALUES (?, ?, ?, 'MOCK', 'MOCK', ?, ?, ?)
                """, paymentId, "payment-analytics-" + suffix, orderId, amount, status,
                Timestamp.from(paidAt));
        if ("SUCCEEDED".equals(status)) {
            seedFact("payment-analytics-" + suffix, "PACKAGE_PURCHASE_REVENUE",
                    paidAt, amount, 0);
        }
        return orderId;
    }

    private void seedRefund(String suffix, UUID orderId, long amount, String status, Instant completedAt) {
        UUID paymentId = jdbc.queryForObject(
                "SELECT id FROM platform.payment_transaction WHERE order_id = ?", UUID.class, orderId);
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status, reason,
                    completed_at, refund_type, policy_version, calculation_snapshot,
                    requested_by, provider, gateway_hold_business_no, revocation_business_no)
                VALUES (?, ?, ?, ?, ?, ?, 'analytics test', ?, 'PARTIAL', 1, '{}'::jsonb,
                        ?, 'MOCK', ?, ?)
                """, UUID.randomUUID(), "refund-analytics-" + suffix, orderId, paymentId, amount,
                status, Timestamp.from(completedAt), USER_ID,
                "hold-analytics-" + suffix, "revoke-analytics-" + suffix);
        if ("COMPLETED".equals(status)) {
            seedFact("refund-analytics-" + suffix, "PACKAGE_REFUND",
                    completedAt, 0, amount);
        }
    }

    private void seedFact(
            String eventId, String eventType, Instant recognizedAt, long revenue, long refund) {
        jdbc.update("""
                INSERT INTO platform.operating_fact(
                    source_service, source_event_id, line_no, event_type,
                    occurred_at, recognized_at, tenant_id, user_id, package_plan_version_id,
                    billing_mode, confirmed_revenue_amount, refund_amount, currency)
                VALUES ('analytics-test', ?, 0, ?, ?, ?, ?, ?, ?,
                        'PACKAGE_ONLY', ?, ?, 'CNY')
                """, eventId, eventType, Timestamp.from(recognizedAt),
                Timestamp.from(recognizedAt), USER_ID, USER_ID, VERSION_ID, revenue, refund);
    }

    private UUID userId(int suffix) {
        return UUID.fromString("74000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value));
    }
}
