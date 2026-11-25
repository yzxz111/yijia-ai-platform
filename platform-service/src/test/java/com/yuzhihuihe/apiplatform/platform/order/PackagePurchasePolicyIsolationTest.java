package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;

/**
 * 一次性购买策略在本次改动之后**仍然按原语义拒绝**。
 *
 * <p>这是「连续包月」功能里风险最高的一处的反向证据：续费必须绕过
 * {@link PackagePurchasePolicyService} 的两条阻断（当期还有额度、累计 3 次），
 * 而绕过一旦被写成「把既有策略调松」，一次性购买的行为就被悄悄改坏了。
 * 本类与 {@code platform.subscription.SubscriptionRenewalPolicyServiceTest}
 * 合起来构成双向证明：那边证明续费不被阻断，这边证明阻断还在。
 *
 * <p>本类必须待在 {@code order} 包内：{@code PackagePurchasePolicyRepository} 是包私有的，
 * 跨包无法构造。{@code PackageSnapshotService} 用替身而非真实快照——真实快照有 18 个字段，
 * 手构一个会把断言变成「快照装配是否正确」而不是「策略是否还在」。
 */
class PackagePurchasePolicyIsolationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).defaultSchema("platform").schemas("platform")
                .locations("classpath:db/migration").load().migrate();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    private final PackageSnapshotService snapshots = mock(PackageSnapshotService.class);
    private final PackagePurchasePolicyService policy =
            new PackagePurchasePolicyService(new PackagePurchasePolicyRepository(jdbc));

    private UUID tenantId;
    private UUID userId;
    private UUID planId;
    private UUID versionId;
    private String planCode;

    @BeforeEach
    void reset() throws SQLException {
        // gateway schema 不在平台侧迁移里，必须自己建最小替身表。
        // 这是仓库既有做法（见 OrderApplicationServiceTest.prepareGatewayPackageInstances）。
        prepareGatewayPackageInstances();
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        planId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        planCode = "ISO_" + planId.toString().replace("-", "").substring(0, 12).toUpperCase();
        execute("""
                INSERT INTO platform.app_user(tenant_id, id, email, password_hash, display_name, status)
                VALUES ('%s', '%s', '%s', 'x', 'Isolation', 'ACTIVE')
                """.formatted(tenantId, userId, userId + "@yuzhi.test"));
        jdbc.update("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES (?, ?, 'Isolation Plan', 'ACTIVE', 'STANDARD')
                """, planId, planCode);
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency, target_margin_ratio)
                VALUES (?, ?, 1, 'PUBLISHED', 2900, 'CNY', 2000000, 30, 2, 60, 10000, 2, 0.30)
                """, versionId, planId);
        when(snapshots.loadPublished(any())).thenReturn(snapshot());
    }

    private void prepareGatewayPackageInstances() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbc.execute("DROP TABLE IF EXISTS gateway.package_instance");
        jdbc.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    grant_request_id uuid UNIQUE,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    package_code varchar(64) NOT NULL,
                    package_name varchar(100) NOT NULL,
                    package_kind varchar(20) NOT NULL DEFAULT 'STANDARD',
                    total_token_quota bigint NOT NULL,
                    reserved_tokens bigint NOT NULL DEFAULT 0,
                    used_tokens bigint NOT NULL DEFAULT 0,
                    usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
                    -- 方案 C：第二份额度（音频秒）。策略现在要问两份，因此替身表
                    -- 也必须有两份，否则这条查询在真实库上能过、在这里过不了。
                    audio_seconds_quota bigint,
                    audio_seconds_reserved bigint NOT NULL DEFAULT 0,
                    audio_seconds_used bigint NOT NULL DEFAULT 0,
                    status varchar(20) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    @Test
    void stillRejectsASecondPurchaseWhileTheCurrentPackageHasUnusedQuota() {
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, used_tokens, reserved_tokens, status, usage_dimension)
                VALUES (?, ?, ?, ?, 'Isolation Plan', 'STANDARD', 2000000, 100, 0, 'ACTIVE', 'TOKEN')
                """, UUID.randomUUID(), tenantId, userId, planCode);

        assertThatThrownBy(() -> policy.assertCanCreate(tenantId, userId, snapshot()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_quota_remaining");
    }

    @Test
    void stillEnforcesTheThreePurchaseCap() {
        // 三个 PENDING 订单就把计数顶到 MAX_PURCHASES_PER_PACKAGE = 3。
        // 这个常量是本次改动**不得触碰**的：续费用自己的 renewal_count，购买用它。
        for (int index = 0; index < 3; index++) {
            UUID orderId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform.customer_order(
                        id, order_no, tenant_id, user_id, package_plan_version_id,
                        amount, currency, status, payment_provider,
                        expires_at, next_close_at, inventory_status, settlement_method)
                    VALUES (?, ?, ?, ?, ?, 2900, 'CNY', 'PENDING', 'MOCK',
                        now() + interval '15 minutes', now(), 'RESERVED', 'UNSELECTED')
                    """, orderId, "ord_" + orderId.toString().replace("-", ""), tenantId, userId, versionId);
        }

        assertThatThrownBy(() -> policy.assertCanCreate(tenantId, userId, snapshot()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_purchase_limit_reached");
    }

    /**
     * 方案 C：一个视频秒已经花完、但音频秒还没动的混合套餐**仍然是"没用完"**。
     *
     * <p>只看主桶会把这种实例判成已用尽，于是同一个套餐可以被再买一次——
     * 而客户手里明明还握着没用过的语音。反过来（音频花完、视频还有）
     * 由同一个"任一桶还有余量"的谓词覆盖，因此两个方向是同一条规则。
     */
    @Test
    void aMixedPackageWithUnusedAudioSecondsIsStillUnconsumed() {
        jdbc.update("""
                INSERT INTO gateway.package_instance(
                    id, tenant_id, user_id, package_code, package_name, package_kind,
                    total_token_quota, used_tokens, reserved_tokens, status, usage_dimension,
                    audio_seconds_quota, audio_seconds_reserved, audio_seconds_used)
                VALUES (?, ?, ?, ?, 'Isolation Plan', 'STANDARD',
                    100, 100, 0, 'ACTIVE', 'VIDEO_SECOND',
                    600, 0, 0)
                """, UUID.randomUUID(), tenantId, userId, planCode);

        assertThatThrownBy(() -> policy.assertCanCreate(tenantId, userId, snapshot()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("package_quota_remaining");
    }

    @Test
    void stillAllowsAPurchaseWhenNothingIsOutstanding() {
        // 正面控制：策略不是「一律拒绝」。没有这条，上面两条断言在策略被改成
        // 「永远拒绝」时也会通过。
        policy.assertCanCreate(tenantId, userId, snapshot());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.customer_order", Integer.class)).isZero();
    }

    @Test
    void theHardCodedPurchaseCapIsStillExactlyThree() throws Exception {
        // 源码级断言：这个常量是「续费必须绕过购买策略」这条结论的根据。
        // 它一旦被改大，续费绕过它的理由就消失了，而设计结论也随之失真——
        // 因此把它钉住，让改动必须是有意识的行为。
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/yuzhihuihe/apiplatform/platform/order/PackagePurchasePolicyService.java"));
        assertThat(source).contains("MAX_PURCHASES_PER_PACKAGE = 3");
    }

    private PackageSnapshotService.PackageSnapshot snapshot() {
        return new PackageSnapshotService.PackageSnapshot(
                versionId, planCode, "Isolation Plan", "STANDARD", 2900L, "CNY",
                2000000L, null, 30, 2, 60, 10000L, 2,
                null, null, 20971520L, false, "TOKEN", List.of());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
