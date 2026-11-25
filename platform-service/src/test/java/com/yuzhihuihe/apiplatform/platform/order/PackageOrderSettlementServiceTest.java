package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
class PackageOrderSettlementServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000002");
    private static final UUID VERSION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final Instant PAID_AT = Instant.parse("2026-08-05T08:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2030-08-05T08:15:00Z");

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
    JdbcTemplate jdbcTemplate;

    @Autowired
    PackageOrderSettlementService service;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'settlement@example.test', 'hash', 'Settlement User', 'ACTIVE')
                """, USER_ID, TENANT_ID);
    }

    /**
     * 历史订单（没有存快照）的结算必须**回填**一份完整快照，包括方案 C 的第二份额度。
     *
     * <p>这条分支此前没有任何测试。它错的方式是静默的：漏一个字段不会让结算失败，
     * 只会让订单快照少一份信息，而网关随后按那份快照发放——一个混合套餐的实例
     * 可能因此没有音频那一列（或者更糟：带着 0）。因此这里断言的是**值**
     * （600 秒），而不只是"字段存在"。
     */
    @Test
    void legacyOrderWithoutASnapshotBackfillsBothQuotasIntoTheOrder() {
        UUID versionId = insertMixedPublishedVersion();
        UUID orderId = insertLegacyOrderWithoutSnapshot("ord_legacy_backfill", 19_900, versionId);
        UUID transactionId = insertTransaction(orderId, "pay_legacy_backfill", 19_900);

        PackageOrderSettlementService.SettlementResult result = service.settle(
                orderId,
                PackageOrderSettlementService.SettlementCommand.external(
                        PAID_AT, PaymentProvider.MOCK, transactionId, "pay_legacy_backfill"));

        assertThat(result.legacySnapshotBackfill())
                .as("没有快照的历史订单必须被回填，而不是拒绝结算")
                .isTrue();
        Map<String, Object> backfilled = jdbcTemplate.queryForMap("""
                SELECT package_snapshot->>'usageDimension' AS dimension,
                       package_snapshot->>'totalTokenQuota' AS video_seconds,
                       package_snapshot->>'audioSecondsQuota' AS audio_seconds,
                       -- 按**模型**定位而不是按下标：快照里的 models 按 modelCode 排序，
                       -- 下标会让这条断言在别名改名时静默地指向另一个模型。
                       (SELECT model->>'usageDimension'
                        FROM jsonb_array_elements(package_snapshot->'models') AS model
                        WHERE model->>'modelCode' = 'audio-asr-basic') AS audio_model_dimension
                FROM platform.customer_order
                WHERE id = ?
                """, orderId);
        assertThat(backfilled)
                .containsEntry("dimension", "VIDEO_SECOND")
                .containsEntry("video_seconds", "100")
                .containsEntry("audio_seconds", "600")
                .as("逐模型维度也必须进快照：网关靠它给每个模型立自己那一列的自额度行")
                .containsEntry("audio_model_dimension", "AUDIO_SECOND");
    }

    @Test
    void externalSettlementOwnsThePaidTransitionAndPackageSideEffects() {
        UUID orderId = insertOrder("ord_shared_external", 2_990, snapshot(2_990));
        UUID transactionId = insertTransaction(orderId, "pay_shared_external", 2_990);

        PackageOrderSettlementService.SettlementResult result = service.settle(
                orderId,
                PackageOrderSettlementService.SettlementCommand.external(
                        PAID_AT, PaymentProvider.MOCK, transactionId, "pay_shared_external"));

        assertThat(result.orderNo()).isEqualTo("ord_shared_external");
        assertThat(result.orderStatus()).isEqualTo("PAID");
        assertThat(result.grant().businessNo()).isEqualTo("order:ord_shared_external");
        assertThat(result.grant().status()).isEqualTo("QUEUED");
        assertThat(result.legacySnapshotBackfill()).isFalse();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, paid_at, settlement_method, payment_provider,
                       successful_payment_transaction_id, balance_debit_ledger_id,
                       lock_version
                FROM platform.customer_order
                WHERE id = ?
                """, orderId))
                .containsEntry("status", "PAID")
                .containsEntry("settlement_method", "EXTERNAL")
                .containsEntry("payment_provider", "MOCK")
                .containsEntry("successful_payment_transaction_id", transactionId)
                .containsEntry("balance_debit_ledger_id", null)
                .containsEntry("lock_version", 1L);
        assertThat(count("platform.grant_request")).isEqualTo(1);
        assertThat(count("platform.outbox_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM platform.admin_audit_log
                WHERE action = 'PAYMENT_POSTED'
                  AND detail->>'settlementMethod' = 'EXTERNAL'
                  AND detail->>'transactionNo' = 'pay_shared_external'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void fulfillmentFailureRollsBackThePaidTransitionAndEveryPackageSideEffect() {
        UUID orderId = insertOrder("ord_shared_rollback", 2_990, snapshot(2_990));
        UUID transactionId = insertTransaction(orderId, "pay_shared_rollback", 2_990);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION platform.reject_shared_settlement_outbox()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced_shared_settlement_outbox_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_shared_settlement_outbox
                BEFORE INSERT ON platform.outbox_event
                FOR EACH ROW EXECUTE FUNCTION platform.reject_shared_settlement_outbox()
                """);
        try {
            assertThatThrownBy(() -> service.settle(
                    orderId,
                    PackageOrderSettlementService.SettlementCommand.external(
                            PAID_AT, PaymentProvider.MOCK,
                            transactionId, "pay_shared_rollback")))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_shared_settlement_outbox "
                    + "ON platform.outbox_event");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS "
                    + "platform.reject_shared_settlement_outbox()");
        }

        Map<String, Object> order = jdbcTemplate.queryForMap("""
                SELECT status, paid_at, settlement_method, payment_provider,
                       successful_payment_transaction_id, lock_version
                FROM platform.customer_order
                WHERE id = ?
                """, orderId);
        assertThat(order)
                .containsEntry("status", "PENDING")
                .containsEntry("paid_at", null)
                .containsEntry("settlement_method", "UNSELECTED")
                .containsEntry("payment_provider", "UNSELECTED")
                .containsEntry("successful_payment_transaction_id", null)
                .containsEntry("lock_version", 0L);
        assertThat(count("platform.grant_request")).isZero();
        assertThat(count("platform.outbox_event")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action IN ('PAYMENT_POSTED', 'PACKAGE_GRANT_CREATED')
                """, Long.class)).isZero();
    }

    private UUID insertOrder(String orderNo, long amount, String packageSnapshot) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, package_snapshot,
                    idempotency_key, expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', 'PENDING', 'UNSELECTED', ?::jsonb,
                        ?, ?, ?)
                """, id, orderNo, TENANT_ID, USER_ID, VERSION_ID, amount,
                packageSnapshot, "create:" + orderNo,
                Timestamp.from(EXPIRES_AT), Timestamp.from(EXPIRES_AT));
        return id;
    }

    /**
     * 一条**历史订单**：没有存快照，也没有幂等键。这正是
     * {@code resolveSnapshot} 的回填分支存在的理由，而在此之前它在本仓库里
     * 没有任何测试触达（见设计文档"明确仍未做的"第 5 条）。
     */
    private UUID insertLegacyOrderWithoutSnapshot(String orderNo, long amount, UUID versionId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, package_snapshot,
                    idempotency_key, expires_at, next_close_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', 'PENDING', 'UNSELECTED', NULL,
                        NULL, ?, ?)
                """, id, orderNo, TENANT_ID, USER_ID, versionId, amount,
                Timestamp.from(EXPIRES_AT), Timestamp.from(EXPIRES_AT));
        return id;
    }

    /**
     * 一个"视频秒 + 音频秒"的已发布版本（方案 C），价格 19,900 分。
     *
     * <p>刻意自己插一行而不是复用迁移里的种子版本：本仓库里没有任何已发布版本
     * 带第二份额度，而"回填必须把第二份额度一起带上"这句话只有在一个**真的
     * 有值**的版本上才证明得了——在种子版本上它只会证明字段是 null。
     */
    private UUID insertMixedPublishedVersion() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, 'Mixed Plan', 'ACTIVE', 'STANDARD')
                """, planId, "MIXED_" + planId.toString().replace("-", "").substring(0, 10));
        // 先建草稿：`package_plan_version_model` 在父版本发布之后完全不可写
        // （`published_version_immutable`，V45 的触发器），因此模型行必须在
        // 发布之前写入——与真实发布路径的顺序一致。
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, audio_seconds_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency,
                    target_margin_ratio, usage_dimension)
                VALUES (?, ?, 1, 'DRAFT', 19900, 'CNY',
                        100, 600, 30, 2, 20, 600, 2, 0.35, 'VIDEO_SECOND')
                """, versionId, planId);
        jdbcTemplate.update("""
                INSERT INTO platform.package_plan_version_model(
                    id, package_plan_version_id, model_code, model_token_quota, capabilities)
                VALUES
                    (?, ?, 'video-basic', 100, '["TEXT","IMAGE","VIDEO"]'::jsonb),
                    (?, ?, 'audio-asr-basic', 600, '["AUDIO_TRANSCRIBE"]'::jsonb)
                """, UUID.randomUUID(), versionId, UUID.randomUUID(), versionId);
        jdbcTemplate.update("""
                UPDATE platform.package_plan_version
                SET status = 'PUBLISHED', published_at = now(),
                    estimated_cost_amount = 0, estimated_margin_ratio = 0,
                    margin_warning = false
                WHERE id = ?
                """, versionId);
        return versionId;
    }

    private UUID insertTransaction(UUID orderId, String transactionNo, long amount) {
        UUID transactionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, expires_at, request_payload_hash)
                VALUES (?, ?, ?, 'MOCK', ?, 'PENDING', 'MOCK', ?, ?)
                """, transactionId, transactionNo, orderId, amount,
                Timestamp.from(EXPIRES_AT), "a".repeat(64));
        return transactionId;
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String snapshot(long priceAmount) {
        return """
                {
                  "packageVersionId":"%s",
                  "packageCode":"STARTER",
                  "packageName":"Starter",
                  "priceAmount":%d,
                  "currency":"CNY",
                  "totalTokenQuota":5000000,
                  "durationDays":30,
                  "maxApiKeys":3,
                  "requestsPerMinute":20,
                  "tokensPerMinute":100000,
                  "maxConcurrency":3,
                  "dailyRequestLimit":null,
                  "maxOutputTokens":null,
                  "maxFileSizeBytes":20971520,
                  "oncePerUser":false,
                  "models":[]
                }
                """.formatted(VERSION_ID, priceAmount);
    }
}
