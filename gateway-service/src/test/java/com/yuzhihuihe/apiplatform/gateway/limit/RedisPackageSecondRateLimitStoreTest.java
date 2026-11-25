package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
 * 视频套餐已售速率的秒数主计数器。
 *
 * <p>它必须与积分计数器行为完全一致且与之不共享任何东西：一次生成的秒数在任务结算之后被记录，
 * 并在下一次提交时被读取，而且两个方向必须无法看到彼此的数字。
 */
@Testcontainers
class RedisPackageSecondRateLimitStoreTest {

    private static final UUID USER_ID = UUID.fromString("95500000-0000-4000-8000-000000000011");
    private static final Instant MINUTE = Instant.parse("2026-09-16T04:05:00Z");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static RedisPackageSecondRateLimitStore store;
    static RedisPackageTokenRateLimitStore creditStore;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        store = new RedisPackageSecondRateLimitStore(redis);
        creditStore = new RedisPackageTokenRateLimitStore(redis);
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
    void anEmptyMinuteAdmitsUntilTheSecondsThePackageGeneratedReachTheSoldRate() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageSecondLimit rate = rate(packageId, 60);

        assertThat(admit(rate)).as("a package that generated nothing yet is admitted").isTrue();
        store.record(packageId, 60).block(Duration.ofSeconds(5));

        assertThat(admit(rate))
                .as("the settled seconds filled the advertised minute, so the next generation is"
                        + " refused")
                .isFalse();
    }

    @Test
    void recordingPartialConsumptionKeepsAdmitting() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageSecondLimit rate = rate(packageId, 190);

        store.record(packageId, 120).block(Duration.ofSeconds(5));

        assertThat(admit(rate)).isTrue();
        store.record(packageId, 70).block(Duration.ofSeconds(5));
        assertThat(admit(rate)).isFalse();
    }

    @Test
    void theTwoDimensionsShareNoCounter() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageSecondLimit seconds = rate(packageId, 10);
        RequestLimitService.PackageTokenLimit credits =
                RequestLimitService.PackageTokenLimit.of(packageId, 10L);

        store.record(packageId, 10).block(Duration.ofSeconds(5));

        assertThat(admit(seconds)).as("the seconds a generation used fill the seconds rate").isFalse();
        assertThat(Boolean.TRUE.equals(
                creditStore.admit(credits, USER_ID, MINUTE).block(Duration.ofSeconds(5))))
                .as("and must never consume the package's chat credits")
                .isTrue();
    }

    @Test
    void secondsAreScopedToThePackageInstance() {
        UUID firstPackage = UUID.randomUUID();
        UUID secondPackage = UUID.randomUUID();

        store.record(firstPackage, 10).block(Duration.ofSeconds(5));

        assertThat(admit(rate(firstPackage, 10))).isFalse();
        assertThat(admit(rate(secondPackage, 10))).isTrue();
    }

    @Test
    void aLaterMinuteStartsEmpty() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageSecondLimit rate = rate(packageId, 10);
        String key = store.counterKey(packageId);

        store.record(packageId, 10).block(Duration.ofSeconds(5));
        assertThat(admit(rate)).isFalse();

        // 重写该 bucket，仿佛记录的分钟是更早的一分钟：此时秒数计数器必须读作空，
        // 而不是把那一分钟带入当前这一分钟。
        redis.<String, String>opsForHash().put(key, "window", anEarlierWindow()).block();
        assertThat(admit(rate))
                .as("seconds generated in an earlier minute must not throttle this one")
                .isTrue();
    }

    @Test
    void usedReportsTheSettledSecondsOfTheCurrentMinuteAndZeroOtherwise() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageSecondLimit rate = rate(packageId, 190);

        assertThat(used(rate)).as("an absent counter reads as nothing used").isZero();

        store.record(packageId, 37).block(Duration.ofSeconds(5));
        assertThat(used(rate)).isEqualTo(37L);

        redis.<String, String>opsForHash()
                .put(store.counterKey(packageId), "window", anEarlierWindow())
                .block(Duration.ofSeconds(5));
        assertThat(used(rate)).as("an expired counter reads as nothing used").isZero();
    }

    @Test
    void probeReportsRedisAvailability() {
        assertThat(store.isAvailable().block(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void theCounterExpiresOnItsOwn() {
        UUID packageId = UUID.randomUUID();
        store.record(packageId, 10).block(Duration.ofSeconds(5));

        Duration ttl = redis.getExpire(store.counterKey(packageId)).block(Duration.ofSeconds(5));

        assertThat(ttl).isNotNull();
        assertThat(ttl.getSeconds()).isBetween(1L, 120L);
    }

    private static boolean admit(RequestLimitService.PackageSecondLimit rate) {
        return Boolean.TRUE.equals(store.admit(rate, USER_ID, MINUTE).block(Duration.ofSeconds(5)));
    }

    private static long used(RequestLimitService.PackageSecondLimit rate) {
        return store.used(rate, USER_ID, MINUTE).block(Duration.ofSeconds(5));
    }

    /** 往前两分钟的窗口，使用 Lua 脚本所存储的单位。 */
    private static String anEarlierWindow() {
        return Long.toString(Instant.now().getEpochSecond() / 60 - 2);
    }

    private static RequestLimitService.PackageSecondLimit rate(UUID packageId, long seconds) {
        return RequestLimitService.PackageSecondLimit.of(packageId, seconds);
    }
}
