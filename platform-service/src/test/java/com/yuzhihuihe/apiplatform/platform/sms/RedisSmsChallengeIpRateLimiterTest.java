package com.yuzhihuihe.apiplatform.platform.sms;

import static com.yuzhihuihe.apiplatform.platform.sms.SmsChallengeIpRateLimiter.Scope.LOGIN;
import static com.yuzhihuihe.apiplatform.platform.sms.SmsChallengeIpRateLimiter.Scope.REGISTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisSmsChallengeIpRateLimiterTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    final SmsPostgresTestSupport.MutableClock clock =
            new SmsPostgresTestSupport.MutableClock(Instant.parse("2026-08-04T00:00:30Z"));

    RedisSmsChallengeIpRateLimiter limiter;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void reset() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        clock.set(Instant.parse("2026-08-04T00:00:30Z"));
        limiter = new RedisSmsChallengeIpRateLimiter(redis, clock);
    }

    @Test
    void allowsFiveSuccessfulReservationsThenRejectsTheSixth() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.reserve(LOGIN, FINGERPRINT)).isNotNull();
        }

        assertRateLimited(() -> limiter.reserve(LOGIN, FINGERPRINT));
    }

    @Test
    void keepsRegistrationAndLoginScopesIndependent() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.reserve(REGISTER, FINGERPRINT);
        }

        assertThat(limiter.reserve(LOGIN, FINGERPRINT)).isNotNull();
        assertRateLimited(() -> limiter.reserve(REGISTER, FINGERPRINT));
    }

    @Test
    void keepsDifferentIpFingerprintsIndependent() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.reserve(LOGIN, FINGERPRINT);
        }

        assertThat(limiter.reserve(LOGIN, OTHER_FINGERPRINT)).isNotNull();
    }

    @Test
    void startsANewAllowanceAtTheNextClockMinute() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.reserve(LOGIN, FINGERPRINT);
        }
        clock.advance(Duration.ofSeconds(30));

        assertThat(limiter.reserve(LOGIN, FINGERPRINT)).isNotNull();
    }

    @Test
    void releasesAFailedSendReservationOnlyOnce() {
        SmsChallengeIpRateLimiter.Reservation failed = limiter.reserve(LOGIN, FINGERPRINT);
        for (int attempt = 1; attempt < 5; attempt++) {
            limiter.reserve(LOGIN, FINGERPRINT);
        }

        limiter.release(failed);
        limiter.release(failed);

        assertThat(limiter.reserve(LOGIN, FINGERPRINT)).isNotNull();
        assertRateLimited(() -> limiter.reserve(LOGIN, FINGERPRINT));
    }

    @Test
    void redisKeyContainsOnlyScopeHmacFingerprintAndMinuteBucket() {
        limiter.reserve(LOGIN, FINGERPRINT);

        Set<String> keys = redis.keys("sms:challenge:ip-minute:v1:*");
        assertThat(keys)
                .containsExactly("sms:challenge:ip-minute:v1:login:"
                        + FINGERPRINT + ":29763360")
                .allMatch(key -> !key.contains("203.0.113.80"));
    }

    @Test
    void failsClosedWhenRedisIsUnavailableOrInputIsInvalid() {
        StringRedisTemplate unavailable = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("test-only failure");
            }
        };
        RedisSmsChallengeIpRateLimiter unavailableLimiter =
                new RedisSmsChallengeIpRateLimiter(unavailable, clock);

        assertUnavailable(() -> unavailableLimiter.reserve(LOGIN, FINGERPRINT));
        assertUnavailable(() -> limiter.reserve(LOGIN, "203.0.113.80"));
        assertUnavailable(() -> limiter.reserve(null, FINGERPRINT));
    }

    @Test
    void concurrentReservationsNeverAdmitMoreThanFive() throws Exception {
        int workers = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        limiter.reserve(LOGIN, FINGERPRINT);
                        return true;
                    } catch (SmsApiException error) {
                        if (!"sms_rate_limited_ip_minute".equals(error.code())) {
                            throw error;
                        }
                        return false;
                    }
                }));
            }
            start.countDown();

            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(5);
        }
    }

    private void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SmsApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(error.code()).isEqualTo("sms_rate_limited_ip_minute");
        });
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SmsApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.code()).isEqualTo("sms_rate_limit_unavailable");
        });
    }
}
