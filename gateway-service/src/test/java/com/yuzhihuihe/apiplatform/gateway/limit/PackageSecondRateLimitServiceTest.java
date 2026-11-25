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
 * 已售每分钟速率的秒数方向，在视频生成时强制执行。
 *
 * <p>它与积分速率的循环相同——先查 Redis，以已结算的行作为降级回退，决策未知时返回 {@code 503}，
 * 记录永远不会干扰它所跟随的结算——但它有一条自己的性质：速率从套餐自身的维度解析，因此秒数
 * 数字按秒强制执行，而没有出售秒数速率的套餐根本不受秒数限制。
 */
class PackageSecondRateLimitServiceTest {

    private static final UUID PACKAGE_ID = UUID.fromString("94500000-0000-4000-8000-000000000011");
    private static final UUID USER_ID = UUID.fromString("94500000-0000-4000-8000-000000000012");
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(50);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T04:05:59Z"), ZoneOffset.UTC);

    @Test
    void admitsWhileTheSettledSecondsAreBelowTheSoldRate() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageSecondRateLimitService service = service(redis, database, videoPlan(190));

        service.admit(PACKAGE_ID, USER_ID).block();

        assertThat(redis.admitted).containsExactly(USER_ID);
        assertThat(redis.packages).containsExactly(PACKAGE_ID);
        assertThat(redis.minutes).containsExactly(Instant.parse("2026-09-16T04:05:00Z"));
        assertThat(database.admitted).isEmpty();
    }

    @Test
    void exceedsTheSoldSecondsWithItsOwnCode() {
        RecordingStore redis = new RecordingStore(true, false);
        RecordingStore database = new RecordingStore(true, true);
        PackageSecondRateLimitService service = service(redis, database, videoPlan(60));

        assertThatThrownBy(() -> service.admit(PACKAGE_ID, USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason())
                            .as("a client must be able to tell the seconds cap from the credit cap")
                            .isEqualTo("seconds_per_minute_exceeded");
                });
        assertThat(database.admitted)
                .as("a reached rate is a decision; it must not be retried on the fallback store")
                .isEmpty();
    }

    @Test
    void aVideoPackageSellingATokenRateIsNotBoundedInSeconds() {
        RecordingStore redis = new RecordingStore(true, false);
        RecordingStore database = new RecordingStore(true, false);
        PackageSecondRateLimitService service =
                service(redis, database, videoPlanWithTokenRate(500_000));

        service.admit(PACKAGE_ID, USER_ID).block();

        assertThat(redis.probes)
                .as("a plan that sold no seconds rate has no seconds decision to make")
                .isZero();
        assertThat(redis.admitted).isEmpty();
        assertThat(database.probes).isZero();
    }

    @Test
    void aTokenPackageOrNoPackageAtAllIsNeverBoundedInSeconds() {
        RecordingStore redis = new RecordingStore(true, false);
        PackageSecondRateLimitService service = service(redis, new RecordingStore(true, false),
                PackageRateSource.unlimited());

        service.admit(null, USER_ID).block();
        service.admit(PACKAGE_ID, USER_ID).block();

        assertThat(redis.probes).isZero();
        assertThat(redis.admitted).isEmpty();
    }

    @Test
    void unavailableRedisCountsSettledSecondsInTheDatabaseInstead() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageSecondRateLimitService service = service(redis, database, videoPlan(60));

        service.admit(PACKAGE_ID, USER_ID).block();

        assertThat(redis.admitted).isEmpty();
        assertThat(database.packages).containsExactly(PACKAGE_ID);
        assertThat(database.admitted).containsExactly(USER_ID);
    }

    @Test
    void ambiguousRedisFailureFailsClosedWithoutCountingInTheDatabase() {
        RecordingStore redis = new RecordingStore(true, new IllegalStateException("redis down"));
        RecordingStore database = new RecordingStore(true, true);
        PackageSecondRateLimitService service = service(redis, database, videoPlan(60));

        assertThatThrownBy(() -> service.admit(PACKAGE_ID, USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
        assertThat(database.admitted).isEmpty();
    }

    @Test
    void undecidableFallbackFailsClosed() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, new IllegalStateException("database down"));
        PackageSecondRateLimitService service = service(redis, database, videoPlan(60));

        assertThatThrownBy(() -> service.admit(PACKAGE_ID, USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
    }

    @Test
    void aRejectionIsCountedAgainstThePackageByItsOwnReason() {
        RecordingStore redis = new RecordingStore(true, false);
        RecordingBlockedAdmissions blocked = new RecordingBlockedAdmissions();
        PackageSecondRateLimitService service = service(redis, new RecordingStore(true, true),
                videoPlan(60));
        ((DefaultPackageSecondRateLimitService) service).setBlockedAdmissionService(blocked);

        assertThatThrownBy(() -> service.admit(PACKAGE_ID, USER_ID).block())
                .isInstanceOf(ResponseStatusException.class);

        assertThat(blocked.packages).containsExactly(PACKAGE_ID);
        assertThat(blocked.reasons).containsExactly("seconds_per_minute_exceeded");
    }

    @Test
    void recordsTheChargedSecondsOnThePrimaryStore() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        PackageSecondRateLimitService service = service(redis, database, videoPlan(190));

        service.record(PACKAGE_ID, 7);
        awaitRecorded(redis);

        assertThat(redis.packages).containsExactly(PACKAGE_ID);
        assertThat(redis.recordedSeconds).containsExactly(7L);
        assertThat(database.recordedSeconds)
                .as("the degraded store reads the usage rows themselves; it has nothing to write")
                .isEmpty();
    }

    @Test
    void aRecordThatCannotBeWrittenIsSwallowed() {
        RecordingStore redis = new RecordingStore(true, true);
        redis.recordFailure = new IllegalStateException("redis down");
        PackageSecondRateLimitService service =
                service(redis, new RecordingStore(true, true), videoPlan(190));

        service.record(PACKAGE_ID, 7);
        awaitRecorded(redis);

        assertThat(redis.recordedSeconds)
                .as("the settlement this belongs to already charged the customer")
                .isEmpty();
    }

    @Test
    void aZeroChargeOrNoPackageIsNeverRecorded() {
        RecordingStore redis = new RecordingStore(true, true);
        PackageSecondRateLimitService service =
                service(redis, new RecordingStore(true, true), videoPlan(190));

        service.record(PACKAGE_ID, 0);
        service.record(null, 7);

        assertThat(redis.recordedSeconds).isEmpty();
    }

    @Test
    void usedReadsTheCounterAndReportsZeroWhenThereIsNone() {
        RecordingStore redis = new RecordingStore(true, true);
        redis.usedValue = 123L;
        PackageSecondRateLimitService service =
                service(redis, new RecordingStore(true, true), videoPlan(190));

        assertThat(service.used(RequestLimitService.PackageSecondLimit.of(PACKAGE_ID, 190L), USER_ID)
                .block()).isEqualTo(123L);
        assertThat(service.used(RequestLimitService.PackageSecondLimit.unlimited(), USER_ID).block())
                .isZero();

        RecordingStore failing = new RecordingStore(true, true);
        failing.usedFailure = new IllegalStateException("redis down");
        PackageSecondRateLimitService degraded =
                service(failing, new RecordingStore(true, true), videoPlan(190));
        assertThat(degraded.used(
                RequestLimitService.PackageSecondLimit.of(PACKAGE_ID, 190L), USER_ID).block())
                .as("a reporting read must not fail the endpoint that asks")
                .isZero();
    }

    private static PackageSecondRateLimitService service(
            PackageSecondRateLimitService.Store redis,
            PackageSecondRateLimitService.Store database,
            PackageRateSource rateSource) {
        return new DefaultPackageSecondRateLimitService(
                redis, database, REDIS_TIMEOUT, rateSource, CLOCK);
    }

    private static PackageRateSource videoPlan(long secondsPerMinute) {
        return packageInstanceId -> Mono.just(
                PackageRateLimits.of(packageInstanceId, "VIDEO_SECOND", secondsPerMinute));
    }

    private static PackageRateSource videoPlanWithTokenRate(long tokensPerMinute) {
        return packageInstanceId -> Mono.just(
                PackageRateLimits.of(packageInstanceId, "VIDEO_SECOND", tokensPerMinute));
    }

    /** 记录是刻意发后即忘的，因此测试必须等待它被尝试执行。 */
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

    private static final class RecordingStore implements PackageSecondRateLimitService.Store {

        private final boolean available;
        private final boolean admits;
        private final Mono<Boolean> failure;
        final List<UUID> admitted = new ArrayList<>();
        final List<UUID> packages = new ArrayList<>();
        final List<Instant> minutes = new ArrayList<>();
        final List<Long> recordedSeconds = new ArrayList<>();
        final List<Long> recordAttempts = new ArrayList<>();
        int probes;
        RuntimeException recordFailure;
        RuntimeException usedFailure;
        long usedValue;

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
            return Mono.just(available);
        }

        @Override
        public Mono<Boolean> admit(
                RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
            admitted.add(userId);
            packages.add(limit.packageInstanceId());
            minutes.add(minute);
            if (failure != null) {
                return failure;
            }
            return Mono.just(admits);
        }

        @Override
        public Mono<Void> record(UUID packageInstanceId, long chargedSeconds) {
            recordAttempts.add(chargedSeconds);
            if (recordFailure != null) {
                return Mono.error(recordFailure);
            }
            packages.add(packageInstanceId);
            recordedSeconds.add(chargedSeconds);
            return Mono.empty();
        }

        @Override
        public Mono<Long> used(
                RequestLimitService.PackageSecondLimit limit, UUID userId, Instant minute) {
            if (usedFailure != null) {
                return Mono.error(usedFailure);
            }
            return Mono.just(usedValue);
        }
    }

    private static final class RecordingBlockedAdmissions implements BlockedAdmissionService {

        final List<UUID> packages = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();

        @Override
        public void record(UUID packageInstanceId, String reason) {
            packages.add(packageInstanceId);
            reasons.add(reason);
        }

        @Override
        public Mono<java.util.Map<String, Long>> last24Hours(UUID packageInstanceId) {
            return Mono.just(java.util.Map.of());
        }
    }
}
