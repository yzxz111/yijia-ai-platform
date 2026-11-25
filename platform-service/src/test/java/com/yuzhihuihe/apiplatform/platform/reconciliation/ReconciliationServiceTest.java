package com.yuzhihuihe.apiplatform.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAnomalyService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import com.yuzhihuihe.apiplatform.platform.reporting.PaymentFeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "reconciliation.job-enabled=false",
        "reconciliation.max-records=1100"
})
class ReconciliationServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("52000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("52000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID =
            UUID.fromString("52000000-0000-4000-8000-000000000003");
    // 套餐版本 id **不写死**，见 publishedStarterVersionId()。
    private static final LocalDate BILL_DATE = LocalDate.of(2026, 7, 31);
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-31T04:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
        } catch (Exception exception) {
            throw new IllegalStateException("btree_gist test setup failed", exception);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @MockitoSpyBean
    JdbcTemplate jdbc;

    @Autowired
    ReconciliationService service;

    @Autowired
    PaymentFeeService paymentFeeService;

    @MockitoBean
    PaymentAvailabilityService availabilityService;

    @MockitoSpyBean
    PaymentAnomalyService anomalyService;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.notification.NotificationBroadcastJob
            notificationBroadcastJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.notification.NotificationFanoutJob
            notificationFanoutJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.notification.WalletUsageDigestJob
            walletUsageDigestJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.outbox.OutboxPublisher outboxPublisher;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.payment.ExpiredOrderJob expiredOrderJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.payment.PaymentCompensationJob paymentCompensationJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.refund.RefundPreparationJob refundPreparationJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.refund.RefundCompensationJob refundCompensationJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.reporting.OperatingAggregationJob
            operatingAggregationJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.reporting.OperatingBackfillJob operatingBackfillJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.reporting.OperatingReconciliationJob
            operatingReconciliationJob;

    @MockitoBean
    com.yuzhihuihe.apiplatform.platform.reporting.ProviderPriceMonitorJob
            providerPriceMonitorJob;

    PaymentAdapter adapter;

    @BeforeEach
    void reset() {
        adapter = mock(PaymentAdapter.class);
        when(adapter.provider()).thenReturn(PaymentProvider.MOCK);
        when(adapter.environment()).thenReturn(
                com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment.LOCAL);
        when(adapter.products()).thenReturn(Set.of(PaymentProduct.MOCK));
        when(availabilityService.requireCheckout(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .thenReturn(adapter);
        jdbc.update("TRUNCATE platform.operating_fact, platform.operating_fact_event_receipt, "
                + "platform.reconciliation_item, platform.reconciliation_run, "
                + "platform.payment_anomaly, platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.refund, platform.payment_transaction, "
                + "platform.customer_order, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "reconciliation-user@example.com");
        insertUser(ADMIN_ID, "reconciliation-admin@example.com");
    }

    @Test
    void postsActualProviderFeeFromVerifiedBillExactlyOnce() {
        insertPayment("pay_actual_fee", "PAID", "SUCCEEDED", 5_000, OCCURRED_AT);
        PaymentAdapter.BillEntry entry = new PaymentAdapter.BillEntry(
                "bill-actual-fee", BillEntryType.PAYMENT, "pay_actual_fee", null,
                5_000, "CNY", 31, "CNY", ProviderOutcome.SUCCEEDED, OCCURRED_AT);
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "9".repeat(64), List.of(entry)));

        service.start(PaymentProvider.MOCK, BILL_DATE, null);
        service.start(PaymentProvider.MOCK, BILL_DATE, null);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.operating_fact
                WHERE event_type='PAYMENT_PROVIDER_FEE_ACTUAL'
                  AND payment_provider_fee_amount=31
                  AND payment_transaction_id IS NOT NULL
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void classifiesAllRequiredDifferencesAndOnlyAutoPostsQueryConfirmedReceipt() {
        insertPayment("pay_repair", "PENDING", "PENDING", 2990, null);
        insertPayment("pay_missing_bill", "PAID", "SUCCEEDED", 4100, OCCURRED_AT);
        insertPayment("pay_amount", "PAID", "SUCCEEDED", 5000, OCCURRED_AT);
        insertPayment("pay_duplicate", "PAID", "SUCCEEDED", 6000, OCCURRED_AT);
        insertPayment("pay_late", "CLOSED", "PENDING", 7000, null);
        insertRefund("ref_status", "COMPLETED", 8000);
        insertRefund("ref_amount", "PROCESSING", 9000);

        List<PaymentAdapter.BillEntry> entries = List.of(
                paymentEntry("bill-repair", "pay_repair", 2990, ProviderOutcome.SUCCEEDED),
                paymentEntry("bill-amount", "pay_amount", 5001, ProviderOutcome.SUCCEEDED),
                paymentEntry("bill-dup-1", "pay_duplicate", 6000, ProviderOutcome.SUCCEEDED),
                paymentEntry("bill-dup-2", "pay_duplicate", 6000, ProviderOutcome.SUCCEEDED),
                paymentEntry("bill-late", "pay_late", 7000, ProviderOutcome.SUCCEEDED),
                paymentEntry("bill-unlinked", "pay_unknown", 1000, ProviderOutcome.SUCCEEDED),
                refundEntry("bill-ref-status", "ref_status", 8000, ProviderOutcome.PENDING),
                refundEntry("bill-ref-amount", "ref_amount", 9001, ProviderOutcome.SUCCEEDED));
        when(adapter.downloadBill(BILL_DATE)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PaymentAdapter.BillDownload(
                    ProviderOutcome.SUCCEEDED, BILL_DATE, "a".repeat(64), entries);
        });
        when(adapter.queryPayment(any())).thenReturn(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED, "pay_repair", "trade-repair", 2990,
                "CNY", OCCURRED_AT, "SUCCESS", null, "b".repeat(64)));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.summary().totalRecords()).isEqualTo(8);
        assertThat(result.summary().matchedCount()).isZero();
        assertThat(result.items().items()).extracting(
                ReconciliationService.ReconciliationItemView::differenceType)
                .contains(
                        "PROVIDER_SUCCESS_PLATFORM_MISSING",
                        "PLATFORM_SUCCESS_PROVIDER_MISSING",
                        "PAYMENT_AMOUNT_MISMATCH",
                        "DUPLICATE_PROVIDER_RECEIPT",
                        "REFUND_STATUS_MISMATCH",
                        "REFUND_AMOUNT_MISMATCH",
                        "LATE_PAYMENT_AFTER_ORDER_CLOSE",
                        "UNLINKED_BILL_ROW");
        assertThat(result.items().items())
                .filteredOn(item -> item.differenceType()
                        .equals("PROVIDER_SUCCESS_PLATFORM_MISSING"))
                .singleElement().satisfies(item -> assertThat(item.status())
                        .isEqualTo("AUTO_RESOLVED"));
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.payment_transaction
                WHERE transaction_no = 'pay_repair'
                """, String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.reconciliation_item
                WHERE difference_type IN (
                    'PAYMENT_AMOUNT_MISMATCH', 'DUPLICATE_PROVIDER_RECEIPT',
                    'REFUND_AMOUNT_MISMATCH') AND status = 'OPEN'
                """, Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'RECONCILIATION_STARTED' AND actor_user_id = ?
                """, Long.class, ADMIN_ID)).isEqualTo(1);
    }

    @Test
    void reportsAuthoritativeMatchesWhenLocalProviderMissingDifferencesAlsoExist() {
        insertPayment("pay_exact_match", "PAID", "SUCCEEDED", 2990, OCCURRED_AT);
        insertPayment("pay_provider_missing", "PAID", "SUCCEEDED", 4100, OCCURRED_AT);
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "9".repeat(64),
                List.of(paymentEntry(
                        "bill-exact-match", "pay_exact_match", 2990,
                        ProviderOutcome.SUCCEEDED))));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.summary().totalRecords()).isEqualTo(1);
        assertThat(result.summary().matchedCount()).isEqualTo(1);
        assertThat(result.summary().differenceCount()).isEqualTo(1);
        assertThat(result.items().items()).singleElement().satisfies(item ->
                assertThat(item.differenceType())
                        .isEqualTo("PLATFORM_SUCCESS_PROVIDER_MISSING"));
    }

    @Test
    void matchesRechargeOwnedPaymentAndRetainsItsProviderTransactionFacts() {
        UUID rechargeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, paid_at, created_at, updated_at)
                VALUES (?, 'rch_reconciliation', ?, ?, 3200, 0, 3200, 'CNY',
                        'PAID', 'idem_rch_reconciliation', ?, ?, ?, ?)
                """, rechargeId, TENANT_ID, USER_ID,
                Timestamp.from(OCCURRED_AT.plusSeconds(900)), Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT.minusSeconds(60)), Timestamp.from(OCCURRED_AT));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    external_trade_no, amount, status, payment_product,
                    paid_at, applied_to_order)
                VALUES (?, 'pay_recharge_reconciliation', NULL, ?, 'MOCK',
                        'trade-recharge-reconciliation', 3200, 'SUCCEEDED', 'MOCK', ?, true)
                """, UUID.randomUUID(), rechargeId, Timestamp.from(OCCURRED_AT));
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "8".repeat(64),
                List.of(paymentEntry(
                        "bill-recharge-match", "pay_recharge_reconciliation", 3200,
                        ProviderOutcome.SUCCEEDED))));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.summary().matchedCount()).isEqualTo(1);
        assertThat(result.summary().differenceCount()).isZero();
        assertThat(result.items().items()).isEmpty();
    }

    @Test
    void persistsEveryMissingSuccessfulPaymentForTheSameRecharge() {
        UUID rechargeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, paid_at, created_at, updated_at)
                VALUES (?, 'rch_shared_missing', ?, ?, 3200, 0, 3200, 'CNY',
                        'PAID', 'idem_rch_shared_missing', ?, ?, ?, ?)
                """, rechargeId, TENANT_ID, USER_ID,
                Timestamp.from(OCCURRED_AT.plusSeconds(900)), Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT.minusSeconds(60)), Timestamp.from(OCCURRED_AT));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    external_trade_no, amount, status, payment_product,
                    paid_at, applied_to_order)
                VALUES
                    (?, 'pay_recharge_missing_applied', NULL, ?, 'MOCK',
                        'trade-recharge-missing-applied', 3200, 'SUCCEEDED',
                        'MOCK', ?, true),
                    (?, 'pay_recharge_missing_duplicate', NULL, ?, 'MOCK',
                        'trade-recharge-missing-duplicate', 3200, 'SUCCEEDED',
                        'MOCK', ?, false)
                """, UUID.randomUUID(), rechargeId, Timestamp.from(OCCURRED_AT),
                UUID.randomUUID(), rechargeId, Timestamp.from(OCCURRED_AT));
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "7".repeat(64), List.of()));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.summary().differenceCount()).isEqualTo(2);
        assertThat(result.items().total()).isEqualTo(2);
        assertThat(result.items().items())
                .extracting(ReconciliationService.ReconciliationItemView::transactionNo)
                .containsExactlyInAnyOrder(
                        "pay_recharge_missing_applied",
                        "pay_recharge_missing_duplicate");
        assertThat(result.items().items())
                .extracting(ReconciliationService.ReconciliationItemView::providerRecordId)
                .containsExactlyInAnyOrder(
                        "platform-payment:pay_recharge_missing_applied",
                        "platform-payment:pay_recharge_missing_duplicate");
        assertThat(result.items().items()).allSatisfy(item ->
                assertThat(item.detail().path("businessNo").asText())
                        .isEqualTo("rch_shared_missing"));

        ReconciliationService.RunDetail replay =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(replay.id()).isEqualTo(result.id());
        assertThat(replay.summary()).isEqualTo(result.summary());
        assertThat(replay.items().total()).isEqualTo(2);
        verify(adapter).downloadBill(BILL_DATE);
    }

    @Test
    void replayReturnsExistingRunWithoutDownloadingOrDuplicatingItems() {
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "c".repeat(64),
                List.of(paymentEntry(
                        "bill-unlinked-replay", "pay_unknown_replay", 1000,
                        ProviderOutcome.SUCCEEDED))));

        ReconciliationService.RunDetail first =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);
        ReconciliationService.RunDetail replay =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.reconciliation_run",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.reconciliation_item",
                Long.class)).isEqualTo(1);
        verify(adapter, times(1)).downloadBill(BILL_DATE);
    }

    @Test
    void billRecordLimitMatchesTheConfiguredMillionRecordContract() {
        assertThat(PaymentAdapter.MAX_BILL_ENTRIES).isEqualTo(1_000_000);
        assertThatThrownBy(() -> new ReconciliationService(
                new ReconciliationRepository(jdbc), availabilityService,
                null, null, null, null, null, paymentFeeService,
                PaymentAdapter.MAX_BILL_ENTRIES + 1, "Asia/Shanghai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max records");
    }

    @Test
    void duplicatePaymentRecoveryRemainsOpenForAdministratorReview() {
        insertPayment("pay_original", "PAID", "SUCCEEDED", 2990, OCCURRED_AT);
        UUID orderId = jdbc.queryForObject("""
                SELECT order_id FROM platform.payment_transaction
                WHERE transaction_no = 'pay_original'
                """, UUID.class);
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status, payment_product)
                VALUES (?, 'pay_duplicate_repair', ?, 'MOCK', 2990, 'PENDING', 'MOCK')
                """, UUID.randomUUID(), orderId);
        PaymentAdapter.BillEntry entry = paymentEntry(
                "bill-duplicate-repair", "pay_duplicate_repair", 2990,
                ProviderOutcome.SUCCEEDED);
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "d".repeat(64), List.of(entry)));
        when(adapter.queryPayment(any())).thenReturn(new PaymentAdapter.ProviderPayment(
                ProviderOutcome.SUCCEEDED, "pay_duplicate_repair", "trade-duplicate-repair",
                2990, "CNY", OCCURRED_AT, "SUCCESS", null, "e".repeat(64)));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.items().items())
                .filteredOn(item -> "pay_duplicate_repair".equals(item.transactionNo()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.differenceType())
                            .isEqualTo("PROVIDER_SUCCESS_PLATFORM_MISSING");
                    assertThat(item.status()).isEqualTo("OPEN");
                    assertThat(item.detail().path("recoveryOutcome").asText())
                            .isEqualTo("DUPLICATE_REQUIRES_REVIEW");
                });
        assertThat(jdbc.queryForObject("""
                SELECT applied_to_order FROM platform.payment_transaction
                WHERE transaction_no = 'pay_duplicate_repair'
                """, Boolean.class)).isFalse();
    }

    @Test
    void matchesAndPersistsLargeBillsWithBoundedDatabaseReads() {
        List<PaymentAdapter.BillEntry> entries = IntStream.range(0, 100)
                .mapToObj(index -> paymentEntry(
                        "bill-bounded-" + index, "pay_missing_" + index,
                        1000 + index, ProviderOutcome.SUCCEEDED))
                .toList();
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "f".repeat(64), entries));
        clearInvocations(jdbc);

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        long databaseReads = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> {
                    Class<?>[] parameters = invocation.getMethod().getParameterTypes();
                    return (invocation.getMethod().getName().equals("query")
                            && Arrays.stream(parameters)
                            .anyMatch(RowMapper.class::isAssignableFrom))
                            || (invocation.getMethod().getName().equals("queryForObject")
                            && parameters.length > 1 && parameters[1] == Class.class);
                })
                .count();
        assertThat(result.summary().differenceCount()).isEqualTo(100);
        assertThat(databaseReads).isLessThanOrEqualTo(12);
    }

    @Test
    void persistsProviderDifferencesBeforeLoadingTheNextBoundedBatch() {
        List<PaymentAdapter.BillEntry> entries = IntStream.range(0, 1001)
                .mapToObj(index -> paymentEntry(
                        "bill-window-" + index, "pay_window_" + index,
                        1000 + index, ProviderOutcome.SUCCEEDED))
                .toList();
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "0".repeat(64), entries));
        clearInvocations(jdbc);

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        List<String> providerPipeline = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getArguments().length > 0
                        && invocation.getArguments()[0] instanceof String)
                .map(invocation -> (String) invocation.getArguments()[0])
                .map(sql -> {
                    if (sql.contains("FROM platform.payment_transaction payment")
                            && sql.contains("transaction_no IN")) {
                        return "PROVIDER_LOOKUP";
                    }
                    if (sql.contains("INSERT INTO platform.reconciliation_item")) {
                        return "ITEM_WRITE";
                    }
                    return "OTHER";
                })
                .filter(phase -> !"OTHER".equals(phase))
                .toList();
        assertThat(result.summary().differenceCount()).isEqualTo(1001);
        assertThat(providerPipeline).containsSubsequence(
                "PROVIDER_LOOKUP", "ITEM_WRITE", "PROVIDER_LOOKUP");
    }

    @Test
    void failedRunRemovesOnlyAnomaliesCreatedByCommittedBatches() {
        PaymentAnomalyService.AnomalyView preexisting = anomalyService.record(
                new PaymentAnomalyService.AnomalyCommand(
                        "UNLINKED_BILL_ROW", PaymentProvider.MOCK,
                        null, null, null,
                        java.util.Map.of(
                                "entryType", "PAYMENT",
                                "reason", "TRANSACTION_NOT_FOUND")));
        insertPayment("pay_failed_cleanup_0", "PAID", "SUCCEEDED", 9000, OCCURRED_AT);
        insertPayment("pay_failed_cleanup_1", "PAID", "SUCCEEDED", 9001, OCCURRED_AT);
        List<PaymentAdapter.BillEntry> entries = IntStream.range(0, 1001)
                .mapToObj(index -> paymentEntry(
                        "bill-failed-cleanup-" + index,
                        "pay_failed_cleanup_" + index,
                        1000 + index, ProviderOutcome.SUCCEEDED))
                .toList();
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "8".repeat(64), entries));
        AtomicInteger committedBatches = new AtomicInteger();
        AtomicReference<List<UUID>> createdByCommittedBatch =
                new AtomicReference<>(List.of());
        doAnswer(invocation -> {
            if (committedBatches.incrementAndGet() == 2) {
                claimAnomalyExternally(createdByCommittedBatch.get().getFirst());
                throw new IllegalStateException("forced_second_batch_failure");
            }
            @SuppressWarnings("unchecked")
            List<UUID> inserted = (List<UUID>) invocation.callRealMethod();
            createdByCommittedBatch.set(inserted);
            return inserted;
        }).when(anomalyService).recordAll(anyList());

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.reconciliation_item", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.payment_anomaly", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.payment_anomaly WHERE id = ?",
                Long.class, preexisting.id())).isEqualTo(1);
        assertThat(createdByCommittedBatch.get()).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.payment_anomaly WHERE id = ?",
                Long.class, createdByCommittedBatch.get().getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.payment_anomaly WHERE id = ?",
                Long.class, createdByCommittedBatch.get().getLast())).isZero();
    }

    @Test
    void staleWorkerCannotDeleteSuccessorResultsAfterLosingLease() {
        when(adapter.downloadBill(BILL_DATE)).thenAnswer(invocation -> {
            UUID runId = jdbc.queryForObject(
                    "SELECT id FROM platform.reconciliation_run WHERE provider = 'MOCK' AND bill_date = ?",
                    UUID.class, BILL_DATE);
            jdbc.update("""
                    UPDATE platform.reconciliation_run
                    SET job_claim_token = ?, job_claimed_until = now() + interval '30 minutes'
                    WHERE id = ?
                    """, UUID.randomUUID(), runId);
            jdbc.update("""
                    INSERT INTO platform.reconciliation_item(
                        id, run_id, provider_record_id, difference_type, status, detail)
                    VALUES (?, ?, 'successor-item', 'UNLINKED_BILL_ROW', 'OPEN', '{}'::jsonb)
                    """, UUID.randomUUID(), runId);
            throw new IllegalStateException("stale_worker_failed_after_reclaim");
        });

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.items().items())
                .singleElement()
                .satisfies(item -> assertThat(item.providerRecordId()).isEqualTo("successor-item"));
    }

    @Test
    void failsWhenLocalPaymentCandidatesExceedTheConfiguredLimit() {
        IntStream.rangeClosed(0, 1100).forEach(index -> insertPayment(
                "pay_local_overflow_" + index, "PAID", "SUCCEEDED",
                1000 + index, OCCURRED_AT));
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "3".repeat(64), List.of()));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.summary().differenceCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.reconciliation_item", Long.class)).isZero();
    }

    @Test
    void failsWhenLocalRefundCandidatesExceedTheConfiguredLimit() {
        insertPayment("pay_refund_overflow", "PAID", "PENDING", 2990, null);
        UUID orderId = jdbc.queryForObject("""
                SELECT order_id FROM platform.payment_transaction
                WHERE transaction_no = 'pay_refund_overflow'
                """, UUID.class);
        UUID paymentId = jdbc.queryForObject("""
                SELECT id FROM platform.payment_transaction
                WHERE transaction_no = 'pay_refund_overflow'
                """, UUID.class);
        IntStream.rangeClosed(0, 1100).forEach(index -> jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status,
                    refund_type, calculation_snapshot, reason, requested_by, provider,
                    provider_status, gateway_hold_business_no, completed_at)
                VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'PARTIAL', '{}'::jsonb,
                        'reconciliation', ?, 'MOCK', 'SUCCESS', ?, ?)
                """, UUID.randomUUID(), "ref_local_overflow_" + index,
                orderId, paymentId, 1000 + index, USER_ID,
                "hold-local-overflow-" + index, Timestamp.from(OCCURRED_AT)));
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "4".repeat(64), List.of()));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.summary().differenceCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.reconciliation_item", Long.class)).isZero();
    }

    @Test
    void reclaimsExpiredRunningRunWithTheSameIdentity() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.reconciliation_run(
                    id, provider, bill_date, status, started_at,
                    job_claim_token, job_claimed_until)
                VALUES (?, 'MOCK', ?, 'RUNNING', now() - interval '2 hours',
                        ?, now() - interval '1 hour')
                """, runId, BILL_DATE, UUID.randomUUID());
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "1".repeat(64), List.of()));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, null);

        assertThat(result.id()).isEqualTo(runId);
        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(adapter).downloadBill(BILL_DATE);
    }

    @Test
    void retriesFailedRunWithTheSameIdentityAndClearsStaleItems() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.reconciliation_run(
                    id, provider, bill_date, status, started_at, completed_at)
                VALUES (?, 'MOCK', ?, 'FAILED', now() - interval '2 hours', now())
                """, runId, BILL_DATE);
        jdbc.update("""
                INSERT INTO platform.reconciliation_item(
                    id, run_id, provider_record_id, difference_type, status, detail)
                VALUES (?, ?, 'stale-provider-record', 'UNLINKED_BILL_ROW', 'OPEN',
                        '{"reason":"STALE_RETRY_ITEM"}'::jsonb)
                """, UUID.randomUUID(), runId);
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "5".repeat(64), List.of()));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, ADMIN_ID);

        assertThat(result.id()).isEqualTo(runId);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.reconciliation_item WHERE run_id = ?
                """, Long.class, runId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'RECONCILIATION_STARTED'
                  AND actor_user_id = ? AND resource_id = ?
                """, Long.class, ADMIN_ID, runId.toString())).isEqualTo(1);
        verify(adapter).downloadBill(BILL_DATE);
    }

    @Test
    void comparesRefundProviderStatusAndTreatsRevokingAsSuccessfulProviderSettlement() {
        insertRefund("ref_revoking", "REVOKING", 8000, "SUCCESS");
        insertRefund("ref_failed_stuck", "PROCESSING", 9000, "FAILED");
        when(adapter.downloadBill(BILL_DATE)).thenReturn(new PaymentAdapter.BillDownload(
                ProviderOutcome.SUCCEEDED, BILL_DATE, "2".repeat(64), List.of(
                refundEntry(
                        "bill-ref-revoking", "ref_revoking", 8000,
                        ProviderOutcome.SUCCEEDED),
                refundEntry(
                        "bill-ref-failed-stuck", "ref_failed_stuck", 9000,
                        ProviderOutcome.FAILED))));

        ReconciliationService.RunDetail result =
                service.start(PaymentProvider.MOCK, BILL_DATE, null);

        assertThat(result.items().items())
                .filteredOn(item -> "REFUND_STATUS_MISMATCH".equals(item.differenceType()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.refundNo()).isEqualTo("ref_failed_stuck");
                    assertThat(item.detail().path("expectedStatus").asText())
                            .isEqualTo("FAILED");
                    assertThat(item.detail().path("actualStatus").asText())
                            .isEqualTo("PROCESSING");
                });
    }

    private PaymentAdapter.BillEntry paymentEntry(
            String recordId, String transactionNo, long amount, ProviderOutcome outcome) {
        return new PaymentAdapter.BillEntry(
                recordId, BillEntryType.PAYMENT, transactionNo, null,
                amount, "CNY", outcome, OCCURRED_AT);
    }

    private PaymentAdapter.BillEntry refundEntry(
            String recordId, String refundNo, long amount, ProviderOutcome outcome) {
        return new PaymentAdapter.BillEntry(
                recordId, BillEntryType.REFUND, null, refundNo,
                amount, "CNY", outcome, OCCURRED_AT);
    }

    private void claimAnomalyExternally(UUID anomalyId) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var claim = connection.prepareStatement("""
                    UPDATE platform.payment_anomaly
                    SET status = 'INVESTIGATING', assignee = ?, lock_version = 1
                    WHERE id = ?
                    """)) {
                claim.setObject(1, ADMIN_ID);
                claim.setObject(2, anomalyId);
                assertThat(claim.executeUpdate()).isEqualTo(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("external anomaly claim failed", exception);
        }
    }

    /**
     * 当前已发布的 STARTER 版本 id，**现查**。
     *
     * <p>这里曾经写死 STARTER v1 的 {@code ...102}。V61__reprice_token_plans_to_thirty_percent_floor.sql:105
     * 把它改成了 RETIRED，而自动补单要走 {@code PackageSnapshotService.loadPublished}
     * 读取版本快照——那个入口只认 PUBLISHED 版本，于是补单抛 404、对账把它记成
     * {@code recoveryOutcome=QUERY_UNCERTAIN}，差异停在 OPEN。
     *
     * <p>「查询确认的收据会被自动补单」这条断言的意图与具体版本号无关，
     * 而版本号会被每一次重新定价迁移换掉，所以现查才是稳定的写法。
     */
    private UUID publishedStarterVersionId() {
        return jdbc.queryForObject("""
                SELECT version.id
                FROM platform.package_plan_version version
                JOIN platform.package_plan plan ON plan.id = version.package_plan_id
                WHERE plan.code = 'STARTER' AND version.status = 'PUBLISHED'
                """, UUID.class);
    }

    private void insertPayment(
            String transactionNo, String orderStatus, String paymentStatus,
            long amount, Instant paidAt) {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', ?, ?)
                """, orderId, "ord_" + transactionNo, TENANT_ID, USER_ID,
                publishedStarterVersionId(),
                amount, orderStatus, paidAt == null ? null : Timestamp.from(paidAt));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, paid_at, applied_to_order)
                VALUES (?, ?, ?, 'MOCK', ?, ?, 'MOCK', ?, ?)
                """, paymentId, transactionNo, orderId, amount, paymentStatus,
                paidAt == null ? null : Timestamp.from(paidAt), "SUCCEEDED".equals(paymentStatus));
        if ("SUCCEEDED".equals(paymentStatus)) {
            jdbc.update("""
                    UPDATE platform.customer_order
                    SET successful_payment_transaction_id = ? WHERE id = ?
                    """, paymentId, orderId);
        }
    }

    private void insertRefund(String refundNo, String status, long amount) {
        String providerStatus = switch (status) {
            case "COMPLETED", "REVOKING" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            default -> "PROCESSING";
        };
        insertRefund(refundNo, status, amount, providerStatus);
    }

    private void insertRefund(
            String refundNo, String status, long amount, String providerStatus) {
        String transactionNo = "pay_" + refundNo;
        insertPayment(transactionNo, "PAID", "SUCCEEDED", amount, OCCURRED_AT.minusSeconds(60));
        UUID orderId = jdbc.queryForObject("""
                SELECT order_id FROM platform.payment_transaction WHERE transaction_no = ?
                """, UUID.class, transactionNo);
        UUID paymentId = jdbc.queryForObject("""
                SELECT id FROM platform.payment_transaction WHERE transaction_no = ?
                """, UUID.class, transactionNo);
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status,
                    refund_type, policy_version, calculation_snapshot, reason,
                    requested_by, provider, provider_status, gateway_hold_business_no)
                VALUES (?, ?, ?, ?, ?, ?, 'FULL', NULL, '{}'::jsonb, 'reconciliation',
                        ?, 'MOCK', ?, ?)
                """, UUID.randomUUID(), refundNo, orderId, paymentId, amount, status,
                USER_ID, providerStatus, "hold-" + refundNo);
    }

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Reconciliation User', 'ACTIVE')
                """, id, TENANT_ID, email);
    }
}
