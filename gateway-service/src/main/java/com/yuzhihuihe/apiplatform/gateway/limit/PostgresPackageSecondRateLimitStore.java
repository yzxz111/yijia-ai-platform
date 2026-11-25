package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Mono;

/**
 * 降级的生成单位计数器，在 Redis 无法应答时使用。
 *
 * <p>它从生成路径写入的用量行中，汇总套餐在当前这一分钟内已经结算的单位：
 * {@code gateway.usage_log} 中该套餐**所有按单位计费维度**的行之和
 * （{@code VIDEO_SECOND} 与 {@code AUDIO_SECOND} 的 {@code usage_units} 都是计费秒数，
 * {@code IMAGE} 的 {@code usage_units} 是计费张数，均由各自的证据写入器写入）。
 *
 * <p><b>为什么不是"只数某一个维度"。</b>售出的那个数字是
 * {@code package_instance.tokens_per_minute}，一个套餐一列、一个上限；而
 * {@link RedisPackageSecondRateLimitStore} 的计数器**按套餐实例一个 key**，
 * 从来不分维度。因此"这个上限是套餐级的"不是取舍而是既有事实，
 * 降级路径只是要跟主路径数**同一件事**：一个混合套餐（视频秒 + 音频秒）里，
 * 视频与语音各自消耗的秒数都要计入同一个上限。早先按
 * {@code usage_dimension = :usage_dimension} 过滤时，混合套餐的语音用量完全
 * 不被计入——于是那个上限对语音形同不存在，而它同样是客户已经买下的东西。
 *
 * <p>token 行**必须排除**：它们的 {@code usage_units} 是 token 数
 * （V3 起 {@code usage_units = total_tokens}），把它加进"秒"里就是单位混乱。
 * 一个按秒计费的套餐本来也承载不了 token 别名（平台发布校验会拒），
 * 因此这条排除是防守性的。
 *
 * <p>它与 credit 降级路径一样存在滞后（只有用量行存在之后，任务才可见），
 * 并且在数据库无法判定时会报错，而不是准入。
 */
public final class PostgresPackageSecondRateLimitStore implements PackageSecondRateLimitService.Store {

    private final DatabaseClient databaseClient;

    public PostgresPackageSecondRateLimitStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Boolean> admit(
            RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced() || userId == null || minute == null) {
            return Mono.error(new IllegalArgumentException("invalid_package_second_limit"));
        }
        return chargedUnits(limit, userId, minute)
                .flatMap(charged -> charged == null
                        ? Mono.error(new IllegalStateException("second_rate_count_unavailable"))
                        : Mono.just(charged < limit.secondsPerMinute()));
    }

    @Override
    public Mono<Long> used(
            RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
        if (limit == null || !limit.enforced() || minute == null) {
            return Mono.just(0L);
        }
        return chargedUnits(limit, userId, minute).defaultIfEmpty(0L);
    }

    private Mono<Long> chargedUnits(
            RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
        return databaseClient.sql("""
                        SELECT COALESCE(sum(usage_units), 0) AS charged_units
                        FROM gateway.usage_log
                        WHERE user_id = :user_id
                          AND package_instance_id = :package_instance_id
                          AND usage_dimension <> 'TOKEN'
                          AND created_at >= :minute_start
                          AND created_at < :minute_start + interval '1 minute'
                        """)
                .bind("user_id", userId)
                .bind("package_instance_id", limit.packageInstanceId())
                .bind("minute_start", minute)
                .map((row, metadata) -> row.get("charged_units", Long.class))
                .one();
    }
}
