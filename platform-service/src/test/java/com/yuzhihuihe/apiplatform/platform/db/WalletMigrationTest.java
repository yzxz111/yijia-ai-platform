package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class WalletMigrationTest {

    private static final String TENANT_A = "10000000-0000-4000-8000-000000000010";
    private static final String USER_A = "10000000-0000-4000-8000-000000000011";
    private static final String TENANT_B = "10000000-0000-4000-8000-000000000020";
    private static final String USER_B = "10000000-0000-4000-8000-000000000021";
    private static final String PACKAGE_VERSION = "00000000-0000-4000-8000-000000000102";
    private static final String PENDING_ORDER = "20000000-0000-4000-8000-000000000010";
    private static final String PAID_ORDER = "20000000-0000-4000-8000-000000000011";
    private static final String PACKAGE_PAYMENT = "30000000-0000-4000-8000-000000000010";
    private static final String EXTERNAL_REFUND = "40000000-0000-4000-8000-000000000010";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrateLegacyDataToWalletSchema() throws SQLException {
        migrateThrough("14");
        seedLegacyData();
        migrateThrough("15");
    }

    @Test
    void createsWalletTablesAndBackfillsAccountsAndTenantOptions() throws SQLException {
        assertThat(tableNames()).contains(
                "account_balance", "balance_ledger", "recharge_option", "recharge_order");
        assertThat(singleLong("SELECT count(*) FROM platform.account_balance"))
                .isEqualTo(singleLong("SELECT count(*) FROM platform.app_user"));
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.app_user AS app_user
                LEFT JOIN platform.account_balance AS account
                  ON account.tenant_id = app_user.tenant_id
                 AND account.user_id = app_user.id
                 AND account.currency = 'CNY'
                WHERE account.id IS NULL OR account.available_amount <> 0
                """)).isZero();
        assertThat(amounts("""
                SELECT amount
                FROM platform.recharge_option
                WHERE tenant_id = '%s'
                ORDER BY amount
                """.formatted(TENANT_A))).containsExactly(
                        1000L, 3000L, 5000L, 10000L, 20000L, 30000L, 50000L);
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.recharge_option
                WHERE tenant_id IN ('%s', '%s')
                """.formatted(TENANT_A, TENANT_B))).isEqualTo(14L);
    }

    @Test
    void enforcesWalletMoneyAndLedgerAuditInvariants() throws SQLException {
        assertSqlRejected("""
                UPDATE platform.account_balance
                SET available_amount = -1
                WHERE tenant_id = '%s' AND user_id = '%s' AND currency = 'CNY'
                """.formatted(TENANT_A, USER_A), "ck_account_balance_nonnegative");

        String accountId = singleString("""
                SELECT id::text
                FROM platform.account_balance
                WHERE tenant_id = '%s' AND user_id = '%s' AND currency = 'CNY'
                """.formatted(TENANT_A, USER_A));
        executeUpdate("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key, description)
                VALUES (
                    '50000000-0000-4000-8000-000000000010', 'ledger-migration-1',
                    '%s', '%s', '%s', 'RECHARGE',
                    1000, 1000, 0, 0, 1000,
                    'RECHARGE_ORDER', '60000000-0000-4000-8000-000000000010',
                    'recharge-migration-1', 'migration-ledger-1', 'migration test')
                """.formatted(TENANT_A, USER_A, accountId));

        assertSqlRejected("""
                UPDATE platform.balance_ledger
                SET description = 'changed'
                WHERE id = '50000000-0000-4000-8000-000000000010'
                """, "balance_ledger is append-only");
        assertSqlRejected("""
                DELETE FROM platform.balance_ledger
                WHERE id = '50000000-0000-4000-8000-000000000010'
                """, "balance_ledger is append-only");
        assertSqlRejected("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key)
                VALUES (
                    '50000000-0000-4000-8000-000000000011', 'ledger-invalid-snapshot',
                    '%s', '%s', '%s', 'PACKAGE_PURCHASE',
                    -1001, 0, 0, 1000, -1,
                    'ORDER', '%s', 'legacy-paid', 'migration-ledger-invalid')
                """.formatted(TENANT_A, USER_A, accountId, PAID_ORDER), "ck_balance_ledger_snapshots");
    }

    @Test
    void rejectsInvalidLedgerIdentitiesAndPostingShapes() throws SQLException {
        String accountId = singleString("""
                SELECT id::text
                FROM platform.account_balance
                WHERE tenant_id = '%s' AND user_id = '%s' AND currency = 'CNY'
                """.formatted(TENANT_A, USER_A));

        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000020", "ledger-blank-business",
                accountId, "RECHARGE", 100, 100, 0, 0, 100,
                "RECHARGE_ORDER", " ", "ledger-idem-business"),
                "ck_balance_ledger_business_no");
        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000021", "ledger-blank-idempotency",
                accountId, "RECHARGE", 100, 100, 0, 0, 100,
                "RECHARGE_ORDER", "recharge-blank-idem", "\t"),
                "ck_balance_ledger_idempotency_key");
        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000022", "ledger-long-idempotency",
                accountId, "RECHARGE", 100, 100, 0, 0, 100,
                "RECHARGE_ORDER", "recharge-long-idem", "i".repeat(129)),
                "value too long");
        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000023", "ledger-purchase-credit",
                accountId, "PACKAGE_PURCHASE", 100, 0, 0, 0, 100,
                "ORDER", "order-credit", "ledger-idem-purchase-credit"),
                "ck_balance_ledger_posting_shape");
        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000024", "ledger-recharge-debit",
                accountId, "RECHARGE", -100, 100, 0, 100, 0,
                "RECHARGE_ORDER", "recharge-debit", "ledger-idem-recharge-debit"),
                "ck_balance_ledger_posting_shape");
        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000025", "ledger-refund-debit",
                accountId, "PACKAGE_REFUND", -100, 0, 0, 100, 0,
                "REFUND", "refund-debit", "ledger-idem-refund-debit"),
                "ck_balance_ledger_posting_shape");
        assertSqlRejected(ledgerInsert(
                "50000000-0000-4000-8000-000000000026", "ledger-business-mismatch",
                accountId, "RECHARGE", 100, 100, 0, 0, 100,
                "RECHARGE", "recharge-mismatch", "ledger-idem-business-mismatch"),
                "ck_balance_ledger_posting_shape");
    }

    @Test
    void rejectsWalletLinkedOrderIdChanges() throws SQLException {
        createWalletOrder(
                "21000000-0000-4000-8000-000000000001", "wallet-order-id-update",
                "51000000-0000-4000-8000-000000000001", "ledger-order-id-update");

        assertSqlRejected("""
                UPDATE platform.customer_order
                SET id = '22000000-0000-4000-8000-000000000001'
                WHERE id = '21000000-0000-4000-8000-000000000001'
                """, "wallet-linked customer_order id is immutable");
    }

    @Test
    void rejectsWalletLinkedOrderDeletes() throws SQLException {
        createWalletOrder(
                "21000000-0000-4000-8000-000000000002", "wallet-order-delete",
                "51000000-0000-4000-8000-000000000002", "ledger-order-delete");

        assertSqlRejected("""
                DELETE FROM platform.customer_order
                WHERE id = '21000000-0000-4000-8000-000000000002'
                """, "wallet-linked customer_order cannot be deleted");
    }

    @Test
    void rejectsWalletLinkedRefundIdChanges() throws SQLException {
        createWalletRefund(
                "21000000-0000-4000-8000-000000000003", "wallet-refund-order-id-update",
                "51000000-0000-4000-8000-000000000003", "ledger-refund-order-id-update",
                "41000000-0000-4000-8000-000000000003", "wallet-refund-id-update",
                "52000000-0000-4000-8000-000000000003", "ledger-refund-id-update");

        assertSqlRejected("""
                UPDATE platform.refund
                SET id = '42000000-0000-4000-8000-000000000003'
                WHERE id = '41000000-0000-4000-8000-000000000003'
                """, "wallet-linked refund id is immutable");
    }

    @Test
    void rejectsWalletLinkedRefundDeletes() throws SQLException {
        createWalletRefund(
                "21000000-0000-4000-8000-000000000004", "wallet-refund-order-delete",
                "51000000-0000-4000-8000-000000000004", "ledger-refund-order-delete",
                "41000000-0000-4000-8000-000000000004", "wallet-refund-delete",
                "52000000-0000-4000-8000-000000000004", "ledger-refund-delete");

        assertSqlRejected("""
                DELETE FROM platform.refund
                WHERE id = '41000000-0000-4000-8000-000000000004'
                """, "wallet-linked refund cannot be deleted");
    }

    @Test
    void rejectsRechargePaymentAsOrderSuccessfulPayment() throws SQLException {
        createPackageOrder(
                TENANT_A, USER_A,
                "23000000-0000-4000-8000-000000000001", "owner-order-recharge-payment");
        createRechargePayment(
                "63000000-0000-4000-8000-000000000001", "owner-recharge-order-success",
                "73000000-0000-4000-8000-000000000001", "owner-recharge-payment-success");

        assertSqlRejected("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = '73000000-0000-4000-8000-000000000001'
                WHERE id = '23000000-0000-4000-8000-000000000001'
                """, "fk_order_successful_payment");
    }

    @Test
    void rejectsAnotherOrdersPaymentAsSuccessfulPayment() throws SQLException {
        createPackageOrder(
                TENANT_A, USER_A,
                "23000000-0000-4000-8000-000000000002", "owner-order-target-success");
        createPackageOrder(
                TENANT_A, USER_A,
                "23000000-0000-4000-8000-000000000003", "owner-order-source-success");
        createPackagePayment(
                "23000000-0000-4000-8000-000000000003",
                "73000000-0000-4000-8000-000000000002", "owner-package-payment-success");

        assertSqlRejected("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = '73000000-0000-4000-8000-000000000002'
                WHERE id = '23000000-0000-4000-8000-000000000002'
                """, "fk_order_successful_payment");
    }

    @Test
    void rejectsRechargePaymentAsExternalRefundSource() throws SQLException {
        createPackageOrder(
                TENANT_A, USER_A,
                "23000000-0000-4000-8000-000000000004", "owner-order-recharge-refund");
        createRechargePayment(
                "63000000-0000-4000-8000-000000000002", "owner-recharge-refund",
                "73000000-0000-4000-8000-000000000003", "owner-recharge-payment-refund");

        assertExternalRefundRejected(
                "43000000-0000-4000-8000-000000000001", "owner-refund-recharge-payment",
                "23000000-0000-4000-8000-000000000004",
                "73000000-0000-4000-8000-000000000003");
    }

    @Test
    void rejectsAnotherOrdersPaymentAsExternalRefundSource() throws SQLException {
        createPackageOrder(
                TENANT_A, USER_A,
                "23000000-0000-4000-8000-000000000005", "owner-order-target-refund");
        createPackageOrder(
                TENANT_A, USER_A,
                "23000000-0000-4000-8000-000000000006", "owner-order-source-refund");
        createPackagePayment(
                "23000000-0000-4000-8000-000000000006",
                "73000000-0000-4000-8000-000000000004", "owner-package-payment-refund");

        assertExternalRefundRejected(
                "43000000-0000-4000-8000-000000000002", "owner-refund-other-payment",
                "23000000-0000-4000-8000-000000000005",
                "73000000-0000-4000-8000-000000000004");
    }

    @Test
    void rejectsOrderLedgerDetachAndKeepsDeleteProtection() throws SQLException {
        createWalletOrder(
                "24000000-0000-4000-8000-000000000001", "immutable-order-detach",
                "54000000-0000-4000-8000-000000000001", "immutable-ledger-order-detach");

        assertSqlRejected("""
                UPDATE platform.customer_order
                SET settlement_method = 'UNSELECTED', balance_debit_ledger_id = NULL
                WHERE id = '24000000-0000-4000-8000-000000000001'
                """, "wallet-linked customer_order ledger reference is immutable");
        assertSqlRejected("""
                DELETE FROM platform.customer_order
                WHERE id = '24000000-0000-4000-8000-000000000001'
                """, "wallet-linked customer_order cannot be deleted");
    }

    @Test
    void rejectsOrderLedgerReplacementDuringOwnerSwap() throws SQLException {
        String orderId = "24000000-0000-4000-8000-000000000002";
        createWalletOrder(
                orderId, "immutable-order-replace",
                "54000000-0000-4000-8000-000000000002", "immutable-ledger-order-replace-a");
        createBalanceLedger(
                TENANT_B, USER_B,
                "54000000-0000-4000-8000-000000000003", "immutable-ledger-order-replace-b",
                "PACKAGE_PURCHASE", -1000, 1000, 0,
                "ORDER", orderId, "immutable-order-replace-b");

        assertSqlRejected("""
                UPDATE platform.customer_order
                SET tenant_id = '%s', user_id = '%s',
                    balance_debit_ledger_id = '54000000-0000-4000-8000-000000000003'
                WHERE id = '%s'
                """.formatted(TENANT_B, USER_B, orderId),
                "wallet-linked customer_order ledger reference is immutable");
    }

    @Test
    void rejectsRefundLedgerDetachAndKeepsDeleteProtection() throws SQLException {
        createWalletRefund(
                "24000000-0000-4000-8000-000000000003", "immutable-refund-order-detach",
                "54000000-0000-4000-8000-000000000004", "immutable-ledger-refund-order-detach",
                "44000000-0000-4000-8000-000000000001", "immutable-refund-detach",
                "55000000-0000-4000-8000-000000000001", "immutable-ledger-refund-detach");

        assertSqlRejected("""
                UPDATE platform.refund
                SET status = 'FAILED', balance_refund_ledger_id = NULL
                WHERE id = '44000000-0000-4000-8000-000000000001'
                """, "wallet-linked refund ledger reference is immutable");
        assertSqlRejected("""
                DELETE FROM platform.refund
                WHERE id = '44000000-0000-4000-8000-000000000001'
                """, "wallet-linked refund cannot be deleted");
    }

    @Test
    void rejectsRefundLedgerReplacementDuringOrderSwap() throws SQLException {
        String refundId = "44000000-0000-4000-8000-000000000002";
        createWalletRefund(
                "24000000-0000-4000-8000-000000000004", "immutable-refund-order-replace-a",
                "54000000-0000-4000-8000-000000000005", "immutable-ledger-refund-order-replace-a",
                refundId, "immutable-refund-replace",
                "55000000-0000-4000-8000-000000000002", "immutable-ledger-refund-replace-a");
        createWalletOrder(
                TENANT_B, USER_B,
                "24000000-0000-4000-8000-000000000005", "immutable-refund-order-replace-b",
                "54000000-0000-4000-8000-000000000006", "immutable-ledger-refund-order-replace-b");
        createBalanceLedger(
                TENANT_B, USER_B,
                "55000000-0000-4000-8000-000000000003", "immutable-ledger-refund-replace-b",
                "PACKAGE_REFUND", 100, 0, 100,
                "REFUND", refundId, "immutable-refund-replace-b");

        assertSqlRejected("""
                UPDATE platform.refund
                SET order_id = '24000000-0000-4000-8000-000000000005',
                    balance_refund_ledger_id = '55000000-0000-4000-8000-000000000003'
                WHERE id = '%s'
                """.formatted(refundId), "wallet-linked refund ledger reference is immutable");
    }

    @Test
    void rejectsOrderIdChangesWhenMatchingLedgerExistsBeforeLink() throws SQLException {
        String orderId = "25000000-0000-4000-8000-000000000001";
        createPackageOrder(TENANT_A, USER_A, orderId, "orphan-order-id");
        createBalanceLedger(
                TENANT_A, USER_A,
                "56000000-0000-4000-8000-000000000001", "orphan-ledger-order-id",
                "PACKAGE_PURCHASE", -1000, 1000, 0,
                "ORDER", orderId, "orphan-order-id");

        assertSqlRejected("""
                UPDATE platform.customer_order
                SET id = '26000000-0000-4000-8000-000000000001'
                WHERE id = '%s'
                """.formatted(orderId), "wallet-linked customer_order id is immutable");
    }

    @Test
    void rejectsOrderDeletesWhenMatchingLedgerExistsBeforeLink() throws SQLException {
        String orderId = "25000000-0000-4000-8000-000000000002";
        createPackageOrder(TENANT_A, USER_A, orderId, "orphan-order-delete");
        createBalanceLedger(
                TENANT_A, USER_A,
                "56000000-0000-4000-8000-000000000002", "orphan-ledger-order-delete",
                "PACKAGE_PURCHASE", -1000, 1000, 0,
                "ORDER", orderId, "orphan-order-delete");

        assertSqlRejected("""
                DELETE FROM platform.customer_order WHERE id = '%s'
                """.formatted(orderId), "wallet-linked customer_order cannot be deleted");
    }

    @Test
    void rejectsRefundIdChangesWhenMatchingLedgerExistsBeforeLink() throws SQLException {
        String refundId = "45000000-0000-4000-8000-000000000001";
        createPackageOrder(
                TENANT_A, USER_A,
                "25000000-0000-4000-8000-000000000003", "orphan-refund-order-id");
        createPendingBalanceRefund(
                refundId, "orphan-refund-id", "25000000-0000-4000-8000-000000000003");
        createBalanceLedger(
                TENANT_A, USER_A,
                "57000000-0000-4000-8000-000000000001", "orphan-ledger-refund-id",
                "PACKAGE_REFUND", 100, 0, 100,
                "REFUND", refundId, "orphan-refund-id");

        assertSqlRejected("""
                UPDATE platform.refund
                SET id = '46000000-0000-4000-8000-000000000001'
                WHERE id = '%s'
                """.formatted(refundId), "wallet-linked refund id is immutable");
    }

    @Test
    void rejectsRefundDeletesWhenMatchingLedgerExistsBeforeLink() throws SQLException {
        String refundId = "45000000-0000-4000-8000-000000000002";
        createPackageOrder(
                TENANT_A, USER_A,
                "25000000-0000-4000-8000-000000000004", "orphan-refund-order-delete");
        createPendingBalanceRefund(
                refundId, "orphan-refund-delete", "25000000-0000-4000-8000-000000000004");
        createBalanceLedger(
                TENANT_A, USER_A,
                "57000000-0000-4000-8000-000000000002", "orphan-ledger-refund-delete",
                "PACKAGE_REFUND", 100, 0, 100,
                "REFUND", refundId, "orphan-refund-delete");

        assertSqlRejected("""
                DELETE FROM platform.refund WHERE id = '%s'
                """.formatted(refundId), "wallet-linked refund cannot be deleted");
    }

    @Test
    void requiresExactlyOnePaymentOwner() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key, expires_at)
                VALUES (
                    '60000000-0000-4000-8000-000000000010', 'recharge-migration-owner',
                    '%s', '%s', 1000, 0, 1000, 'CNY', 'PENDING',
                    'migration-owner', now() + interval '15 minutes')
                """.formatted(TENANT_A, USER_A));

        assertSqlRejected("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product)
                VALUES (
                    '70000000-0000-4000-8000-000000000010', 'payment-no-owner',
                    NULL, NULL, 'MOCK', 1000, 'CREATED', 'MOCK')
                """, "ck_payment_owner");
        assertSqlRejected("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product)
                VALUES (
                    '70000000-0000-4000-8000-000000000011', 'payment-two-owners',
                    '%s', '60000000-0000-4000-8000-000000000010',
                    'MOCK', 1000, 'CREATED', 'MOCK')
                """.formatted(PENDING_ORDER), "ck_payment_owner");
    }

    @Test
    void backfillsExistingPackagePaymentsOrdersAndRefundsAsExternal() throws SQLException {
        assertThat(singleString("""
                SELECT settlement_method
                FROM platform.customer_order
                WHERE id = '%s'
                """.formatted(PAID_ORDER))).isEqualTo("EXTERNAL");
        assertThat(singleString("""
                SELECT settlement_method
                FROM platform.customer_order
                WHERE id = '%s'
                """.formatted(PENDING_ORDER))).isEqualTo("UNSELECTED");
        assertThat(singleString("""
                SELECT CASE
                    WHEN order_id IS NOT NULL AND recharge_order_id IS NULL THEN 'PACKAGE'
                    ELSE 'INVALID'
                END
                FROM platform.payment_transaction
                WHERE id = '%s'
                """.formatted(PACKAGE_PAYMENT))).isEqualTo("PACKAGE");
        assertThat(singleString("""
                SELECT funding_source
                FROM platform.refund
                WHERE id = '%s'
                """.formatted(EXTERNAL_REFUND))).isEqualTo("EXTERNAL");
        assertThat(singleString("""
                SELECT integration_status
                FROM platform.sms_template
                WHERE template_key = 'BALANCE_RECHARGE_SUCCEEDED'
                """)).isEqualTo("ACTIVE");
        assertThat(singleString("""
                SELECT integration_status
                FROM platform.sms_template
                WHERE template_key = 'BALANCE_LOW'
                """)).isEqualTo("PENDING_INTEGRATION");
    }

    private static void migrateThrough(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static void seedLegacyData() throws SQLException {
        executeUpdate("""
                INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name)
                VALUES
                    ('%s', '%s', 'wallet-legacy-a@example.test', 'hash', 'Wallet Legacy A'),
                    ('%s', '%s', 'wallet-legacy-b@example.test', 'hash', 'Wallet Legacy B')
                """.formatted(USER_A, TENANT_A, USER_B, TENANT_B));
        executeUpdate("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at)
                VALUES
                    ('%s', 'wallet-legacy-pending', '%s', '%s', '%s',
                     1900, 'CNY', 'PENDING', 'MOCK', NULL),
                    ('%s', 'wallet-legacy-paid', '%s', '%s', '%s',
                     3000, 'CNY', 'PAID', 'MOCK', now())
                """.formatted(
                        PENDING_ORDER, TENANT_A, USER_A, PACKAGE_VERSION,
                        PAID_ORDER, TENANT_A, USER_A, PACKAGE_VERSION));
        executeUpdate("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, payment_product, paid_at, applied_to_order)
                VALUES (
                    '%s', 'wallet-legacy-payment', '%s', 'MOCK', 'wallet-legacy-trade',
                    3000, 'SUCCEEDED', 'MOCK', now(), true)
                """.formatted(PACKAGE_PAYMENT, PAID_ORDER));
        executeUpdate("""
                UPDATE platform.customer_order
                SET successful_payment_transaction_id = '%s'
                WHERE id = '%s'
                """.formatted(PACKAGE_PAYMENT, PAID_ORDER));
        executeUpdate("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id,
                    amount, status, reason, refund_type, policy_version,
                    calculation_snapshot, requested_by, provider, completed_at)
                VALUES (
                    '%s', 'wallet-legacy-refund', '%s', '%s',
                    1000, 'COMPLETED', 'legacy wallet migration refund', 'PARTIAL', 1,
                    '{"migrated":true}'::jsonb, '%s', 'MOCK', now())
                """.formatted(EXTERNAL_REFUND, PAID_ORDER, PACKAGE_PAYMENT, USER_A));
    }

    private static void createWalletOrder(
            String orderId, String orderNo, String ledgerId, String ledgerEntryNo) throws SQLException {
        createWalletOrder(TENANT_A, USER_A, orderId, orderNo, ledgerId, ledgerEntryNo);
    }

    private static void createWalletOrder(
            String tenantId,
            String userId,
            String orderId,
            String orderNo,
            String ledgerId,
            String ledgerEntryNo) throws SQLException {
        createPackageOrder(tenantId, userId, orderId, orderNo);
        createBalanceLedger(
                tenantId, userId, ledgerId, ledgerEntryNo,
                "PACKAGE_PURCHASE", -1000, 1000, 0,
                "ORDER", orderId, orderNo);
        executeUpdate("""
                UPDATE platform.customer_order
                SET settlement_method = 'BALANCE', balance_debit_ledger_id = '%s'
                WHERE id = '%s'
                """.formatted(ledgerId, orderId));
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.customer_order AS customer_order
                JOIN platform.balance_ledger AS ledger
                  ON ledger.id = customer_order.balance_debit_ledger_id
                 AND ledger.tenant_id = customer_order.tenant_id
                 AND ledger.user_id = customer_order.user_id
                 AND ledger.business_id = customer_order.id
                WHERE customer_order.id = '%s'
                """.formatted(orderId))).isEqualTo(1L);
    }

    private static void createPackageOrder(
            String tenantId, String userId, String orderId, String orderNo) throws SQLException {
        executeUpdate("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, currency, status, payment_provider, paid_at)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s',
                    1000, 'CNY', 'PAID', 'MOCK', now())
                """.formatted(orderId, orderNo, tenantId, userId, PACKAGE_VERSION));
    }

    private static void createPackagePayment(
            String orderId, String paymentId, String transactionNo) throws SQLException {
        executeUpdate("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, amount,
                    status, payment_product, paid_at)
                VALUES ('%s', '%s', '%s', 'MOCK', 1000, 'SUCCEEDED', 'MOCK', now())
                """.formatted(paymentId, transactionNo, orderId));
    }

    private static void createRechargePayment(
            String rechargeId, String rechargeNo, String paymentId, String transactionNo)
            throws SQLException {
        executeUpdate("""
                INSERT INTO platform.recharge_order(
                    id, recharge_no, tenant_id, user_id, paid_amount, bonus_amount,
                    credited_amount, currency, status, idempotency_key, expires_at)
                VALUES (
                    '%s', '%s', '%s', '%s', 1000, 0,
                    1000, 'CNY', 'PENDING', '%s', now() + interval '15 minutes')
                """.formatted(rechargeId, rechargeNo, TENANT_A, USER_A, rechargeNo));
        executeUpdate("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, recharge_order_id, provider,
                    amount, status, payment_product, paid_at)
                VALUES (
                    '%s', '%s', NULL, '%s', 'MOCK',
                    1000, 'SUCCEEDED', 'MOCK', now())
                """.formatted(paymentId, transactionNo, rechargeId));
    }

    private static void assertExternalRefundRejected(
            String refundId, String refundNo, String orderId, String paymentId) {
        assertSqlRejected("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id,
                    amount, status, reason, refund_type, policy_version,
                    calculation_snapshot, requested_by, funding_source)
                VALUES (
                    '%s', '%s', '%s', '%s',
                    100, 'PENDING_APPROVAL', 'payment owner migration test', 'PARTIAL', 1,
                    '{"paymentOwnerTest":true}'::jsonb, '%s', 'EXTERNAL')
                """.formatted(refundId, refundNo, orderId, paymentId, USER_A),
                "refund_payment_transaction_id_fkey");
    }

    private static void createBalanceLedger(
            String tenantId,
            String userId,
            String ledgerId,
            String ledgerEntryNo,
            String entryType,
            long amountDelta,
            long balanceBefore,
            long balanceAfter,
            String businessType,
            String businessId,
            String businessNo) throws SQLException {
        String accountId = singleString("""
                SELECT id::text
                FROM platform.account_balance
                WHERE tenant_id = '%s' AND user_id = '%s' AND currency = 'CNY'
                """.formatted(tenantId, userId));
        executeUpdate("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s',
                    %d, 0, 0, %d, %d,
                    '%s', '%s', '%s', 'wallet-link:%s')
                """.formatted(
                        ledgerId, ledgerEntryNo, tenantId, userId, accountId, entryType,
                        amountDelta, balanceBefore, balanceAfter,
                        businessType, businessId, businessNo, ledgerEntryNo));
    }

    private static String ledgerInsert(
            String ledgerId,
            String entryNo,
            String accountId,
            String entryType,
            long amountDelta,
            long paidAmount,
            long bonusAmount,
            long balanceBefore,
            long balanceAfter,
            String businessType,
            String businessNo,
            String idempotencyKey) {
        return """
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key)
                VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s',
                    %d, %d, %d, %d, %d,
                    '%s', '%s', '%s', '%s')
                """.formatted(
                        ledgerId, entryNo, TENANT_A, USER_A, accountId, entryType,
                        amountDelta, paidAmount, bonusAmount, balanceBefore, balanceAfter,
                        businessType, ledgerId, businessNo, idempotencyKey);
    }

    private static void createWalletRefund(
            String orderId,
            String orderNo,
            String debitLedgerId,
            String debitLedgerEntryNo,
            String refundId,
            String refundNo,
            String refundLedgerId,
            String refundLedgerEntryNo) throws SQLException {
        createWalletOrder(orderId, orderNo, debitLedgerId, debitLedgerEntryNo);
        createPendingBalanceRefund(refundId, refundNo, orderId);
        createBalanceLedger(
                TENANT_A, USER_A, refundLedgerId, refundLedgerEntryNo,
                "PACKAGE_REFUND", 100, 0, 100,
                "REFUND", refundId, refundNo);
        executeUpdate("""
                UPDATE platform.refund
                SET status = 'COMPLETED', balance_refund_ledger_id = '%s', completed_at = now()
                WHERE id = '%s'
                """.formatted(refundLedgerId, refundId));
        assertThat(singleLong("""
                SELECT count(*)
                FROM platform.refund
                JOIN platform.customer_order ON customer_order.id = refund.order_id
                JOIN platform.balance_ledger AS ledger
                  ON ledger.id = refund.balance_refund_ledger_id
                 AND ledger.tenant_id = customer_order.tenant_id
                 AND ledger.user_id = customer_order.user_id
                 AND ledger.business_id = refund.id
                WHERE refund.id = '%s'
                """.formatted(refundId))).isEqualTo(1L);
    }

    private static void createPendingBalanceRefund(
            String refundId, String refundNo, String orderId) throws SQLException {
        executeUpdate("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id,
                    amount, status, reason, refund_type, policy_version,
                    calculation_snapshot, requested_by, funding_source)
                VALUES (
                    '%s', '%s', '%s', NULL,
                    100, 'PENDING_APPROVAL', 'wallet link migration test', 'PARTIAL', 1,
                    '{"walletLinkTest":true}'::jsonb, '%s', 'BALANCE')
                """.formatted(refundId, refundNo, orderId, USER_A));
    }

    private static Set<String> tableNames() throws SQLException {
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

    private static List<Long> amounts(String sql) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getLong(1));
            }
        }
        return values;
    }

    private static long singleLong(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as("query returned a row: %s", sql).isTrue();
            long value = result.getLong(1);
            assertThat(result.next()).as("query returned one row: %s", sql).isFalse();
            return value;
        }
    }

    private static String singleString(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).as("query returned a row: %s", sql).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).as("query returned one row: %s", sql).isFalse();
            return value;
        }
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

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
