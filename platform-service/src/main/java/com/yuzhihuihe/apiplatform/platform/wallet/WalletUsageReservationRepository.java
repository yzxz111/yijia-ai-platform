package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WalletUsageReservationRepository {

    private final JdbcTemplate jdbc;

    WalletUsageReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AccountRow lockAccount(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT id, tenant_id, user_id, available_amount, reserved_amount
                FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND currency = 'CNY'
                FOR UPDATE
                """, (result, rowNumber) -> account(result), tenantId, userId)
                .stream().findFirst().orElse(null);
    }

    AccountRow lockAccountById(UUID accountId) {
        return jdbc.query("""
                SELECT id, tenant_id, user_id, available_amount, reserved_amount
                FROM platform.account_balance WHERE id = ? FOR UPDATE
                """, (result, rowNumber) -> account(result), accountId)
                .stream().findFirst().orElse(null);
    }

    ReservationRow lockReservation(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, user_id, account_id, request_id, estimated_amount,
                       settled_amount, status, model_code, price_version,
                       actual_input_tokens, actual_output_tokens, billing_mode, expires_at
                FROM platform.wallet_usage_reservation WHERE id = ? FOR UPDATE
                """, (result, rowNumber) -> reservation(result), id)
                .stream().findFirst().orElse(null);
    }

    ReservationRow findReservation(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, user_id, account_id, request_id, estimated_amount,
                       settled_amount, status, model_code, price_version,
                       actual_input_tokens, actual_output_tokens, billing_mode, expires_at
                FROM platform.wallet_usage_reservation WHERE id = ?
                """, (result, rowNumber) -> reservation(result), id)
                .stream().findFirst().orElse(null);
    }

    ReservationRow findExisting(UUID accountId, String requestId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, tenant_id, user_id, account_id, request_id, estimated_amount,
                       settled_amount, status, model_code, price_version,
                       actual_input_tokens, actual_output_tokens, billing_mode, expires_at
                FROM platform.wallet_usage_reservation
                WHERE account_id = ? AND (request_id = ? OR idempotency_key = ?)
                """, (result, rowNumber) -> reservation(result),
                accountId, requestId, idempotencyKey).stream().findFirst().orElse(null);
    }

    int reserveBalance(UUID accountId, long estimatedAmount) {
        return jdbc.update("""
                UPDATE platform.account_balance
                SET available_amount = available_amount - ?,
                    reserved_amount = reserved_amount + ?,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND available_amount >= ?
                """, estimatedAmount, estimatedAmount, accountId, estimatedAmount);
    }

    void insertReservation(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID accountId,
            String requestId,
            long estimatedAmount,
            String modelCode,
            String priceVersion,
            long inputTokens,
            long outputTokens,
            String idempotencyKey,
            Instant expiresAt) {
        jdbc.update("""
                INSERT INTO platform.wallet_usage_reservation(
                    id, tenant_id, user_id, account_id, request_id,
                    estimated_amount, model_code, price_version,
                    input_tokens, output_tokens, idempotency_key, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, userId, accountId, requestId, estimatedAmount,
                modelCode, priceVersion, inputTokens, outputTokens, idempotencyKey,
                // 必须显式转成 OffsetDateTime，不能直接传 Instant。
                // PostgreSQL 驱动无法从一个 java.time.Instant 推断出 SQL 类型，
                // 于是每次 reserve 都会以
                //   "Can't infer the SQL type to use for an instance of java.time.Instant"
                // 失败——而那条 INSERT 的 SQL 本身完全合法，所以它表现为
                // BadSqlGrammarException，看着像语法错误，其实是参数绑定问题。
                // 读路径（同文件 getObject("expires_at", OffsetDateTime.class)）一直
                // 用的是 OffsetDateTime，写路径漏了这一步。
                expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    void insertUsageLedger(
            UUID ledgerId,
            String entryNo,
            UUID tenantId,
            UUID userId,
            UUID accountId,
            long amount,
            long balanceBefore,
            long balanceAfter,
            UUID reservationId,
            String modelCode,
            String priceVersion) {
        jdbc.update("""
                INSERT INTO platform.balance_ledger(
                    id, entry_no, tenant_id, user_id, account_id, entry_type,
                    amount_delta, paid_amount, bonus_amount, balance_before, balance_after,
                    business_type, business_id, business_no, idempotency_key, model_code, price_version)
                VALUES (?, ?, ?, ?, ?, 'MODEL_USAGE', ?, ?, 0, ?, ?, 'MODEL_USAGE', ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, idempotency_key) DO NOTHING
                """, ledgerId, entryNo, tenantId, userId, accountId, -amount, amount,
                balanceBefore, balanceAfter, reservationId, reservationId.toString(),
                "model-usage:" + reservationId, modelCode, priceVersion);
    }

    int settleBalance(UUID accountId, long release, long estimatedAmount) {
        return jdbc.update("""
                UPDATE platform.account_balance
                SET available_amount = available_amount + ?,
                    reserved_amount = reserved_amount - ?,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND reserved_amount >= ?
                """, release, estimatedAmount, accountId, estimatedAmount);
    }

    int settleReservation(
            UUID reservationId,
            long settledAmount,
            long inputTokens,
            long outputTokens,
            String billingMode) {
        return jdbc.update("""
                UPDATE platform.wallet_usage_reservation
                SET settled_amount = ?, actual_input_tokens = ?, actual_output_tokens = ?,
                    billing_mode = ?, status = 'SETTLED', settled_at = now()
                WHERE id = ? AND status = 'RESERVED'
                """, settledAmount, inputTokens, outputTokens, billingMode, reservationId);
    }

    int releaseBalance(UUID accountId, long estimatedAmount) {
        return jdbc.update("""
                UPDATE platform.account_balance
                SET available_amount = available_amount + ?,
                    reserved_amount = reserved_amount - ?,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND reserved_amount >= ?
                """, estimatedAmount, estimatedAmount, accountId, estimatedAmount);
    }

    void releaseReservation(UUID reservationId, boolean expired) {
        jdbc.update("""
                UPDATE platform.wallet_usage_reservation
                SET status = ?, released_at = now()
                WHERE id = ? AND status = 'RESERVED'
                """, expired ? "EXPIRED" : "RELEASED", reservationId);
    }

    List<UUID> expiredReservationIds(int limit) {
        return jdbc.queryForList("""
                SELECT id
                FROM platform.wallet_usage_reservation
                WHERE status = 'RESERVED' AND expires_at <= now()
                ORDER BY expires_at, id
                LIMIT ?
                """, UUID.class, limit);
    }

    private AccountRow account(java.sql.ResultSet result) throws java.sql.SQLException {
        return new AccountRow(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getLong("available_amount"),
                result.getLong("reserved_amount"));
    }

    private ReservationRow reservation(java.sql.ResultSet result) throws java.sql.SQLException {
        return new ReservationRow(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("request_id"),
                result.getLong("estimated_amount"),
                (Long) result.getObject("settled_amount"),
                result.getString("status"),
                result.getString("model_code"),
                result.getString("price_version"),
                (Long) result.getObject("actual_input_tokens"),
                (Long) result.getObject("actual_output_tokens"),
                result.getString("billing_mode"),
                result.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    record AccountRow(
            UUID id, UUID tenantId, UUID userId, long availableAmount, long reservedAmount) {
    }

    record ReservationRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID accountId,
            String requestId,
            long estimatedAmount,
            Long settledAmount,
            String status,
            String modelCode,
            String priceVersion,
            Long actualInputTokens,
            Long actualOutputTokens,
            String billingMode,
            Instant expiresAt) {
    }
}
