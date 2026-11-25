package com.yuzhihuihe.apiplatform.platform.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RedisPasswordLoginRateLimiter implements PasswordLoginRateLimiter {

    private static final String KEY_PREFIX = "identity:password-login:v1:";
    private static final RedisScript<Long> INSPECT = RedisScript.of("""
            local account_ttl = redis.call('TTL', KEYS[1])
            local ip_ttl = redis.call('TTL', KEYS[2])
            if account_ttl < 0 then account_ttl = 0 end
            if ip_ttl < 0 then ip_ttl = 0 end
            return math.max(account_ttl, ip_ttl)
            """, Long.class);
    private static final RedisScript<Long> RECORD_FAILURE = RedisScript.of("""
            local account_count = redis.call('INCR', KEYS[1])
            if account_count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if account_count >= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[2], '1', 'NX', 'EX', ARGV[3])
            end

            local ip_count = redis.call('INCR', KEYS[3])
            if ip_count == 1 then redis.call('EXPIRE', KEYS[3], ARGV[4]) end
            if ip_count >= tonumber(ARGV[5]) then
              redis.call('SET', KEYS[4], '1', 'NX', 'EX', ARGV[3])
            end
            return 1
            """, Long.class);
    private static final RedisScript<Long> RECORD_SUCCESS = RedisScript.of("""
            return redis.call('DEL', KEYS[1], KEYS[2])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final byte[] hmacKey;
    private final int accountLimit;
    private final long accountWindowSeconds;
    private final int ipLimit;
    private final long ipWindowSeconds;
    private final long blockDurationSeconds;

    @Autowired
    public RedisPasswordLoginRateLimiter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${identity.password-login-rate-limit.hmac-key}") String hmacKey,
            @Value("${identity.password-login-rate-limit.account-limit:5}") int accountLimit,
            @Value("${identity.password-login-rate-limit.account-window:15m}") Duration accountWindow,
            @Value("${identity.password-login-rate-limit.ip-limit:20}") int ipLimit,
            @Value("${identity.password-login-rate-limit.ip-window:10m}") Duration ipWindow,
            @Value("${identity.password-login-rate-limit.block-duration:15m}") Duration blockDuration) {
        this(redisProvider.getIfAvailable(), hmacKey, accountLimit, accountWindow,
                ipLimit, ipWindow, blockDuration);
    }

    RedisPasswordLoginRateLimiter(
            StringRedisTemplate redis,
            String hmacKey,
            int accountLimit,
            Duration accountWindow,
            int ipLimit,
            Duration ipWindow,
            Duration blockDuration) {
        if (hmacKey == null || hmacKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("password login HMAC key must be at least 32 bytes");
        }
        this.redis = redis;
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8).clone();
        this.accountLimit = requirePositive(accountLimit, "accountLimit");
        this.accountWindowSeconds = requirePositive(accountWindow, "accountWindow");
        this.ipLimit = requirePositive(ipLimit, "ipLimit");
        this.ipWindowSeconds = requirePositive(ipWindow, "ipWindow");
        this.blockDurationSeconds = requirePositive(blockDuration, "blockDuration");
    }

    @Override
    public LoginAttempt inspect(String normalizedIdentifier, String clientIp) {
        if (redis == null || normalizedIdentifier == null || normalizedIdentifier.isBlank()
                || clientIp == null || clientIp.isBlank()) {
            throw IdentityApiException.rateLimitUnavailable();
        }
        LoginAttempt attempt = new LoginAttempt(
                fingerprint("password-login:v1:account:", normalizedIdentifier),
                fingerprint("password-login:v1:ip:", clientIp));
        try {
            Long retryAfter = redis.execute(
                    INSPECT,
                    List.of(accountBlockKey(attempt), ipBlockKey(attempt)));
            if (retryAfter == null || retryAfter < 0) {
                throw IdentityApiException.rateLimitUnavailable();
            }
            if (retryAfter > 0) {
                throw IdentityApiException.rateLimited(Duration.ofSeconds(retryAfter));
            }
            return attempt;
        } catch (IdentityApiException error) {
            throw error;
        } catch (RuntimeException unavailable) {
            throw IdentityApiException.rateLimitUnavailable();
        }
    }

    @Override
    public void recordFailure(LoginAttempt attempt) {
        requireAttempt(attempt);
        try {
            Long result = redis.execute(
                    RECORD_FAILURE,
                    List.of(
                            accountCountKey(attempt), accountBlockKey(attempt),
                            ipCountKey(attempt), ipBlockKey(attempt)),
                    Long.toString(accountWindowSeconds),
                    Integer.toString(accountLimit),
                    Long.toString(blockDurationSeconds),
                    Long.toString(ipWindowSeconds),
                    Integer.toString(ipLimit));
            if (result == null || result != 1) {
                throw IdentityApiException.rateLimitUnavailable();
            }
        } catch (IdentityApiException error) {
            throw error;
        } catch (RuntimeException unavailable) {
            throw IdentityApiException.rateLimitUnavailable();
        }
    }

    @Override
    public void recordSuccess(LoginAttempt attempt) {
        requireAttempt(attempt);
        try {
            Long result = redis.execute(
                    RECORD_SUCCESS,
                    List.of(accountCountKey(attempt), accountBlockKey(attempt)));
            if (result == null || result < 0) {
                throw IdentityApiException.rateLimitUnavailable();
            }
        } catch (IdentityApiException error) {
            throw error;
        } catch (RuntimeException unavailable) {
            throw IdentityApiException.rateLimitUnavailable();
        }
    }

    private String fingerprint(String domain, String value) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    hmac.doFinal((domain + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    private void requireAttempt(LoginAttempt attempt) {
        if (redis == null || attempt == null) {
            throw IdentityApiException.rateLimitUnavailable();
        }
    }

    private String accountCountKey(LoginAttempt attempt) {
        return KEY_PREFIX + "account:count:" + attempt.accountFingerprint();
    }

    private String accountBlockKey(LoginAttempt attempt) {
        return KEY_PREFIX + "account:block:" + attempt.accountFingerprint();
    }

    private String ipCountKey(LoginAttempt attempt) {
        return KEY_PREFIX + "ip:count:" + attempt.ipFingerprint();
    }

    private String ipBlockKey(LoginAttempt attempt) {
        return KEY_PREFIX + "ip:block:" + attempt.ipFingerprint();
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.toSeconds();
    }
}
