package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WalletQueryRepository {

    private final JdbcTemplate jdbc;

    WalletQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SummaryRow findSummary(UUID tenantId, UUID userId) {
        return jdbc.query("""
                        SELECT account.available_amount, account.currency,
                               COALESCE((
                                   SELECT max(ledger.created_at)
                                   FROM platform.balance_ledger AS ledger
                                   WHERE ledger.account_id = account.id
                                     AND ledger.tenant_id = account.tenant_id
                                     AND ledger.user_id = account.user_id
                               ), account.updated_at) AS updated_at
                        FROM platform.account_balance AS account
                        WHERE account.tenant_id = ? AND account.user_id = ?
                          AND account.currency = 'CNY'
                        """,
                (result, rowNumber) -> new SummaryRow(
                        result.getLong("available_amount"),
                        result.getString("currency"),
                        result.getObject("updated_at", OffsetDateTime.class).toInstant()),
                tenantId, userId).stream().findFirst().orElse(null);
    }

    long countLedger(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.balance_ledger AS ledger
                JOIN platform.account_balance AS account
                  ON account.tenant_id = ledger.tenant_id
                 AND account.user_id = ledger.user_id
                 AND account.id = ledger.account_id
                 AND account.currency = 'CNY'
                WHERE ledger.tenant_id = ? AND ledger.user_id = ?
                """, Long.class, tenantId, userId);
    }

    List<LedgerRow> findLedger(
            UUID tenantId, UUID userId, int size, int offset,
            LedgerSort sort, SortDirection direction) {
        String ordering = " ORDER BY " + sort.column + " " + direction
                + ", ledger.id " + direction;
        return jdbc.query("""
                        SELECT ledger.entry_no, ledger.entry_type, ledger.amount_delta,
                               ledger.balance_after, ledger.business_no,
                               ledger.description, ledger.created_at
                        FROM platform.balance_ledger AS ledger
                        JOIN platform.account_balance AS account
                          ON account.tenant_id = ledger.tenant_id
                         AND account.user_id = ledger.user_id
                         AND account.id = ledger.account_id
                         AND account.currency = 'CNY'
                        WHERE ledger.tenant_id = ? AND ledger.user_id = ?
                        """ + ordering + " LIMIT ? OFFSET ?",
                (result, rowNumber) -> new LedgerRow(
                        result.getString("entry_no"),
                        result.getString("entry_type"),
                        result.getLong("amount_delta"),
                        result.getLong("balance_after"),
                        result.getString("business_no"),
                        result.getString("description"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant()),
                tenantId, userId, size, offset);
    }

    enum LedgerSort {
        CREATED_AT("ledger.created_at"),
        AMOUNT_DELTA("ledger.amount_delta");

        private final String column;

        LedgerSort(String column) {
            this.column = column;
        }
    }

    enum SortDirection {
        ASC,
        DESC
    }

    record SummaryRow(long availableAmount, String currency, Instant updatedAt) {
    }

    record LedgerRow(
            String entryNo,
            String entryType,
            long amountDelta,
            long balanceAfter,
            String businessNo,
            String description,
            Instant createdAt) {
    }
}
