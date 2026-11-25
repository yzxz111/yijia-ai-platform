package com.yuzhihuihe.apiplatform.gateway.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V79 是回填，而不是建表：它只修改部署时就已经存在的套餐实例，因此在一个干净的数据库上
 * （Flyway 跑它时 {@code gateway.package_instance} 还是空的）它什么也做不了，跑完迁移的测试
 * 无法观察到它。这里换一种方式：先按"部署当天已经卖出去的样子"插入夹具，再把迁移文件本身
 * 执行一遍，然后断言结果。
 *
 * <p>夹具刻意覆盖了那条 UPDATE 的每一条边界：需要抬高的、已经高于新值（运维手工抬过）的、
 * 卖出时就没有每日上限（NULL）的、已作废的历史快照、视频实例（同一列在那边是每秒秒数），
 * 以及不在这三个套餐编码里的 FREE 档位。
 */
@Testcontainers
class TokenPackageLimitBackfillMigrationTest {

    private static final String MIGRATION =
            "db/migration/V79__raise_sold_token_limits_on_existing_packages.sql";

    private static final UUID TENANT_ID = UUID.fromString("9f000000-0000-4000-8000-000000000001");
    private static final UUID STARTER_RAISED = UUID.fromString("9f000000-0000-4000-8000-000000000011");
    private static final UUID STARTER_DAILY_RAISED =
            UUID.fromString("9f000000-0000-4000-8000-000000000012");
    private static final UUID STARTER_NO_DAILY =
            UUID.fromString("9f000000-0000-4000-8000-000000000013");
    private static final UUID STARTER_ALREADY_ABOVE =
            UUID.fromString("9f000000-0000-4000-8000-000000000014");
    private static final UUID STARTER_REVOKED =
            UUID.fromString("9f000000-0000-4000-8000-000000000015");
    private static final UUID ADVANCED_RAISED = UUID.fromString("9f000000-0000-4000-8000-000000000021");
    private static final UUID ENTERPRISE_QUEUED =
            UUID.fromString("9f000000-0000-4000-8000-000000000031");
    private static final UUID VIDEO_CREATOR_UNTOUCHED =
            UUID.fromString("9f000000-0000-4000-8000-000000000041");
    private static final UUID FREE_UNTOUCHED = UUID.fromString("9f000000-0000-4000-8000-000000000051");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void seedWhatWasAlreadySold() throws Exception {
        execute("TRUNCATE gateway.package_instance CASCADE");
        // 卖出 100,000/分钟、每日 2,000 的 STARTER，正是把客户挡在 429 后面的那一行。
        seed(STARTER_RAISED, "STARTER", "TOKEN", "ACTIVE", 100_000, 2000);
        // 每日上限也低于新值，因此两列都要动 —— 速率本来就是新值，只有日限需要抬。
        seed(STARTER_DAILY_RAISED, "STARTER", "TOKEN", "ACTIVE", 500_000, 2000);
        // 卖出时就没有每日上限：NULL 必须保持 NULL，不能被发明出来的 5,000 取代。
        seed(STARTER_NO_DAILY, "STARTER", "TOKEN", "ACTIVE", 100_000, null);
        // 运维已经手工抬到新值之上：GREATEST 意味着这一行不该被碰，连 updated_at 都不该动。
        seed(STARTER_ALREADY_ABOVE, "STARTER", "TOKEN", "ACTIVE", 900_000, 9000);
        // 已作废的实例保留历史快照：改写客户买过的记录会伪造账目。
        seed(STARTER_REVOKED, "STARTER", "TOKEN", "REVOKED", 100_000, 2000);
        // 速率不动、只抬每日上限的那一档。
        seed(ADVANCED_RAISED, "ADVANCED", "TOKEN", "ACTIVE", 500_000, 10_000);
        // 尚未开始计时的排队套餐，会带着回填后的快照激活。
        seed(ENTERPRISE_QUEUED, "ENTERPRISE", "TOKEN", "QUEUED", 2_000_000, 50_000);
        // 同一列在视频实例上是每秒秒数上限，写入令牌速率不是抬高上限而是取消上限。
        seed(VIDEO_CREATOR_UNTOUCHED, "VIDEO_CREATOR", "VIDEO_SECOND", "ACTIVE", 190, 500);
        // FREE 不在这次修正的档位里。
        seed(FREE_UNTOUCHED, "FREE", "TOKEN", "ACTIVE", 20_000, 20);
    }

