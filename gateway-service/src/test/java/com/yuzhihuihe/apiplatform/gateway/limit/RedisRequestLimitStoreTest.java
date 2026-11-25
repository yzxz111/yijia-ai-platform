package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

@Testcontainers
class RedisRequestLimitStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static RedisRequestLimitStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        store = new RedisRequestLimitStore(redis);
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
    void luaAtomicallyEnforcesConcurrencyAndReleaseIsIdempotent() {
        RequestLimitService.EffectiveLimit limit = limit(10, 1);
        RequestLimitService.LimitLease first = store.acquire(limit).block();

        assertRateLimited(() -> store.acquire(limit).block());
        store.release(first).then(store.release(first)).block();

        assertThat(store.acquire(limit).block()).isNotNull();
    }

    @Test
    void luaAtomicallyEnforcesApiKeyUserAndModelRpm() {
        RequestLimitService.EffectiveLimit limit = limit(2, 10);

        store.acquire(limit).block();
        store.acquire(limit).block();

        assertRateLimited(() -> store.acquire(limit).block());
    }

    @Test
    void channelConcurrencyIsSharedAcrossUsers() {
        UUID channelId = UUID.randomUUID();
        RequestLimitService.EffectiveLimit firstUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "text-basic", channelId,
                10, 1, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit secondUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "text-basic", channelId,
                10, 1, Duration.ofSeconds(30));

        store.acquire(firstUser).block();

        assertRateLimited(() -> store.acquire(secondUser).block());
    }

    @Test
    void rebindMovesChannelConcurrencyWithoutConsumingAnotherGlobalRequest() {
        RequestLimitService.EffectiveLimit initial = limit(1, 1);
        RequestLimitService.LimitLease lease = store.acquire(initial).block();
        UUID selectedChannel = UUID.randomUUID();

        RequestLimitService.LimitLease rebound = store.rebindChannel(lease, selectedChannel).block();

        assertThat(rebound.leaseId()).isEqualTo(lease.leaseId());
        assertThat(rebound.channelId()).isEqualTo(selectedChannel);
        RequestLimitService.EffectiveLimit oldChannelOtherScope = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "other-model-" + UUID.randomUUID(),
                lease.channelId(), 10, 1, Duration.ofSeconds(30));
        assertThat(store.acquire(oldChannelOtherScope).block()).isNotNull();
        RequestLimitService.EffectiveLimit selectedChannelOtherScope = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "selected-model-" + UUID.randomUUID(),
                selectedChannel, 10, 1, Duration.ofSeconds(30));
        assertRateLimited(() -> store.acquire(selectedChannelOtherScope).block());
    }

    @Test
    void routeActivationChecksLowerRpmWithoutIncrementingThePublicRequestAgain() {
        UUID apiKeyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String modelCode = "activation-model-" + UUID.randomUUID();
        UUID admissionChannel = UUID.randomUUID();
        RequestLimitService.EffectiveLimit publicLimit = new RequestLimitService.EffectiveLimit(
                apiKeyId, userId, modelCode, admissionChannel,
                10, 10, Duration.ofSeconds(30));
        RequestLimitService.LimitLease first = store.acquire(publicLimit).block();

        store.activateRoute(first, UUID.randomUUID(), 1, 10).block();

        assertThat(redis.opsForHash().get(
                        "gateway:limit:{gateway-limits}:rpm:api:" + apiKeyId, "count").block())
                .isEqualTo("1");
        RequestLimitService.LimitLease second = store.acquire(publicLimit).block();
        assertRateLimited(() -> store.activateRoute(
                second, UUID.randomUUID(), 1, 10).block());
        assertThat(redis.opsForHash().get(
                        "gateway:limit:{gateway-limits}:rpm:api:" + apiKeyId, "count").block())
                .isEqualTo("2");
    }

    @Test
    void rebindDoesNotShortenTargetChannelTtlWhenItContainsANewerLease() {
        UUID sourceChannel = UUID.randomUUID();
        UUID targetChannel = UUID.randomUUID();
        RequestLimitService.EffectiveLimit shortLease = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "short-model-" + UUID.randomUUID(), sourceChannel,
                10, 2, Duration.ofSeconds(5));
        RequestLimitService.EffectiveLimit longLease = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "long-model-" + UUID.randomUUID(), targetChannel,
                10, 2, Duration.ofSeconds(120));
        RequestLimitService.LimitLease leaseToMove = store.acquire(shortLease).block();
        store.acquire(longLease).block();
        String targetKey = "gateway:limit:{gateway-limits}:concurrency:channel:" + targetChannel;
        Duration ttlBefore = redis.getExpire(targetKey).block();

        store.rebindChannel(leaseToMove, targetChannel).block();

        Duration ttlAfter = redis.getExpire(targetKey).block();
        assertThat(ttlBefore).isGreaterThan(Duration.ofSeconds(170));
        assertThat(ttlAfter).isGreaterThan(Duration.ofSeconds(170));
    }

    @Test
    void rebindRejectsAnExpiredSourceLease() {
        RequestLimitService.LimitLease lease = store.acquire(limit(10, 2)).block();
        UUID targetChannel = UUID.randomUUID();
        String sourceKey = "gateway:limit:{gateway-limits}:concurrency:channel:" + lease.channelId();
        String targetKey = "gateway:limit:{gateway-limits}:concurrency:channel:" + targetChannel;
        redis.opsForZSet().add(sourceKey, lease.leaseId().toString(), 0).block();

        assertThatThrownBy(() -> store.rebindChannel(lease, targetChannel).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("limit_lease_unavailable");
                });
        assertThat(redis.opsForZSet().score(targetKey, lease.leaseId().toString()).block()).isNull();
    }

    @Test
    void sameChannelRebindRejectsAnExpiredLease() {
        RequestLimitService.LimitLease lease = store.acquire(limit(10, 2)).block();
        String channelKey = "gateway:limit:{gateway-limits}:concurrency:channel:" + lease.channelId();
        redis.opsForZSet().add(channelKey, lease.leaseId().toString(), 0).block();

        assertThatThrownBy(() -> store.rebindChannel(lease, lease.channelId()).block())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void staleLeaseCanRetryRebindAndReleaseTheAuthoritativeChannelIdempotently() {
        RequestLimitService.LimitLease original = store.acquire(limit(10, 1)).block();
        UUID targetChannel = UUID.randomUUID();
        String targetKey = "gateway:limit:{gateway-limits}:concurrency:channel:" + targetChannel;

        store.rebindChannel(original, targetChannel).block();
        RequestLimitService.LimitLease retried = store.rebindChannel(original, targetChannel).block();

        assertThat(retried.channelId()).isEqualTo(targetChannel);
        assertThat(redis.opsForZSet().size(targetKey).block()).isEqualTo(1L);
        store.release(original).then(store.release(original)).block();
        RequestLimitService.EffectiveLimit anotherScope = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "replacement-model-" + UUID.randomUUID(), targetChannel,
                10, 1, Duration.ofSeconds(30));
        assertThat(store.acquire(anotherScope).block()).isNotNull();
    }

    @Test
    void modelConcurrencyIsSharedAcrossUsers() {
        String modelCode = "shared-model-" + UUID.randomUUID();
        RequestLimitService.EffectiveLimit firstUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                10, 1, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit secondUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                10, 1, Duration.ofSeconds(30));

        store.acquire(firstUser).block();

        assertRateLimited(() -> store.acquire(secondUser).block());
    }

    @Test
    void modelRpmIsSharedAcrossUsers() {
        String modelCode = "shared-model-" + UUID.randomUUID();
        RequestLimitService.EffectiveLimit firstUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                1, 10, Duration.ofSeconds(30));
        RequestLimitService.EffectiveLimit secondUser = new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), modelCode, UUID.randomUUID(),
                1, 10, Duration.ofSeconds(30));

        store.acquire(firstUser).block();

        assertRateLimited(() -> store.acquire(secondUser).block());
    }

    private RequestLimitService.EffectiveLimit limit(int rpm, int concurrency) {
        return new RequestLimitService.EffectiveLimit(
                UUID.randomUUID(), UUID.randomUUID(), "test-model-" + UUID.randomUUID(), UUID.randomUUID(),
                rpm, concurrency, Duration.ofSeconds(30));
    }

    private void assertRateLimited(ThrowingCallable action) {
        assertThatThrownBy(action).satisfies(error -> {
            assertThat(error).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        });
    }
}
