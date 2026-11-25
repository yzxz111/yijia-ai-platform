package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
class RedisPasswordLoginRateLimiterTest {

    private static final String SECRET = "test-password-login-hmac-key-with-32-bytes";
    private static final String EMAIL = "user@example.com";
    private static final String IP = "203.0.113.80";
    private static final Pattern SAFE_KEY = Pattern.compile(
            "identity:password-login:v1:(account|ip):(count|block):[0-9a-f]{64}");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    RedisPasswordLoginRateLimiter limiter;

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
        limiter = newLimiter(redis);
    }

    @Test
    void fifthAccountFailureActivatesAStableBlockForSubsequentInspections() {
        for (int attemptNumber = 1; attemptNumber <= 5; attemptNumber++) {
            PasswordLoginRateLimiter.LoginAttempt attempt = limiter.inspect(EMAIL, IP);
            limiter.recordFailure(attempt);
        }

        assertRateLimited(() -> limiter.inspect(EMAIL, "203.0.113.81"));
    }

    @Test
    void twentiethIpFailureAcrossAccountsBlocksTheNextInspectionFromThatIp() {
        for (int attemptNumber = 1; attemptNumber <= 20; attemptNumber++) {
            String account = "user-" + attemptNumber + "@example.com";
            PasswordLoginRateLimiter.LoginAttempt attempt = limiter.inspect(account, IP);
            limiter.recordFailure(attempt);
        }

        assertRateLimited(() -> limiter.inspect("different@example.com", IP));
        assertThat(limiter.inspect("different@example.com", "203.0.113.81")).isNotNull();
    }

    @Test
    void successClearsOnlyAccountState() {
        PasswordLoginRateLimiter.LoginAttempt attempt = limiter.inspect(EMAIL, IP);
        limiter.recordFailure(attempt);
        limiter.recordSuccess(attempt);

        Set<String> keys = redis.keys("identity:password-login:v1:*");
        assertThat(keys)
                .noneMatch(key -> key.contains(":account:"))
                .anyMatch(key -> key.contains(":ip:count:"));
    }

    @Test
    void blockedInspectionsDoNotExtendTheBlockTtl() {
        for (int attemptNumber = 1; attemptNumber <= 5; attemptNumber++) {
            PasswordLoginRateLimiter.LoginAttempt attempt = limiter.inspect(EMAIL, IP);
            limiter.recordFailure(attempt);
        }
        String blockKey = redis.keys("identity:password-login:v1:account:block:*")
                .iterator().next();
        Long before = redis.getExpire(blockKey);

        assertRateLimited(() -> limiter.inspect(EMAIL, IP));

        assertThat(redis.getExpire(blockKey)).isLessThanOrEqualTo(before);
    }

    @Test
    void redisKeysContainOnlyHmacFingerprints() {
        PasswordLoginRateLimiter.LoginAttempt attempt = limiter.inspect(EMAIL, IP);
        limiter.recordFailure(attempt);

        assertThat(attempt.accountFingerprint()).matches("[0-9a-f]{64}");
        assertThat(attempt.ipFingerprint()).matches("[0-9a-f]{64}");
        assertThat(redis.keys("identity:password-login:v1:*")).allSatisfy(key -> {
            assertThat(key).matches(SAFE_KEY);
            assertThat(key).doesNotContain(EMAIL, IP);
        });
    }

    @Test
    void missingOrShortHmacSecretIsRejectedAtConstruction() {
        assertThatIllegalArgumentException().isThrownBy(() -> newLimiter(redis, null));
        assertThatIllegalArgumentException().isThrownBy(() -> newLimiter(redis, "too-short"));
    }

    @Test
    void redisFailureIsServiceUnavailableAndNeverFailsOpen() {
        StringRedisTemplate unavailable = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("test-only failure");
            }
        };

        assertThatThrownBy(() -> newLimiter(unavailable).inspect(EMAIL, IP))
                .isInstanceOfSatisfying(IdentityApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.code()).isEqualTo("login_rate_limit_unavailable");
                });
    }

    private RedisPasswordLoginRateLimiter newLimiter(StringRedisTemplate template) {
        return newLimiter(template, SECRET);
    }

    private RedisPasswordLoginRateLimiter newLimiter(
            StringRedisTemplate template, String secret) {
        return new RedisPasswordLoginRateLimiter(
                template,
                secret,
                5,
                Duration.ofMinutes(15),
                20,
                Duration.ofMinutes(10),
                Duration.ofMinutes(15));
    }

    private void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(IdentityApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(error.code()).isEqualTo("login_rate_limited");
            assertThat(error.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(15));
        });
    }
}
