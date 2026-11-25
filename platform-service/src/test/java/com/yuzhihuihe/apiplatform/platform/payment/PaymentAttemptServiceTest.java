package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
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
        "outbox.publisher.initial-delay-ms=3600000"
})
class PaymentAttemptServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("95000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("95000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("95000000-0000-4000-8000-000000000003");
    private static final UUID STARTER_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final Instant NOW = Instant.parse("2030-03-04T05:06:07Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private StubAdapter mockAdapter;
    private StubAdapter wechatAdapter;
    private PaymentAttemptService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.refund, "
                + "platform.payment_transaction, platform.customer_order, platform.grant_request, "
                + "platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "attempt.user@example.com");
        insertUser(OTHER_USER_ID, "attempt.other@example.com");
        jdbcTemplate.update("""
                UPDATE platform.payment_config SET enabled = true
                WHERE (provider = 'MOCK' AND environment = 'LOCAL')
                   OR (provider = 'WECHAT_PAY' AND environment = 'SANDBOX')
                """);
        mockAdapter = new StubAdapter(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL, PaymentProduct.MOCK);
        wechatAdapter = new StubAdapter(
                PaymentProvider.WECHAT_PAY, PaymentEnvironment.SANDBOX,
                PaymentProduct.WECHAT_NATIVE);
        PaymentAdapterRegistry registry = new PaymentAdapterRegistry(
                List.of(mockAdapter, wechatAdapter));
        meterRegistry = new SimpleMeterRegistry();
        service = new PaymentAttemptService(
                new PaymentAttemptRepository(jdbcTemplate),
                new PaymentAvailabilityService(
                        new PaymentAvailabilityRepository(jdbcTemplate),
                        registry, transactionManager),
                new CommercialTransactionMetrics(meterRegistry),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsProviderMismatchUnownedTerminalAndExpiredOrders() {
        insertOrder("ord_owned", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        assertThatThrownBy(() -> create(
                "ord_owned", PaymentProvider.WECHAT_PAY, PaymentProduct.ALIPAY_PAGE))
                .hasMessageContaining("payment_product_provider_mismatch");

        insertOrder("ord_unowned", OTHER_USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        assertThatThrownBy(() -> create(
                "ord_unowned", PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("order_not_found");

        insertOrder("ord_paid", USER_ID, "PAID", NOW.plusSeconds(900), 1900);
        insertOrder("ord_closed", USER_ID, "CLOSED", NOW.plusSeconds(900), 1900);
        insertOrder("ord_expired", USER_ID, "PENDING", NOW.minusSeconds(1), 1900);
        assertThatThrownBy(() -> create(
                "ord_paid", PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("order_not_payable");
        assertThatThrownBy(() -> create(
                "ord_closed", PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("order_not_payable");
        assertThatThrownBy(() -> create(
                "ord_expired", PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("order_expired");
    }

    @Test
    void copiesOrderFactsAndCallsAdapterOutsideDatabaseTransactions() {
        insertOrder("ord_facts", USER_ID, "PENDING", NOW.plusSeconds(900), 12_345);

        PaymentAttemptService.PaymentAttemptResult result = create(
                "ord_facts", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.action().type()).isEqualTo(PaymentActionType.NONE);
        assertThat(mockAdapter.calledInsideTransaction).isFalse();
        assertThat(mockAdapter.lastCommand.get().amount()).isEqualTo(12_345);
        assertThat(mockAdapter.lastCommand.get().currency()).isEqualTo("CNY");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT amount, provider, payment_product, status, request_payload_hash
                FROM platform.payment_transaction WHERE transaction_no = ?
                """, result.transactionNo()))
                .containsEntry("amount", 12_345L)
                .containsEntry("provider", "MOCK")
                .containsEntry("payment_product", "MOCK")
                .containsEntry("status", "PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT request_payload_hash FROM platform.payment_transaction
                WHERE transaction_no = ?
                """, String.class, result.transactionNo())).matches("[0-9a-f]{64}");
    }

    @Test
    void persistsValidatedPaymentActionsForLaterResume() {
        Instant orderExpiresAt = NOW.plusSeconds(240);
        insertOrder("ord_qr_action", USER_ID, "PENDING", orderExpiresAt, 1900);
        mockAdapter.creation.set(new PaymentAdapter.PaymentCreation(
                ProviderOutcome.PENDING, "qr-external", "WAIT_BUYER_PAY",
                new PaymentAdapter.PaymentAction(
                        PaymentActionType.QR_CODE, null, Map.of(), "weixin://pay/qr-test",
                        NOW.plusSeconds(300))));

        PaymentAttemptService.PaymentAttemptResult qrResult = create(
                "ord_qr_action", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(qrResult.action().expiresAt()).isEqualTo(orderExpiresAt);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT payment_action ->> 'type' AS type,
                       payment_action ->> 'qrContent' AS qr_content,
                       payment_action ->> 'expiresAt' AS expires_at
                FROM platform.payment_transaction WHERE transaction_no = ?
                """, qrResult.transactionNo()))
                .containsEntry("type", "QR_CODE")
                .containsEntry("qr_content", "weixin://pay/qr-test")
                .containsEntry("expires_at", orderExpiresAt.toString());

        insertOrder("ord_form_action", USER_ID, "PENDING", orderExpiresAt, 1900);
        mockAdapter.creation.set(new PaymentAdapter.PaymentCreation(
                ProviderOutcome.PENDING, "form-external", "WAIT_BUYER_PAY",
                new PaymentAdapter.PaymentAction(
                        PaymentActionType.HTML_FORM, URI.create("https://pay.example.test/submit"),
                        Map.of("app_id", "test-app", "sign", "test-sign"), null,
                        NOW.plusSeconds(120))));

        PaymentAttemptService.PaymentAttemptResult formResult = create(
                "ord_form_action", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT payment_action ->> 'type' AS type,
                       payment_action ->> 'targetUrl' AS target_url,
                       payment_action -> 'fields' ->> 'sign' AS sign
                FROM platform.payment_transaction WHERE transaction_no = ?
                """, formResult.transactionNo()))
                .containsEntry("type", "HTML_FORM")
                .containsEntry("target_url", "https://pay.example.test/submit")
                .containsEntry("sign", "test-sign");
    }

    @Test
    void concurrentRequestsCreateOnlyOneActiveAttempt() throws Exception {
        insertOrder("ord_concurrent", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(
                    () -> concurrentCreate(ready, start, "ord_concurrent"));
            Future<Object> second = executor.submit(
                    () -> concurrentCreate(ready, start, "ord_concurrent"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(
                    PaymentAttemptService.PaymentAttemptResult.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(Throwable.class::isInstance)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM platform.payment_transaction
                    WHERE status IN ('CREATED', 'PENDING')
                    """, Integer.class)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedAndClosedAttemptsCanRetryAndSwitchProvider() {
        insertOrder("ord_retry", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        PaymentAttemptService.PaymentAttemptResult first = create(
                "ord_retry", PaymentProvider.MOCK, PaymentProduct.MOCK);
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction SET status = 'FAILED'
                WHERE transaction_no = ?
                """, first.transactionNo());

        PaymentAttemptService.PaymentAttemptResult second = create(
                "ord_retry", PaymentProvider.WECHAT_PAY, PaymentProduct.WECHAT_NATIVE);
        jdbcTemplate.update("""
                UPDATE platform.payment_transaction SET status = 'CLOSED'
                WHERE transaction_no = ?
                """, second.transactionNo());
        PaymentAttemptService.PaymentAttemptResult third = create(
                "ord_retry", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(List.of(first.transactionNo(), second.transactionNo(), third.transactionNo()))
                .doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.payment_transaction
                WHERE order_id = (SELECT id FROM platform.customer_order WHERE order_no = 'ord_retry')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void uncertainCreationStaysPendingAndDefiniteFailureIsFailed() {
        insertOrder("ord_uncertain", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        mockAdapter.creation.set(creation(ProviderOutcome.UNCERTAIN, "TIMEOUT"));
        PaymentAttemptService.PaymentAttemptResult uncertain = create(
                "ord_uncertain", PaymentProvider.MOCK, PaymentProduct.MOCK);
        assertThat(uncertain.status()).isEqualTo("PENDING");

        insertOrder("ord_failed", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        mockAdapter.creation.set(creation(ProviderOutcome.FAILED, "REJECTED"));
        PaymentAttemptService.PaymentAttemptResult failed = create(
                "ord_failed", PaymentProvider.MOCK, PaymentProduct.MOCK);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM platform.payment_transaction WHERE transaction_no = ?
                """, String.class, failed.transactionNo())).isEqualTo("FAILED");
    }

    @Test
    void providerOutcomesEmitBoundedPaymentResultMetrics() {
        insertOrder("ord_metric_pending", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        create("ord_metric_pending", PaymentProvider.MOCK, PaymentProduct.MOCK);

        insertOrder("ord_metric_failed", USER_ID, "PENDING", NOW.plusSeconds(900), 1900);
        mockAdapter.creation.set(creation(ProviderOutcome.FAILED, "REJECTED"));
        create("ord_metric_failed", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(meterRegistry.get("platform.payment.result")
                .tags("provider", "MOCK", "product", "MOCK", "outcome", "PENDING")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("platform.payment.result")
                .tags("provider", "MOCK", "product", "MOCK", "outcome", "FAILED")
                .counter().count()).isEqualTo(1);
    }

    private Object concurrentCreate(
            CountDownLatch ready,
            CountDownLatch start,
            String orderNo) {
        try {
            ready.countDown();
            start.await();
            return create(orderNo, PaymentProvider.MOCK, PaymentProduct.MOCK);
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private PaymentAttemptService.PaymentAttemptResult create(
            String orderNo,
            PaymentProvider provider,
            PaymentProduct product) {
        return service.create(
                TENANT_ID, USER_ID, orderNo,
                new PaymentAttemptService.CreatePaymentAttemptRequest(provider, product),
                "127.0.0.1");
    }

    private void insertUser(UUID userId, String email) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'test-password-hash', 'Payment User', 'ACTIVE')
                """, userId, TENANT_ID, email);
    }

    private void insertOrder(
            String orderNo,
            UUID userId,
            String status,
            Instant expiresAt,
            long amount) {
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, expires_at,
                    package_snapshot, next_close_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', ?, 'UNSELECTED', ?,
                        '{"packageCode":"STARTER"}'::jsonb, ?)
                """, UUID.randomUUID(), orderNo, TENANT_ID, userId, STARTER_VERSION_ID,
                amount, status, Timestamp.from(expiresAt), Timestamp.from(expiresAt));
    }

    private static PaymentAdapter.PaymentCreation creation(
            ProviderOutcome outcome,
            String providerStatus) {
        return new PaymentAdapter.PaymentCreation(
                outcome,
                outcome == ProviderOutcome.FAILED ? null : "mock-external-trade",
                providerStatus,
                new PaymentAdapter.PaymentAction(
                        PaymentActionType.NONE, null, Map.of(), null, NOW.plusSeconds(300)));
    }

    private static final class StubAdapter implements PaymentAdapter {
        private final PaymentProvider provider;
        private final PaymentEnvironment environment;
        private final PaymentProduct product;
        private final AtomicReference<PaymentCreation> creation = new AtomicReference<>(
                PaymentAttemptServiceTest.creation(ProviderOutcome.PENDING, "WAIT_BUYER_PAY"));
        private final AtomicReference<PaymentCommand> lastCommand = new AtomicReference<>();
        private final AtomicBoolean calledInsideTransaction = new AtomicBoolean();

        private StubAdapter(
                PaymentProvider provider,
                PaymentEnvironment environment,
                PaymentProduct product) {
            this.provider = provider;
            this.environment = environment;
            this.product = product;
        }

        @Override
        public PaymentProvider provider() {
            return provider;
        }

        @Override
        public PaymentEnvironment environment() {
            return environment;
        }

        @Override
        public Set<PaymentProduct> products() {
            return Set.of(product);
        }

        @Override
        public AdapterConfiguration configuration() {
            return new AdapterConfiguration(true, provider.name().toLowerCase() + "-fingerprint",
                    "VALID", NOW);
        }

        @Override
        public PaymentCreation createPayment(PaymentCommand command) {
            calledInsideTransaction.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            lastCommand.set(command);
            PaymentCreation configured = creation.get();
            return new PaymentCreation(
                    configured.outcome(),
                    configured.externalTradeNo() == null
                            ? null : "external-" + command.transactionNo(),
                    configured.providerStatus(), configured.action());
        }

        @Override
        public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
            return null;
        }

        @Override
        public ProviderPayment queryPayment(PaymentQuery query) {
            return null;
        }

        @Override
        public ProviderPayment closePayment(PaymentQuery query) {
            return null;
        }

        @Override
        public ProviderRefund createRefund(RefundCommand command) {
            return null;
        }

        @Override
        public ProviderRefund queryRefund(RefundQuery query) {
            return null;
        }

        @Override
        public BillDownload downloadBill(LocalDate billDate) {
            return null;
        }
    }
}
