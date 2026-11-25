package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WalletAccountRepository {

    private final JdbcTemplate jdbc;

    WalletAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void createAccount(UUID id, UUID tenantId, UUID userId) {
        jdbc.update("""
                INSERT INTO platform.account_balance(
                    id, tenant_id, user_id, currency, available_amount)
                VALUES (?, ?, ?, 'CNY', 0)
                ON CONFLICT (tenant_id, user_id, currency) DO NOTHING
                """, id, tenantId, userId);
    }

    void seedDefaultRechargeOptions(UUID tenantId) {
        jdbc.update("""
                INSERT INTO platform.recharge_option(
                    id, tenant_id, amount, enabled, display_order)
                SELECT gen_random_uuid(), ?, defaults.amount, true, defaults.display_order
                FROM (VALUES
                    (1000::bigint, 10),
                    (3000::bigint, 20),
                    (5000::bigint, 30),
                    (10000::bigint, 40),
                    (20000::bigint, 50),
                    (30000::bigint, 60),
                    (50000::bigint, 70)
                ) AS defaults(amount, display_order)
                ON CONFLICT (tenant_id, amount) DO NOTHING
                """, tenantId);
    }

    AccountRow findAccount(UUID tenantId, UUID userId) {
        return jdbc.query("""
                        SELECT id, tenant_id, user_id, currency,
                               available_amount, reserved_amount, version, updated_at
                        FROM platform.account_balance
                        WHERE tenant_id = ? AND user_id = ? AND currency = 'CNY'
                        """,
                (result, rowNumber) -> new AccountRow(
                        result.getObject("id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getString("currency"),
                        result.getLong("available_amount"),
                        result.getLong("reserved_amount"),
                        result.getLong("version"),
                        result.getObject("updated_at", OffsetDateTime.class).toInstant()),
                tenantId, userId).stream().findFirst().orElse(null);
    }

    record AccountRow(
            UUID id,
            UUID tenantId,
            UUID userId,
            String currency,
            long availableAmount,
            long reservedAmount,
            long version,
            Instant updatedAt) {
    }
}
