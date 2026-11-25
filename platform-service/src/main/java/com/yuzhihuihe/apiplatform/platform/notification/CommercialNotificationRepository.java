package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CommercialNotificationRepository {

    private final JdbcTemplate jdbc;

    CommercialNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<GrantNotification> findActiveGrant(UUID grantId) {
        return jdbc.query("""
                SELECT grant_request.tenant_id, grant_request.user_id,
                       grant_request.completed_at, customer_order.amount,
                       (customer_order.package_snapshot->>'durationDays')::integer AS duration_days
                FROM platform.grant_request AS grant_request
                JOIN platform.customer_order AS customer_order
                  ON grant_request.business_no = 'order:' || customer_order.order_no
                WHERE grant_request.id = ? AND grant_request.status = 'ACTIVE'
                  AND grant_request.completed_at IS NOT NULL
                  AND jsonb_exists(customer_order.package_snapshot, 'durationDays')
                """, (result, rowNumber) -> new GrantNotification(
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getObject("completed_at", OffsetDateTime.class),
                        result.getLong("amount"), result.getInt("duration_days")), grantId)
                .stream()
                .findFirst();
    }

    record GrantNotification(
            UUID tenantId,
            UUID userId,
            OffsetDateTime completedAt,
            long amountFen,
            int durationDays) {
    }
}
