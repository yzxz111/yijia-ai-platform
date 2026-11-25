package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CommercialTransactionMigrationTest {

    private static final String USER_ID = "10000000-0000-4000-8000-000000000001";
    private static final String PACKAGE_VERSION_ID = "00000000-0000-4000-8000-000000000102";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void migratesLegacyCommercialTransactionsWithoutLosingBusinessData() throws SQLException {
        migrateThrough("6");
        seedLegacyTransactions();

        migrateThrough("7");

        assertThat(columnNames("customer_order"))
                .as("V7 customer_order columns")
                .contains("expires_at", "next_close_at", "package_snapshot");
        assertPendingOrderSchedule();
        assertCompleteOrderSnapshots();
        assertMockPaymentProducts();
        assertMockPaymentConfigurationDisabled();
        assertLegacyRefunds();
        assertThat(indexDefinition("payment_transaction", "ix_payment_reconciliation_success"))
                .contains("USING btree (provider, paid_at, transaction_no)")
                .contains("WHERE ((status)::text = 'SUCCEEDED'::text)");
        assertThat(indexDefinition("refund", "ix_refund_reconciliation_completed"))
                .contains("USING btree (provider, completed_at, refund_no)")
                .contains("WHERE ((status)::text = 'COMPLETED'::text)");
        assertCommercialConstraintsRejectInvalidWrites();
    }

    private void migrateThrough(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private void seedLegacyTransactions() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO platform.app_user(
                        id, tenant_id, email, password_hash, display_name)
                    VALUES (
                        '10000000-0000-4000-8000-000000000001',
                        '10000000-0000-4000-8000-000000000002',
                        'legacy@example.test', 'hash', 'Legacy User')
                    """);
            statement.executeUpdate("""
                    INSERT INTO platform.customer_order(
                        id, order_no, tenant_id, user_id, package_plan_version_id,
                        amount, currency, status, payment_provider, paid_at, created_at, updated_at)
                    VALUES
                        ('20000000-0000-4000-8000-000000000001', 'legacy-pending',
                         '10000000-0000-4000-8000-000000000002', '%s', '%s',
                         1900, 'CNY', 'PENDING', 'MOCK', NULL,
                         '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
                        ('20000000-0000-4000-8000-000000000002', 'legacy-paid-pending-refund',
                         '10000000-0000-4000-8000-000000000002', '%s', '%s',
                         1900, 'CNY', 'PAID', 'MOCK', '2026-01-02T00:01:00Z',
                         '2026-01-02T00:00:00Z', '2026-01-02T00:01:00Z'),
                        ('20000000-0000-4000-8000-000000000003', 'legacy-paid-succeeded-refund',
                         '10000000-0000-4000-8000-000000000002', '%s', '%s',
                         2500, 'CNY', 'PAID', 'MOCK', '2026-01-03T00:01:00Z',
                         '2026-01-03T00:00:00Z', '2026-01-03T00:01:00Z'),
                        ('20000000-0000-4000-8000-000000000004', 'legacy-paid-failed-refund',
                         '10000000-0000-4000-8000-000000000002', '%s', '%s',
                         3200, 'CNY', 'PAID', 'MOCK', '2026-01-04T00:01:00Z',
                         '2026-01-04T00:00:00Z', '2026-01-04T00:01:00Z')
                    """.formatted(USER_ID, PACKAGE_VERSION_ID, USER_ID, PACKAGE_VERSION_ID,
                            USER_ID, PACKAGE_VERSION_ID, USER_ID, PACKAGE_VERSION_ID));
            statement.executeUpdate("""
                    INSERT INTO platform.payment_transaction(
                        id, transaction_no, order_id, provider, external_trade_no,
                        amount, status, paid_at, created_at, updated_at)
                    VALUES
                        ('30000000-0000-4000-8000-000000000001', 'legacy-tx-pending-refund',
                         '20000000-0000-4000-8000-000000000002', 'MOCK', 'legacy-trade-1',
                         1900, 'SUCCEEDED', '2026-01-02T00:01:00Z',
                         '2026-01-02T00:00:30Z', '2026-01-02T00:01:00Z'),
                        ('30000000-0000-4000-8000-000000000002', 'legacy-tx-succeeded-refund',
                         '20000000-0000-4000-8000-000000000003', 'MOCK', 'legacy-trade-2',
                         2500, 'SUCCEEDED', '2026-01-03T00:01:00Z',
                         '2026-01-03T00:00:30Z', '2026-01-03T00:01:00Z'),
                        ('30000000-0000-4000-8000-000000000003', 'legacy-tx-failed-refund',
                         '20000000-0000-4000-8000-000000000004', 'MOCK', 'legacy-trade-3',
                         3200, 'SUCCEEDED', '2026-01-04T00:01:00Z',
                         '2026-01-04T00:00:30Z', '2026-01-04T00:01:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO platform.refund(
                        id, refund_no, order_id, payment_transaction_id, external_refund_no,
                        amount, status, reason, completed_at, created_at, updated_at)
                    VALUES
                        ('40000000-0000-4000-8000-000000000001', 'legacy-refund-pending',
                         '20000000-0000-4000-8000-000000000002',
                         '30000000-0000-4000-8000-000000000001', 'legacy-external-refund-1',
                         1900, 'PENDING', 'legacy pending', NULL,
                         '2026-01-02T00:02:00Z', '2026-01-02T00:02:00Z'),
                        ('40000000-0000-4000-8000-000000000002', 'legacy-refund-succeeded',
                         '20000000-0000-4000-8000-000000000003',
                         '30000000-0000-4000-8000-000000000002', 'legacy-external-refund-2',
                         1000, 'SUCCEEDED', 'legacy succeeded', '2026-01-03T00:03:00Z',
                         '2026-01-03T00:02:00Z', '2026-01-03T00:03:00Z'),
                        ('40000000-0000-4000-8000-000000000003', 'legacy-refund-failed',
                         '20000000-0000-4000-8000-000000000004',
                         '30000000-0000-4000-8000-000000000003', 'legacy-external-refund-3',
                         3200, 'FAILED', 'legacy failed', NULL,
                         '2026-01-04T00:02:00Z', '2026-01-04T00:03:00Z')
                    """);
        }
    }

    private void assertPendingOrderSchedule() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT created_at, expires_at, next_close_at
                        FROM platform.customer_order
                        WHERE order_no = 'legacy-pending'
                        """);
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            OffsetDateTime createdAt = result.getObject("created_at", OffsetDateTime.class);
            assertThat(result.getObject("expires_at", OffsetDateTime.class)).isEqualTo(createdAt.plusMinutes(15));
            assertThat(result.getObject("next_close_at", OffsetDateTime.class)).isEqualTo(createdAt.plusMinutes(15));
        }
    }

    private void assertCompleteOrderSnapshots() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM platform.customer_order
                        WHERE package_snapshot IS NOT NULL
                          AND package_snapshot ?& ARRAY[
                              'packageVersionId', 'packageCode', 'packageName', 'priceAmount',
                              'currency', 'totalTokenQuota', 'durationDays', 'maxApiKeys',
                              'requestsPerMinute', 'tokensPerMinute', 'maxConcurrency',
                              'dailyRequestLimit', 'maxOutputTokens', 'maxFileSizeBytes',
                              'oncePerUser', 'models']
                          AND (package_snapshot ->> 'priceAmount')::bigint = amount
                          AND package_snapshot ->> 'currency' = currency
                          AND jsonb_array_length(package_snapshot -> 'models') = 4
                          AND package_snapshot -> 'models' -> 0 ->> 'modelCode' = 'file-basic'
                          AND package_snapshot -> 'models' -> 1 ->> 'modelCode' = 'text-basic'
                          AND package_snapshot -> 'models' -> 2 ->> 'modelCode' = 'text-standard'
                          AND package_snapshot -> 'models' -> 3 ->> 'modelCode' = 'vision-basic'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM jsonb_array_elements(package_snapshot -> 'models') AS model
                              WHERE NOT model ?& ARRAY['modelCode', 'modelTokenQuota', 'capabilities'])
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(4L);
        }
    }

    private void assertMockPaymentProducts() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM platform.payment_transaction
                        WHERE provider = 'MOCK' AND payment_product = 'MOCK'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(3L);
        }
    }

    private void assertMockPaymentConfigurationDisabled() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT enabled
                        FROM platform.payment_config
                        WHERE provider = 'MOCK' AND environment = 'LOCAL'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean("enabled")).isFalse();
            assertThat(result.next()).isFalse();
        }
    }

    private void assertLegacyRefunds() throws SQLException {
        List<String> refunds = new ArrayList<>();
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT refund_no, status, amount, external_refund_no, order_id,
                               payment_transaction_id, requested_by, provider,
                               gateway_hold_business_no, revocation_business_no
                        FROM platform.refund
                        ORDER BY refund_no
                        """)) {
            while (result.next()) {
                refunds.add(String.join("|",
                        result.getString("refund_no"),
                        result.getString("status"),
                        result.getString("amount"),
                        result.getString("external_refund_no"),
                        result.getString("order_id"),
                        result.getString("payment_transaction_id"),
                        result.getString("requested_by"),
                        result.getString("provider"),
                        result.getString("gateway_hold_business_no"),
                        result.getString("revocation_business_no")));
            }
        }
        assertThat(refunds).containsExactly(
                "legacy-refund-failed|FAILED|3200|legacy-external-refund-3|"
                        + "20000000-0000-4000-8000-000000000004|30000000-0000-4000-8000-000000000003|"
                        + USER_ID + "|MOCK|legacy-hold:legacy-refund-failed|legacy-revoke:legacy-refund-failed",
                "legacy-refund-pending|PROCESSING|1900|legacy-external-refund-1|"
                        + "20000000-0000-4000-8000-000000000002|30000000-0000-4000-8000-000000000001|"
                        + USER_ID + "|MOCK|legacy-hold:legacy-refund-pending|legacy-revoke:legacy-refund-pending",
                "legacy-refund-succeeded|COMPLETED|1000|legacy-external-refund-2|"
                        + "20000000-0000-4000-8000-000000000003|30000000-0000-4000-8000-000000000002|"
                        + USER_ID + "|MOCK|legacy-hold:legacy-refund-succeeded|legacy-revoke:legacy-refund-succeeded");
    }

    private void assertCommercialConstraintsRejectInvalidWrites() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status, payment_product)
                VALUES (
                    '50000000-0000-4000-8000-000000000001', 'active-attempt-1',
                    '20000000-0000-4000-8000-000000000001', 'MOCK', 1900, 'PENDING', 'MOCK')
                """);
        assertSqlRejected("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status, payment_product)
                VALUES (
                    '50000000-0000-4000-8000-000000000002', 'active-attempt-2',
                    '20000000-0000-4000-8000-000000000001', 'MOCK', 1900, 'CREATED', 'MOCK')
                """, "uq_payment_active_attempt_per_order");

        executeUpdate("""
                UPDATE platform.payment_transaction
                SET applied_to_order = true
                WHERE transaction_no = 'legacy-tx-pending-refund'
                """);
        assertSqlRejected("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount, status,
                    payment_product, applied_to_order)
                VALUES (
                    '50000000-0000-4000-8000-000000000003', 'applied-payment-2',
                    '20000000-0000-4000-8000-000000000002', 'MOCK', 1900, 'SUCCEEDED',
                    'MOCK', true)
                """, "uq_payment_applied_per_order");

        assertSqlRejected("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status, reason)
                VALUES (
                    '60000000-0000-4000-8000-000000000001', 'second-open-refund',
                    '20000000-0000-4000-8000-000000000002',
                    '30000000-0000-4000-8000-000000000001', 0, 'PREPARING', 'duplicate open')
                """, "uq_refund_open_per_order");

        assertSqlRejected("""
                UPDATE platform.payment_transaction
                SET payment_product = 'UNSUPPORTED'
                WHERE transaction_no = 'legacy-tx-succeeded-refund'
                """, "ck_payment_product");
        assertSqlRejected("""
                UPDATE platform.refund
                SET status = 'SUCCEEDED'
                WHERE refund_no = 'legacy-refund-succeeded'
                """, "ck_refund_status");
        assertSqlRejected("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id, amount, status, reason,
                    refund_type, calculation_snapshot)
                VALUES (
                    '60000000-0000-4000-8000-000000000002', 'invalid-zero-refund',
                    '20000000-0000-4000-8000-000000000004',
                    '30000000-0000-4000-8000-000000000003', 0, 'PENDING_APPROVAL',
                    'invalid amount', 'FULL', '{}'::jsonb)
                """, "ck_refund_amount_state");
    }

    private void executeUpdate(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void assertSqlRejected(String sql, String constraint) {
        assertThatThrownBy(() -> executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
    }

    private Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'platform' AND table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        assertThat(names).as("columns for platform.%s", table).isNotEmpty();
        return names;
    }

    private String indexDefinition(String table, String index) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'platform' AND tablename = ? AND indexname = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("index platform.%s", index).isTrue();
                String definition = result.getString(1).replaceAll("\\s+", " ");
                assertThat(result.next()).as("single definition for platform.%s", index).isFalse();
                return definition;
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
