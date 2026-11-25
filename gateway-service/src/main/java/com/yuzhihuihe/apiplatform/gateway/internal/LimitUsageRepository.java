package com.yuzhihuihe.apiplatform.gateway.internal;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * 读取某个套餐实例已售出的限额，供 {@code /internal/limit-usage} 使用。
 *
 * <p>它有意把 {@code usage_dimension} 与 {@code tokens_per_minute} 放在一起读取：每分钟
 * 这一列是携带维度的，所以维度必须随它一起传递，否则调用方无法分辨 token 速率与
 * 秒/分钟速率。{@code user_id} 也要读取，因为降级计数器统计的是某个客户已结算的行数，
 * 需要知道该客户是谁。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class LimitUsageRepository {

    private final DatabaseClient databaseClient;

    LimitUsageRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<PackageLimits> find(UUID packageInstanceId) {
        return databaseClient.sql("""
                        SELECT user_id, usage_dimension, tokens_per_minute, daily_request_limit
                        FROM gateway.package_instance
                        WHERE id = :package_instance_id
                        """)
                .bind("package_instance_id", packageInstanceId)
                .map((row, metadata) -> new PackageLimits(
                        packageInstanceId,
                        row.get("user_id", UUID.class),
                        row.get("usage_dimension", String.class),
                        row.get("tokens_per_minute", Long.class),
                        row.get("daily_request_limit", Integer.class)))
                .one();
    }

    record PackageLimits(
            UUID packageInstanceId,
            UUID userId,
            String usageDimension,
            Long tokensPerMinute,
            Integer dailyRequestLimit) { }
}