    @Test
    void raisesSoldTokenPackagesAndLeavesEveryOtherRowExactlyAsItWas() throws Exception {
        Instant untouchedUpdatedAt = updatedAt(STARTER_ALREADY_ABOVE);

        runMigration();

        Map<UUID, String> limits = limits();
        assertThat(limits)
                .containsEntry(STARTER_RAISED, "500000/5000")
                .containsEntry(STARTER_DAILY_RAISED, "500000/5000")
                .containsEntry(STARTER_NO_DAILY, "500000/NULL")
                .containsEntry(STARTER_ALREADY_ABOVE, "900000/9000")
                .containsEntry(STARTER_REVOKED, "100000/2000")
                .containsEntry(ADVANCED_RAISED, "500000/25000")
                .containsEntry(ENTERPRISE_QUEUED, "2000000/100000")
                .containsEntry(VIDEO_CREATOR_UNTOUCHED, "190/500")
                .containsEntry(FREE_UNTOUCHED, "20000/20");

        // 未被触碰的行连 updated_at 都不该动：那条 UPDATE 的 WHERE 子句把"没有变化"
        // 的行排除在外，因此手工抬过值的实例保留它自己的修改时间。
        assertThat(updatedAt(STARTER_ALREADY_ABOVE)).isEqualTo(untouchedUpdatedAt);
        assertThat(updatedAt(STARTER_RAISED)).isAfter(untouchedUpdatedAt);
    }

    @Test
    void isSafeToRunTwice() throws Exception {
        runMigration();
        Map<UUID, String> once = limits();

        runMigration();

        assertThat(limits()).isEqualTo(once);
    }

    private static void runMigration() throws Exception {
        try (InputStream migration = TokenPackageLimitBackfillMigrationTest.class
                        .getClassLoader().getResourceAsStream(MIGRATION);
                Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();
            statement.execute(new String(migration.readAllBytes(), UTF_8));
        }
    }

    private static void seed(
            UUID id, String code, String dimension, String status, long tokensPerMinute,
            Integer dailyRequestLimit)
            throws Exception {
        // ck_package_instance_dates 要求排队中的实例还没有起止时间：它尚未开始计时，
        // 因此 starts_at / expires_at / activated_at 都是 NULL。
        String dates = "QUEUED".equals(status)
                ? "NULL, NULL, NULL"
                : "now(), now() + interval '30 days', now()";
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, status,
                    starts_at, expires_at, activated_at, daily_request_limit, usage_dimension,
                    updated_at)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', gen_random_uuid(),
                    'identity-%s', gen_random_uuid(), '%s', '%s', 0,
                    '[{"modelCode":"text-basic"}]', 1000000, 2, 20, %d, 3, 30, '%s',
                    %s, %s, '%s', now())
                """.formatted(id, TENANT_ID, id, code, code, tokensPerMinute, status, dates,
                dailyRequestLimit == null ? "NULL" : dailyRequestLimit.toString(), dimension));
    }

    private static Map<UUID, String> limits() throws Exception {
        Map<UUID, String> limits = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT id, tokens_per_minute, daily_request_limit
                        FROM gateway.package_instance
                        """)) {
            while (rows.next()) {
                Object daily = rows.getObject("daily_request_limit");
                limits.put(rows.getObject("id", UUID.class),
                        rows.getLong("tokens_per_minute") + "/"
                                + (daily == null ? "NULL" : daily));
            }
        }
        return limits;
    }

    private static Instant updatedAt(UUID id) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT updated_at FROM gateway.package_instance WHERE id = '" + id + "'")) {
            assertThat(row.next()).isTrue();
            return row.getTimestamp("updated_at").toInstant();
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
