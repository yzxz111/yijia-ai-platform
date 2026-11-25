package com.yuzhihuihe.apiplatform.gateway.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V80 的两件事必须在 SQL 层面被钉住，而「把全部迁移跑一遍」观察不到它们：
 *
 * <ol>
 *   <li>唯一性从「每用户一个 ACTIVE」收窄成「每用户每维度一个 ACTIVE」。收窄意味着
 *       同一维度的第二个 ACTIVE 仍然要被拒绝 —— 如果哪天有人把它换成普通索引，
 *       排队语义会静默消失，同一维度的两个实例会同时扣同一份额度。</li>
 *   <li>{@code api_key.usage_dimension} 的回填：绑定到视频实例的 key 必须变成
 *       VIDEO_SECOND，否则激活重绑会把文本 key 抢到视频套餐上（这正是本次改动
 *       要消除的既有缺陷）。回填只对"部署时已经存在"的行有意义，所以这里先把
 *       Flyway 停在 V79，插入旧形状的行，再单独执行 V80 脚本。</li>
 * </ol>
 *
 * <p>夹具只在 {@code @BeforeAll} 建一次，且各测试只读取（或只写入互不重叠的行），
 * 因此不依赖执行顺序。
 */
@Testcontainers
class DimensionScopedActivePackagesMigrationTest {

    private static final String MIGRATION =
            "db/migration/V80__dimension_scoped_active_packages.sql";

    private static final UUID USER_ID = UUID.fromString("9e000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("9e000000-0000-4000-8000-000000000002");
    private static final UUID TEXT_PACKAGE = UUID.fromString("9e000000-0000-4000-8000-000000000011");
    private static final UUID VIDEO_PACKAGE = UUID.fromString("9e000000-0000-4000-8000-000000000012");
    private static final UUID TEXT_KEY = UUID.fromString("9e000000-0000-4000-8000-000000000021");
    private static final UUID VIDEO_KEY = UUID.fromString("9e000000-0000-4000-8000-000000000022");
    private static final UUID WALLET_KEY = UUID.fromString("9e000000-0000-4000-8000-000000000023");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void seedLegacyRowsThenApplyTheMigration() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway").schemas("gateway")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("79"))
                .load().migrate();

        // 旧索引只允许一个 ACTIVE，所以第二个维度此时只能先是 QUEUED —— 它正是
        // 运行期"用户又买了视频套餐"时的样子，迁移后由激活逻辑翻成 ACTIVE。
        insertPackage(TEXT_PACKAGE, "TOKEN", "ACTIVE");
        insertPackage(VIDEO_PACKAGE, "VIDEO_SECOND", "QUEUED");
        // 迁移之前 api_key 还没有 usage_dimension 列。
        insertLegacyKey(TEXT_KEY, TEXT_PACKAGE, false);
        insertLegacyKey(VIDEO_KEY, VIDEO_PACKAGE, true);
        // 钱包 key 没有绑定实例：它的维度无从推断，应保持默认的 TOKEN。
        insertLegacyKey(WALLET_KEY, null, false);

        runMigration();
    }

