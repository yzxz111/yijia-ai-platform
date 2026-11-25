package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 所售每分钟额度速率是一种已售出的允许量，因此它以与每日请求上限相同的错误形态和相同的
 * 失败即拒绝（fail-closed）处理来判定：先 Redis，以已结算的用量行作为降级回退，而当判定
 * 未知时一律返回 {@code 503 dependency_unavailable}。相比之下，记录发生在客户的请求已经
 * 计费之后，因此绝不能影响该请求。
 */
class PackageTokenRateLimitServiceTest {

    private static final UUID PACKAGE_ID = UUID.fromString("94500000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("94500000-0000-4000-8000-000000000002");
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(50);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T04:05:59Z"), ZoneOffset.UTC);

    @Test
    void admitsWhileTheRecordedCreditsAreBelowTheSoldRate() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        service.admit(rate(30), USER_ID).block();

        assertThat(redis.admitted).hasSize(1);
        assertThat(redis.packages).containsExactly(PACKAGE_ID);
        assertThat(database.admitted).isEmpty();
    }

    @Test
    void exceedsTheSoldRateWithTheExistingRateLimitShape() {
        RecordingStore redis = new RecordingStore(true, false);
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(rate(30), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded");
                });
        assertThat(database.admitted)
                .as("a reached rate is a decision; it must not be retried on the fallback store")
                .isEmpty();
    }

    @Test
    void packagesWithoutASoldRateNeverTouchAStore() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        service.admit(RequestLimitService.PackageTokenLimit.unlimited(), USER_ID).block();
        service.record(RequestLimitService.PackageTokenLimit.unlimited(), 500);

        assertThat(redis.probes).isZero();
        assertThat(redis.admitted).isEmpty();
        assertThat(redis.recorded).isEmpty();
        assertThat(database.probes).isZero();
        assertThat(database.admitted).isEmpty();
    }

    @Test
    void readsTheSoldFieldLenientlySoAnAbsentOrZeroRateKeepsThePreviousBehaviour() {
        assertThat(RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, null))
                .isEqualTo(RequestLimitService.PackageTokenLimit.unlimited());
        assertThat(RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 0L))
                .isEqualTo(RequestLimitService.PackageTokenLimit.unlimited());
        assertThat(RequestLimitService.PackageTokenLimit.of(null, 100L))
                .isEqualTo(RequestLimitService.PackageTokenLimit.unlimited());
        assertThat(RequestLimitService.PackageTokenLimit.unlimited().enforced()).isFalse();
        assertThat(RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 100L).enforced()).isTrue();
    }

    @Test
    void unavailableRedisCountsSettledCreditsInTheDatabaseInstead() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        service.admit(rate(30), USER_ID).block();

        assertThat(redis.admitted).isEmpty();
        assertThat(database.packages).containsExactly(PACKAGE_ID);
        assertThat(database.admitted).containsExactly(USER_ID);
    }

    @Test
    void databaseFallbackStillEnforcesTheSoldRate() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, false);
        PackageTokenRateLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(rate(30), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded");
                });
    }

    @Test
    void ambiguousRedisFailureFailsClosedWithoutCountingInTheDatabase() {
        RecordingStore redis = new RecordingStore(true, new IllegalStateException("redis down"));
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(rate(30), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
        assertThat(database.admitted)
                .as("redis may have counted the credits; the fallback must not count a second time")
                .isEmpty();
    }

    @Test
    void undecidableFallbackFailsClosed() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, new IllegalStateException("database down"));
        PackageTokenRateLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(rate(30), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
    }

    @Test
    void unresponsiveRedisProbeDoesNotStallAdmission() {
        RecordingStore redis = new RecordingStore(true, true);
        redis.probeDelay = Duration.ofSeconds(2);
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        long startedAt = System.nanoTime();
        service.admit(rate(30), USER_ID).block();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(database.packages).containsExactly(PACKAGE_ID);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void recordsTheChargedCreditsOnThePrimaryStore() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        service.record(rate(30), 30);
        awaitRecorded(redis);

        assertThat(redis.packages).containsExactly(PACKAGE_ID);
        assertThat(redis.recorded).containsExactly(30L);
        assertThat(database.recorded)
                .as("the degraded store reads the usage rows themselves; it has nothing to write")
                .isEmpty();
    }

    @Test
    void aRecordThatCannotBeWrittenIsSwallowed() {
        RecordingStore redis = new RecordingStore(true, true);
        redis.recordFailure = new IllegalStateException("redis down");
        RecordingStore database = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, database);

        service.record(rate(30), 30);
        awaitRecorded(redis);

        assertThat(redis.recorded)
                .as("the settlement this belongs to has already charged the customer and must not"
                        + " be disturbed by a rate counter")
                .isEmpty();
    }

    @Test
    void aZeroOrAbsentChargeIsNeverRecorded() {
        RecordingStore redis = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, new RecordingStore(true, true));

        service.record(rate(30), 0);
        service.record(null, 30);

        assertThat(redis.recorded).isEmpty();
    }

    @Test
    void theMinuteItDecidesIsTheEpochMinute() {
        RecordingStore redis = new RecordingStore(true, true);
        PackageTokenRateLimitService service = service(redis, new RecordingStore(true, true));

        service.admit(rate(30), USER_ID).block();

        // 时钟故意设为 04:05:59Z：分钟被截断而非向上取整，这也正是
        // Lua 脚本的 floor(TIME()/60) 所计算的结果。
        assertThat(redis.minutes).containsExactly(Instant.parse("2026-09-16T04:05:00Z"));
    }

    private static PackageTokenRateLimitService service(
            PackageTokenRateLimitService.Store redis, PackageTokenRateLimitService.Store database) {
        return new DefaultPackageTokenRateLimitService(redis, database, REDIS_TIMEOUT, CLOCK);
    }

    private static RequestLimitService.PackageTokenLimit rate(long credits) {
        return RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, credits);
    }

    /** 该记录故意是发后即忘的，因此测试必须等待它被尝试。 */
    private static void awaitRecorded(RecordingStore store) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && store.recordAttempts.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class RecordingStore implements PackageTokenRateLimitService.Store {

        private final boolean available;
        private final boolean admits;
        private final Mono<Boolean> failure;
        final List<UUID> admitted = new ArrayList<>();
        final List<UUID> packages = new ArrayList<>();
        final List<Instant> minutes = new ArrayList<>();
        final List<Long> recorded = new ArrayList<>();
        final List<Long> recordAttempts = new ArrayList<>();
        int probes;
        Duration probeDelay = Duration.ZERO;
        RuntimeException recordFailure;

        RecordingStore(boolean available, boolean admits) {
            this(available, admits, null);
        }

        RecordingStore(boolean available, RuntimeException failure) {
            this(available, false, Mono.error(failure));
        }

        RecordingStore(boolean available, boolean admits, Mono<Boolean> failure) {
            this.available = available;
            this.admits = admits;
            this.failure = failure;
        }

        @Override
        public Mono<Boolean> isAvailable() {
            probes++;
            Mono<Boolean> probe = Mono.just(available);
            return probeDelay.isZero() ? probe : probe.delayElement(probeDelay);
        }

        @Override
        public Mono<Boolean> admit(
                RequestLimitService.PackageTokenLimit limit, UUID userId, Instant minute) {
            admitted.add(userId);
            packages.add(limit.packageInstanceId());
            minutes.add(minute);
            if (failure != null) {
                return failure;
            }
            return Mono.just(admits);
        }

        @Override
        public Mono<Void> record(RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
            recordAttempts.add(chargedCredits);
            if (recordFailure != null) {
                return Mono.error(recordFailure);
            }
            packages.add(limit.packageInstanceId());
            recorded.add(chargedCredits);
            return Mono.empty();
        }
    }
}
