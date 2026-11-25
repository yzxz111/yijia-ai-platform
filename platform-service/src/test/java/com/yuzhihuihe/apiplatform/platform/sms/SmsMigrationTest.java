package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SmsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void createsSecureSmsStateAndSeedsApprovedTemplates() throws SQLException {
        migrate();

        assertThat(columnNames("app_user")).contains(
                "phone_ciphertext", "phone_lookup_hash", "phone_last4",
                "phone_verified_at", "sms_notifications_enabled");
        assertThat(columnNames("app_user")).doesNotContain("phone", "phone_number", "mobile");
        assertThat(tableNames()).contains(
                "sms_template", "sms_verification_challenge", "sms_proof", "sms_delivery");
        assertThat(indexDefinition("uq_app_user_phone_lookup_hash"))
                .startsWith("CREATE UNIQUE INDEX uq_app_user_phone_lookup_hash")
                .contains("(phone_lookup_hash)")
                .contains("WHERE (phone_lookup_hash IS NOT NULL)");
        assertThat(indexDefinition("uq_sms_delivery_deduplication"))
                .startsWith("CREATE UNIQUE INDEX uq_sms_delivery_deduplication")
                .contains("(deduplication_key)")
                .doesNotContain(" WHERE ");
        assertThat(indexDefinition("ix_sms_challenge_phone_daily"))
                .contains("(phone_lookup_hash, created_at DESC)")
                .doesNotContain(" WHERE ");
        assertThat(constraintDefinition("sms_verification_challenge", "ck_sms_challenge_purpose"))
                .contains("REGISTER", "CHANGE_PASSWORD", "PAYMENT_CONFIRM", "API_KEY_RESET",
                        "PHONE_BIND", "PHONE_CHANGE", "PHONE_CHANGE_CONFIRM");
        assertThat(constraintDefinition("sms_proof", "ck_sms_proof_purpose"))
                .contains("CHANGE_PASSWORD", "PAYMENT_CONFIRM", "API_KEY_RESET",
                        "PHONE_CHANGE_CONFIRM");
        assertThat(constraintDefinition("sms_delivery", "ck_sms_delivery_status"))
                .contains("PENDING", "SENDING", "SENT", "FAILED", "DEAD");
        assertThat(constraintDefinition("app_user", "uq_app_user_tenant_id"))
                .isEqualTo("UNIQUE (tenant_id, id)");
        assertThat(constraintDefinition("sms_verification_challenge", "fk_sms_challenge_user"))
                .contains("FOREIGN KEY (tenant_id, user_id)", "(tenant_id, id)", "ON DELETE CASCADE");
        assertThat(constraintDefinition("sms_proof", "fk_sms_proof_user"))
                .contains("FOREIGN KEY (tenant_id, user_id)", "(tenant_id, id)", "ON DELETE CASCADE");
        assertThat(constraintDefinition("sms_delivery", "fk_sms_delivery_user"))
                .contains("FOREIGN KEY (tenant_id, user_id)", "(tenant_id, id)",
                        "ON DELETE SET NULL (user_id)");
    }

    @Test
    void storesSensitiveDeliveryVariablesOnlyAsCiphertext() throws SQLException {
        migrate();

        assertThat(columnType("sms_delivery", "sensitive_variables_ciphertext"))
                .isEqualTo("bytea");
        assertThat(constraintDefinition("sms_delivery", "ck_sms_delivery_no_plaintext_code"))
                .contains("code");
        assertCheckRejected("""
                INSERT INTO platform.sms_delivery(
                    id, template_key, template_code, phone_ciphertext, phone_last4,
                    variables, deduplication_key)
                VALUES (
                    '10000000-0000-0000-0000-000000000001',
                    'REGISTER_CODE', 'SMS_511405090', decode('00', 'hex'), '0000',
                    '{"code":"redacted"}'::jsonb, 'plaintext-code-must-be-rejected')
                """, "ck_sms_delivery_no_plaintext_code");
    }

    @Test
    void capsChallengeLifetimeAtFiveMinutes() {
        migrate();

        assertCheckRejected(challengeInsert(
                "20000000-0000-0000-0000-000000000001",
                "2026-08-04 00:05:01+00", null, null), "ck_sms_challenge_times");
    }

    @Test
    void rejectsChallengeVerificationAfterExpiry() {
        migrate();

        assertCheckRejected(challengeInsert(
                "20000000-0000-0000-0000-000000000002",
                "2026-08-04 00:05:00+00", "2026-08-04 00:05:01+00", null),
                "ck_sms_challenge_times");
    }

    @Test
    void rejectsChallengeConsumptionAfterExpiry() {
        migrate();

        assertCheckRejected(challengeInsert(
                "20000000-0000-0000-0000-000000000003",
                "2026-08-04 00:05:00+00", "2026-08-04 00:01:00+00",
                "2026-08-04 00:05:01+00"), "ck_sms_challenge_times");
    }

    @Test
    void rejectsChallengeConsumptionBeforeVerification() {
        migrate();

        assertCheckRejected(challengeInsert(
                "20000000-0000-0000-0000-000000000004",
                "2026-08-04 00:05:00+00", "2026-08-04 00:04:00+00",
                "2026-08-04 00:03:00+00"), "ck_sms_challenge_times");
    }

    @Test
    void acceptsRegistrationChallengeAtEqualityAndExpiryBoundary() throws SQLException {
        migrate();

        executeSql(challengeInsert(
                "20000000-0000-0000-0000-000000000005",
                "2026-08-04 00:05:00+00", "2026-08-04 00:05:00+00",
                "2026-08-04 00:05:00+00"));
    }

    @Test
    void rejectsProofConsumptionAfterExpiry() throws SQLException {
        migrate();
        executeSql("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES (
                    '30000000-0000-0000-0000-000000000001',
                    '30000000-0000-0000-0000-000000000002',
                    'sms-proof-migration@example.invalid', 'test-hash', 'SMS proof migration')
                """);

        assertCheckRejected("""
                INSERT INTO platform.sms_proof(
                    id, token_hash, tenant_id, user_id, purpose,
                    expires_at, consumed_at, created_at)
                VALUES (
                    '30000000-0000-0000-0000-000000000003', repeat('d', 64),
                    '30000000-0000-0000-0000-000000000002',
                    '30000000-0000-0000-0000-000000000001', 'CHANGE_PASSWORD',
                    TIMESTAMPTZ '2026-08-04 00:01:00+00',
                    TIMESTAMPTZ '2026-08-04 00:01:01+00',
                    TIMESTAMPTZ '2026-08-04 00:00:00+00')
                """, "ck_sms_proof_times");
    }

    @Test
    void rejectsCrossTenantChallengeUserReference() throws SQLException {
        migrate();
        insertUser(
                "40000000-0000-0000-0000-000000000001",
                "40000000-0000-0000-0000-000000000002",
                "sms-challenge-tenant@example.invalid");

        assertSqlRejected("""
                INSERT INTO platform.sms_verification_challenge(
                    id, tenant_id, user_id, purpose, phone_ciphertext, phone_lookup_hash,
                    phone_last4, code_hash, expires_at, request_ip_hash, created_at)
                VALUES (
                    '40000000-0000-0000-0000-000000000004',
                    '40000000-0000-0000-0000-000000000003',
                    '40000000-0000-0000-0000-000000000001', 'PHONE_BIND',
                    decode('00', 'hex'), repeat('a', 64), '0000', repeat('b', 64),
                    TIMESTAMPTZ '2026-08-04 00:05:00+00', repeat('c', 64),
                    TIMESTAMPTZ '2026-08-04 00:00:00+00')
                """, "23503", "fk_sms_challenge_user");
    }

    @Test
    void rejectsCrossTenantProofUserReference() throws SQLException {
        migrate();
        insertUser(
                "41000000-0000-0000-0000-000000000001",
                "41000000-0000-0000-0000-000000000002",
                "sms-proof-tenant@example.invalid");

        assertSqlRejected("""
                INSERT INTO platform.sms_proof(
                    id, token_hash, tenant_id, user_id, purpose, expires_at, created_at)
                VALUES (
                    '41000000-0000-0000-0000-000000000004', repeat('d', 64),
                    '41000000-0000-0000-0000-000000000003',
                    '41000000-0000-0000-0000-000000000001', 'CHANGE_PASSWORD',
                    TIMESTAMPTZ '2026-08-04 00:01:00+00',
                    TIMESTAMPTZ '2026-08-04 00:00:00+00')
                """, "23503", "fk_sms_proof_user");
    }

    @Test
    void rejectsCrossTenantDeliveryUserReference() throws SQLException {
        migrate();
        insertUser(
                "42000000-0000-0000-0000-000000000001",
                "42000000-0000-0000-0000-000000000002",
                "sms-delivery-tenant@example.invalid");

        assertSqlRejected(deliveryInsert(
                "42000000-0000-0000-0000-000000000004",
                "cross-tenant-delivery",
                "'42000000-0000-0000-0000-000000000003'",
                "'42000000-0000-0000-0000-000000000001'",
                "'PENDING'", "NULL", "NULL"), "23503", "fk_sms_delivery_user");
    }

    @Test
    void preservesDeliveryTenantWhenReferencedUserIsDeleted() throws SQLException {
        migrate();
        String userId = "43000000-0000-0000-0000-000000000001";
        String tenantId = "43000000-0000-0000-0000-000000000002";
        String deliveryId = "43000000-0000-0000-0000-000000000003";
        insertUser(userId, tenantId, "sms-delivery-history@example.invalid");
        executeSql(deliveryInsert(
                deliveryId, "delivery-history", "'" + tenantId + "'", "'" + userId + "'",
                "'PENDING'", "NULL", "NULL"));

        executeSql("DELETE FROM platform.app_user WHERE id = '" + userId + "'");

        assertThat(singleString("""
                SELECT tenant_id::text FROM platform.sms_delivery
                WHERE id = '%s'
                """.formatted(deliveryId))).isEqualTo(tenantId);
        assertThat(singleString("""
                SELECT user_id::text FROM platform.sms_delivery
                WHERE id = '%s'
                """.formatted(deliveryId))).isNull();
    }

    @Test
    void rejectsSendingDeliveryWithoutClaimLease() {
        migrate();

        assertCheckRejected(deliveryInsert(
                "44000000-0000-0000-0000-000000000001", "sending-without-claim",
                "NULL", "NULL", "'SENDING'", "NULL", "NULL"), "ck_sms_delivery_claim");
    }

    @Test
    void rejectsClaimLeaseOutsideSendingStatus() {
        migrate();

        assertCheckRejected(deliveryInsert(
                "44000000-0000-0000-0000-000000000002", "pending-with-claim",
                "NULL", "NULL", "'PENDING'",
                "'44000000-0000-0000-0000-000000000003'",
                "TIMESTAMPTZ '2026-08-04 00:01:00+00'"), "ck_sms_delivery_claim");
    }

    @Test
    void acceptsClaimStateForPendingAndSendingDeliveries() throws SQLException {
        migrate();

        executeSql(deliveryInsert(
                "44000000-0000-0000-0000-000000000004", "valid-pending",
                "NULL", "NULL", "'PENDING'", "NULL", "NULL"));
        executeSql(deliveryInsert(
                "44000000-0000-0000-0000-000000000005", "valid-sending",
                "NULL", "NULL", "'SENDING'",
                "'44000000-0000-0000-0000-000000000006'",
                "TIMESTAMPTZ '2026-08-04 00:01:00+00'"));
    }

    @Test
    void seedsExactApprovedTemplateMetadata() throws SQLException {
        migrate();

        assertThat(templates()).containsExactlyInAnyOrderEntriesOf(expectedTemplates());
    }

    @Test
    void rejectsNonStringTemplateVariableNames() {
        migrate();

        assertCheckRejected(
                templateInsert("INVALID_NON_STRING", "SMS_TEST_NON_STRING", "[1]"),
                "ck_sms_template_variables");
    }

    @Test
    void rejectsDuplicateTemplateVariableNames() {
        migrate();

        assertCheckRejected(templateInsert(
                "INVALID_DUPLICATE", "SMS_TEST_DUPLICATE", "[\"code\", \"code\"]"),
                "ck_sms_template_variables");
    }

    @Test
    void rejectsUnsafeTemplateVariableNames() {
        migrate();

        assertCheckRejected(
                templateInsert("INVALID_UNSAFE", "SMS_TEST_UNSAFE", "[\"not-safe!\"]"),
                "ck_sms_template_variables");
    }

    private Map<String, TemplateRow> expectedTemplates() {
        return Map.ofEntries(
                entry("REGISTER_CODE", "SMS_511405090", "VERIFICATION", "ACTIVE", "[\"code\"]"),
                entry("LOGIN_CODE", "SMS_511430077", "VERIFICATION", "ACTIVE", "[\"code\"]"),
                entry("PASSWORD_CODE", "SMS_511175095", "VERIFICATION", "ACTIVE", "[\"code\"]"),
                entry("PAYMENT_CONFIRM_CODE", "SMS_511390110", "VERIFICATION", "ACTIVE", "[\"code\"]"),
                entry("API_KEY_RESET_CODE", "SMS_511455130", "VERIFICATION", "ACTIVE", "[\"code\"]"),
                entry("PHONE_CHANGE_CODE", "SMS_511410115", "VERIFICATION", "ACTIVE", "[\"code\"]"),
                entry("BALANCE_RECHARGE_SUCCEEDED", "SMS_511190106", "NOTIFICATION", "ACTIVE", "[\"payMoney\", \"balance\"]"),
                entry("PACKAGE_PAYMENT_SUCCEEDED", "SMS_511335109", "NOTIFICATION", "ACTIVE", "[\"payMoney\", \"expireDate\"]"),
                entry("API_USAGE_DEDUCTED", "SMS_511360123", "NOTIFICATION", "ACTIVE", "[\"costMoney\", \"balance\"]"),
                entry("BALANCE_LOW", "SMS_511390111", "NOTIFICATION", "ACTIVE", "[\"warnMoney\"]"),
                entry("PACKAGE_EXPIRES_IN_3_DAYS", "SMS_511440119", "NOTIFICATION", "ACTIVE", "[\"expireDate\"]"),
                entry("ACCOUNT_ANOMALY_FROZEN", "SMS_511300116", "NOTIFICATION", "ACTIVE", "[]"),
                entry("ACCOUNT_BANNED", "SMS_511465117", "NOTIFICATION", "ACTIVE", "[]"),
                entry("LOCATION_LOGIN_WARNING", "SMS_511410119", "NOTIFICATION", "ACTIVE", "[\"loginAddr\"]"),
                entry("ORDER_PAYMENT_FAILED", "SMS_511295121", "NOTIFICATION", "ACTIVE", "[\"payMoney\"]"),
                entry("PACKAGE_EXPIRED", "SMS_511180141", "NOTIFICATION", "ACTIVE", "[]"),
                entry("API_RATE_LIMITED", "SMS_511495111", "NOTIFICATION", "ACTIVE", "[]"),
                entry("ACCOUNT_UNFROZEN", "SMS_511380117", "NOTIFICATION", "ACTIVE", "[]"),
                entry("PLATFORM_MAINTENANCE", "SMS_511280134", "NOTIFICATION", "ACTIVE", "[\"maintainTime\"]"),
                entry("REFUND_SUCCEEDED", "SMS_511460123", "NOTIFICATION", "ACTIVE", "[\"refundMoney\"]"),
                entry("ORDER_TIMED_OUT", "SMS_511260138", "NOTIFICATION", "ACTIVE", "[\"orderNo\"]"),
                entry("SUPPORT_TICKET_COMPLETED", "SMS_511135150", "NOTIFICATION", "ACTIVE", "[\"ticketNo\"]"),
                entry("PACKAGE_PURCHASE_SUCCEEDED_FALLBACK", "SMS_511410101", "NOTIFICATION", "RESERVED", "[]"),
                entry("GENERIC_VERIFICATION", "SMS_337520649", "VERIFICATION", "RESERVED", "[\"code\"]"),
                // V72：自动续费的 4 个模板。先以 RESERVED + 占位码登记，
                // 待阿里云模板审核通过后替换 template_code 并激活（P-2）。
                entry("SUBSCRIPTION_RENEWAL_DUE", "RESERVED_SUB_RENEWAL_DUE", "NOTIFICATION", "RESERVED",
                        "[\"renewMoney\", \"renewDate\", \"planName\"]"),
                entry("SUBSCRIPTION_RENEWED", "RESERVED_SUB_RENEWED", "NOTIFICATION", "RESERVED",
                        "[\"payMoney\"]"),
                entry("SUBSCRIPTION_RENEWAL_FAILED", "RESERVED_SUB_RENEWAL_FAILED", "NOTIFICATION", "RESERVED",
                        "[\"failReason\", \"retryDate\"]"),
                entry("SUBSCRIPTION_PRICE_CHANGED", "RESERVED_SUB_PRICE_CHANGED", "NOTIFICATION", "RESERVED",
                        "[\"oldMoney\", \"newMoney\", \"effectiveDate\"]"));
    }

    private Map.Entry<String, TemplateRow> entry(
            String key, String code, String type, String status, String variableNames) {
        return Map.entry(key, new TemplateRow(code, type, status, variableNames));
    }

    @Test
    void allowsSmsLoginChallengePurpose() throws SQLException {
        migrate();

        executeSql("""
                INSERT INTO platform.sms_verification_challenge(
                    id, purpose, phone_ciphertext, phone_lookup_hash, phone_last4,
                    code_hash, expires_at, request_ip_hash, created_at)
                VALUES (
                    '55000000-0000-0000-0000-000000000001', 'LOGIN', decode('00', 'hex'),
                    repeat('a', 64), '0000', repeat('b', 64),
                    TIMESTAMPTZ '2026-08-04 00:05:00+00', repeat('c', 64),
                    TIMESTAMPTZ '2026-08-04 00:00:00+00')
                """);

        assertThat(singleString("""
                SELECT purpose FROM platform.sms_verification_challenge
                WHERE id = '55000000-0000-0000-0000-000000000001'
                """)).isEqualTo("LOGIN");
    }

    @Test
    void allowsPhoneChangeConfirmationChallengeAndProofPurposes() throws SQLException {
        migrate();
        insertUser(
                "56000000-0000-0000-0000-000000000001",
                "56000000-0000-0000-0000-000000000002",
                "phone-change-confirm-migration@example.invalid");

        executeSql("""
                INSERT INTO platform.sms_verification_challenge(
                    id, tenant_id, user_id, purpose, phone_ciphertext, phone_lookup_hash,
                    phone_last4, code_hash, expires_at, request_ip_hash, created_at)
                VALUES (
                    '56000000-0000-0000-0000-000000000003',
                    '56000000-0000-0000-0000-000000000002',
                    '56000000-0000-0000-0000-000000000001', 'PHONE_CHANGE_CONFIRM',
                    decode('00', 'hex'), repeat('a', 64), '5470', repeat('b', 64),
                    TIMESTAMPTZ '2026-08-20 00:05:00+00', repeat('c', 64),
                    TIMESTAMPTZ '2026-08-20 00:00:00+00')
                """);
        executeSql("""
                INSERT INTO platform.sms_proof(
                    id, token_hash, tenant_id, user_id, purpose, expires_at, created_at)
                VALUES (
                    '56000000-0000-0000-0000-000000000004', repeat('9', 64),
                    '56000000-0000-0000-0000-000000000002',
                    '56000000-0000-0000-0000-000000000001', 'PHONE_CHANGE_CONFIRM',
                    TIMESTAMPTZ '2026-08-20 00:05:00+00',
                    TIMESTAMPTZ '2026-08-20 00:00:00+00')
                """);

        assertThat(singleString("""
                SELECT purpose FROM platform.sms_verification_challenge
                WHERE id = '56000000-0000-0000-0000-000000000003'
                """)).isEqualTo("PHONE_CHANGE_CONFIRM");
        assertThat(singleString("""
                SELECT purpose FROM platform.sms_proof
                WHERE id = '56000000-0000-0000-0000-000000000004'
                """)).isEqualTo("PHONE_CHANGE_CONFIRM");
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Set<String> tableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = connection();
                ResultSet result = connection.getMetaData()
                        .getTables(null, "platform", "%", new String[] {"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private Set<String> columnNames(String table) throws SQLException {
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

    private Map<String, TemplateRow> templates() throws SQLException {
        Map<String, TemplateRow> rows = new HashMap<>();
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT template_key, template_code, template_type,
                               integration_status, variable_names::text
                        FROM platform.sms_template
                        """)) {
            while (result.next()) {
                rows.put(result.getString(1), new TemplateRow(
                        result.getString(2), result.getString(3), result.getString(4), result.getString(5)));
            }
        }
        return rows;
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT data_type FROM information_schema.columns
                        WHERE table_schema = 'platform' AND table_name = ? AND column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("column %s.%s", table, column).isTrue();
                return result.getString(1);
            }
        }
    }

    private String challengeInsert(
            String id, String expiresAt, String verifiedAt, String consumedAt) {
        return """
                INSERT INTO platform.sms_verification_challenge(
                    id, purpose, phone_ciphertext, phone_lookup_hash, phone_last4,
                    code_hash, expires_at, verified_at, consumed_at, request_ip_hash, created_at)
                VALUES (
                    '%s', 'REGISTER', decode('00', 'hex'), repeat('a', 64), '0000',
                    repeat('b', 64), TIMESTAMPTZ '%s', %s, %s, repeat('c', 64),
                    TIMESTAMPTZ '2026-08-04 00:00:00+00')
                """.formatted(id, expiresAt, timestampOrNull(verifiedAt), timestampOrNull(consumedAt));
    }

    private String timestampOrNull(String value) {
        return value == null ? "NULL" : "TIMESTAMPTZ '" + value + "'";
    }

    private String templateInsert(String key, String code, String variables) {
        return """
                INSERT INTO platform.sms_template(
                    template_key, template_code, template_type, variable_names, integration_status)
                VALUES ('%s', '%s', 'VERIFICATION', '%s'::jsonb, 'RESERVED')
                """.formatted(key, code, variables);
    }

    private String deliveryInsert(
            String id,
            String deduplicationKey,
            String tenantId,
            String userId,
            String status,
            String claimToken,
            String claimedUntil) {
        return """
                INSERT INTO platform.sms_delivery(
                    id, tenant_id, user_id, template_key, template_code,
                    phone_ciphertext, phone_last4, deduplication_key,
                    status, claim_token, claimed_until)
                VALUES (
                    '%s', %s, %s, 'PACKAGE_EXPIRED', 'SMS_511180141',
                    decode('00', 'hex'), '0000', '%s', %s, %s, %s)
                """.formatted(
                id, tenantId, userId, deduplicationKey, status, claimToken, claimedUntil);
    }

    private void insertUser(String userId, String tenantId, String email) throws SQLException {
        executeSql("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES ('%s', '%s', '%s', 'test-hash', 'SMS migration user')
                """.formatted(userId, tenantId, email));
    }

    private void executeSql(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String singleString(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as("query returned a row: %s", sql).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).as("query returned exactly one row: %s", sql).isFalse();
            return value;
        }
    }

    private void assertCheckRejected(String sql, String constraint) {
        assertSqlRejected(sql, "23514", constraint);
    }

    private void assertSqlRejected(String sql, String sqlState, String constraint) {
        assertThatThrownBy(() -> executeSql(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> {
                    SQLException sqlError = (SQLException) error;
                    assertThat(sqlError.getSQLState()).isEqualTo(sqlState);
                    assertThat(sqlError.getMessage()).contains(constraint);
                });
    }

    private String constraintDefinition(String table, String constraint) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT pg_get_constraintdef(constraint_row.oid)
                        FROM pg_constraint constraint_row
                        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                        JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                        WHERE schema_row.nspname = 'platform'
                          AND table_row.relname = ? AND constraint_row.conname = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("constraint %s.%s", table, constraint).isTrue();
                return result.getString(1);
            }
        }
    }

    private String indexDefinition(String index) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'platform' AND indexname = ?
                        """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("index %s", index).isTrue();
                return result.getString(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record TemplateRow(String code, String type, String status, String variableNames) {
    }
}
