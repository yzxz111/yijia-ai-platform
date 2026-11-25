package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 匿名读图端点限流器的契约。
 *
 * <p>它守的是设计文档里那条一直没落地的必做项
 * （{@code docs/operations/图生图方案B-网关中转设计.md:71}：
 * "必须限流（否则被当免费图床）…超限 429"）。这里钉四件事：
 *
 * <ol>
 *   <li>第 N+1 次拿到 <b>429</b> 且带 {@code Retry-After}；
 *   <li><b>不同来源 IP 各自独立计数</b>——否则一个攻击者能把所有厂商挤下线；
 *   <li><b>同一地址的不同写法落到同一个桶</b>——否则 {@code 1.2.3.4} 与
 *       {@code 1.2.3.4:5678} 会把限额乘几倍；
 *   <li>Redis 不可用时 <b>fail closed</b>（503），限流器绝不降级成"全部放行"。
 * </ol>
 *
 * <p>用真 Redis（Testcontainers）而不是 mock：这条链的语义就是"固定窗口计数"，
 * mock 掉 Redis 等于把被测对象删掉。
 */
@Testcontainers
class FileContentRateLimiterTest {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
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
    void rejectsTheAttemptAfterTheLimitForOneCaller() {
        FileContentRateLimiter limiter = new FileContentRateLimiter(redis, LIMIT, WINDOW, true);

        for (int attempt = 0; attempt < LIMIT; attempt++) {
            limiter.acquire(exchangeFrom("203.0.113.7")).block();
        }

        assertThatThrownBy(() -> limiter.acquire(exchangeFrom("203.0.113.7")).block())
                .isInstanceOf(FileContentRateLimiter.RateLimitExceededException.class)
                .isInstanceOfSatisfying(ResponseStatusException.class, status -> {
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(status.getReason()).isEqualTo(FileContentRateLimiter.RATE_LIMITED);
                    // 厂商需要知道等多久，否则它会立刻重试并再次被拒。
                    assertThat(status.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                            .isEqualTo("60");
                });
    }

    @Test
    void countsEachCallerIndependently() {
        FileContentRateLimiter limiter = new FileContentRateLimiter(redis, LIMIT, WINDOW, true);

        // 把 A 用满
        for (int attempt = 0; attempt < LIMIT; attempt++) {
            limiter.acquire(exchangeFrom("203.0.113.7")).block();
        }

        // B 不该受影响：限流必须是每来源独立的，否则一个攻击者可以
        // 用垃圾请求把真实厂商挤下线。
        limiter.acquire(exchangeFrom("198.51.100.9")).block();
        limiter.acquire(exchangeFrom("198.51.100.9")).block();
        limiter.acquire(exchangeFrom("198.51.100.9")).block();
        assertThatThrownBy(() -> limiter.acquire(exchangeFrom("198.51.100.9")).block())
                .isInstanceOf(FileContentRateLimiter.RateLimitExceededException.class);
    }

    @Test
    void treatsDifferentSpellingsOfTheSameAddressAsOneCaller() {
        FileContentRateLimiter limiter = new FileContentRateLimiter(redis, LIMIT, WINDOW, true);

        // 同一地址的三种写法都必须落进同一个桶，否则限额会被按写法数量放大。
        limiter.acquire(exchangeFrom("203.0.113.7")).block();
        limiter.acquire(exchangeFrom("203.0.113.7:54321")).block();
        limiter.acquire(exchangeFrom("203.000.113.007")).block();

        assertThatThrownBy(() -> limiter.acquire(exchangeFrom("203.0.113.7")).block())
                .isInstanceOf(FileContentRateLimiter.RateLimitExceededException.class);
    }

    @Test
    void fallsBackToTheConnectionAddressWhenHeadersAreAbsent() {
        FileContentRateLimiter limiter = new FileContentRateLimiter(redis, LIMIT, WINDOW, true);

        // 没有 nginx 时（本地直连）用连接地址，同样要限住。
        for (int attempt = 0; attempt < LIMIT; attempt++) {
            limiter.acquire(exchangeWithoutHeaders()).block();
        }
        assertThatThrownBy(() -> limiter.acquire(exchangeWithoutHeaders()).block())
                .isInstanceOf(FileContentRateLimiter.RateLimitExceededException.class);
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        FileContentRateLimiter limiter = new FileContentRateLimiter(null, LIMIT, WINDOW, true);

        assertThatThrownBy(() -> limiter.acquire(exchangeFrom("203.0.113.7")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, status -> {
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(status.getReason())
                            .isEqualTo(FileContentRateLimiter.UNAVAILABLE);
                });
    }

    @Test
    void letsTrafficThroughWhenFailClosedIsExplicitlyDisabled() {
        // 这是**明知的选择**：宁可多花带宽也不让厂商拉不到图。
        // 它必须是一个显式配置，而不是默认行为。
        FileContentRateLimiter limiter = new FileContentRateLimiter(null, LIMIT, WINDOW, false);

        limiter.acquire(exchangeFrom("203.0.113.7")).block();
    }

    @Test
    void rejectsInvalidConfigurationAtConstruction() {
        assertThatThrownBy(() -> new FileContentRateLimiter(redis, 0, WINDOW, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file_content_rate_limit_configuration_invalid");
        assertThatThrownBy(() -> new FileContentRateLimiter(redis, LIMIT, Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file_content_rate_limit_configuration_invalid");
    }

    @Test
    void normalizesUnresolvableCallersIntoOneBucket() {
        // 畸形值不能被当成"无限个不同来源"来绕过限额。
        assertThat(FileContentRateLimiter.normalize("not-an-ip"))
                .isEqualTo(FileContentRateLimiter.normalize(null))
                .isEqualTo(FileContentRateLimiter.normalize("   "));
    }

    /** 来自 nginx 的形态：{@code X-Real-IP} 是被覆盖过的真实客户端地址。 */
    private static MockServerWebExchange exchangeFrom(String realIp) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/files/x/content")
                        .header("X-Real-IP", realIp)
                        .remoteAddress(new InetSocketAddress("10.0.0.1", 40000)));
    }

    private static MockServerWebExchange exchangeWithoutHeaders() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/files/x/content")
                        .remoteAddress(new InetSocketAddress("203.0.113.7", 40000)));
    }
}
