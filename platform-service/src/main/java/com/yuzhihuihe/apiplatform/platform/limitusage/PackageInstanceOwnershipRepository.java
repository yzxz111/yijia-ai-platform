package com.yuzhihuihe.apiplatform.platform.limitusage;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 回答「调用方自有套餐」相关接口在与网关通信之前必须先敲定的那个问题：
 * 该套餐实例是否属于已认证的租户 / 用户组合？
 *
 * <p>该实例本身归网关所有，因此数据行存放在
 * {@code gateway.package_instance} 中；平台的数据库角色已持有该表的 SELECT 权限，
 * 运营事实接入路径也会为同一份分配快照读取该表。按租户 <em>与</em> 用户双重限定
 * 查询范围，正是这一点使得他人的实例与不存在的实例无法区分 ——
 * 调用方只会收到 404，绝不会得知该实例存在。这次读取
 * 至多返回一行，并且刻意不产出任何数据：计数器本身来自
 * 网关，而这张表不得成为它们的第二个、更陈旧的数据来源。
 */
@Repository
public class PackageInstanceOwnershipRepository {

    private final JdbcTemplate jdbc;

    public PackageInstanceOwnershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isOwnedByCaller(UUID packageInstanceId, UUID tenantId, UUID userId) {
        if (packageInstanceId == null || tenantId == null || userId == null) {
            return false;
        }
        return jdbc.query("""
                SELECT instance.id
                FROM gateway.package_instance instance
                WHERE instance.id=? AND instance.tenant_id=? AND instance.user_id=?
                """, (result, row) -> result.getObject(1, UUID.class),
                packageInstanceId, tenantId, userId).stream().findFirst().isPresent();
    }
}
