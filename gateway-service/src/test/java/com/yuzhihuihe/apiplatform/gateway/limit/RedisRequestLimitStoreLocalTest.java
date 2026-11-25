package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Opt-in real local Redis/Garnet test. Only UUID-owned keys are deleted; no FLUSH commands. */
@EnabledIfEnvironmentVariable(named = "GATEWAY_TEST_REDIS_PORT", matches = "[0-9]+")
class RedisRequestLimitStoreLocalTest {
    private static final String PREFIX = "gateway:limit:{gateway-limits}:";
    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private RedisRequestLimitStore store;
    private final List<RequestLimitService.LimitLease> leases = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private UUID apiKeyId;
    private UUID userId;
    private UUID channelId;
    private String model;

    @BeforeEach
    void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getenv("GATEWAY_TEST_REDIS_PORT")));
        String password = System.getenv("GATEWAY_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(RedisPassword.of(password));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        store = new RedisRequestLimitStore(redis);
        apiKeyId = UUID.randomUUID(); userId = UUID.randomUUID(); channelId = UUID.randomUUID();
        model = "limit-test-" + UUID.randomUUID();
        keys.addAll(List.of(PREFIX + "rpm:api:" + apiKeyId, PREFIX + "rpm:user:" + userId,
                PREFIX + "rpm:model:" + model));
        keys.addAll(concurrencyKeys(channelId));
    }

    @AfterEach
    void cleanup() {
        try {
            for (RequestLimitService.LimitLease lease : leases) {
                store.release(lease).block(Duration.ofSeconds(5));
                keys.add(PREFIX + "lease:" + lease.leaseId());
            }
            if (redis != null) redis.delete(Flux.fromIterable(keys)).block(Duration.ofSeconds(5));
        } finally {
            if (factory != null) factory.destroy();
        }
    }

    private List<String> concurrencyKeys(UUID channel) {
        return List.of(PREFIX + "concurrency:api:" + apiKeyId,
                PREFIX + "concurrency:user:" + userId, PREFIX + "concurrency:model:" + model,
                PREFIX + "concurrency:channel:" + channel);
    }

    private RequestLimitService.EffectiveLimit limit(Duration ttl, int concurrency) {
        return new RequestLimitService.EffectiveLimit(apiKeyId, userId, model, channelId,
                1000, concurrency, ttl);
    }

    private RequestLimitService.LimitLease acquire(Duration ttl, int concurrency) {
        RequestLimitService.LimitLease lease = store.acquire(limit(ttl, concurrency))
                .block(Duration.ofSeconds(5));
        leases.add(lease);
        return lease;
    }

    @Test
    void shortLeaseNeverShortensAnySharedConcurrencySet() {
        acquire(Duration.ofMinutes(3), 3);
        acquire(Duration.ofMillis(100), 3);
        for (String key : concurrencyKeys(channelId)) {
            assertThat(redis.getExpire(key).block(Duration.ofSeconds(5)))
                    .isGreaterThan(Duration.ofSeconds(230));
        }
    }

    @Test
    void simultaneousAdmissionsCannotExceedTheConcurrencyLimit() {
        List<RequestLimitService.LimitLease> admitted = Flux.range(0, 30)
                .flatMap(ignored -> store.acquire(limit(Duration.ofSeconds(30), 5))
                        .onErrorResume(ResponseStatusException.class, error -> {
                            assertThat(error.getStatusCode().value()).isEqualTo(429);
                            return Mono.empty();
                        }), 30).collectList().block(Duration.ofSeconds(10));
        leases.addAll(admitted);
        assertThat(admitted).hasSize(5);
    }

    @Test
    void channelRebindAndRepeatedReleaseRemoveTheMovedLease() {
        RequestLimitService.LimitLease original = acquire(Duration.ofSeconds(30), 2);
        UUID target = UUID.randomUUID();
        keys.add(PREFIX + "concurrency:channel:" + target);
        store.rebindChannel(original, target).block(Duration.ofSeconds(5));
        store.release(original).then(store.release(original)).block(Duration.ofSeconds(5));
        assertThat(redis.opsForZSet().size(PREFIX + "concurrency:channel:" + target)
                .block(Duration.ofSeconds(5))).isZero();
    }
}