    @Test
    void scopesActiveUniquenessToTheDimension() throws Exception {
        // 旧索引必须消失：留着它，第二个维度的实例永远无法生效。
        assertThat(indexDefinition("uq_active_package_per_user")).isNull();
        assertThat(indexDefinition("uq_active_package_per_user_dimension"))
                .contains("user_id", "usage_dimension", "ACTIVE");

        // 同一维度的第二个 ACTIVE 仍然被拒绝：收窄，而不是放弃。
        assertThatThrownBy(() -> insertPackage(UUID.randomUUID(), "TOKEN", "ACTIVE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_active_package_per_user_dimension");

        // 换个维度就能生效 —— 这就是"同时持有文本套餐与视频套餐"。
        execute("UPDATE gateway.package_instance SET status = 'ACTIVE', starts_at = now(), "
                + "expires_at = now() + interval '30 days', activated_at = now() "
                + "WHERE id = '" + VIDEO_PACKAGE + "'");
        assertThat(count("SELECT count(*) FROM gateway.package_instance WHERE user_id = '"
                + USER_ID + "' AND status = 'ACTIVE'")).isEqualTo(2);

        // 排队实例不受该唯一性约束：同一维度可以排很多个。
        insertPackage(UUID.randomUUID(), "TOKEN", "QUEUED");
        insertPackage(UUID.randomUUID(), "TOKEN", "QUEUED");
        assertThat(count("SELECT count(*) FROM gateway.package_instance "
                + "WHERE status = 'QUEUED'")).isEqualTo(2);
    }

    @Test
    void backfillsKeyDimensionFromTheBoundPackage() throws Exception {
        assertThat(dimensionOf(TEXT_KEY)).isEqualTo("TOKEN");
        // 绑定到视频实例的 key（托管 key 是每实例一把）必须跟着实例走。
        assertThat(dimensionOf(VIDEO_KEY)).isEqualTo("VIDEO_SECOND");
        // 钱包 key 没有实例可推断，保持默认值 —— 与它此前的实际行为一致。
        assertThat(dimensionOf(WALLET_KEY)).isEqualTo("TOKEN");

        // 枚举外的维度在数据库层就被拒绝，而不是等到查询时静默不匹配。
        assertThatThrownBy(() -> execute("UPDATE gateway.api_key "
                + "SET usage_dimension = 'VIDEO' WHERE id = '" + TEXT_KEY + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_api_key_usage_dimension");
    }

    @Test
    void keepsFreeClaimUniquenessAcrossDimensions() throws Exception {
        // 「每个身份只能免费领一次」是身份级规则：多了一个维度不该变成能领三次。
        assertThat(indexDefinition("uq_free_claim_package_per_identity"))
                .contains("identity_key")
                .doesNotContain("usage_dimension");

        String identityKey = "free-claim-identity-" + UUID.randomUUID();
        insertFreeClaim(identityKey, "TOKEN");
        assertThatThrownBy(() -> insertFreeClaim(identityKey, "VIDEO_SECOND"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_free_claim_package_per_identity");
    }

    private static void insertFreeClaim(String identityKey, String dimension) throws Exception {
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, package_kind,
                    once_per_user, status, starts_at, expires_at, activated_at,
                    usage_dimension)
                VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                    '%s', gen_random_uuid(), '%s', gen_random_uuid(),
                    'FREE', 'FREE', 0, '[{"modelCode":"text-basic"}]',
                    20000, 2, 20, 20, 3, 30, 'FREE_CLAIM', true, 'ACTIVE',
                    now(), now() + interval '30 days', now(), '%s')
                """.formatted(TENANT_ID, identityKey, dimension));
    }

    private static void insertPackage(UUID id, String dimension, String status) throws Exception {
        // ck_package_instance_dates 要求排队中的实例还没有起止时间。
        String dates = "QUEUED".equals(status)
                ? "NULL, NULL, NULL"
                : "now(), now() + interval '30 days', now()";
        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, price_amount,
                    allowed_models, total_token_quota, max_api_keys, requests_per_minute,
                    tokens_per_minute, max_concurrency, duration_days, status,
                    starts_at, expires_at, activated_at, usage_dimension)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-%s',
                    gen_random_uuid(), 'STARTER', 'STARTER', 0,
                    '[{"modelCode":"text-basic"}]', 1000000, 2, 20, 20, 3, 30, '%s',
                    %s, '%s')
                """.formatted(id, TENANT_ID, USER_ID, id, status, dates, dimension));
    }

    /** 迁移之前的 api_key 形状：没有 usage_dimension 列。 */
    private static void insertLegacyKey(UUID id, UUID packageId, boolean managed) throws Exception {
        // ck_api_key_managed_secret 要求托管 key 带着至少 29 字节的密文与正的密钥版本。
        String secret = managed ? "decode(repeat('ab', 32), 'hex'), 1" : "NULL, NULL";
        execute("""
                INSERT INTO gateway.api_key(
                    id, tenant_id, user_id, package_instance_id, name,
                    key_prefix, key_hash, allowed_models, status, managed,
                    secret_ciphertext, secret_key_version)
                VALUES ('%s', '%s', '%s', %s, 'legacy',
                    'sk-yzh-%s', md5('%s') || repeat('a', 32), '["text-basic"]'::jsonb,
                    'ACTIVE', %s, %s)
                """.formatted(id, TENANT_ID, USER_ID,
                packageId == null ? "NULL" : "'" + packageId + "'",
                id.toString().substring(0, 8), id, managed, secret));
    }

    private static void runMigration() throws Exception {
        try (InputStream migration = DimensionScopedActivePackagesMigrationTest.class
                        .getClassLoader().getResourceAsStream(MIGRATION);
                Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();
            statement.execute(new String(migration.readAllBytes(), UTF_8));
        }
    }

    private static String indexDefinition(String indexName) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'gateway' "
                                + "AND indexname = '" + indexName + "'")) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static String dimensionOf(UUID keyId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT usage_dimension FROM gateway.api_key "
                        + "WHERE id = '" + keyId + "'")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
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
