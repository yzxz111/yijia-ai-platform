package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
 * {@code /internal/limit-usage} 背后的被阻止准入计数器。
 *
 * <p>它只起提示作用且只依赖 Redis，因此关键在于：它按套餐、按原因统计被告知的内容，
 * 只上报最近 24 个完整小时内的拒绝，并且 Redis 不可用时是丢失计数，
 * 而不是让读取方失败。
 */
@Testcontainers
class RedisBlockedAdmissionServiceTest {

    private static final UUID PACKAGE_ID = UUID.fromString("95600000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PACKAGE_ID =
            UUID.fromString("95600000-0000-4000-8000-000000000002");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T04:05:59Z"), ZoneOffset.UTC);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static RedisBlockedAdmissionService service;
    static LettuceConnectionFactory broken;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        service = new RedisBlockedAdmissionService(redis, Duration.ofSeconds(5), CLOCK);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (broken != null) {
            broken.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        redis.execute(connection -> connection.serverCommands().flushAll()).then().block();
    }

    @Test
    void countsRejectionsPerReasonAndPerPackage() {
        service.record(PACKAGE_ID, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);
        service.record(PACKAGE_ID, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);
        service.record(PACKAGE_ID, BlockedAdmissionService.DAILY_REQUEST_LIMIT_EXCEEDED);
        service.record(OTHER_PACKAGE_ID, BlockedAdmissionService.SECONDS_PER_MINUTE_EXCEEDED);

        assertThat(awaitCounts(PACKAGE_ID)).containsExactlyInAnyOrderEntriesOf(Map.of(
                BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED, 2L,
                BlockedAdmissionService.DAILY_REQUEST_LIMIT_EXCEEDED, 1L));
        assertThat(awaitCounts(OTHER_PACKAGE_ID)).containsExactly(
                Map.entry(BlockedAdmissionService.SECONDS_PER_MINUTE_EXCEEDED, 1L));
    }

    @Test
    void aPackageThatWasNeverRefusedReportsNothing() {
        assertThat(service.last24Hours(UUID.randomUUID()).block(Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void rejectionsOlderThanTheWindowAreNotReported() {
        // 30 小时前的那个小时落在最近 24 个完整小时之外；只有当窗口算错时，
        // 计数器才会保留它。
        String key = service.counterKey(PACKAGE_ID);
        long staleHour = Instant.now(CLOCK).getEpochSecond() / 3600 - 30;
        redis.<String, String>opsForHash()
                .put(key, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED + ":" + staleHour, "9")
                .block(Duration.ofSeconds(5));

        service.record(PACKAGE_ID, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);

        assertThat(awaitCounts(PACKAGE_ID)).containsExactly(
                Map.entry(BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED, 1L));
    }

    @Test
    void theCounterExpiresOnItsOwn() {
        service.record(PACKAGE_ID, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);
        awaitCounts(PACKAGE_ID);

        Duration ttl = redis.getExpire(service.counterKey(PACKAGE_ID)).block(Duration.ofSeconds(5));

        assertThat(ttl).isNotNull();
        assertThat(ttl.getSeconds()).isBetween(1L, 90000L);
    }

    @Test
    void anUnavailableRedisLosesCountsRatherThanFailing() {
        broken = brokenFactory();
        RedisBlockedAdmissionService unreachable = new RedisBlockedAdmissionService(
                new ReactiveStringRedisTemplate(broken), Duration.ofMillis(50), CLOCK);

        unreachable.record(PACKAGE_ID, BlockedAdmissionService.TOKENS_PER_MINUTE_EXCEEDED);

        assertThat(unreachable.last24Hours(PACKAGE_ID).block(Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void anUnknownOrOversizedReasonIsStillBounded() {
        service.record(PACKAGE_ID, "Rate Limit! Exceeded \u4e2d\u6587");

        assertThat(awaitCounts(PACKAGE_ID))
                .as("a reason is a code, so anything else is sanitised into the same key space")
                .containsExactly(Map.entry("rate_limit__exceeded___", 1L));
    }

    /**
     * 等到读数**稳定**再返回，而不是"一读到非空就返回"。
     *
     * <p>被阻止准入的计数是 fire-and-forget 写进 Redis 的（{@code record} 不等待结果），
     * 因此第一次读到的 map 可能只包含若干次自增里的前一部分。旧写法一读到非空就返回，
     * 于是"两个原因各 2 次"这类断言会随机看见一个缺项的结果——一次真实的 flake，
     * 而它看起来像被测代码算错了。本类因此在两次全量跑中失败过一次、单跑又通过。
     *
     * <p>稳定 = 连续两次读到的内容相同。超时后返回最后一次读到的内容，
     * 让断言用它自己的消息失败，而不是在这里抛一个与原因无关的超时。
     */
    private static Map<String, Long> awaitCounts(UUID packageInstanceId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Map<String, Long> previous = Map.of();
        Map<String, Long> counts = Map.of();
        while (System.nanoTime() < deadline) {
            Map<String, Long> read = service.last24Hours(packageInstanceId)
                    .block(Duration.ofSeconds(5));
            counts = read == null ? Map.of() : read;
            if (!counts.isEmpty() && counts.equals(previous)) {
                return counts;
            }
            previous = counts;
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return counts;
    }

    private static LettuceConnectionFactory brokenFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory("127.0.0.1", 1);
        factory.afterPropertiesSet();
        return factory;
    }
}
