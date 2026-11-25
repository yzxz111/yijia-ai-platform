package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Mono;

/**
 * 降级的每日计数器，在 Redis 无法作答时使用。
 *
 * <p>它统计的是本套餐在所公布的上海自然日内已结算的请求数，这与控制台向客户展示为
 * 「今日请求数」的数字相同（{@code UsageAnalyticsRepository}）。这使它成为一种滞后但诚实的
 * 强制执行：只有用量行存在之后，请求才会在此可见，因此在 Redis 故障期间，上限可能被那些
 * 仍在飞行中的请求所突破。它绝不会少报当天数量，并且在数据库无法判定时报错而不是准入。
 */
public final class PostgresDailyRequestLimitStore implements DailyRequestLimitService.Store {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final DatabaseClient databaseClient;

    public PostgresDailyRequestLimitStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Boolean> admit(
            RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
        if (limit == null || !limit.enforced() || userId == null || day == null) {
            return Mono.error(new IllegalArgumentException("invalid_daily_request_limit"));
        }
        return settledRequests(limit, userId, day)
                .flatMap(settled -> settled == null
                        ? Mono.error(new IllegalStateException("daily_request_count_unavailable"))
                        : Mono.just(settled < limit.dailyRequestLimit()));
    }

    @Override
    public Mono<Long> used(
            RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
        if (limit == null || !limit.enforced() || day == null) {
            return Mono.just(0L);
        }
        return settledRequests(limit, userId, day).defaultIfEmpty(0L);
    }

    private Mono<Long> settledRequests(
            RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
        return databaseClient.sql("""
                        SELECT count(*) AS settled_requests
                        FROM gateway.usage_log
                        WHERE user_id = :user_id
                          AND package_instance_id = :package_instance_id
                          AND created_at >= :day_start
                        """)
                .bind("user_id", userId)
                .bind("package_instance_id", limit.packageInstanceId())
                .bind("day_start", day.atStartOfDay(SHANGHAI).toInstant())
                .map((row, metadata) -> row.get("settled_requests", Long.class))
                .one();
    }
}
