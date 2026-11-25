package com.yuzhihuihe.apiplatform.gateway.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class UsageEconomicsMigrationTest {

    private static final String GATEWAY_USER = "gateway_app";
    private static final String GATEWAY_PASSWORD = "gateway-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void createGatewaySchema() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + GATEWAY_USER + " LOGIN PASSWORD '" + GATEWAY_PASSWORD + "'");
            statement.execute("CREATE ROLE platform_app LOGIN PASSWORD 'platform-test-password'");
            statement.execute("CREATE SCHEMA gateway AUTHORIZATION " + GATEWAY_USER);
        }
    }

    @Test
    void migratesHistoricalCostsWithoutInventingBreakdownsAndCreatesClaimableOutbox()
            throws SQLException {
        migrate("23");
        insertHistoricalCosts();
        insertHistoricalFailover();

        migrate(null);

        Set<String> economicsColumns = Set.of(
                "provider_price_id",
                "provider_input_cost_amount",
                "provider_output_cost_amount",
                "provider_original_input_cost_amount",
                "provider_original_output_cost_amount",
                "provider_original_cost_amount",
                "provider_original_cost_currency",
                "provider_exchange_rate");
        Set<String> detailedUsageColumns = Set.of(
                "provider_request_id",
                "usage_source",
                "cache_hit_tokens",
                "cache_miss_tokens",
                "cache_write_tokens",
                "reasoning_tokens",
                "text_tokens",
                "image_tokens",
                "audio_tokens",
                "raw_usage",
                "customer_billable",
                "provider_billable",
                "reconciliation_status");
        assertThat(columnNames("provider_model_price")).contains(
                "cache_read_price_per_million", "cache_write_price_per_million",
                "reasoning_price_per_million");
        assertThat(columnNames("provider_cost_record")).containsAll(economicsColumns);
        assertThat(columnNames("usage_stage"))
                .containsAll(economicsColumns)
                .containsAll(detailedUsageColumns)
                .contains("provider_uncached_input_cost_amount", "provider_cache_read_cost_amount",
                        "provider_cache_write_cost_amount", "provider_normal_output_cost_amount",
                        "provider_reasoning_cost_amount");

        assertHistoricalCostShape(
                "provider_cost_record", "10000000-0000-4000-8000-000000000001",
                "amount", "currency");
        assertHistoricalCostShape(
                "usage_stage", "20000000-0000-4000-8000-000000000001",
                "provider_cost_amount", "provider_cost_currency");
        assertThat(singleString("""
                SELECT usage_source FROM gateway.usage_stage
                WHERE id = '20000000-0000-4000-8000-000000000001'
                """))
                .isEqualTo("PROVIDER_ACTUAL");
        assertThat(singleString("""
                SELECT reconciliation_status FROM gateway.usage_stage
                WHERE id = '20000000-0000-4000-8000-000000000001'
                """))
                .isEqualTo("NOT_APPLICABLE");
        assertThat(singleLong("""
                SELECT customer_billable::int FROM gateway.usage_stage
                WHERE id = '20000000-0000-4000-8000-000000000001'
                """)).isOne();
        assertThat(singleLong("""
                SELECT provider_billable::int FROM gateway.usage_stage
                WHERE id = '20000000-0000-4000-8000-000000000001'
                """)).isOne();
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.usage_log parent
                WHERE parent.request_id = 'legacy-failover'
                  AND parent.input_tokens = (
                      SELECT sum(stage.input_tokens)
                      FROM gateway.usage_stage stage
                      WHERE stage.usage_log_id = parent.id AND stage.customer_billable)
                  AND parent.output_tokens = (
                      SELECT sum(stage.output_tokens)
                      FROM gateway.usage_stage stage
                      WHERE stage.usage_log_id = parent.id AND stage.customer_billable)
                """)).isOne();
        assertThat(singleLong("""
                SELECT count(*) FROM gateway.usage_stage
                WHERE provider_request_id IS NULL
                  AND cache_hit_tokens IS NULL
                  AND cache_miss_tokens IS NULL
                  AND cache_write_tokens IS NULL
                  AND reasoning_tokens IS NULL
                  AND text_tokens IS NULL
                  AND image_tokens IS NULL
                  AND audio_tokens IS NULL
                  AND raw_usage IS NULL
                """)).isEqualTo(3);
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_usage_source"))
                .contains("PROVIDER_ACTUAL", "LOCAL_ESTIMATED", "BILL_RECONCILED", "MANUAL_CORRECTED");
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_reconciliation_status"))
                .contains("PENDING", "MATCHED", "MISMATCHED", "ADJUSTED", "NOT_APPLICABLE");
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_usage_details"))
                .contains("cache_hit_tokens", "cache_miss_tokens", "cache_write_tokens",
                        "reasoning_tokens", "text_tokens", "image_tokens", "audio_tokens");
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_raw_usage"))
                .contains("jsonb_typeof", "octet_length", "16384");
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_provider_billable_cost"))
                .contains("provider_billable", "provider_cost_amount");
        assertThat(constraintDefinition("usage_stage", "ck_usage_stage_detailed_cost"))
                .contains("provider_uncached_input_cost_amount", "provider_cache_read_cost_amount",
                        "provider_reasoning_cost_amount");

        assertThat(columnNames("usage_economics_outbox")).containsExactlyInAnyOrder(
                "event_id", "event_fingerprint", "payload", "payload_hash", "status",
                "attempt_count", "claim_token", "claimed_until", "next_attempt_at",
                "sent_at", "last_error", "created_at", "updated_at");
        assertThat(constraintDefinition("usage_economics_outbox", "ck_usage_economics_outbox_status"))
                .contains("PENDING", "SENDING", "SENT", "FAILED", "DEAD");
        assertThat(indexDefinition("uq_usage_economics_outbox_fingerprint"))
                .contains("UNIQUE", "event_fingerprint");
        assertThat(indexDefinition("ix_usage_economics_outbox_due"))
                .contains("next_attempt_at", "created_at", "event_id", "PENDING", "FAILED");
        assertThat(indexDefinition("ix_usage_economics_outbox_stale_claim"))
                .contains("claimed_until", "event_id", "SENDING");

        assertRejected("""
                UPDATE gateway.provider_cost_record
                SET provider_input_cost_amount = 123,
                    provider_output_cost_amount = NULL
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET provider_cost_amount = NULL,
                    provider_cost_currency = NULL,
                    provider_input_cost_amount = 100,
                    provider_output_cost_amount = 356
                """);
        assertRejected("""
                UPDATE gateway.provider_cost_record
                SET provider_original_input_cost_amount = 10,
                    provider_original_output_cost_amount = 5,
                    provider_original_cost_amount = 15,
                    provider_original_cost_currency = 'USD',
                    provider_exchange_rate = NULL
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET provider_original_input_cost_amount = 10,
                    provider_original_output_cost_amount = 5,
                    provider_original_cost_amount = 15,
                    provider_original_cost_currency = 'USD',
                    provider_exchange_rate = NULL
                """);
        assertRejected("""
                UPDATE gateway.provider_cost_record
                SET provider_original_input_cost_amount = 'NaN'::numeric,
                    provider_original_output_cost_amount = 5,
                    provider_original_cost_amount = 'NaN'::numeric,
                    provider_original_cost_currency = 'USD',
                    provider_exchange_rate = 7.2
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET provider_original_input_cost_amount = 10,
                    provider_original_output_cost_amount = 5,
                    provider_original_cost_amount = 15,
                    provider_original_cost_currency = 'USD',
                    provider_exchange_rate = 'NaN'::numeric
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET cache_hit_tokens = -1
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET image_tokens = input_tokens + 1
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET reasoning_tokens = output_tokens + 1
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET cache_hit_tokens = input_tokens,
                    cache_write_tokens = 1
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET usage_source = 'UNKNOWN'
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET reconciliation_status = 'UNKNOWN'
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET raw_usage = '1'::jsonb
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET raw_usage = jsonb_build_object('payload', repeat('x', 16384))
                """);
        assertRejected("""
                UPDATE gateway.usage_stage
                SET provider_billable = false
                """);
        assertRejected("""
                INSERT INTO gateway.usage_economics_outbox(
                    event_id, event_fingerprint, payload, payload_hash,
                    status, claim_token, claimed_until)
                VALUES (
                    '30000000-0000-4000-8000-000000000001', repeat('a', 64), '{}', repeat('b', 64),
                    'SENDING', NULL, NULL)
                """);
        assertRejected("""
                INSERT INTO gateway.usage_economics_outbox(
                    event_id, event_fingerprint, payload, payload_hash, status, sent_at)
                VALUES (
                    '30000000-0000-4000-8000-000000000002', repeat('c', 64), '{}', repeat('d', 64),
                    'SENT', NULL)
                """);
        assertRejected("""
                INSERT INTO gateway.usage_economics_outbox(
                    event_id, event_fingerprint, payload, payload_hash,
                    status, claim_token, claimed_until, sent_at)
                VALUES (
                    '30000000-0000-4000-8000-000000000003', repeat('e', 64), '{}', repeat('f', 64),
                    'PENDING', '30000000-0000-4000-8000-000000000004', now(), now())
                """);

        insertLegacyUsageStageAfterMigration();
        assertThat(singleString("""
                SELECT usage_source || ':' || customer_billable || ':' || provider_billable
                    || ':' || reconciliation_status
                FROM gateway.usage_stage
                WHERE id = '21000000-0000-4000-8000-000000000001'
                """)).isEqualTo("LOCAL_ESTIMATED:true:true:PENDING");

        assertThat(migrate(null)).isZero();
        assertThat(singleLong("SELECT count(*) FROM gateway.provider_cost_record")).isOne();
        assertThat(singleLong("SELECT count(*) FROM gateway.usage_stage")).isEqualTo(4);
    }

    private void insertLegacyUsageStageAfterMigration() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            // 该 stage 引用的 usage 行，原因同上：
            // fk_usage_stage_usage_dimension 把这一对作为键。
            statement.executeUpdate("""
                    INSERT INTO gateway.usage_log(
                        id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                        package_instance_id, model_code, duration_ms, http_status, outcome)
                    VALUES (
                        '21000000-0000-4000-8000-000000000002', 'legacy-post-migration-usage',
                        '21000000-0000-4000-8000-000000000011',
                        '21000000-0000-4000-8000-000000000012',
                        '21000000-0000-4000-8000-000000000013',
                        '21000000-0000-4000-8000-000000000014',
                        '21000000-0000-4000-8000-000000000015', 'legacy-model', 100, 200,
                        'SUCCEEDED')
                    """);
            statement.executeUpdate("""
                    INSERT INTO gateway.usage_stage(
                        id, usage_log_id, stage_index, stage_type, logical_model_code,
                        model_id, provider_id, channel_id, upstream_model,
                        input_tokens, output_tokens, total_tokens, usage_estimated,
                        duration_ms, outcome, price_estimated)
                    VALUES (
                        '21000000-0000-4000-8000-000000000001',
                        '21000000-0000-4000-8000-000000000002', 0, 'FINAL_RESPONSE',
                        'legacy-model', '21000000-0000-4000-8000-000000000003',
                        '21000000-0000-4000-8000-000000000004',
                        '21000000-0000-4000-8000-000000000005', 'legacy-upstream',
                        10, 5, 15, true, 100, 'SUCCEEDED', true)
                    """);
            statement.execute("SET session_replication_role = origin");
        }
    }

    private void insertHistoricalCosts() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            // V49 让 stage 的 usage 行成为其标识的一部分
            // （对 (usage_log_id, usage_dimension) 的 fk_usage_stage_usage_dimension），
            // 因此历史 stage 行现在需要它所引用的 usage 行。这些父行与下面的行
            // 一样在同一个 replication-role 覆盖下写入：它们代表的是早于本测试所关注
            // 这些列的行，而不是曾经可连接的行。
            statement.executeUpdate("""
                    INSERT INTO gateway.usage_log(
                        id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                        package_instance_id, model_code, duration_ms, http_status, outcome)
                    VALUES
                        ('10000000-0000-4000-8000-000000000002', 'legacy-cost-usage',
                         '10000000-0000-4000-8000-000000000011',
                         '10000000-0000-4000-8000-000000000012',
                         '10000000-0000-4000-8000-000000000013',
                         '10000000-0000-4000-8000-000000000014',
                         '10000000-0000-4000-8000-000000000015', 'legacy-model', 100, 200,
                         'SUCCEEDED'),
                        ('20000000-0000-4000-8000-000000000002', 'legacy-stage-usage',
                         '20000000-0000-4000-8000-000000000011',
                         '20000000-0000-4000-8000-000000000012',
                         '20000000-0000-4000-8000-000000000013',
                         '20000000-0000-4000-8000-000000000014',
                         '20000000-0000-4000-8000-000000000015', 'legacy-model', 100, 200,
                         'SUCCEEDED')
                    """);
            statement.executeUpdate("""
                    INSERT INTO gateway.provider_cost_record(
                        id, usage_log_id, provider_id, channel_id, model_code,
                        input_tokens, output_tokens, amount, currency, price_estimated)
                    VALUES (
                        '10000000-0000-4000-8000-000000000001',
                        '10000000-0000-4000-8000-000000000002',
                        '10000000-0000-4000-8000-000000000003',
                        '10000000-0000-4000-8000-000000000004',
                        'legacy-model', 10, 5, 123, 'CNY', false)
                    """);
            statement.executeUpdate("""
                    INSERT INTO gateway.usage_stage(
                        id, usage_log_id, stage_index, stage_type, logical_model_code,
                        model_id, provider_id, channel_id, upstream_model,
                        input_tokens, output_tokens, total_tokens, usage_estimated,
                        duration_ms, outcome, provider_cost_amount,
                        provider_cost_currency, price_estimated)
                    VALUES (
                        '20000000-0000-4000-8000-000000000001',
                        '20000000-0000-4000-8000-000000000002', 0, 'FINAL_RESPONSE',
                        'legacy-model', '20000000-0000-4000-8000-000000000003',
                        '20000000-0000-4000-8000-000000000004',
                        '20000000-0000-4000-8000-000000000005', 'legacy-upstream',
                        10, 5, 15, false, 100, 'SUCCEEDED', 456, 'CNY', false)
                    """);
            statement.execute("SET session_replication_role = origin");
        }
    }

    private void assertHistoricalCostShape(
            String table, String id, String totalColumn, String currencyColumn)
            throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT " + totalColumn + ", " + currencyColumn + ", "
                        + "price_estimated, provider_price_id, provider_input_cost_amount, "
                        + "provider_output_cost_amount, provider_original_input_cost_amount, "
                        + "provider_original_output_cost_amount, provider_original_cost_amount, "
                        + "provider_original_cost_currency, provider_exchange_rate FROM gateway." + table
                        + " WHERE id = '" + id + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(totalColumn)).isPositive();
            assertThat(result.getString(currencyColumn)).isEqualTo("CNY");
            assertThat(result.getBoolean("price_estimated")).isTrue();
            for (int column = 4; column <= 11; column++) {
                assertThat(result.getObject(column)).isNull();
            }
            assertThat(result.next()).isFalse();
        }
    }

    private void assertRejected(String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).isInstanceOf(SQLException.class);
    }

    private int migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), GATEWAY_USER, GATEWAY_PASSWORD)
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load().migrate().migrationsExecuted;
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = adminConnection();
                ResultSet result = connection.getMetaData().getColumns(null, "gateway", table, "%")) {
            while (result.next()) {
                names.add(result.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private String constraintDefinition(String table, String constraint) throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE connamespace = 'gateway'::regnamespace
                          AND conrelid = ('gateway.' || ?)::regclass
                          AND conname = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String indexDefinition(String index) throws SQLException {
        try (Connection connection = adminConnection();
                var statement = connection.prepareStatement("""
                        SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'gateway' AND indexname = ?
                        """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private void insertHistoricalFailover() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.executeUpdate("""
                    INSERT INTO gateway.usage_log(
                        id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                        package_instance_id, model_id, model_code, provider_id, channel_id,
                        input_tokens, output_tokens, total_tokens, usage_estimated, stream,
                        duration_ms, http_status, outcome)
                    VALUES (
                        '22000000-0000-4000-8000-000000000001', 'legacy-failover',
                        '22000000-0000-4000-8000-000000000002',
                        '22000000-0000-4000-8000-000000000003',
                        '22000000-0000-4000-8000-000000000004',
                        '22000000-0000-4000-8000-000000000005',
                        '22000000-0000-4000-8000-000000000006',
                        '22000000-0000-4000-8000-000000000007', 'legacy-model',
                        '22000000-0000-4000-8000-000000000008',
                        '22000000-0000-4000-8000-000000000009',
                        13, 4, 17, false, false, 120, 200, 'SUCCEEDED')
                    """);
            statement.executeUpdate("""
                    INSERT INTO gateway.usage_stage(
                        id, usage_log_id, stage_index, stage_type, logical_model_code,
                        model_id, provider_id, channel_id, upstream_model,
                        input_tokens, output_tokens, total_tokens, usage_estimated,
                        duration_ms, outcome, provider_cost_amount,
                        provider_cost_currency, price_estimated)
                    VALUES
                        (
                            '22000000-0000-4000-8000-000000000010',
                            '22000000-0000-4000-8000-000000000001', 0, 'FINAL_RESPONSE',
                            'legacy-model', '22000000-0000-4000-8000-000000000007',
                            '22000000-0000-4000-8000-000000000008',
                            '22000000-0000-4000-8000-000000000009', 'legacy-upstream-a',
                            8, 1, 9, false, 50, 'FAILED', 9, 'CNY', false
                        ),
                        (
                            '22000000-0000-4000-8000-000000000011',
                            '22000000-0000-4000-8000-000000000001', 1, 'FINAL_RESPONSE',
                            'legacy-model', '22000000-0000-4000-8000-000000000007',
                            '22000000-0000-4000-8000-000000000008',
                            '22000000-0000-4000-8000-000000000009', 'legacy-upstream-b',
                            5, 3, 8, false, 70, 'SUCCEEDED', 8, 'CNY', false
                        )
                    """);
            statement.execute("SET session_replication_role = origin");
        }
    }

    private String singleString(String sql) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
