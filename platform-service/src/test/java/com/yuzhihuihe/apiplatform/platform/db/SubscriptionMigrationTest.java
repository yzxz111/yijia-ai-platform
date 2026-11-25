package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V71～V74 订阅迁移的约束级验证。
 *
 * <p>这些断言刻意打在**数据库**上而不是服务层：订阅的幂等与「一个计划至多一份活跃订阅」
 * 是不变量，不能只靠应用层判断（设计 §5.1、§5.3）。
 */
@Testcontainers
class SubscriptionMigrationTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * 用例之间共享同一个容器，而 {@code uq_subscription_active_plan} 是跨用例生效的，
     * 因此每个用例前必须清掉订阅数据，否则一个用例插入的 ACTIVE 订阅会把下一个用例顶掉。
     */
    @BeforeEach
    void clearSubscriptionData() throws SQLException {
        execute("DELETE FROM platform.subscription_cycle");
        execute("DELETE FROM platform.subscription");
        execute("DELETE FROM platform.notification_preference WHERE user_id = '" + USER_ID + "'");
    }

    @Test
    void createsSubscriptionTablesWithExpectedColumns() throws SQLException {
        assertThat(columnType("subscription", "next_charge_at")).isEqualTo("timestamp with time zone");
        assertThat(columnType("subscription", "package_plan_id")).isEqualTo("uuid");
        assertThat(isNullable("subscription", "next_charge_at")).isFalse();
        assertThat(isNullable("subscription", "current_period_end")).isFalse();
        assertThat(columnType("subscription_cycle", "business_key")).isEqualTo("character varying");
        assertThat(characterLength("subscription_cycle", "business_key")).isEqualTo(128);
        // 金额是分（minor unit），必须是整数类型，不允许浮点。
        assertThat(columnType("subscription_cycle", "amount")).isEqualTo("bigint");
        assertThat(isNullable("subscription_cycle", "failure_reason")).isTrue();
    }

    @Test
    void aUserHasAtMostOneActiveSubscriptionPerPlan() throws SQLException {
        seedUserPlanAndVersion();
        insertSubscription("aaaaaaaa-0000-0000-0000-000000000001", "ACTIVE");

        // 同一计划第二份 ACTIVE 必须被部分唯一索引拒绝。
        assertThatThrownBy(() ->
                insertSubscription("aaaaaaaa-0000-0000-0000-000000000002", "ACTIVE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_subscription_active_plan");

        // PAST_DUE 属于同一活跃族，同样被拒。
        assertThatThrownBy(() ->
                insertSubscription("aaaaaaaa-0000-0000-0000-000000000003", "PAST_DUE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_subscription_active_plan");

        // 终态不占用该唯一性，因此用户解约后可以重新订阅。
        insertSubscription("aaaaaaaa-0000-0000-0000-000000000004", "CANCELED");
        insertSubscription("aaaaaaaa-0000-0000-0000-000000000005", "EXPIRED");
        insertSubscription("aaaaaaaa-0000-0000-0000-000000000006", "TERMINATED");
        assertThat(countSubscriptions()).isEqualTo(4);
    }

    @Test
    void rejectsUnknownSubscriptionStatus() throws SQLException {
        seedUserPlanAndVersion();
        assertThatThrownBy(() ->
                insertSubscription("bbbbbbbb-0000-0000-0000-000000000001", "RENEWING"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_subscription_status");
    }

    @Test
    void rejectsCancelMarkerWithoutDisablingAutoRenew() throws SQLException {
        seedUserPlanAndVersion();
        // cancel_at_period_end 与 auto_renew 必须同向，避免
        // 「已请求取消但仍会扣款」的自相矛盾状态。
        assertThatThrownBy(() -> execute("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, auto_renew, cancel_at_period_end,
                    status, current_period_start, current_period_end, next_charge_at)
                VALUES ('%s', '%s', '%s', '%s', true, true,
                    'ACTIVE', now(), now() + interval '30 days', now() + interval '29 days')
                """.formatted(UUID.randomUUID(), TENANT_ID, USER_ID, PLAN_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_subscription_cancel_marker");
    }

    @Test
    void rejectsPeriodEndNotAfterStart() throws SQLException {
        seedUserPlanAndVersion();
        assertThatThrownBy(() -> execute("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id,
                    status, current_period_start, current_period_end, next_charge_at)
                VALUES ('%s', '%s', '%s', '%s',
                    'ACTIVE', now(), now(), now())
                """.formatted(UUID.randomUUID(), TENANT_ID, USER_ID, PLAN_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_subscription_period");
    }

    @Test
    void aCycleBusinessKeyIsUnique() throws SQLException {
        seedUserPlanAndVersion();
        String subscriptionId = insertSubscription(
                "cccccccc-0000-0000-0000-000000000001", "ACTIVE");
        insertCycle("dddddddd-0000-0000-0000-000000000001", subscriptionId, 1, "PENDING");

        // 这是「同一账期至多扣款一次」的数据库底线：调度器重复执行也无法造出第二行。
        assertThatThrownBy(() ->
                insertCycle("dddddddd-0000-0000-0000-000000000002", subscriptionId, 1, "PENDING"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_subscription_cycle_business_key");
    }

    @Test
    void aCyclePeriodIndexIsUniquePerSubscription() throws SQLException {
        seedUserPlanAndVersion();
        String subscriptionId = insertSubscription(
                "eeeeeeee-0000-0000-0000-000000000001", "ACTIVE");
        insertCycle("ffffffff-0000-0000-0000-000000000001", subscriptionId, 1, "PENDING");

        assertThatThrownBy(() -> execute("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount)
                VALUES ('%s', '%s', 1, now(), now() + interval '30 days',
                    'renewal:%s:1-duplicate', 'PENDING', 2900)
                """.formatted(UUID.randomUUID(), subscriptionId, subscriptionId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_subscription_cycle_period");
    }

    @Test
    void subscriptionLeaseFieldsMustBeBothPresentOrBothAbsent() throws SQLException {
        seedUserPlanAndVersion();
        String subscriptionId = insertSubscription(
                "99999999-0000-4000-8000-000000000001", "ACTIVE");

        // 租约在**订阅行**上：持有租约必须两个字段都有，否则「只清了其中一个」会静默通过，
        // 而那个半清状态会让该订阅既不能被领取、也不像被领取，续费静默停摆。
        assertThatThrownBy(() -> execute("""
                UPDATE platform.subscription SET claim_token = gen_random_uuid(), claimed_until = NULL
                WHERE id = '%s'
                """.formatted(subscriptionId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_subscription_claim");

        assertThatThrownBy(() -> execute("""
                UPDATE platform.subscription SET claim_token = NULL, claimed_until = now() + interval '5 min'
                WHERE id = '%s'
                """.formatted(subscriptionId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_subscription_claim");

        execute("""
                UPDATE platform.subscription
                SET claim_token = gen_random_uuid(), claimed_until = now() + interval '5 min'
                WHERE id = '%s'
                """.formatted(subscriptionId));
        assertThat(scalar("""
                SELECT (claim_token IS NOT NULL)::text FROM platform.subscription WHERE id = '%s'
                """.formatted(subscriptionId))).isEqualTo("true");
    }

    @Test
    void cycleStatusNoLongerCarriesAChargingValue() throws SQLException {
        seedUserPlanAndVersion();
        String subscriptionId = insertSubscription(
                "99999999-0000-4000-8000-000000000002", "ACTIVE");

        // CHARGING 随租约一起移到了订阅行，账期行不该再有这个状态：
        // 「一个订阅至多一个在途扣款」由订阅行的单一租约保证，账期行只记历史。
        assertThatThrownBy(() -> execute("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount)
                VALUES ('%s', '%s', 1, now(), now() + interval '30 days',
                    'renewal:%s:1', 'CHARGING', 2900)
                """.formatted(UUID.randomUUID(), subscriptionId, subscriptionId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_subscription_cycle_status");
    }

    @Test
    void subscriptionCategoryIsAcceptedByBothNotificationChecks() throws SQLException {
        seedUserPlanAndVersion();

        // notification_event.category 必须接受新分类。
        execute("""
                INSERT INTO platform.notification_event(
                    id, event_key, category, event_type, aggregate_type, aggregate_id,
                    tenant_id, user_id, payload)
                VALUES ('%s', '%s', 'SUBSCRIPTION', 'SUBSCRIPTION_RENEWAL_DUE', 'TEST', '%s',
                    '%s', '%s', '{}'::jsonb)
                """.formatted(UUID.randomUUID(), "migration-test:" + UUID.randomUUID(),
                UUID.randomUUID(), TENANT_ID, USER_ID));

        // notification_preference.category 同样必须接受。
        execute("""
                INSERT INTO platform.notification_preference(
                    tenant_id, user_id, category, channel, enabled)
                VALUES ('%s', '%s', 'SUBSCRIPTION', 'SMS', false)
                """.formatted(TENANT_ID, USER_ID));
    }

    @Test
    void keepsExistingNotificationCategoriesAccepted() throws SQLException {
        seedUserPlanAndVersion();
        // 只增不改：既有 5 个取值必须全部仍然被接受，否则本迁移就破坏了通知中心。
        for (String category : new String[] {
                "SECURITY", "BILLING", "USAGE", "OPERATIONS", "SUPPORT"}) {
            execute("""
                    INSERT INTO platform.notification_preference(
                        tenant_id, user_id, category, channel, enabled)
                    VALUES ('%s', '%s', '%s', 'IN_APP', true)
                    """.formatted(TENANT_ID, USER_ID, category));
        }
        assertThat(countPreferencesForUser()).isEqualTo(5);
    }

    @Test
    void rejectsUnknownNotificationCategoryOnBothTables() throws SQLException {
        seedUserPlanAndVersion();
        // 反面：分类白名单仍然生效，不能被新分类顺手放宽成任意字符串。
        assertThatThrownBy(() -> execute("""
                INSERT INTO platform.notification_preference(
                    tenant_id, user_id, category, channel, enabled)
                VALUES ('%s', '%s', 'PROMOTIONS', 'IN_APP', true)
                """.formatted(TENANT_ID, USER_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_notification_preference_category");

        assertThatThrownBy(() -> execute("""
                INSERT INTO platform.notification_event(
                    id, event_key, category, event_type, aggregate_type, aggregate_id,
                    tenant_id, user_id, payload)
                VALUES ('%s', '%s', 'PROMOTIONS', 'SOMETHING', 'TEST', '%s',
                    '%s', '%s', '{}'::jsonb)
                """.formatted(UUID.randomUUID(), "migration-test:" + UUID.randomUUID(),
                UUID.randomUUID(), TENANT_ID, USER_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_notification_event_category");
    }

    @Test
    void dueScanIndexAndUniquenessIndexesExist() throws SQLException {
        assertThat(indexDefinition("ix_subscription_due"))
                .contains("next_charge_at")
                .contains("auto_renew");
        // 这两条唯一索引是「至多一份活跃订阅」与「同一期至多一行」的数据库底线。
        assertThat(indexDefinition("uq_subscription_active_plan"))
                .contains("package_plan_id")
                .contains("ACTIVE");
        assertThat(indexDefinition("uq_subscription_cycle_period"))
                .contains("subscription_id")
                .contains("period_index");
        assertThat(indexDefinition("uq_subscription_cycle_business_key"))
                .contains("business_key");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void seedUserPlanAndVersion() throws SQLException {
        execute("""
                INSERT INTO platform.app_user(tenant_id, id, email, password_hash, display_name, status)
                VALUES ('%s', '%s', 'subscription-test@yuzhi.test', 'x', 'Subscription Test', 'ACTIVE')
                ON CONFLICT (tenant_id, id) DO NOTHING
                """.formatted(TENANT_ID, USER_ID));
        execute("""
                INSERT INTO platform.package_plan(id, code, name, status, kind)
                VALUES ('%s', 'SUBSCRIPTION_TEST_PLAN', 'Subscription Test Plan', 'ACTIVE', 'STANDARD')
                ON CONFLICT (id) DO NOTHING
                """.formatted(PLAN_ID));
        execute("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, status, price_amount, currency,
                    total_token_quota, duration_days, max_api_keys,
                    requests_per_minute, tokens_per_minute, max_concurrency,
                    target_margin_ratio)
                VALUES ('%s', '%s', 1, 'DRAFT', 2900, 'CNY',
                    2000000, 30, 2, 60, 10000, 2, 0.30)
                ON CONFLICT (id) DO NOTHING
                """.formatted(VERSION_ID, PLAN_ID));
    }

    private static String insertSubscription(String id, String status) throws SQLException {
        execute("""
                INSERT INTO platform.subscription(
                    id, tenant_id, user_id, package_plan_id, status,
                    current_period_start, current_period_end, next_charge_at)
                VALUES ('%s', '%s', '%s', '%s', '%s',
                    now(), now() + interval '30 days', now() + interval '29 days')
                """.formatted(id, TENANT_ID, USER_ID, PLAN_ID, status));
        return id;
    }

    private static void insertCycle(
            String id, String subscriptionId, int periodIndex, String status) throws SQLException {
        execute("""
                INSERT INTO platform.subscription_cycle(
                    id, subscription_id, period_index, period_start, period_end,
                    business_key, status, amount)
                VALUES ('%s', '%s', %d, now(), now() + interval '30 days',
                    'renewal:%s:%d', '%s', 2900)
                """.formatted(id, subscriptionId, periodIndex, subscriptionId, periodIndex, status));
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String columnType(String table, String column) throws SQLException {
        return scalar("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'platform' AND table_name = ? AND column_name = ?
                """, table, column);
    }

    private static boolean isNullable(String table, String column) throws SQLException {
        return "YES".equals(scalar("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'platform' AND table_name = ? AND column_name = ?
                """, table, column));
    }

    private static int characterLength(String table, String column) throws SQLException {
        return Integer.parseInt(scalar("""
                SELECT character_maximum_length::text FROM information_schema.columns
                WHERE table_schema = 'platform' AND table_name = ? AND column_name = ?
                """, table, column));
    }

    private static String indexDefinition(String indexName) throws SQLException {
        return scalar("SELECT indexdef FROM pg_indexes WHERE schemaname = 'platform' AND indexname = ?",
                indexName);
    }

    private static long countSubscriptions() throws SQLException {
        return Long.parseLong(scalar("SELECT count(*) FROM platform.subscription"));
    }

    private static long countPreferencesForUser() throws SQLException {
        return Long.parseLong(scalar(
                "SELECT count(*) FROM platform.notification_preference WHERE user_id = ?",
                USER_ID));
    }

    private static String scalar(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
