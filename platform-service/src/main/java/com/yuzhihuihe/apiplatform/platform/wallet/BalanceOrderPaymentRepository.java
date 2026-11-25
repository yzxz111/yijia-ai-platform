package com.yuzhihuihe.apiplatform.platform.wallet;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BalanceOrderPaymentRepository {

    private final JdbcTemplate jdbc;

    BalanceOrderPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    GrantRow findOriginalGrant(UUID tenantId, UUID userId, String businessNo) {
        return jdbc.query("""
                        SELECT g.id, e.id AS event_id,
                               g.business_no
                        FROM platform.grant_request AS g
                        JOIN platform.outbox_event AS e
                          ON e.aggregate_type = 'GRANT'
                         AND e.aggregate_id = g.id
                         AND e.business_key = g.business_no
                        WHERE g.tenant_id = ? AND g.user_id = ?
                          AND g.business_no = ?
                        """,
                (result, rowNumber) -> new GrantRow(
                        result.getObject("id", UUID.class),
                        result.getObject("event_id", UUID.class),
                        result.getString("business_no")),
                tenantId, userId, businessNo).stream().findFirst().orElse(null);
    }

    record GrantRow(UUID id, UUID eventId, String businessNo) {
    }
}
