package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WalletUsageNotificationRepository {

    private final JdbcTemplate jdbc;

    WalletUsageNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureLowBalanceState(UUID tenantId, UUID userId) {
        jdbc.update("""
                INSERT INTO platform.notification_trigger_state(
                    tenant_id, user_id, trigger_type, state_key, state)
                VALUES (?, ?, 'LOW_BALANCE', 'DEFAULT', '{"armed":true}'::jsonb)
                ON CONFLICT (user_id, trigger_type, state_key) DO NOTHING
                """, tenantId, userId);
    }

    void rearmLowBalance(UUID userId) {
        jdbc.update("""
                UPDATE platform.notification_trigger_state
                SET state=jsonb_set(state, '{armed}', 'true'::jsonb, true), updated_at=now()
                WHERE user_id=? AND trigger_type='LOW_BALANCE' AND state_key='DEFAULT'
                """, userId);
    }

    TriggerState lockLowBalanceState(UUID userId) {
        return jdbc.query("""
                SELECT coalesce((state->>'armed')::boolean, true), last_triggered_at
                FROM platform.notification_trigger_state
                WHERE user_id=? AND trigger_type='LOW_BALANCE' AND state_key='DEFAULT'
                FOR UPDATE
                """, (result, row) -> new TriggerState(
                result.getBoolean(1), result.getTimestamp(2) == null
                        ? null : result.getTimestamp(2).toInstant()), userId)
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("wallet_low_balance_state_missing"));
    }

    void disarmLowBalance(UUID userId, Instant triggeredAt, Instant nextEligibleAt) {
        jdbc.update("""
                UPDATE platform.notification_trigger_state
                SET state=jsonb_set(state, '{armed}', 'false'::jsonb, true),
                    last_triggered_at=?, next_eligible_at=?, updated_at=now()
                WHERE user_id=? AND trigger_type='LOW_BALANCE' AND state_key='DEFAULT'
                """, Timestamp.from(triggeredAt), Timestamp.from(nextEligibleAt), userId);
    }

    record TriggerState(boolean armed, Instant lastTriggeredAt) {
    }
}
