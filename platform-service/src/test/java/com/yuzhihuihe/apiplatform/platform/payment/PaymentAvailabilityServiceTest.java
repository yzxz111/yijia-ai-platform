package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class PaymentAvailabilityServiceTest {

    private static final Instant VALIDATED_AT = Instant.parse("2030-02-03T04:05:06Z");

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

    private StubAdapter adapter;
    private PaymentAvailabilityService service;

    @BeforeEach
    void resetConfiguration() {
        jdbcTemplate.update("""
                UPDATE platform.payment_config
                SET enabled = false, encrypted_credentials = decode('010203', 'hex'),
                    credential_fingerprint = NULL, last_validation_status = NULL,
                    last_validated_at = NULL
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);
        adapter = new StubAdapter();
        service = new PaymentAvailabilityService(
                new PaymentAvailabilityRepository(jdbcTemplate),
                new PaymentAdapterRegistry(List.of(adapter)), transactionManager);
    }

    @Test
    void checkoutRequiresBothHealthyAdapterAndDatabaseEnablement() {
        assertThatThrownBy(() -> service.requireCheckout(
                PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("payment_checkout_unavailable");

        jdbcTemplate.update("""
                UPDATE platform.payment_config SET enabled = true
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);
        assertThat(service.requireCheckout(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .isSameAs(adapter);

        PaymentAvailabilityService missingAdapter = new PaymentAvailabilityService(
                new PaymentAvailabilityRepository(jdbcTemplate),
                new PaymentAdapterRegistry(List.of()), transactionManager);
        assertThatThrownBy(() -> missingAdapter.requireCheckout(
                PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("payment_adapter_not_configured");
    }

    @Test
    void checkoutMapsRegistryRejectionsToUnprocessableEntity() {
        assertThatThrownBy(() -> service.requireCheckout(
                PaymentProvider.WECHAT_PAY, PaymentProduct.ALIPAY_PAGE))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(422);
                    assertThat(exception).hasMessageContaining(
                            "payment_product_provider_mismatch");
                });
    }

    @Test
    void synchronizationStoresOnlySafeConfigurationMetadata() {
        jdbcTemplate.update("""
                UPDATE platform.payment_config SET enabled = true
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);

        service.requireCheckout(PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT credential_fingerprint, last_validation_status,
                       last_validated_at, encode(encrypted_credentials, 'hex') AS credentials
                FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """))
                .containsEntry("credential_fingerprint", "mock-fingerprint")
                .containsEntry("last_validation_status", "VALID")
                .containsEntry("credentials", "010203");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_validated_at FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """, java.time.OffsetDateTime.class).toInstant()).isEqualTo(VALIDATED_AT);
    }

    @Test
    void unchangedSafeMetadataDoesNotRewriteTheConfigurationRow() {
        Instant originalUpdatedAt = Instant.parse("2020-01-02T03:04:05Z");
        jdbcTemplate.update("""
                UPDATE platform.payment_config
                SET enabled = true, credential_fingerprint = 'mock-fingerprint',
                    last_validation_status = 'VALID', last_validated_at = ?, updated_at = ?
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """, java.sql.Timestamp.from(VALIDATED_AT),
                java.sql.Timestamp.from(originalUpdatedAt));

        service.requireCheckout(PaymentProvider.MOCK, PaymentProduct.MOCK);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT updated_at FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """, java.time.OffsetDateTime.class).toInstant()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void unhealthyAdapterCannotBeEnabled() {
        adapter.configuration.set(new PaymentAdapter.AdapterConfiguration(
                true, "mock-fingerprint", "INVALID", VALIDATED_AT));

        assertThatThrownBy(() -> service.assertCanEnable(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL))
                .hasMessageContaining("payment_adapter_unhealthy");
    }

    @Test
    void disabledCheckoutKeepsConfiguredAdapterOperational() {
        assertThat(service.requireOperational(PaymentProvider.MOCK, PaymentProduct.MOCK))
                .isSameAs(adapter);
        assertThatThrownBy(() -> service.requireCheckout(
                PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("payment_checkout_unavailable");

        adapter.configuration.set(new PaymentAdapter.AdapterConfiguration(
                false, null, null, null));
        assertThatThrownBy(() -> service.requireOperational(
                PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("payment_adapter_not_configured");
    }

    @Test
    void unconfiguredOperationalAdapterClearsStaleReadinessWithoutCredentials() {
        jdbcTemplate.update("""
                UPDATE platform.payment_config
                SET credential_fingerprint = 'stale-fingerprint',
                    last_validation_status = 'VALID', last_validated_at = now()
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);
        adapter.configuration.set(new PaymentAdapter.AdapterConfiguration(
                false, null, null, null));

        assertThatThrownBy(() -> service.requireOperational(
                PaymentProvider.MOCK, PaymentProduct.MOCK))
                .hasMessageContaining("payment_adapter_not_configured");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT credential_fingerprint, last_validation_status, last_validated_at,
                       encode(encrypted_credentials, 'hex') AS credentials
                FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """))
                .containsEntry("credential_fingerprint", null)
                .containsEntry("last_validation_status", null)
                .containsEntry("last_validated_at", null)
                .containsEntry("credentials", "010203");
    }

    @Test
    void rejectedAvailabilityCheckCommitsSafeMetadataOutsideCallerRollback() {
        jdbcTemplate.update("""
                UPDATE platform.payment_config
                SET credential_fingerprint = 'stale-fingerprint',
                    last_validation_status = 'VALID', last_validated_at = now()
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);
        adapter.configuration.set(new PaymentAdapter.AdapterConfiguration(
                false, null, null, null));
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(ignored ->
                service.requireOperational(PaymentProvider.MOCK, PaymentProduct.MOCK)))
                .hasMessageContaining("payment_adapter_not_configured");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT credential_fingerprint, last_validation_status, last_validated_at,
                       encode(encrypted_credentials, 'hex') AS credentials
                FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """))
                .containsEntry("credential_fingerprint", null)
                .containsEntry("last_validation_status", null)
                .containsEntry("last_validated_at", null)
                .containsEntry("credentials", "010203");
    }

    private static final class StubAdapter implements PaymentAdapter {
        private final AtomicReference<AdapterConfiguration> configuration = new AtomicReference<>(
                new AdapterConfiguration(true, "mock-fingerprint", "VALID", VALIDATED_AT));

        @Override
        public PaymentProvider provider() {
            return PaymentProvider.MOCK;
        }

        @Override
        public PaymentEnvironment environment() {
            return PaymentEnvironment.LOCAL;
        }

        @Override
        public Set<PaymentProduct> products() {
            return Set.of(PaymentProduct.MOCK);
        }

        @Override
        public AdapterConfiguration configuration() {
            return configuration.get();
        }

        @Override
        public PaymentCreation createPayment(PaymentCommand command) {
            return null;
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
