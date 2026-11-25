package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BalancePostingRepository {

    private final JdbcTemplate jdbc;

    BalancePostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AccountRow lockAccount(UUID tenantId, UUID userId) {
        return jdbc.query("""
                        SELECT id, available_amount
                        FROM platform.account_balance
                        WHERE tenant_id = ? AND user_id = ? AND currency = 'CNY'
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new AccountRow(
                        result.getObject("id", UUID.class),
                        result.getLong("available_amount")),
                tenantId, userId).stream().findFirst().orElse(null);
    }

    List<LedgerRow> findExisting(
            UUID tenantId,
            UUID userId,
            UUID accountId,
            String entryType,
            String businessType,
            UUID businessId,
            String idempotencyKey) {
        return jdbc.query("""
                        SELECT id, entry_no, entry_type, amount_delta, paid_amount, bonus_amount,
                               balance_before, balance_after, business_type, business_id,
                               business_no, idempotency_key, created_at
                        FROM platform.balance_ledger
                        WHERE tenant_id = ? AND user_id = ? AND account_id = ?
                          AND ((entry_type = ? AND business_type = ? AND business_id = ?)
                               OR idempotency_key = ?)
                        ORDER BY id
                        """,
                (result, rowNumber) -> new LedgerRow(
                        result.getObject("id", UUID.class),
                        result.getString("entry_no"),
                        result.getString("entry_type"),
                        result.getLong("amount_delta"),
                        result.getLong("paid_amount"),
                        result.getLong("bonus_amount"),
                        result.getLong("balance_before"),
                        result.getLong("balance_after"),
                        result.getString("business_type"),
                        result.getObject("business_id", UUID.class),
                        result.getString("business_no"),
                        result.getString("idempotency_key"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant()),
                tenantId, userId, accountId, entryType, businessType, businessId, idempotencyKey);
    }

    Instant insertLedger(
            UUID ledgerId,
            String entryNo,
            UUID tenantId,
            UUID userId,
            UUID accountId,
            String entryType,
            long amountDelta,
            long paidAmount,
            long bonusAmount,
            long balanceBefore,
            long balanceAfter,
            String businessType,
            UUID businessId,
            String businessNo,
            String idempotencyKey) {
        return jdbc.queryForObject("""
                        INSERT INTO platform.balance_ledger(
                            id, entry_no, tenant_id, user_id, account_id,
                            entry_type, amount_delta, paid_amount, bonus_amount,
                            balance_before, balance_after, business_type, business_id,
                            business_no, idempotency_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING created_at
                        """,
                (result, rowNumber) -> result.getObject(
                        "created_at", OffsetDateTime.class).toInstant(),
                ledgerId, entryNo, tenantId, userId, accountId, entryType, amountDelta,
                paidAmount, bonusAmount, balanceBefore, balanceAfter, businessType,
                businessId, businessNo, idempotencyKey);
    }

    int updateAccount(UUID tenantId, UUID userId, UUID accountId, long balanceAfter) {
        return jdbc.update("""
                UPDATE platform.account_balance
                SET available_amount = ?, version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND user_id = ? AND id = ? AND currency = 'CNY'
                """, balanceAfter, tenantId, userId, accountId);
    }

    record AccountRow(UUID id, long availableAmount) {
    }

    record LedgerRow(
            UUID id,
            String entryNo,
            String entryType,
            long amountDelta,
            long paidAmount,
            long bonusAmount,
            long balanceBefore,
            long balanceAfter,
            String businessType,
            UUID businessId,
            String businessNo,
            String idempotencyKey,
            Instant createdAt) {
    }
}
