package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "reconciliation.job-enabled=false"
})
class PaymentAnomalyServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000003");
    private static final UUID ORDER_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000004");
    private static final UUID TRANSACTION_ID =
            UUID.fromString("51000000-0000-4000-8000-000000000005");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");

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
    PaymentAnomalyService service;

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE platform.payment_anomaly, platform.admin_audit_log, "
                + "platform.refund, platform.payment_transaction, platform.customer_order, "
                + "platform.refresh_token, platform.user_role, platform.invitation, "
                + "platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "anomaly-user@example.com");
        insertUser(ADMIN_ID, "anomaly-admin@example.com");
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status)
                VALUES (?, 'ord_anomaly', ?, ?, ?, 2990, 'CNY', 'PENDING')
                """, ORDER_ID, TENANT_ID, USER_ID, VERSION_ID);
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status, payment_product)
                VALUES (?, 'pay_anomaly', ?, 'WECHAT_PAY', 2990, 'PENDING', 'WECHAT_NATIVE')
                """, TRANSACTION_ID, ORDER_ID);
    }

    @Test
    void deterministicallyDeduplicatesAndPersistsOnlyAllowlistedFacts() {
        Map<String, Object> firstFacts = new LinkedHashMap<>();
        firstFacts.put("expectedAmount", 2990L);
        firstFacts.put("actualAmount", 3990L);
        Map<String, Object> reorderedFacts = new LinkedHashMap<>();
        reorderedFacts.put("actualAmount", 3990L);
        reorderedFacts.put("expectedAmount", 2990L);

        service.record(new PaymentAnomalyService.AnomalyCommand(
                "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                ORDER_ID, TRANSACTION_ID, null, firstFacts));
        service.record(new PaymentAnomalyService.AnomalyCommand(
                "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                ORDER_ID, TRANSACTION_ID, null, reorderedFacts));
        service.record(new PaymentAnomalyService.AnomalyCommand(
                "PAYMENT_STATUS_MISMATCH", PaymentProvider.WECHAT_PAY,
                ORDER_ID, TRANSACTION_ID, null,
                Map.of("expectedStatus", "SUCCEEDED", "actualStatus", "PENDING")));
        service.record(new PaymentAnomalyService.AnomalyCommand(
                "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.ALIPAY,
                ORDER_ID, TRANSACTION_ID, null,
                Map.of("expectedAmount", 2991L, "actualAmount", 3990L)));
        UUID otherOrderId = UUID.randomUUID();
        UUID otherTransactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status)
                VALUES (?, 'ord_anomaly_other', ?, ?, ?, 2990, 'CNY', 'PENDING')
                """, otherOrderId, TENANT_ID, USER_ID, VERSION_ID);
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status, payment_product)
                VALUES (?, 'pay_anomaly_other', ?, 'WECHAT_PAY', 2990, 'PENDING', 'WECHAT_NATIVE')
                """, otherTransactionId, otherOrderId);
        service.record(new PaymentAnomalyService.AnomalyCommand(
                "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                otherOrderId, otherTransactionId, null,
                Map.of("expectedAmount", 2992L, "actualAmount", 3990L)));
        PaymentAnomalyService.AnomalyView resolvedDecoy = service.record(
                new PaymentAnomalyService.AnomalyCommand(
                        "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                        ORDER_ID, TRANSACTION_ID, null,
                        Map.of("expectedAmount", 2993L, "actualAmount", 3990L)));
        jdbc.update("UPDATE platform.payment_anomaly SET status = 'RESOLVED' WHERE id = ?",
                resolvedDecoy.id());
        PaymentAnomalyService.AnomalyView oldDecoy = service.record(
                new PaymentAnomalyService.AnomalyCommand(
                        "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                        ORDER_ID, TRANSACTION_ID, null,
                        Map.of("expectedAmount", 2994L, "actualAmount", 3990L)));
        jdbc.update("""
                UPDATE platform.payment_anomaly
                SET created_at = now() - interval '1 day' WHERE id = ?
                """, oldDecoy.id());

        PaymentAnomalyService.PageResult<PaymentAnomalyService.AnomalyView> result =
                service.list(new PaymentAnomalyService.AnomalyFilters(
                        0, 20, "PAYMENT_AMOUNT_MISMATCH", "OPEN",
                        PaymentProvider.WECHAT_PAY,
                        Instant.now().minusSeconds(60), Instant.now().plusSeconds(60),
                        ORDER_ID, "createdAt", "desc"));
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.fingerprint()).matches("[0-9a-f]{64}");
            assertThat(item.detail().fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("expectedAmount", "actualAmount");
        });
    }

    @Test
    void rejectsSecretsRawCallbacksClientActionsAndNestedDetail() {
        for (String key : new String[]{"secret", "rawCallback", "clientAction", "targetUrl"}) {
            assertThatThrownBy(() -> service.record(
                    new PaymentAnomalyService.AnomalyCommand(
                            "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                            ORDER_ID, TRANSACTION_ID, null, Map.of(key, "sensitive"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("anomaly detail");
        }
        assertThatThrownBy(() -> service.record(
                new PaymentAnomalyService.AnomalyCommand(
                        "PAYMENT_AMOUNT_MISMATCH", PaymentProvider.WECHAT_PAY,
                        ORDER_ID, TRANSACTION_ID, null,
                        Map.of("reason", Map.of("payload", "raw")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anomaly detail");
        assertThatThrownBy(() -> service.record(
                new PaymentAnomalyService.AnomalyCommand(
                        "PAYMENT_QUERY_UNCERTAIN", PaymentProvider.WECHAT_PAY,
                        ORDER_ID, TRANSACTION_ID, null,
                        Map.of("reason", "provider message containing secret=abc"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anomaly detail");
    }

    @Test
    void filtersAndOptimisticallyTransitionsAssignmentAndResolutionWithAudit() {
        PaymentAnomalyService.AnomalyView created = service.record(
                new PaymentAnomalyService.AnomalyCommand(
                        "PAYMENT_QUERY_UNCERTAIN", PaymentProvider.WECHAT_PAY,
                        ORDER_ID, TRANSACTION_ID, null,
                        Map.of("reason", "QUERY_UNCERTAIN")));

        PaymentAnomalyService.AnomalyView investigating = service.update(
                created.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", ADMIN_ID, null, created.lockVersion()),
                ADMIN_ID);
        assertThat(investigating.status()).isEqualTo("INVESTIGATING");
        assertThat(investigating.assignee()).isEqualTo(ADMIN_ID);
        assertThat(investigating.resolvedAt()).isNull();

        assertThatThrownBy(() -> service.update(
                created.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", ADMIN_ID, "provider refund issued", created.lockVersion()),
                ADMIN_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(409);
                    assertThat(exception).hasMessageContaining("payment_anomaly_version_conflict");
                });

        PaymentAnomalyService.AnomalyView resolved = service.update(
                created.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", ADMIN_ID, "provider refund issued",
                        investigating.lockVersion()),
                ADMIN_ID);
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolutionNote()).isEqualTo("provider refund issued");
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(service.list(new PaymentAnomalyService.AnomalyFilters(
                0, 20, null, "RESOLVED", null, null, null,
                ORDER_ID, "updatedAt", "asc")).items()).singleElement();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'PAYMENT_ANOMALY_UPDATED'
                  AND resource_id = ?
                  AND detail #>> '{oldStatus}' = 'INVESTIGATING'
                  AND detail #>> '{newStatus}' = 'RESOLVED'
                  AND detail::text NOT LIKE '%provider refund issued%'
                """, Long.class, ADMIN_ID, created.id().toString())).isEqualTo(1);
    }

    @Test
    void enforcesForwardOnlyStateTransitions() {
        PaymentAnomalyService.AnomalyView open = record("DIRECT_RESOLVE");
        assertUnprocessable(() -> service.update(
                open.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", ADMIN_ID, "must investigate first", open.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_transition");

        PaymentAnomalyService.AnomalyView investigating = service.update(
                open.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", ADMIN_ID, null, open.lockVersion()),
                ADMIN_ID);
        PaymentAnomalyService.AnomalyView resolved = service.update(
                open.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", ADMIN_ID, "confirmed", investigating.lockVersion()),
                ADMIN_ID);

        assertUnprocessable(() -> service.update(
                resolved.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "OPEN", null, null, resolved.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_transition");
    }

    @Test
    void enforcesAssignmentAndResolutionNoteInvariants() {
        PaymentAnomalyService.AnomalyView unassigned = record("UNASSIGNED_INVESTIGATION");
        assertUnprocessable(() -> service.update(
                unassigned.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", null, null, unassigned.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_assignment");

        PaymentAnomalyService.AnomalyView noted = record("NOTED_INVESTIGATION");
        assertUnprocessable(() -> service.update(
                noted.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", ADMIN_ID, "too early", noted.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_resolution");

        PaymentAnomalyService.AnomalyView open = record("MISSING_RESOLUTION_NOTE");
        PaymentAnomalyService.AnomalyView investigating = service.update(
                open.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", ADMIN_ID, null, open.lockVersion()),
                ADMIN_ID);
        assertUnprocessable(() -> service.update(
                investigating.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", ADMIN_ID, null, investigating.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_resolution");
        assertUnprocessable(() -> service.update(
                investigating.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", null, "confirmed", investigating.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_assignment");
    }

    @Test
    void rejectsBackwardTransitionBeforeActiveFingerprintConstraint() {
        PaymentAnomalyService.AnomalyView first = record("FINGERPRINT_COLLISION");
        PaymentAnomalyService.AnomalyView investigating = service.update(
                first.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", ADMIN_ID, null, first.lockVersion()),
                ADMIN_ID);
        PaymentAnomalyService.AnomalyView resolved = service.update(
                first.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "RESOLVED", ADMIN_ID, "confirmed", investigating.lockVersion()),
                ADMIN_ID);
        PaymentAnomalyService.AnomalyView replacement = record("FINGERPRINT_COLLISION");
        assertThat(replacement.id()).isNotEqualTo(resolved.id());

        assertUnprocessable(() -> service.update(
                resolved.id(),
                new PaymentAnomalyService.UpdateAnomalyCommand(
                        "INVESTIGATING", ADMIN_ID, null, resolved.lockVersion()),
                ADMIN_ID), "invalid_payment_anomaly_transition");
    }

    @Test
    void mapsMalformedAnomalyTypeFilterToUnprocessableEntity() {
        assertThatThrownBy(() -> service.list(new PaymentAnomalyService.AnomalyFilters(
                0, 20, "PAYMENT;DROP TABLE", null, null,
                null, null, null, "createdAt", "desc")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value())
                                .isEqualTo(422));
    }

    private PaymentAnomalyService.AnomalyView record(String reason) {
        return service.record(new PaymentAnomalyService.AnomalyCommand(
                "PAYMENT_QUERY_UNCERTAIN", PaymentProvider.WECHAT_PAY,
                ORDER_ID, TRANSACTION_ID, null, Map.of("reason", reason)));
    }

    private void assertUnprocessable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            String reason) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(422);
                    assertThat(exception).hasMessageContaining(reason);
                });
    }

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'hash', 'Anomaly User', 'ACTIVE')
                """, id, TENANT_ID, email);
    }
}
