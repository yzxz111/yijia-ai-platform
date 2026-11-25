package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapterRegistry;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentServiceTestFactory;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
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
class RechargePaymentAttemptServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("96000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2030-03-04T05:06:07Z");

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
    PlatformTransactionManager transactionManager;

    private StubAdapter mockAdapter;
    private StubAdapter wechatAdapter;
    private StubAdapter alipayAdapter;
    private RechargePaymentAttemptService service;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.sms_proof, platform.sms_delivery, "
                + "platform.admin_audit_log, platform.payment_transaction, "
                + "platform.recharge_order, platform.account_balance, platform.refresh_token, "
                + "platform.user_role, platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, false);
        insertUser(OTHER_USER_ID, false);
        jdbc.update("""
                UPDATE platform.payment_config SET enabled = false
                """);
        jdbc.update("""
                UPDATE platform.payment_config SET enabled = true
                WHERE (provider = 'MOCK' AND environment = 'LOCAL')
                   OR (provider = 'WECHAT_PAY' AND environment = 'SANDBOX')
                   OR (provider = 'ALIPAY' AND environment = 'SANDBOX')
                """);
        mockAdapter = new StubAdapter(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL, PaymentProduct.MOCK);
        wechatAdapter = new StubAdapter(
                PaymentProvider.WECHAT_PAY, PaymentEnvironment.SANDBOX,
                PaymentProduct.WECHAT_NATIVE);
        alipayAdapter = new StubAdapter(
                PaymentProvider.ALIPAY, PaymentEnvironment.SANDBOX,
                PaymentProduct.ALIPAY_PAGE);
        service = service(null);
    }

    @Test
    void acceptsOnlyOwnedPendingNonExpiredRechargeOrders() {
        insertRecharge("rch_owned", USER_ID, "PENDING", NOW.plusSeconds(900), 1_900);
        assertThat(create("rch_owned", PaymentProvider.MOCK, PaymentProduct.MOCK).status())
                .isEqualTo("PENDING");

        insertRecharge("rch_other", OTHER_USER_ID, "PENDING", NOW.plusSeconds(900), 1_900);
        assertThatThrownBy(() -> create(
                "rch_other", PaymentProvider.MOCK, PaymentProduct.MOCK))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("recharge_order_not_found");

        for (String status : List.of("PAID", "CLOSED", "FAILED")) {
            String rechargeNo = "rch_" + status.toLowerCase();
            insertRecharge(rechargeNo, USER_ID, status, NOW.plusSeconds(900), 1_900);
            assertThatThrownBy(() -> create(
                    rechargeNo, PaymentProvider.MOCK, PaymentProduct.MOCK))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409 CONFLICT")
                    .hasMessageContaining("recharge_order_not_payable");
        }
        insertRecharge("rch_expired", USER_ID, "PENDING", NOW.minusSeconds(1), 1_900);
        assertThatThrownBy(() -> create(
                "rch_expired", PaymentProvider.MOCK, PaymentProduct.MOCK))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT")
                .hasMessageContaining("recharge_order_not_payable");
    }

    @Test
    void requiresExactEnabledProviderProductConfiguration() {
        insertRecharge("rch_wechat", USER_ID, "PENDING", NOW.plusSeconds(900), 2_000);
        create("rch_wechat", PaymentProvider.WECHAT_PAY, PaymentProduct.WECHAT_NATIVE);
        assertThat(wechatAdapter.invocations()).isEqualTo(1);

        insertRecharge("rch_alipay", USER_ID, "PENDING", NOW.plusSeconds(900), 2_100);
        create("rch_alipay", PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE);
        assertThat(alipayAdapter.invocations()).isEqualTo(1);

        insertRecharge("rch_mismatch", USER_ID, "PENDING", NOW.plusSeconds(900), 2_200);
        assertThatThrownBy(() -> create(
                "rch_mismatch", PaymentProvider.WECHAT_PAY, PaymentProduct.ALIPAY_PAGE))
                .hasMessageContaining("payment_product_provider_mismatch");

        jdbc.update("""
                UPDATE platform.payment_config SET enabled = false
                WHERE provider = 'WECHAT_PAY' AND environment = 'SANDBOX'
                """);
        insertRecharge("rch_disabled", USER_ID, "PENDING", NOW.plusSeconds(900), 2_300);
        assertThatThrownBy(() -> create(
                "rch_disabled", PaymentProvider.WECHAT_PAY, PaymentProduct.WECHAT_NATIVE))
                .hasMessageContaining("payment_checkout_unavailable");
    }

    @Test
    void consumesPaymentConfirmationProofOnceForPhoneVerifiedUser() {
        jdbc.update("""
                UPDATE platform.app_user
                SET phone_ciphertext = ?, phone_lookup_hash = ?, phone_last4 = '8000',
                    phone_verified_at = now()
                WHERE id = ?
                """, new byte[] {1, 2, 3}, "0".repeat(64), USER_ID);
        insertRecharge("rch_proof", USER_ID, "PENDING", NOW.plusSeconds(900), 2_400);
        SmsProofService proofs = mock(SmsProofService.class);
        when(proofs.consume(
                "proof-token", TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM))
                .thenReturn(new SmsProofService.ProofClaims(
                        TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM));
        service = service(proofs);

        RechargePaymentAttemptService.PaymentAttemptResult result = service.create(
                TENANT_ID, USER_ID, "rch_proof",
                new RechargePaymentAttemptService.CreatePaymentAttemptRequest(
                        PaymentProvider.MOCK, PaymentProduct.MOCK, "proof-token"),
                "127.0.0.1");

        assertThat(result.status()).isEqualTo("PENDING");
        verify(proofs, times(1)).consume(
                "proof-token", TENANT_ID, USER_ID, SmsPurpose.PAYMENT_CONFIRM);
    }

    @Test
    void persistsCanonicalRechargeOwnershipAndCallsAdapterOutsideTransaction() {
        Instant expiresAt = NOW.plusSeconds(900);
        insertRecharge("rch_facts", USER_ID, "PENDING", expiresAt, 12_345);

        RechargePaymentAttemptService.PaymentAttemptResult result = create(
                "rch_facts", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(mockAdapter.calledInsideTransaction()).isFalse();
        assertThat(mockAdapter.lastCommand().orderNo()).isEqualTo("rch_facts");
        assertThat(mockAdapter.lastCommand().amount()).isEqualTo(12_345);
        assertThat(mockAdapter.lastCommand().currency()).isEqualTo("CNY");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT payment.order_id, payment.recharge_order_id,
                       recharge.tenant_id, recharge.user_id, payment.provider,
                       payment.payment_product, payment.amount, payment.status,
                       payment.request_payload_hash
                FROM platform.payment_transaction AS payment
                JOIN platform.recharge_order AS recharge
                  ON recharge.id = payment.recharge_order_id
                WHERE payment.transaction_no = ?
                """, result.transactionNo());
        assertThat(row.get("order_id")).isNull();
        assertThat(row)
                .containsEntry("tenant_id", TENANT_ID)
                .containsEntry("user_id", USER_ID)
                .containsEntry("provider", "MOCK")
                .containsEntry("payment_product", "MOCK")
                .containsEntry("amount", 12_345L)
                .containsEntry("status", "PENDING")
                .containsEntry("request_payload_hash", sha256(String.join("\n",
                        result.transactionNo(), "rch_facts", "12345", "CNY", "MOCK", "MOCK",
                        expiresAt.toString())));
    }

    @Test
    void permitsOneActiveAttemptAndAllowsRetryAfterTerminalAttempt() throws Exception {
        insertRecharge("rch_concurrent", USER_ID, "PENDING", NOW.plusSeconds(900), 1_900);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(
                    () -> concurrentCreate(ready, start, "rch_concurrent"));
            Future<Object> second = executor.submit(
                    () -> concurrentCreate(ready, start, "rch_concurrent"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(
                    RechargePaymentAttemptService.PaymentAttemptResult.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(Throwable.class::isInstance)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.payment_transaction
                WHERE recharge_order_id = (
                    SELECT id FROM platform.recharge_order WHERE recharge_no = 'rch_concurrent')
                  AND status IN ('CREATED', 'PENDING')
                """, Integer.class)).isEqualTo(1);

        jdbc.update("""
                UPDATE platform.payment_transaction SET status = 'FAILED'
                WHERE recharge_order_id = (
                    SELECT id FROM platform.recharge_order WHERE recharge_no = 'rch_concurrent')
                """);
        RechargePaymentAttemptService.PaymentAttemptResult retry = service.create(
                TENANT_ID, USER_ID, "rch_concurrent",
                new RechargePaymentAttemptService.CreatePaymentAttemptRequest(
                        PaymentProvider.ALIPAY, PaymentProduct.ALIPAY_PAGE),
                "127.0.0.1");
        assertThat(retry.status()).isEqualTo("PENDING");
    }

    @Test
    void adapterExceptionLeavesCoherentPendingTransactionAndRecharge() {
        insertRecharge("rch_exception", USER_ID, "PENDING", NOW.plusSeconds(900), 2_500);
        mockAdapter.failure.set(new IllegalStateException("provider unavailable"));

        RechargePaymentAttemptService.PaymentAttemptResult result = create(
                "rch_exception", PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForMap("""
                SELECT provider_status, status FROM platform.payment_transaction
                WHERE transaction_no = ?
                """, result.transactionNo()))
                .containsEntry("provider_status", "ADAPTER_EXCEPTION")
                .containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM platform.recharge_order WHERE recharge_no = 'rch_exception'
                """, String.class)).isEqualTo("PENDING");
    }

    private RechargePaymentAttemptService service(SmsProofService proofs) {
        PaymentAdapterRegistry registry = new PaymentAdapterRegistry(
                List.of(mockAdapter, wechatAdapter, alipayAdapter));
        return WalletServiceTestFactory.rechargePaymentAttempt(
                jdbc,
                PaymentServiceTestFactory.availability(jdbc, registry, transactionManager),
                new CommercialTransactionMetrics(new SimpleMeterRegistry()),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC),
                proofs);
    }

    private RechargePaymentAttemptService.PaymentAttemptResult create(
            String rechargeNo, PaymentProvider provider, PaymentProduct product) {
        return service.create(
                TENANT_ID, USER_ID, rechargeNo,
                new RechargePaymentAttemptService.CreatePaymentAttemptRequest(provider, product),
                "127.0.0.1");
    }

    private Object concurrentCreate(
            CountDownLatch ready, CountDownLatch start, String rechargeNo) {
        try {
            ready.countDown();
            start.await();
            return create(rechargeNo, PaymentProvider.MOCK, PaymentProduct.MOCK);
        } catch (Throwable error) {
            return error;
        }
    }

    private void insertUser(UUID userId, boolean phoneVerified) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status,
                    phone_verified_at)
                VALUES (?, ?, ?, 'hash', 'Recharge Attempt User', 'ACTIVE', ?)
                """, userId, TENANT_ID, userId + "@example.test",
                phoneVerified ? Timestamp.from(NOW) : null);
    }

    private void insertRecharge(
            String rechargeNo, UUID userId, String status, Instant expiresAt, long amount) {
        Instant createdAt = expiresAt.minusSeconds(900);
        Timestamp paidAt = "PAID".equals(status)
                ? Timestamp.from(createdAt.plusSeconds(1)) : null;
        Timestamp closedAt = "CLOSED".equals(status)
                ? Timestamp.from(createdAt.plusSeconds(1)) : null;
        jdbc.update("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key,
                    expires_at, paid_at, closed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'CNY', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), rechargeNo, TENANT_ID, userId, amount, amount,
                status, "idem_" + rechargeNo, Timestamp.from(expiresAt), paidAt, closedAt,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class StubAdapter implements PaymentAdapter {
        private final PaymentProvider provider;
        private final PaymentEnvironment environment;
        private final PaymentProduct product;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<PaymentCommand> command = new AtomicReference<>();
        private final AtomicBoolean calledInsideTransaction = new AtomicBoolean();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

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
            return new AdapterConfiguration(
                    true, provider.name().toLowerCase() + "-fingerprint", "VALID", NOW);
        }

        @Override
        public PaymentCreation createPayment(PaymentCommand paymentCommand) {
            invocations.incrementAndGet();
            calledInsideTransaction.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            command.set(paymentCommand);
            RuntimeException configuredFailure = failure.get();
            if (configuredFailure != null) {
                throw configuredFailure;
            }
            return new PaymentCreation(
                    ProviderOutcome.PENDING,
                    "external-" + paymentCommand.transactionNo(),
                    "WAIT_BUYER_PAY",
                    new PaymentAction(
                            PaymentActionType.NONE, null, Map.of(), null,
                            NOW.plusSeconds(300)));
        }

        int invocations() {
            return invocations.get();
        }

        PaymentCommand lastCommand() {
            return command.get();
        }

        boolean calledInsideTransaction() {
            return calledInsideTransaction.get();
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
