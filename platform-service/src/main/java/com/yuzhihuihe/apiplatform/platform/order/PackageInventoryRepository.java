package com.yuzhihuihe.apiplatform.platform.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackageInventoryRepository {

    private final JdbcTemplate jdbc;

    PackageInventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<InventoryRow> lockInventory(UUID packageVersionId) {
        return jdbc.query("""
                SELECT status, stock_total, stock_sold, stock_reserved, low_stock_threshold,
                       max_purchases_per_user, once_per_user
                FROM platform.package_plan_version
                WHERE id = ?
                FOR UPDATE
                """, (result, rowNumber) -> new InventoryRow(
                result.getString("status"),
                nullableLong(result, "stock_total"),
                result.getLong("stock_sold"),
                result.getLong("stock_reserved"),
                result.getLong("low_stock_threshold"),
                nullableInteger(result, "max_purchases_per_user"),
                result.getBoolean("once_per_user")), packageVersionId)
                .stream().findFirst();
    }

    boolean hasActiveUser(UUID tenantId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.app_user
                    WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE')
                """, Boolean.class, userId, tenantId));
    }

    long purchaseCount(UUID userId, UUID packageVersionId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.customer_order
                WHERE user_id = ? AND package_plan_version_id = ?
                  AND inventory_status IN ('RESERVED', 'SOLD')
                """, Long.class, userId, packageVersionId);
    }

    int incrementReserved(UUID packageVersionId) {
        return jdbc.update("""
                UPDATE platform.package_plan_version
                SET stock_reserved = stock_reserved + 1
                WHERE id = ?
                  AND (stock_total IS NULL OR stock_sold + stock_reserved < stock_total)
                """, packageVersionId);
    }

    Optional<UUID> findPackageVersionId(UUID orderId) {
        return jdbc.query("""
                SELECT package_plan_version_id
                FROM platform.customer_order
                WHERE id = ?
                """, (result, rowNumber) -> result.getObject("package_plan_version_id", UUID.class),
                orderId).stream().findFirst();
    }

    Optional<String> lockReservationStatus(UUID orderId, UUID packageVersionId) {
        return jdbc.query("""
                SELECT inventory_status
                FROM platform.customer_order
                WHERE id = ? AND package_plan_version_id = ?
                FOR UPDATE
                """, (result, rowNumber) -> result.getString("inventory_status"),
                orderId, packageVersionId).stream().findFirst();
    }

    int changeInventory(String targetStatus, UUID packageVersionId) {
        return jdbc.update("""
                UPDATE platform.package_plan_version
                SET stock_reserved = stock_reserved - 1,
                    stock_sold = stock_sold + CASE WHEN ? = 'SOLD' THEN 1 ELSE 0 END
                WHERE id = ? AND stock_reserved > 0
                """, targetStatus, packageVersionId);
    }

    void changeOrderStatus(String targetStatus, UUID orderId) {
        jdbc.update("""
                UPDATE platform.customer_order
                SET inventory_status = ?, updated_at = now()
                WHERE id = ? AND inventory_status = 'RESERVED'
                """, targetStatus, orderId);
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    record InventoryRow(
            String status,
            Long stockTotal,
            long stockSold,
            long stockReserved,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            boolean oncePerUser) {
    }
}
