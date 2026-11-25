package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
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

@Testcontainers
class RedisDailyRequestLimitStoreTest {

    private static final UUID USER_ID = UUID.fromString("95000000-0000-4000-8000-000000000001");
    private static final LocalDate DAY = LocalDate.parse("2026-09-16");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;
    static RedisDailyRequestLimitStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        store = new RedisDailyRequestLimitStore(redis);
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
    void admitsUpToTheSoldDailyCapThenRejects() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageDailyLimit cap = cap(packageId, 2);

        assertThat(admit(cap)).isTrue();
        assertThat(admit(cap)).isTrue();
        assertThat(admit(cap)).isFalse();
    }

    @Test
    void countersAreScopedToThePackageInstance() {
        UUID firstPackage = UUID.randomUUID();
        UUID secondPackage = UUID.randomUUID();

        assertThat(admit(cap(firstPackage, 1))).isTrue();
        assertThat(admit(cap(firstPackage, 1))).isFalse();
        assertThat(admit(cap(secondPackage, 1))).isTrue();
    }

    @Test
    void aNewShanghaiDayStartsANewCount() {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageDailyLimit cap = cap(packageId, 1);

        assertThat(store.admit(cap, USER_ID, DAY).block()).isTrue();
        assertThat(store.admit(cap, USER_ID, DAY).block()).isFalse();
        assertThat(store.admit(cap, USER_ID, DAY.plusDays(1)).block())
                .as("the counter resets on the calendar day the package advertises")
                .isTrue();
    }

    @Test
    void admissionIsAtomicUnderConcurrency() throws Exception {
        UUID packageId = UUID.randomUUID();
        RequestLimitService.PackageDailyLimit cap = cap(packageId, 3);
        List<CompletableFuture<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            calls.add(CompletableFuture.supplyAsync(() -> admit(cap)));
        }

        int admitted = 0;
        for (CompletableFuture<Boolean> call : calls) {
            try {
                if (call.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            } catch (ExecutionException error) {
                throw new AssertionError(error.getCause());
            }
        }

        assertThat(admitted).isEqualTo(3);
    }

    @Test
    void probeReportsRedisAvailability() {
        assertThat(store.isAvailable().block(Duration.ofSeconds(5))).isTrue();
    }

    private static boolean admit(RequestLimitService.PackageDailyLimit cap) {
        return Boolean.TRUE.equals(store.admit(cap, USER_ID, DAY).block(Duration.ofSeconds(5)));
    }

    private static RequestLimitService.PackageDailyLimit cap(UUID packageId, int limit) {
        return RequestLimitService.PackageDailyLimit.of(packageId, limit);
    }
}
