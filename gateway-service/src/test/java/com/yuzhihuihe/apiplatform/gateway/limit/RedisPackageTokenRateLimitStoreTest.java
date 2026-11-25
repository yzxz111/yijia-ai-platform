package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 已售每分钟积分速率的主计数器。积分在请求结算之后被记录，并在下一次准入时被读取，而且
 * 记录与读取这两半必须对它们所属的分钟达成一致。
 */
@Testcontainers
class RedisPackageTokenRateLimitStoreTest {

    private static final UUID USER_ID = UUID.fromString("95500000-0000-4000-8000-000000000001");
    private static final Instant MINUTE = Instant.parse("2026-09-16T04:05:00Z");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static RedisPackageTokenRateLimitStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        store = new RedisPackageTokenRateLimitStore(redis);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        redis.execute(connection -> connection.serverCommands().flushAll()).then().block();
    }

    @Test
    void anEmptyMinuteAdmitsUntilTheCreditsThePackageConsumedReachTheSoldRate() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageTokenLimit rate = rate(packageId, 30);

        assertThat(admit(rate)).as("a package that spent nothing yet is admitted").isTrue();
        store.record(rate, 30).block(Duration.ofSeconds(5));

        assertThat(admit(rate))
                .as("the settled charge filled the advertised minute, so the next request is"
                        + " refused")
                .isFalse();
    }

    @Test
    void recordingPartialConsumptionKeepsAdmitting() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageTokenLimit rate = rate(packageId, 30);

        store.record(rate, 29).block(Duration.ofSeconds(5));

        assertThat(admit(rate)).isTrue();
        store.record(rate, 1).block(Duration.ofSeconds(5));
        assertThat(admit(rate)).isFalse();
    }

    @Test
    void creditsAreScopedToThePackageInstance() {
        UUID firstPackage = UUID.randomUUID();
        UUID secondPackage = UUID.randomUUID();

        store.record(rate(firstPackage, 10), 10).block(Duration.ofSeconds(5));

        assertThat(admit(rate(firstPackage, 10))).isFalse();
        assertThat(admit(rate(secondPackage, 10))).isTrue();
    }

    @Test
    void aLaterMinuteStartsEmpty() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageTokenLimit rate = rate(packageId, 10);
        String key = store.counterKey(packageId);

        store.record(rate, 10).block(Duration.ofSeconds(5));
        assertThat(admit(rate)).isFalse();

        // 重写该 bucket，仿佛记录的分钟是更早的一分钟：此时积分计数器必须读作空，
        // 而不是把那一分钟带入当前这一分钟。
        redis.<String, String>opsForHash().put(key, "window", anEarlierWindow()).block();
        assertThat(admit(rate))
                .as("consumption recorded in an earlier minute must not throttle this one")
                .isTrue();
    }

    @Test
    void recordingIsAtomicUnderConcurrency() throws Exception {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageTokenLimit rate = rate(packageId, 10_000);
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            calls.add(CompletableFuture.runAsync(() -> store.record(rate, 100).block(Duration.ofSeconds(5))));
        }
        for (CompletableFuture<Void> call : calls) {
            try {
                call.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                throw new AssertionError(error.getCause());
            }
        }

        assertThat(credits(packageId))
                .as("eight concurrent settlements must add up to exactly eight charges")
                .isEqualTo("800");
    }

    @Test
    void probeReportsRedisAvailability() {
        assertThat(store.isAvailable().block(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void theCounterExpiresOnItsOwn() {
        UUID packageId = UUID.randomUUID();
        store.record(rate(packageId, 10), 10).block(Duration.ofSeconds(5));

        Duration ttl = redis.getExpire(store.counterKey(packageId)).block(Duration.ofSeconds(5));

        assertThat(ttl).isNotNull();
        assertThat(ttl.getSeconds()).isBetween(1L, 120L);
    }

    private static boolean admit(RequestLimitService.PackageTokenLimit rate) {
        return Boolean.TRUE.equals(store.admit(rate, USER_ID, MINUTE).block(Duration.ofSeconds(5)));
    }

    private static String credits(UUID packageId) {
        String key = store.counterKey(packageId);
        return redis.<String, String>opsForHash().get(key, "credits").block(Duration.ofSeconds(5));
    }

    /**
     * 往前两分钟的窗口，使用 Lua 脚本所存储的同一单位。之所以是两分钟而不是一分钟，是为了让
     * 时钟与 Redis 主机相差几秒的测试机器仍然能写入一个存储层可与当前分钟区分开的窗口。
     */
    private static String anEarlierWindow() {
        return Long.toString(Instant.now().getEpochSecond() / 60 - 2);
    }

    private static RequestLimitService.PackageTokenLimit rate(UUID packageId, long credits) {
        return RequestLimitService.PackageTokenLimit.of(packageId, credits);
    }
}
