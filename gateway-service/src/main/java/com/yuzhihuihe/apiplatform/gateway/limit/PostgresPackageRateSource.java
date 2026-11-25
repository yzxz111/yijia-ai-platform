package com.yuzhihuihe.apiplatform.gateway.limit;

import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Mono;

/**
 * 从 {@code gateway.package_instance} 读取某个套餐实例已售出的每分钟速率。
 *
 * <p>只做一次带索引的主键查询，仅由视频路径的准入使用：聊天路径已在它自己的目录查询中
 * 读取同样这两列并据此构建限额。找不到的套餐实例会解析为「没有已售出速率」，而不是
 * 报错——紧随其后的预留才决定该套餐是否存在、是否可用。
 */
public final class PostgresPackageRateSource implements PackageRateSource {

    private final DatabaseClient databaseClient;

    public PostgresPackageRateSource(DatabaseClient databaseClient) {
        if (databaseClient == null) {
            throw new IllegalArgumentException("invalid_package_rate_source");
        }
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<PackageRateLimits> rateFor(UUID packageInstanceId) {
        if (packageInstanceId == null) {
            return Mono.just(PackageRateLimits.unlimited());
        }
        return databaseClient.sql("""
                        SELECT usage_dimension, tokens_per_minute
                        FROM gateway.package_instance
                        WHERE id = :package_instance_id
                        """)
                .bind("package_instance_id", packageInstanceId)
                .map((row, metadata) -> PackageRateLimits.of(
                        packageInstanceId,
                        row.get("usage_dimension", String.class),
                        row.get("tokens_per_minute", Long.class)))
                .one()
                .defaultIfEmpty(PackageRateLimits.unlimited());
    }
}
