package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Mono;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class RequestLimitConfiguration {

    @Bean
    RequestLimitService requestLimitService(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            DatabaseClient databaseClient,
            @Value("${gateway.limits.db-fallback-max-concurrency:2}") int fallbackMaxConcurrency,
            @Value("${spring.data.redis.timeout:1s}") Duration redisTimeout,
            DailyRequestLimitService dailyRequestLimitService,
            PackageTokenRateLimitService packageTokenRateLimitService) {
        RequestLimitService.LimitStore unavailableRedis = new RequestLimitService.LimitStore() {
            @Override
            public Mono<Boolean> isAvailable() {
                return Mono.just(false);
            }

            @Override
            public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
                return Mono.error(new IllegalStateException("redis_unavailable"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.error(new IllegalStateException("redis_unavailable"));
            }
        };
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        RequestLimitService.LimitStore primary = redis == null
                ? unavailableRedis : new RedisRequestLimitStore(redis);
        return new DefaultRequestLimitService(
                primary, new PostgresRequestLimitStore(databaseClient), fallbackMaxConcurrency,
                redisTimeout, null, dailyRequestLimitService, packageTokenRateLimitService);
    }

    /**
     * 已售出的每分钟额度速率（{@code tokens_per_minute} 在 token 维度上读取，
     * 展示为 积分/分钟）。它的计数器不需要自己的数据库表结构：降级路径统计的是
     * 网关已经写入的用量行。
     *
     * <p>在 {@code VIDEO_SECOND} 实例上，同一列改为表示每秒分钟数速率；
     * {@link PackageRateLimits} 会在请求本服务之前就判定适用哪种读法，因此
     * 秒数数值绝不会到达额度计数器。
     */
    @Bean
    PackageTokenRateLimitService packageTokenRateLimitService(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            DatabaseClient databaseClient,
            @Value("${spring.data.redis.timeout:1s}") Duration redisTimeout) {
        PackageTokenRateLimitService.Store unavailableRedis =
                new PackageTokenRateLimitService.Store() {
                    @Override
                    public Mono<Boolean> isAvailable() {
                        return Mono.just(false);
                    }

                    @Override
                    public Mono<Boolean> admit(
                            RequestLimitService.PackageTokenLimit limit,
                            java.util.UUID userId,
                            java.time.Instant minute) {
                        return Mono.error(new IllegalStateException("redis_unavailable"));
                    }
                };
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        PackageTokenRateLimitService.Store primary = redis == null
                ? unavailableRedis : new RedisPackageTokenRateLimitStore(redis);
        return new DefaultPackageTokenRateLimitService(
                primary, new PostgresPackageTokenRateLimitStore(databaseClient), redisTimeout);
    }

    /**
     * 视频套餐已售出的每分钟秒数速率（{@code tokens_per_minute} 在
     * {@code VIDEO_SECOND} 维度上读取，展示为 SPM）。它的计数器与额度计数器相互独立，
     * 因此一次生成的秒数绝不会被计入对话额度，反之亦然；
     * 降级路径统计的是视频路径已经写入的 {@code VIDEO_SECOND} 用量行。
     */
    @Bean
    PackageSecondRateLimitService packageSecondRateLimitService(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            DatabaseClient databaseClient,
            @Value("${spring.data.redis.timeout:1s}") Duration redisTimeout) {
        PackageSecondRateLimitService.Store unavailableRedis =
                new PackageSecondRateLimitService.Store() {
                    @Override
                    public Mono<Boolean> isAvailable() {
                        return Mono.just(false);
                    }

                    @Override
                    public Mono<Boolean> admit(
                            RequestLimitService.PackageSecondLimit limit,
                            java.util.UUID userId,
                            java.time.Instant minute) {
                        return Mono.error(new IllegalStateException("redis_unavailable"));
                    }
                };
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        PackageSecondRateLimitService.Store primary = redis == null
                ? unavailableRedis : new RedisPackageSecondRateLimitStore(redis);
        return new DefaultPackageSecondRateLimitService(
                primary, new PostgresPackageSecondRateLimitStore(databaseClient), redisTimeout,
                new PostgresPackageRateSource(databaseClient));
    }

    /**
     * {@code /internal/limit-usage} 背后的被拒准入计数器。仅使用 Redis 且仅供参考：
     * 它的写入与读取都只用于报表，绝不用于准入决策，因此没有 Redis 时丢失的是计数，
     * 而不是端点或流量被阻塞。
     */
    @Bean
    BlockedAdmissionService blockedAdmissionService(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            @Value("${spring.data.redis.timeout:1s}") Duration redisTimeout) {
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        return redis == null
                ? BlockedAdmissionService.disabled()
                : new RedisBlockedAdmissionService(redis, redisTimeout);
    }

    @Bean
    DailyRequestLimitService dailyRequestLimitService(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            DatabaseClient databaseClient,
            @Value("${spring.data.redis.timeout:1s}") Duration redisTimeout) {
        DailyRequestLimitService.Store unavailableRedis = new DailyRequestLimitService.Store() {
            @Override
            public Mono<Boolean> isAvailable() {
                return Mono.just(false);
            }

            @Override
            public Mono<Boolean> admit(
                    RequestLimitService.PackageDailyLimit limit,
                    java.util.UUID userId,
                    java.time.LocalDate day) {
                return Mono.error(new IllegalStateException("redis_unavailable"));
            }
        };
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        DailyRequestLimitService.Store primary = redis == null
                ? unavailableRedis : new RedisDailyRequestLimitStore(redis);
        return new DefaultDailyRequestLimitService(
                primary, new PostgresDailyRequestLimitStore(databaseClient), redisTimeout);
    }
}
