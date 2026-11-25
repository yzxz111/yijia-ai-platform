package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class FreePackageClaimRepository {

    private final JdbcTemplate jdbc;

    FreePackageClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UserTenantRow> findActiveUser(UUID userId) {
        return jdbc.query("""
                SELECT id, tenant_id
                FROM platform.app_user
                WHERE id = ? AND status = 'ACTIVE'
                """, (result, rowNumber) -> new UserTenantRow(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class)), userId).stream().findFirst();
    }

    List<UUID> findEligibleFreeVersionIds() {
        return jdbc.queryForList("""
                SELECT v.id
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.kind = 'FREE_CLAIM'
                  AND p.status = 'ACTIVE'
                  AND v.status = 'PUBLISHED'
                  AND v.price_amount = 0
                  AND v.once_per_user = true
                ORDER BY v.version DESC
                """, UUID.class);
    }

    boolean hasClaimed(UUID userId, String businessNo) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM platform.grant_request
                    WHERE user_id = ?
                      AND business_no = ?
                )
                """, Boolean.class, userId, businessNo));
    }

    record UserTenantRow(UUID id, UUID tenantId) {
    }
}
