package com.yuzhihuihe.apiplatform.platform.grant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class GrantRepository {

    private final JdbcTemplate jdbc;

    GrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> findActiveUserTenantId(UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT tenant_id
                        FROM platform.app_user
                        WHERE id = ? AND status = 'ACTIVE'
                        """,
                        (result, rowNumber) -> result.getObject("tenant_id", UUID.class),
                        userId)
                .stream()
                .findFirst();
    }
}
