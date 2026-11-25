package com.yuzhihuihe.apiplatform.gateway.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import java.util.List;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class RateLimitSmsReporter {

    private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return value
            """, Long.class);

    private final PlatformSmsClient client;
    private final RejectionCounter counter;
    private final TenantResolver tenants;
    private final Clock clock;
    private final int threshold;
    private final Duration cooldown;
    private final boolean enabled;

    @Autowired
    public RateLimitSmsReporter(
            PlatformSmsClient client,
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            DatabaseClient databaseClient,
            @Value("${platform.sms.rate-limit-threshold:3}") int threshold,
            @Value("${platform.sms.rate-limit-cooldown:6h}") Duration cooldown,
            @Value("${platform.sms.signals-enabled:false}") boolean enabled) {
        this(
                client,
                redisCounter(redisProvider.getIfAvailable()),
                (apiKeyId, userId) -> databaseClient.sql("""
                                SELECT tenant_id FROM gateway.api_key
                                WHERE id = :api_key_id AND user_id = :user_id
                                """)
                        .bind("api_key_id", apiKeyId).bind("user_id", userId)
                        .map((row, metadata) -> row.get("tenant_id", UUID.class)).one(),
                Clock.systemUTC(), threshold, cooldown, enabled);
    }

    RateLimitSmsReporter(
            PlatformSmsClient client,
            RejectionCounter counter,
            TenantResolver tenants,
            Clock clock,
            int threshold,
            Duration cooldown) {
        this(client, counter, tenants, clock, threshold, cooldown, true);
    }

    RateLimitSmsReporter(
            PlatformSmsClient client,
            RejectionCounter counter,
            TenantResolver tenants,
            Clock clock,
            int threshold,
            Duration cooldown,
            boolean enabled) {
        this.client = Objects.requireNonNull(client, "client");
        this.counter = Objects.requireNonNull(counter, "counter");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (threshold <= 0) {
            throw new IllegalArgumentException("invalid_rate_limit_sms_threshold");
        }
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("invalid_rate_limit_sms_cooldown");
        }
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.enabled = enabled;
    }

    public Mono<Void> report(RequestLimitService.LimitCommand command) {
        Objects.requireNonNull(command, "command");
        return report(command.apiKeyId(), command.userId(), command.modelCode());
    }

    public Mono<Void> reportLease(RequestLimitService.LimitLease lease) {
        Objects.requireNonNull(lease, "lease");
        return report(lease.apiKeyId(), lease.userId(), lease.modelCode());
    }

    private Mono<Void> report(UUID apiKeyId, UUID userId, String modelCode) {
        return Mono.defer(() -> {
            if (!enabled) {
                return Mono.empty();
            }
            long bucket = clock.millis() / cooldown.toMillis();
            return counter.increment(
                            userId, modelCode, bucket,
                            cooldown.multipliedBy(2))
                    .filter(count -> count == threshold)
                    .flatMap(ignored -> tenants.tenantId(apiKeyId, userId))
                    .flatMap(tenantId -> client.reportRateLimit(
                            tenantId, userId, modelCode, bucket))
                    .then();
        });
    }

    private static RejectionCounter redisCounter(ReactiveStringRedisTemplate redis) {
        if (redis == null) {
            return (userId, modelCode, bucket, ttl) -> Mono.just(0L);
        }
        return (userId, modelCode, bucket, ttl) -> {
            String key = "sms:rate-limit:" + userId + ":" + sha256(modelCode) + ":" + bucket;
            return redis.execute(
                            INCREMENT_WITH_TTL, List.of(key), Long.toString(ttl.toMillis()))
                    .next()
                    .switchIfEmpty(Mono.error(
                            new IllegalStateException("rate_limit_sms_counter_unavailable")));
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("rate_limit_sms_hash_failed", error);
        }
    }

    @FunctionalInterface
    interface RejectionCounter {
        Mono<Long> increment(UUID userId, String modelCode, long bucket, Duration ttl);
    }

    @FunctionalInterface
    interface TenantResolver {
        Mono<UUID> tenantId(UUID apiKeyId, UUID userId);
    }
}
