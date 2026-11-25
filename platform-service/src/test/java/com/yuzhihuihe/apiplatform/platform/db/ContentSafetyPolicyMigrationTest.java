package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ContentSafetyPolicyMigrationTest {

    private static final String EMPTY_CONFIG_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private Flyway flyway;

    @BeforeEach
    void resetDatabase() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                ;
        flyway.clean();
    }

    @Test
    void seedsCanonicalPublishedPolicyForGatewayFallbackVersion() throws Exception {
        flyway.migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT version, status, config::text, config_sha256,
                               published_at IS NOT NULL AS published
                        FROM platform.content_safety_policy_version
                        WHERE status = 'PUBLISHED'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("version")).isEqualTo(1L);
            assertThat(result.getString("status")).isEqualTo("PUBLISHED");
            assertThat(result.getString("config")).isEqualTo("{}");
            assertThat(result.getString("config_sha256")).isEqualTo(EMPTY_CONFIG_SHA256);
            assertThat(result.getBoolean("published")).isTrue();
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void seedsNextAvailablePublishedVersionWhenVersionOneIsRetired() throws Exception {
        migrateThroughVersion23();
        execute("""
                INSERT INTO platform.content_safety_policy_version(
                    version, status, config, config_sha256)
                VALUES (1, 'RETIRED', '{}'::jsonb, '%s')
                """.formatted(EMPTY_CONFIG_SHA256));

        flyway.migrate();

        assertThat(singleString("""
                SELECT version || ':' || status
                FROM platform.content_safety_policy_version
                WHERE status = 'PUBLISHED'
                """)).isEqualTo("2:PUBLISHED");
        assertThat(singleString("""
                SELECT status FROM platform.content_safety_policy_version WHERE version = 1
                """)).isEqualTo("RETIRED");
    }

    @Test
    void preservesExistingPublishedPolicy() throws Exception {
        migrateThroughVersion23();
        execute("""
                INSERT INTO platform.content_safety_policy_version(
                    version, status, config, config_sha256, published_at)
                VALUES (7, 'PUBLISHED', '{"existing":true}'::jsonb,
                    '%s', '2026-08-01T00:00:00Z')
                """.formatted(EMPTY_CONFIG_SHA256));

        flyway.migrate();

        assertThat(singleString("""
                SELECT version || ':' || config::text
                FROM platform.content_safety_policy_version
                WHERE status = 'PUBLISHED'
                """)).isEqualTo("7:{\"existing\": true}");
    }

    private void migrateThroughVersion23() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("23"))
                .load()
                .migrate();
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String singleString(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }
}
