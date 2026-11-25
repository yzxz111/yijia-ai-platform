package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OperatingReportingMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrate() throws SQLException {
        installBtreeGist();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void createsOperatingReportingTablesAndDimensions() throws SQLException {
        assertThat(extensionSchema("btree_gist")).isEqualTo("public");
        assertThat(tableNames()).contains(
                "operating_fact",
                "payment_fee_rate_version",
                "provider_price_snapshot",
                "operating_daily_aggregate",
                "operating_reconciliation_run",
                "operating_reconciliation_item",
                "operating_alert",
                "operating_backfill_checkpoint");

        assertThat(columnNames("operating_fact")).contains(
                "source_service", "source_event_id", "line_no", "event_type",
                "occurred_at", "recognized_at", "tenant_id", "user_id", "order_id",
                "package_plan_version_id", "payment_transaction_id", "refund_id", "request_id",
                "logical_model", "upstream_model", "channel", "provider",
                "input_tokens", "output_tokens", "total_tokens", "billing_mode",
                "confirmed_revenue_amount", "cash_in_amount", "refund_amount",
                "provider_input_cost_amount", "provider_output_cost_amount",
                "payment_provider_fee_amount", "refund_provider_fee_amount",
                "allocated_usage_revenue_amount", "currency", "price_snapshot_id",
                "payment_fee_rate_version_id", "estimated", "missing_source", "metadata", "sign", "reversal");
        assertThat(columnNames("operating_daily_aggregate")).contains(
                "business_date", "timezone", "tenant_id", "logical_model", "upstream_model",
                "channel", "provider", "billing_mode", "currency", "confirmed_revenue_amount",
                "cash_in_amount", "refund_amount", "provider_cost_amount", "payment_provider_fee_amount",
                "refund_provider_fee_amount", "allocated_usage_revenue_amount", "profit_amount",
                "input_tokens", "output_tokens", "total_tokens", "estimated_count", "missing_source_count");
        assertThat(columnNames("provider_price_snapshot")).contains(
                "cache_read_price", "cache_write_price", "reasoning_price");

        assertThat(indexDefinition("uq_operating_fact_source_line"))
                .contains("UNIQUE", "source_service", "source_event_id", "line_no");
        assertThat(indexDefinition("ix_operating_fact_dirty_recompute"))
                .contains("recognized_at", "tenant_id");
        assertThat(indexDefinition("ix_operating_backfill_checkpoint_claim"))
                .contains("status", "next_attempt_at");
    }

    @Test
    void enforcesAppendOnlyFactsAndSourceLineIdempotency() throws SQLException {
        insertValidFact("event-1", 1);

        assertSqlRejected(validFactSql("event-1", 1), "uq_operating_fact_source_line");
        assertSqlRejected(
                "UPDATE platform.operating_fact SET event_type = 'CHANGED' WHERE source_event_id = 'event-1'",
                "operating_fact_append_only");
        assertSqlRejected(
                "DELETE FROM platform.operating_fact WHERE source_event_id = 'event-1'",
                "operating_fact_append_only");
    }

    @Test
    void rejectsInvalidFactsIncludingNullNegativeAndSpecialNumericValues() {
        assertSqlRejected(validFactSql("event-null", 1)
                .replace("0.000000, 'CNY'", "NULL, 'CNY'"), "not-null");
        assertSqlRejected(validFactSql("event-negative", 1)
                .replace("0.000000, 'CNY'", "-0.000001, 'CNY'"), "ck_operating_fact_amounts");
        assertSqlRejected(validFactSql("event-nan", 1)
                .replace("0.000000, 'CNY'", "'NaN'::numeric, 'CNY'"), "ck_operating_fact_amounts");
        assertSqlRejected(validFactSql("event-infinity", 1)
                .replace("0.000000, 'CNY'", "'Infinity'::numeric, 'CNY'"));
        assertSqlRejected(validFactSql("event-billing", 1)
                .replace("'PACKAGE_ONLY'", "'OTHER'"), "ck_operating_fact_billing_mode");
        assertSqlRejected(validFactSql("event-sign", 1)
                .replace("'PACKAGE_ONLY', 1,", "'PACKAGE_ONLY', 0,"), "ck_operating_fact_sign");
        assertSqlRejected(validFactSql("event-reversal-positive", 1)
                .replace("billing_mode, sign,", "billing_mode, sign, reversal,")
                .replace("'PACKAGE_ONLY', 1,", "'PACKAGE_ONLY', 1, true,"),
                "ck_operating_fact_reversal_sign");
        assertSqlRejected(validFactSql("event-normal-negative", 1)
                .replace("billing_mode, sign,", "billing_mode, sign, reversal,")
                .replace("'PACKAGE_ONLY', 1,", "'PACKAGE_ONLY', -1, false,"),
                "ck_operating_fact_reversal_sign");
    }

    @Test
    void rejectsInvalidRateAndPriceSnapshotNumerics() {
        assertSqlRejected("""
                INSERT INTO platform.payment_fee_rate_version(
                    provider, product, effective_from, payment_fee_rate, refund_fee_rate)
                VALUES ('ALIPAY', 'QR', now(), 'NaN'::numeric, 0.001000)
                """, "ck_payment_fee_rates");
        assertSqlRejected("""
                INSERT INTO platform.provider_price_snapshot(
                    provider, logical_model, upstream_model, input_price, output_price,
                    currency, exchange_rate, price_source, effective_from, original_price_id)
                VALUES ('OPENAI', 'text-basic', 'model-a', 0.001000, 'Infinity'::numeric,
                    'USD', 7.000000, 'PROVIDER_API', now(), 'price-1')
                """);
        assertSqlRejected("""
                INSERT INTO platform.provider_price_snapshot(
                    provider, logical_model, upstream_model, input_price, output_price,
                    currency, exchange_rate, price_source, effective_from, original_price_id)
                VALUES ('OPENAI', 'text-basic', 'model-a', 0.001000, 0.002000,
                    'USD', 0.000000, 'PROVIDER_API', now(), 'price-2')
                """, "ck_provider_exchange_rate");
        assertSqlRejected("""
                INSERT INTO platform.provider_price_snapshot(
                    provider, logical_model, upstream_model, input_price, output_price,
                    cache_read_price, currency, exchange_rate, price_source,
                    effective_from, original_price_id)
                VALUES ('OPENAI', 'text-basic', 'model-category-price', 0.001000, 0.002000,
                    -0.001000, 'USD', 7.000000, 'PROVIDER_API', now(), 'price-category-negative')
                """, "ck_provider_category_prices");
    }

    @Test
    void rejectsOverlappingFeeAndPriceVersionsButAllowsTouchingBoundaries() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.payment_fee_rate_version(
                    provider, product, effective_from, effective_to, payment_fee_rate, refund_fee_rate)
                VALUES ('ALIPAY-OVERLAP', 'QR', '2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z',
                    0.010000, 0.005000)
                """);
        executeUpdate("""
                INSERT INTO platform.payment_fee_rate_version(
                    provider, product, effective_from, effective_to, payment_fee_rate, refund_fee_rate)
                VALUES ('ALIPAY-OVERLAP', 'QR', '2026-02-01T00:00:00Z', '2026-03-01T00:00:00Z',
                    0.011000, 0.006000)
                """);
        assertSqlRejected("""
                INSERT INTO platform.payment_fee_rate_version(
                    provider, product, effective_from, effective_to, payment_fee_rate, refund_fee_rate)
                VALUES ('ALIPAY-OVERLAP', 'QR', '2026-01-15T00:00:00Z', '2026-02-15T00:00:00Z',
                    0.012000, 0.007000)
                """, "ex_payment_fee_rate_no_overlap");

        executeUpdate("""
                INSERT INTO platform.provider_price_snapshot(
                    provider, logical_model, upstream_model, input_price, output_price,
                    currency, exchange_rate, price_source, effective_from, effective_to, original_price_id)
                VALUES ('OPENAI-OVERLAP', 'text-basic', 'model-overlap', 0.001000, 0.002000,
                    'USD', 7.000000, 'PROVIDER_API', '2026-01-01T00:00:00Z',
                    '2026-02-01T00:00:00Z', 'price-overlap-1')
                """);
        executeUpdate("""
                INSERT INTO platform.provider_price_snapshot(
                    provider, logical_model, upstream_model, input_price, output_price,
                    currency, exchange_rate, price_source, effective_from, effective_to, original_price_id)
                VALUES ('OPENAI-OVERLAP', 'text-basic', 'model-overlap', 0.001100, 0.002100,
                    'USD', 7.000000, 'PROVIDER_API', '2026-02-01T00:00:00Z',
                    '2026-03-01T00:00:00Z', 'price-overlap-2')
                """);
        assertSqlRejected("""
                INSERT INTO platform.provider_price_snapshot(
                    provider, logical_model, upstream_model, input_price, output_price,
                    currency, exchange_rate, price_source, effective_from, effective_to, original_price_id)
                VALUES ('OPENAI-OVERLAP', 'text-basic', 'model-overlap', 0.001200, 0.002200,
                    'USD', 7.000000, 'PROVIDER_API', '2026-01-15T00:00:00Z',
                    '2026-02-15T00:00:00Z', 'price-overlap-3')
                """, "ex_provider_price_no_overlap");
    }

    @Test
    void treatsNullDailyDimensionsAsOneUniqueCombination() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.operating_daily_aggregate(business_date, timezone, currency)
                VALUES ('2026-08-08', 'Asia/Shanghai', 'CNY')
                """);
        assertSqlRejected("""
                INSERT INTO platform.operating_daily_aggregate(business_date, timezone, currency)
                VALUES ('2026-08-08', 'Asia/Shanghai', 'CNY')
                """, "uq_operating_daily_dimensions");
    }

    @Test
    void factInsertSerializesWithDailyRecomputeAndLeavesDirtyMarker() throws Exception {
        assertThat(functionDefinition("mark_operating_day_dirty"))
                .contains("pg_advisory_xact_lock")
                .contains("1357911")
                .contains("2000-01-01");
        try (Connection recompute = connection()) {
            recompute.setAutoCommit(false);
            recompute.createStatement().execute("""
                    SELECT pg_advisory_xact_lock(
                        1357911, DATE '2026-09-01' - DATE '2000-01-01')
                    """);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var insert = executor.submit(() -> {
                    executeUpdate(validFactSql("serialized-insert", 1).replace(
                            "now(), now()",
                            "'2026-09-01T01:00:00Z', '2026-09-01T01:00:00Z'"));
                    return true;
                });
                assertThatThrownBy(() -> insert.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                recompute.commit();
                assertThat(insert.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
        assertThat(queryBoolean("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.operating_daily_aggregate
                    WHERE business_date='2026-09-01' AND currency='CNY' AND needs_recompute)
                """)).isTrue();
    }

    private static String validFactSql(String eventId, int lineNo) {
        return """
                INSERT INTO platform.operating_fact(
                    source_service, source_event_id, line_no, event_type, occurred_at, recognized_at,
                    billing_mode, sign, confirmed_revenue_amount, cash_in_amount, refund_amount,
                    provider_input_cost_amount, provider_output_cost_amount,
                    payment_provider_fee_amount, refund_provider_fee_amount,
                    allocated_usage_revenue_amount, currency)
                VALUES ('gateway', '%s', %d, 'USAGE', now(), now(), 'PACKAGE_ONLY', 1,
                    0.000000, 0.000000, 0.000000, 0.000000, 0.000000,
                    0.000000, 0.000000, 0.000000, 'CNY')
                """.formatted(eventId, lineNo);
    }

    private static void insertValidFact(String eventId, int lineNo) throws SQLException {
        executeUpdate(validFactSql(eventId, lineNo));
    }

    private static void executeUpdate(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void assertSqlRejected(String sql, String expectedMessage) {
        assertThatThrownBy(() -> executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static void assertSqlRejected(String sql) {
        assertThatThrownBy(() -> executeUpdate(sql)).isInstanceOf(SQLException.class);
    }

    private static Set<String> tableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection();
                ResultSet result = connection.getMetaData().getTables(null, "platform", "%", new String[] {"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static void installBtreeGist() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
        }
    }

    private static String extensionSchema(String extension) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT namespace.nspname
                        FROM pg_extension AS extension
                        JOIN pg_namespace AS namespace ON namespace.oid = extension.extnamespace
                        WHERE extension.extname = ?
                        """)) {
            statement.setString(1, extension);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("extension %s exists", extension).isTrue();
                return result.getString(1);
            }
        }
    }

    private static Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'platform' AND table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private static String indexDefinition(String index) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'platform' AND indexname = ?
                        """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("index platform.%s exists", index).isTrue();
                return result.getString(1);
            }
        }
    }

    private static String functionDefinition(String function) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT pg_get_functiondef(procedure.oid)
                        FROM pg_proc procedure
                        JOIN pg_namespace namespace ON namespace.oid=procedure.pronamespace
                        WHERE namespace.nspname='platform' AND procedure.proname=?
                        """)) {
            statement.setString(1, function);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static boolean queryBoolean(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
