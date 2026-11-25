package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Mono;

/**
 * 降级的 credit 计数器，在 Redis 无法应答时使用。
 *
 * <p>它汇总套餐在当前这一分钟内已经结算的用量，与控制台统计请求数所用的行集合、时钟列，以及
 * 每日上限的降级存储读取的完全一致。它是有滞后的（只有用量行存在之后，请求才可见），并且比
 * 主计数器略宽（它汇总每一行已结算的全部用量，包括混合请求由钱包支付的那部分），因此在 Redis
 * 故障期间，速率可能被仍在途中的请求超出，而一个完全由套餐覆盖的请求会被精确计入。它绝不会
 * 少报这一分钟，并且在数据库无法判定时会报错，而不是准入。
 *
 * <p>这里没有对缓存命中的折扣建模：该列是请求已结算的用量，
 * 也就是客户自己的用量页面在这一分钟展示的内容。
 */
public final class PostgresPackageTokenRateLimitStore implements PackageTokenRateLimitService.Store {

    private final DatabaseClient databaseClient;

    public PostgresPackageTokenRateLimitStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Boolean> admit(
            RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced() || userId == null || minute == null) {
            return Mono.error(new IllegalArgumentException("invalid_package_token_limit"));
        }
        return chargedCredits(limit, userId, minute)
                .flatMap(charged -> charged == null
                        ? Mono.error(new IllegalStateException("token_rate_count_unavailable"))
                        : Mono.just(charged < limit.tokensPerMinute()));
    }

    @Override
    public Mono<Long> used(
            RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced() || minute == null) {
            return Mono.just(0L);
        }
        return chargedCredits(limit, userId, minute).defaultIfEmpty(0L);
    }

    private Mono<Long> chargedCredits(
            RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
        return databaseClient.sql("""
                        SELECT COALESCE(sum(total_tokens), 0) AS charged_credits
                        FROM gateway.usage_log
                        WHERE user_id = :user_id
                          AND package_instance_id = :package_instance_id
                          AND created_at >= :minute_start
                        """)
                .bind("user_id", userId)
                .bind("package_instance_id", limit.packageInstanceId())
                .bind("minute_start", minute)
                .map((row, metadata) -> row.get("charged_credits", Long.class))
                .one();
    }
}
