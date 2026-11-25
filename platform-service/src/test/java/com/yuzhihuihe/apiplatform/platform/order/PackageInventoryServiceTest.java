package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class PackageInventoryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("97000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("97000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("97000000-0000-4000-8000-000000000003");
    private static final UUID PLAN_ID = UUID.fromString("97000000-0000-4000-8000-000000000010");
    private static final UUID VERSION_ID = UUID.fromString("97000000-0000-4000-8000-000000000011");

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
    PackageInventoryService service;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.refund, platform.payment_transaction, platform.customer_order, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("TRUNCATE platform.package_plan_version_model, "
                + "platform.package_plan_version, platform.package_plan RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'inventory.user@example.com', 'hash', 'Inventory User', 'ACTIVE'),
                       (?, ?, 'inventory.other@example.com', 'hash', 'Other User', 'ACTIVE')
                """, USER_ID, TENANT_ID, OTHER_USER_ID, TENANT_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan(id, code, name, description, status)
                VALUES (?, 'INVENTORY_TEST', 'Inventory test', 'Inventory test plan', 'ACTIVE')
                """, PLAN_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, published_at,
                    max_file_size_bytes, stock_total, stock_sold, stock_reserved,
                    low_stock_threshold, max_purchases_per_user)
                VALUES (?, ?, 1, 100, 'CNY', 1000, 30, 1, 1, 1000, 1, 0.4,
                        'PUBLISHED', now(), 1048576, 1, 0, 0, 1, 1)
                """, VERSION_ID, PLAN_ID);
    }

    @Test
    void reservesConsumesAndReleasesOneInventoryUnit() {
        PackageInventoryService.InventoryView reserved = service.reserve(
                TENANT_ID, USER_ID, VERSION_ID);

        assertThat(reserved.stockTotal()).isEqualTo(1L);
        assertThat(reserved.stockReserved()).isEqualTo(1L);
        assertThat(reserved.remainingStock()).isZero();

        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, inventory_status)
                VALUES (?, ?, ?, ?, ?, 100, 'CNY', 'PENDING', 'UNSELECTED', 'RESERVED')
                """, orderId, "ord_inventory_consume", TENANT_ID, USER_ID, VERSION_ID);

        service.release(orderId, VERSION_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT inventory_status FROM platform.customer_order WHERE id = ?",
                String.class, orderId)).isEqualTo("RELEASED");

        service.reserve(TENANT_ID, OTHER_USER_ID, VERSION_ID);
        UUID consumeOrderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, inventory_status)
                VALUES (?, ?, ?, ?, ?, 100, 'CNY', 'PENDING', 'UNSELECTED', 'RESERVED')
                """, consumeOrderId, "ord_inventory_consume_after_release", TENANT_ID, OTHER_USER_ID, VERSION_ID);

        service.consume(consumeOrderId, VERSION_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_sold FROM platform.package_plan_version WHERE id = ?",
                Long.class, VERSION_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT inventory_status FROM platform.customer_order WHERE id = ?",
                String.class, consumeOrderId)).isEqualTo("SOLD");
        UUID releaseOrderId = orderId;
        assertThat(jdbcTemplate.queryForObject(
                "SELECT inventory_status FROM platform.customer_order WHERE id = ?",
                String.class, releaseOrderId)).isEqualTo("RELEASED");
    }

    @Test
    void rejectsSoldOutAndPerUserLimitBeforeCreatingAReservation() {
        service.reserve(TENANT_ID, USER_ID, VERSION_ID);
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, inventory_status)
                VALUES (?, ?, ?, ?, ?, 100, 'CNY', 'PENDING', 'UNSELECTED', 'SOLD')
                """, orderId, "ord_inventory_existing", TENANT_ID, USER_ID, VERSION_ID);
        assertThatThrownBy(() -> service.reserve(TENANT_ID, USER_ID, VERSION_ID))
                .hasMessageContaining("package_purchase_limit_reached");
        assertThatThrownBy(() -> service.reserve(TENANT_ID, OTHER_USER_ID, VERSION_ID))
                .hasMessageContaining("package_sold_out");
    }

    @Test
    void serializesConcurrentReservationsForOneUnit() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> tryReserve(USER_ID, ready, start));
            Future<String> second = executor.submit(() -> tryReserve(OTHER_USER_ID, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.stream.Stream.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "package_sold_out");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT stock_reserved FROM platform.package_plan_version WHERE id = ?",
                    Long.class, VERSION_ID)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private String tryReserve(UUID userId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.reserve(TENANT_ID, userId, VERSION_ID);
            return "SUCCESS";
        } catch (ResponseStatusException exception) {
            return exception.getReason();
        }
    }
}
