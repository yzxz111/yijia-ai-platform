package com.yuzhihuihe.apiplatform.platform.safety;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountEnforcementRepository {

    private final JdbcTemplate jdbc;

    public AccountEnforcementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isRestricted(UUID tenantId, UUID userId) {
        Boolean restricted = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM platform.account_enforcement enforcement
                    WHERE enforcement.tenant_id = ? AND enforcement.user_id = ?
                      AND enforcement.state IN ('FROZEN', 'BANNED')
                      AND enforcement.starts_at <= now()
                      AND (enforcement.ends_at IS NULL OR enforcement.ends_at > now())
                )
                """, Boolean.class, tenantId, userId);
        return Boolean.TRUE.equals(restricted);
    }

    public int initializeMissingGatewayCacheBaselines() {
        return jdbc.update("""
                INSERT INTO gateway.safety_enforcement_cache(
                    id, tenant_id, user_id, state, reason_code,
                    event_version, effective_at, updated_at)
                SELECT gen_random_uuid(), app_user.tenant_id, app_user.id,
                       'CLEARED', 'account_initialized', 1, now(), now()
                FROM platform.app_user app_user
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """);
    }
}
