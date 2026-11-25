package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
@Import(OrderApplicationServiceTest.FixedClockConfiguration.class)
class OrderApplicationServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("93000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("93000000-0000-4000-8000-000000000003");
    /**
     * 种子套餐方案当前处于 PUBLISHED 状态的版本。
     *
     * <p>每当有套餐方案重新发布，这些 ID 就会变动，下面断言的金额也随之变动：
     * V61 依据修正后的提供方成本基准重新定价了每一个 token 套餐方案，因此
     * STARTER 现为 v3、CNY 29 / 2,000,000 tokens，ADVANCED v3 为 CNY 399，ENTERPRISE v4
     * 为 CNY 1,599，FREE v2 为 50,000 tokens。V63 发布了 v3/v4 行，以修正
     * 售出的速率限制，而未改动价格或额度。被取代的版本仍以 RETIRED
     * 行存在，这就是为什么针对其中某个版本下单会被以
     * {@code published_package_version_not_found} 拒绝 —— 订单解析出的版本必须是
     * 已发布的那个版本。
     */
    private static final UUID FREE_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000111");
    private static final UUID STARTER_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000122");
    private static final UUID ADVANCED_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000123");
    private static final UUID ENTERPRISE_VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000224");
    private static final UUID CUSTOM_PLAN_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000010");
    private static final UUID CUSTOM_VERSION_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000011");
    private static final UUID ZERO_PRICE_VERSION_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000012");
    private static final UUID UNPUBLISHED_VERSION_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000013");
    private static final UUID GENERATED_STANDARD_PLAN_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000014");
    private static final UUID GENERATED_STANDARD_VERSION_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000015");
    private static final UUID MISSING_VERSION_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000016");
    /**
     * 零价格夹具需要有一个属于自己的套餐方案。
     *
     * <p>它原本挂在种子 STARTER 套餐方案上，而该方案已经有一个已发布
     * 版本：{@code uq_published_package_plan_version}（V45）限定每个套餐方案恰好一个，
     * 因此在干净的数据库上该夹具会违反这一约束。该用例的意图是「已发布的
     * 零价格版本无法被下单」，这并不需要某个特定的套餐方案 —— 只需要一个
     * 尚未被占用的套餐方案。
     */
    private static final UUID ZERO_PRICE_PLAN_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000017");
    private static final Instant FIXED_NOW = Instant.parse("2030-01-02T03:04:05Z");

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
    OrderApplicationService service;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.refund, platform.payment_transaction, platform.customer_order, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        insertUser(USER_ID, "order.user@example.com");
        insertUser(OTHER_USER_ID, "order.other@example.com");
        prepareCatalogFixtures();
        prepareGatewayPackageInstances();
    }

    @Test
    void createsPublishedStarterWithFixedExpiryAndCanonicalSnapshot() {
        OrderApplicationService.OrderCreated result = create(
                USER_ID, "starter-checkout", STARTER_VERSION_ID);

        assertThat(result.orderNo()).startsWith("ord_").hasSize(36);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.amount()).isEqualTo(2_900L);
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));
        assertThat(result.packageSnapshot().path("packageCode").asText()).isEqualTo("STARTER");
        assertThat(result.packageSnapshot().path("totalTokenQuota").asLong()).isEqualTo(2_000_000L);
        assertThat(result.packageSnapshot().path("models").get(0).path("modelCode").asText())
                .isEqualTo("file-basic");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'ORDER_CREATED' AND resource_id = ?
                """, Integer.class, result.orderNo())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payment_provider FROM platform.customer_order WHERE order_no = ?
                """, String.class, result.orderNo())).isEqualTo("UNSELECTED");
    }

    @Test
    void createsPublishedAdvancedOrder() {
        OrderApplicationService.OrderCreated result = create(
                USER_ID, "advanced-checkout", ADVANCED_VERSION_ID);

        assertThat(result.amount()).isEqualTo(39_900L);
        assertThat(result.packageSnapshot().path("packageCode").asText()).isEqualTo("ADVANCED");
    }

    @Test
    void createsPublishedEnterpriseOrder() {
        OrderApplicationService.OrderCreated result = create(
                USER_ID, "enterprise-checkout", ENTERPRISE_VERSION_ID);

        // ENTERPRISE v4（CNY 1,599），即当前已发布的版本。v2 的 CNY 249 相对 v1 的 CNY 2,499
        // 是漏掉了一位数字，并以每次最高亏损 CNY 793 的价格售出了 100,000,000 tokens；
        // V61 将其退役，V63 则为修正后的售出速率限制、以相同价格重新发布了 v4。
        // 这里对金额做断言，因此未来的任何重新定价都必须
        // 回到这个测试里来，而不能让它在错误的状态下保持绿色。
        assertThat(result.amount()).isEqualTo(159_900L);
        assertThat(result.packageSnapshot().path("packageCode").asText()).isEqualTo("ENTERPRISE");
    }

    @Test
    void createsFixedEnterpriseCustomOrder() {
        OrderApplicationService.OrderCreated result = create(
                USER_ID, "enterprise-checkout", CUSTOM_VERSION_ID);

        assertThat(result.amount()).isEqualTo(399_900L);
        assertThat(result.packageSnapshot().path("packageCode").asText())
                .isEqualTo("ENTERPRISE_CUSTOM");
        assertThat(result.packageSnapshot().path("models").get(0).path("modelCode").asText())
                .isEqualTo("text-max");
    }

    @Test
    void rejectsFreeOrder() {
        assertThatThrownBy(() -> create(USER_ID, "free-checkout", FREE_VERSION_ID))
                .hasMessageContaining("paid_package_required");
        assertThat(orderCount()).isZero();
    }

    @Test
    void rejectsPublishedZeroPriceOrder() {
        assertThatThrownBy(() -> create(USER_ID, "zero-checkout", ZERO_PRICE_VERSION_ID))
                .hasMessageContaining("paid_package_required");
        assertThat(orderCount()).isZero();
    }

    @Test
    void createsPublishedStandardPackageWithGeneratedCode() {
        OrderApplicationService.OrderCreated result = create(
                USER_ID, "generated-standard-checkout", GENERATED_STANDARD_VERSION_ID);

        assertThat(result.amount()).isEqualTo(49_900L);
        assertThat(result.packageSnapshot().path("packageCode").asText())
                .isEqualTo("PKG_93000000000040008000000000000014");
        assertThat(result.packageSnapshot().path("packageKind").asText()).isEqualTo("STANDARD");
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void rejectsUnpublishedVersion() {
        assertThatThrownBy(() -> create(USER_ID, "draft-checkout", UNPUBLISHED_VERSION_ID))
                .hasMessageContaining("published_package_version_not_found");
        assertThat(orderCount()).isZero();
    }

    @Test
    void rejectsMissingVersion() {
        assertThatThrownBy(() -> create(USER_ID, "missing-checkout", MISSING_VERSION_ID))
                .hasMessageContaining("published_package_version_not_found");
        assertThat(orderCount()).isZero();
    }

    @Test
    void sameUserKeyAndVersionReplaysOriginalOrderAndStoredSnapshot() {
        OrderApplicationService.OrderCreated first = create(
                USER_ID, "repeat-checkout", STARTER_VERSION_ID);
        ((ObjectNode) first.packageSnapshot()).put("priceAmount", 1);

        OrderApplicationService.OrderCreated replay = create(
                USER_ID, "repeat-checkout", STARTER_VERSION_ID);

        assertThat(replay.orderNo()).isEqualTo(first.orderNo());
        assertThat(replay.amount()).isEqualTo(2_900L);
        assertThat(replay.packageSnapshot().path("priceAmount").asLong()).isEqualTo(2_900L);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.admin_audit_log WHERE action = 'ORDER_CREATED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameUserKeyAndVersionCreatesOneOrderAndOneAuditEvent() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OrderApplicationService.OrderCreated> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return create(USER_ID, "concurrent-checkout", STARTER_VERSION_ID);
            });
            Future<OrderApplicationService.OrderCreated> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return create(USER_ID, "concurrent-checkout", STARTER_VERSION_ID);
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            OrderApplicationService.OrderCreated firstResult = first.get(30, TimeUnit.SECONDS);
            OrderApplicationService.OrderCreated secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(secondResult.orderNo()).isEqualTo(firstResult.orderNo());
            assertThat(orderCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform.admin_audit_log WHERE action = 'ORDER_CREATED'",
                    Integer.class)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sameUserKeyWithDifferentVersionIsRejected() {
        create(USER_ID, "mismatch-checkout", STARTER_VERSION_ID);

        assertThatThrownBy(() -> create(USER_ID, "mismatch-checkout", ADVANCED_VERSION_ID))
                .hasMessageContaining("order_idempotency_payload_mismatch");
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void differentUsersMayReuseTheSameKey() {
        OrderApplicationService.OrderCreated first = create(
                USER_ID, "shared-checkout", STARTER_VERSION_ID);
        OrderApplicationService.OrderCreated second = create(
                OTHER_USER_ID, "shared-checkout", STARTER_VERSION_ID);

        assertThat(second.orderNo()).isNotEqualTo(first.orderNo());
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    void rejectsRepurchasingSamePackageWhileCurrentPackageStillHasRemainingQuota() {
        insertGatewayPackageInstance(USER_ID, "STARTER", "ACTIVE", 5_000_000L, 250_000L, 0L);

        assertThatThrownBy(() -> create(USER_ID, "starter-rebuy-blocked", STARTER_VERSION_ID))
                .hasMessageContaining("package_quota_remaining");
        assertThat(orderCount()).isZero();
    }

    @Test
    void allowsThirdSamePackagePurchaseAfterExhaustionAndBlocksFourth() {
        insertGatewayPackageInstance(USER_ID, "STARTER", "EXHAUSTED", 5_000_000L, 5_000_000L, 0L);
        insertGatewayPackageInstance(USER_ID, "STARTER", "EXHAUSTED", 5_000_000L, 5_000_000L, 0L);

        OrderApplicationService.OrderCreated third = create(
                USER_ID, "starter-third-purchase", STARTER_VERSION_ID);

        assertThat(third.packageSnapshot().path("packageCode").asText()).isEqualTo("STARTER");
        assertThat(orderCount()).isEqualTo(1);

        assertThatThrownBy(() -> create(USER_ID, "starter-fourth-purchase", STARTER_VERSION_ID))
                .hasMessageContaining("package_purchase_limit_reached");
        assertThat(orderCount()).isEqualTo(1);
    }

    private OrderApplicationService.OrderCreated create(UUID userId, String key, UUID versionId) {
        return service.create(TENANT_ID, userId, key,
                new OrderApplicationService.CreateOrderCommand(versionId));
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM platform.customer_order", Integer.class);
    }

    private void insertUser(UUID userId, String email) {
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, ?, 'test-password-hash', 'Order User', 'ACTIVE')
                """, userId, TENANT_ID, email);
    }

    private void prepareCatalogFixtures() {
        if (count("platform.package_plan", CUSTOM_PLAN_ID) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO platform.package_plan(id, code, name, description, status)
                    VALUES (?, 'ENTERPRISE_CUSTOM', 'Enterprise Custom', 'Fixed enterprise package', 'ACTIVE')
                    """, CUSTOM_PLAN_ID);
        }
        if (count("platform.package_plan_version", CUSTOM_VERSION_ID) == 0) {
            insertVersion(CUSTOM_VERSION_ID, CUSTOM_PLAN_ID, 1, 399_900L, "DRAFT");
            jdbcTemplate.update("""
                    INSERT INTO platform.package_plan_version_model(
                        id, package_plan_version_id, model_code, model_token_quota, capabilities)
                    VALUES (?, ?, 'text-max', 50000000, '["TEXT"]'::jsonb)
                    """, UUID.randomUUID(), CUSTOM_VERSION_ID);
            jdbcTemplate.update("""
                    UPDATE platform.package_plan_version
                    SET status = 'PUBLISHED', published_at = now()
                    WHERE id = ?
                    """, CUSTOM_VERSION_ID);
        }
        if (count("platform.package_plan", ZERO_PRICE_PLAN_ID) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO platform.package_plan(id, code, name, description, status)
                    VALUES (?, 'PKG_ZERO_PRICE_FIXTURE', 'Zero Price Fixture',
                            'Published zero-price version for the order guard', 'ACTIVE')
                    """, ZERO_PRICE_PLAN_ID);
        }
        if (count("platform.package_plan_version", ZERO_PRICE_VERSION_ID) == 0) {
            insertVersion(ZERO_PRICE_VERSION_ID, ZERO_PRICE_PLAN_ID, 1, 0, "PUBLISHED");
        }
        if (count("platform.package_plan_version", UNPUBLISHED_VERSION_ID) == 0) {
            insertVersion(UNPUBLISHED_VERSION_ID, ZERO_PRICE_PLAN_ID, 2, 9_900L, "DRAFT");
        }
        if (count("platform.package_plan", GENERATED_STANDARD_PLAN_ID) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO platform.package_plan(id, code, name, description, status)
                    VALUES (?, 'PKG_93000000000040008000000000000014',
                            'Generated Standard', 'Generated standard package', 'ACTIVE')
                    """, GENERATED_STANDARD_PLAN_ID);
        }
        if (count("platform.package_plan_version", GENERATED_STANDARD_VERSION_ID) == 0) {
            insertVersion(
                    GENERATED_STANDARD_VERSION_ID, GENERATED_STANDARD_PLAN_ID,
                    1, 49_900L, "PUBLISHED");
        }
    }

    private void prepareGatewayPackageInstances() {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbcTemplate.execute("DROP TABLE IF EXISTS gateway.package_instance");
        jdbcTemplate.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    grant_event_id uuid NOT NULL UNIQUE,
                    grant_request_id uuid NOT NULL UNIQUE,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_plan_version_id uuid NOT NULL,
                    package_code varchar(64) NOT NULL,
                    package_name varchar(100) NOT NULL,
                    total_token_quota bigint NOT NULL,
                    reserved_tokens bigint NOT NULL DEFAULT 0,
                    used_tokens bigint NOT NULL DEFAULT 0,
                    -- 方案 C：第二份额度。购买策略现在要问两份（"还没用完"= 任一桶还有余量），
                    -- 因此替身表也要有两份，否则这条查询在真实库上能过、在这里过不了。
                    audio_seconds_quota bigint,
                    audio_seconds_reserved bigint NOT NULL DEFAULT 0,
                    audio_seconds_used bigint NOT NULL DEFAULT 0,
                    status varchar(20) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    private void insertGatewayPackageInstance(
            UUID userId,
            String packageCode,
            String status,
            long totalTokenQuota,
            long usedTokens,
            long reservedTokens) {
        jdbcTemplate.update("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id,
                    package_plan_version_id, package_code, package_name, total_token_quota,
                    reserved_tokens, used_tokens, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, userId,
                STARTER_VERSION_ID, packageCode, packageCode + " package", totalTokenQuota,
                reservedTokens, usedTokens, status);
    }

    private void insertVersion(UUID id, UUID planId, int version, long price, String status) {
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, published_at,
                    max_file_size_bytes, once_per_user)
                VALUES (?, ?, ?, ?, 'CNY', 50000000, 30, 20, 200, 1000000,
                        20, 0.40000, ?, CASE WHEN ? = 'PUBLISHED' THEN now() ELSE NULL END,
                        20971520, false)
                """, id, planId, version, price, status, status);
    }

    private int count(String table, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedOrderClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
