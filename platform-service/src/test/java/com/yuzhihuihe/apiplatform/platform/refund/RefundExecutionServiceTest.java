package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        "outbox.publisher.initial-delay-ms=3600000",
        "refund.jobs.preparation-delay-ms=3600000",
        "refund.jobs.compensation-delay-ms=3600000"
})
class RefundExecutionServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000003");
    private static final UUID ORDER_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000004");
    private static final UUID PAYMENT_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000005");
    private static final UUID GRANT_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000006");
    private static final UUID PACKAGE_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000007");
    private static final UUID REFUND_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000008");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("43000000-0000-4000-8000-000000000009");
    private static final UUID DEBIT_LEDGER_ID =
            UUID.fromString("43000000-0000-4000-8000-00000000000a");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final String ORDER_NO = "ord_refund_execution";
    private static final String REFUND_NO = "ref_execution_001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RefundExecutionService executionService;

    @Autowired
    RefundApplicationService applicationService;

    @MockitoBean
    PaymentAvailabilityService availabilityService;

    @MockitoBean
    GatewayRefundClient gatewayRefundClient;

    PaymentAdapter adapter;
    GatewayRefundClient.RefundSnapshot approvedSnapshot;

    @BeforeEach
    void resetState() throws Exception {
        reset(availabilityService, gatewayRefundClient);
        adapter = mock(PaymentAdapter.class);
        when(availabilityService.requireOperational(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .thenReturn(adapter);
        jdbc.update("TRUNCATE platform.payment_anomaly, platform.admin_audit_log, "
                + "platform.outbox_event, platform.refund, platform.payment_transaction, "
                + "platform.customer_order, platform.grant_request, platform.refresh_token, "
                + "platform.user_role, platform.invitation, platform.app_user "
                + "RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "execution-user@example.com");
        insertUser(ADMIN_ID, "execution-admin@example.com");
        insertPolicy();
        insertCommercialFixture("PENDING_APPROVAL", REFUND_NO, 8_500L, "PARTIAL");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyPendingApprovalIsApprovedAndCommitPrecedesProviderIo() {
        when(gatewayRefundClient.prepare("refund-hold:" + REFUND_NO, GRANT_ID))
                .thenReturn(approvedSnapshot);
        when(adapter.createRefund(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForMap("""
                    SELECT status, reviewed_by, reviewed_at
                    FROM platform.refund WHERE refund_no = ?
                    """, REFUND_NO))
                    .containsEntry("status", "SUBMITTING")
                    .containsEntry("reviewed_by", ADMIN_ID);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM platform.admin_audit_log
                    WHERE action = 'REFUND_APPROVED' AND resource_id = ?
                    """, Long.class, REFUND_NO)).isEqualTo(1);
            PaymentAdapter.RefundCommand command = invocation.getArgument(0);
            return providerRefund(ProviderOutcome.PENDING, command.refundNo(), command.amount());
        });

        RefundApplicationService.RefundView approved =
                applicationService.approve(ADMIN_ID, REFUND_NO);

        assertThat(approved.status()).isEqualTo("SUBMITTING");
        assertThat(approved.reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?", REFUND_NO))
                .isEqualTo("PROCESSING");

        jdbc.update("UPDATE platform.refund SET status = 'CANCELED' WHERE refund_no = ?", REFUND_NO);
        assertThatThrownBy(() -> applicationService.approve(ADMIN_ID, REFUND_NO))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_cannot_be_approved");
        verify(adapter, times(1)).createRefund(any());
    }

    @Test
    void synchronousSuccessAtomicallyCreatesOneRevocationEventAndReplayDoesNothing() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.SUCCEEDED, REFUND_NO, 8_500L));

        executionService.submit(REFUND_NO);
        executionService.submit(REFUND_NO);

        assertThat(jdbc.queryForMap("""
                SELECT status, revocation_business_no FROM platform.refund WHERE refund_no = ?
                """, REFUND_NO))
                .containsEntry("status", "REVOKING")
                .containsEntry("revocation_business_no", "refund-revoke:" + REFUND_NO);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.outbox_event
                WHERE event_type = 'PACKAGE_REFUNDED_V1'
                  AND business_key = ?
                """, Long.class, "refund-revoke:" + REFUND_NO)).isEqualTo(1);
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_id, payload::text AS payload
                FROM platform.outbox_event WHERE business_key = ?
                """, "refund-revoke:" + REFUND_NO);
        assertThat(event)
                .containsEntry("aggregate_type", "REFUND")
                .containsEntry("aggregate_id", REFUND_ID);
        assertThat((String) event.get("payload"))
                .contains("\"refundNo\": \"" + REFUND_NO + "\"")
                .contains("\"revocationBusinessNo\": \"refund-revoke:" + REFUND_NO + "\"")
                .contains(GRANT_ID.toString())
                .contains(USER_ID.toString());
        verify(adapter, times(1)).createRefund(any());
    }

    @Test
    void balanceSubmissionSkipsProviderAndPublishesOneRevocationWithoutCreditingWallet() {
        convertFixtureToWalletFunding();
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?",
                REFUND_NO);

        RefundExecutionService.ExecutionResult first = executionService.submit(REFUND_NO);
        RefundExecutionService.ExecutionResult replay = executionService.submit(REFUND_NO);

        assertThat(first).isEqualTo(new RefundExecutionService.ExecutionResult("REVOKING", false));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForMap("""
                SELECT status, revocation_business_no, payment_transaction_id,
                       external_refund_no, provider, provider_status,
                       provider_attempt_count, balance_refund_ledger_id
                FROM platform.refund WHERE refund_no = ?
                """, REFUND_NO))
                .containsEntry("status", "REVOKING")
                .containsEntry("revocation_business_no", "refund-revoke:" + REFUND_NO)
                .containsEntry("payment_transaction_id", null)
                .containsEntry("external_refund_no", null)
                .containsEntry("provider", null)
                .containsEntry("provider_status", null)
                .containsEntry("provider_attempt_count", 0)
                .containsEntry("balance_refund_ledger_id", null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.outbox_event
                WHERE event_type = 'PACKAGE_REFUNDED_V1' AND business_key = ?
                """, Long.class, "refund-revoke:" + REFUND_NO)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT available_amount FROM platform.account_balance WHERE id = ?
                """, Long.class, ACCOUNT_ID)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.balance_ledger
                WHERE entry_type = 'PACKAGE_REFUND' AND business_id = ?
                """, Long.class, REFUND_ID)).isZero();
        verifyNoInteractions(availabilityService);
        verify(adapter, never()).createRefund(any());
        verify(adapter, never()).queryRefund(any());
    }

    @Test
    void acceptedAndTimeoutRemainProcessingAndQueryReusesTheRefundNumber() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.PENDING, REFUND_NO, 8_500L));
        when(adapter.queryRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.SUCCEEDED, REFUND_NO, 8_500L));

        executionService.submit(REFUND_NO);
        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?", REFUND_NO))
                .isEqualTo("PROCESSING");
        executionService.query(REFUND_NO);
        executionService.query(REFUND_NO);

        ArgumentCaptor<PaymentAdapter.RefundCommand> creates =
                ArgumentCaptor.forClass(PaymentAdapter.RefundCommand.class);
        ArgumentCaptor<PaymentAdapter.RefundQuery> queries =
                ArgumentCaptor.forClass(PaymentAdapter.RefundQuery.class);
        verify(adapter).createRefund(creates.capture());
        verify(adapter).queryRefund(queries.capture());
        assertThat(creates.getValue().refundNo()).isEqualTo(REFUND_NO);
        assertThat(queries.getValue().refundNo()).isEqualTo(REFUND_NO);
        verify(adapter, times(1)).queryRefund(any());

        jdbc.update("DELETE FROM platform.outbox_event");
        jdbc.update("UPDATE platform.refund SET status = 'FAILED' WHERE refund_no = ?", REFUND_NO);
        insertSecondRefund("ref_execution_timeout", "SUBMITTING");
        when(adapter.createRefund(any())).thenThrow(new IllegalStateException("provider timeout"));

        executionService.submit("ref_execution_timeout");

        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?",
                "ref_execution_timeout")).isEqualTo("PROCESSING");
    }

    @Test
    void definiteFailureFailsAndReleasesHoldWithoutRevocationEvent() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.FAILED, REFUND_NO, 8_500L));
        when(gatewayRefundClient.release("refund-hold:" + REFUND_NO, GRANT_ID))
                .thenReturn(new GatewayRefundClient.ReleaseResult(
                        "refund-hold:" + REFUND_NO, "RELEASED"));

        executionService.submit(REFUND_NO);

        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?", REFUND_NO))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.outbox_event",
                Long.class)).isZero();
        verify(gatewayRefundClient).release("refund-hold:" + REFUND_NO, GRANT_ID);
    }

    @Test
    void refundNumberMismatchCreatesAnomalyWithoutAdvancingState() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.SUCCEEDED, "ref_provider_other", 8_500L));

        executionService.submit(REFUND_NO);

        assertMismatchRejected();
    }

    @Test
    void amountMismatchCreatesAnomalyWithoutAdvancingState() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.SUCCEEDED, REFUND_NO, 8_499L));

        executionService.submit(REFUND_NO);

        assertMismatchRejected();
    }

    @Test
    void definitiveProviderMismatchDoesNotReleaseTheHold() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.FAILED, "ref_provider_other", 8_500L));

        executionService.submit(REFUND_NO);

        assertMismatchRejected();
    }

    @Test
    void adapterUnavailableBeforeSubmissionKeepsSubmittingForCreateRetry() {
        jdbc.update("UPDATE platform.refund SET status = 'SUBMITTING' WHERE refund_no = ?", REFUND_NO);
        when(availabilityService.requireOperational(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .thenThrow(new IllegalStateException("adapter unavailable"))
                .thenReturn(adapter);
        when(adapter.createRefund(any()))
                .thenReturn(providerRefund(ProviderOutcome.SUCCEEDED, REFUND_NO, 8_500L));

        executionService.submit(REFUND_NO);

        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?", REFUND_NO))
                .isEqualTo("SUBMITTING");
        verify(adapter, never()).createRefund(any());

        executionService.submit(REFUND_NO);

        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?", REFUND_NO))
                .isEqualTo("REVOKING");
        verify(adapter).createRefund(any());
    }

    private void assertMismatchRejected() {
        assertThat(singleString("SELECT status FROM platform.refund WHERE refund_no = ?", REFUND_NO))
                .isEqualTo("SUBMITTING");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'REFUND_PROVIDER_MISMATCH' AND refund_id = ?
                """, Long.class, REFUND_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.outbox_event",
                Long.class)).isZero();
        verify(gatewayRefundClient, never()).release(any(), any());
    }

    private PaymentAdapter.ProviderRefund providerRefund(
            ProviderOutcome outcome, String refundNo, long amount) {
        return new PaymentAdapter.ProviderRefund(
                outcome, refundNo, "external-" + refundNo, amount,
                outcome.name(), outcome == ProviderOutcome.FAILED ? "DECLINED" : null);
    }

    private void insertCommercialFixture(
            String refundStatus, String refundNo, long refundAmount, String refundType)
            throws Exception {
        Instant paidAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Instant requestedAt = paidAt.plus(1, ChronoUnit.HOURS);
        Instant snapshotAt = requestedAt.plus(1, ChronoUnit.MINUTES);
        approvedSnapshot = new GatewayRefundClient.RefundSnapshot(
                "refund-hold:" + refundNo, PACKAGE_ID, "READY", "ACTIVE",
                1_000_000L, 100_000L, 900_000L,
                requestedAt.plus(30, ChronoUnit.DAYS), snapshotAt);
        RefundApplicationService.CalculationSnapshot calculation =
                new RefundApplicationService.CalculationSnapshot(
                        10_001L, paidAt, requestedAt, 1_000_000L, 100_000L, 900_000L,
                        500, 100L, 1_000, 7, 9_000L, 500L, refundAmount, refundType,
                        "BASE_FLOOR_FEE_HALF_UP", 1, snapshotAt, PACKAGE_ID, "ACTIVE",
                        approvedSnapshot.packageExpiresAt(), snapshotAt);
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at,
                    package_snapshot, expires_at, next_close_at, settlement_method)
                VALUES (?, ?, ?, ?, ?, 10001, 'CNY', 'PAID', 'MOCK', ?,
                        '{}'::jsonb, ?, ?, 'EXTERNAL')
                """, ORDER_ID, ORDER_NO, TENANT_ID, USER_ID, VERSION_ID,
                Timestamp.from(paidAt), Timestamp.from(paidAt.plusSeconds(1_800)),
                Timestamp.from(paidAt.plusSeconds(1_800)));
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, payment_product, paid_at, applied_to_order)
                VALUES (?, 'pay_refund_execution', ?, 'MOCK', 'mock-trade-execution',
                        10001, 'SUCCEEDED', 'MOCK', ?, true)
                """, PAYMENT_ID, ORDER_ID, Timestamp.from(paidAt));
        jdbc.update("UPDATE platform.customer_order SET successful_payment_transaction_id = ? WHERE id = ?",
                PAYMENT_ID, ORDER_ID);
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'ORDER', 'paid order', ?)
                """, GRANT_ID, "order:" + ORDER_NO, TENANT_ID, USER_ID, VERSION_ID, USER_ID);
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status,
                    refund_type, policy_version, calculation_snapshot, reason, requested_by,
                    provider, gateway_hold_business_no, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?::jsonb, 'requested', ?, 'MOCK', ?, now(), ?, ?)
                """, REFUND_ID, refundNo, ORDER_ID, PAYMENT_ID, refundAmount, refundStatus,
                refundType, objectMapper.writeValueAsString(calculation), USER_ID,
                "refund-hold:" + refundNo, Timestamp.from(requestedAt), Timestamp.from(requestedAt));
    }

    private void convertFixtureToWalletFunding() {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount, version)
                VALUES (?, ?, ?, 'CNY', 0, 1)
                """, ACCOUNT_ID, TENANT_ID, USER_ID);
        jdbc.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id,
                    entry_type, amount_delta, paid_amount, bonus_amount,
                    balance_before, balance_after, business_type, business_id,
                    business_no, idempotency_key)
                VALUES (?, 'wle_refund_execution_debit', ?, ?, ?,
                        'PACKAGE_PURCHASE', -10001, 0, 0, 10001, 0,
                        'ORDER', ?, ?, ?)
                """, DEBIT_LEDGER_ID, TENANT_ID, USER_ID, ACCOUNT_ID,
                ORDER_ID, ORDER_NO, "wallet-pay:" + ORDER_NO);
        jdbc.update("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = NULL,
                    settlement_method = 'BALANCE', payment_provider = 'UNSELECTED',
                    balance_debit_ledger_id = ?
                WHERE id = ?
                """, DEBIT_LEDGER_ID, ORDER_ID);
        jdbc.update("""
                UPDATE platform.refund
                SET payment_transaction_id = NULL, funding_source = 'BALANCE', provider = NULL
                WHERE id = ?
                """, REFUND_ID);
        jdbc.update("DELETE FROM platform.payment_transaction WHERE id = ?", PAYMENT_ID);
    }

    private void insertSecondRefund(String refundNo, String status) {
        UUID refundId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status,
                    refund_type, policy_version, calculation_snapshot, reason, requested_by,
                    provider, gateway_hold_business_no, next_attempt_at)
                SELECT ?, ?, order_id, payment_transaction_id, amount, ?, refund_type,
                       policy_version, calculation_snapshot, reason, requested_by,
                       provider, ?, now()
                FROM platform.refund WHERE refund_no = ?
                """, refundId, refundNo, status, "refund-hold:" + refundNo, REFUND_NO);
    }

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Refund User', 'ACTIVE')
                """, id, TENANT_ID, email);
    }

    private void insertPolicy() {
        jdbc.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (1, 500, 100, 7, 1000, now() - interval '1 day', true)
                """);
    }

    private String singleString(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }
}
