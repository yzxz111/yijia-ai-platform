package com.yuzhihuihe.apiplatform.platform.order;

import java.sql.PreparedStatement;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackagePurchasePolicyRepository {

    private final JdbcTemplate jdbc;

    PackagePurchasePolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lock(UUID tenantId, UUID userId, String packageCode) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, tenantId + ":" + userId + ":" + packageCode);
                statement.execute();
                return null;
            }
        });
    }

    /**
     * 该用户是否已经持有一个**还没用完**的同 code 套餐。
     *
     * <p>方案 C 起这个判断要问两份额度：一个同时卖视频秒与音频秒的实例，
     * 视频秒花完时它的音频秒仍然有效，因此它仍然是"没用完"。只看主桶会
     * 把它判成已用尽，于是"你已经买过这个套餐"的守卫会失效（或反过来多拦一次），
     * 而两种结果都不会报错——它只会改变一次购买决定。
     *
     * <p>判据与网关侧 `ApiKeyRepository`、`ManagedApiKeyRepository` 的两条准入
     * 谓词逐字相同：**任一桶还有余量**就还算没用完。
     */
    boolean hasUnconsumedPackage(UUID tenantId, UUID userId, String packageCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                    FROM gateway.package_instance
                    WHERE tenant_id = ? AND user_id = ? AND package_code = ?
                      AND status IN ('QUEUED', 'ACTIVE')
                      AND (used_tokens + reserved_tokens < total_token_quota
                           OR (audio_seconds_quota IS NOT NULL
                               AND audio_seconds_reserved + audio_seconds_used
                                   < audio_seconds_quota))
                )
                """, Boolean.class, tenantId, userId, packageCode));
    }

    long purchaseCount(UUID tenantId, UUID userId, String packageCode) {
        Long packageInstances = jdbc.queryForObject("""
                SELECT count(*)
                FROM gateway.package_instance
                WHERE tenant_id = ? AND user_id = ? AND package_code = ?
                """, Long.class, tenantId, userId, packageCode);
        Long pendingOrders = jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.customer_order o
                JOIN platform.package_plan_version v ON v.id = o.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE o.tenant_id = ? AND o.user_id = ? AND o.status = 'PENDING'
                  AND p.code = ?
                """, Long.class, tenantId, userId, packageCode);
        return Math.addExact(
                packageInstances == null ? 0L : packageInstances,
                pendingOrders == null ? 0L : pendingOrders);
    }
}
