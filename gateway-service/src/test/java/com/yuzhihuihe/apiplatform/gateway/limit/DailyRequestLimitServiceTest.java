package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 每日请求上限是已上线的、按套餐实例生效的产品限制
 * （{@code gateway.package_instance.daily_request_limit}）。它的准入点与
 * 每分钟限流相同，因此必须用相同的错误形状、以及对无法判定的存储采用相同的
 * 失败即拒绝（fail-closed）处理来做判定。
 */
class DailyRequestLimitServiceTest {

    private static final UUID PACKAGE_ID = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(50);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsTheRequestThatReachesTheSoldDailyCap() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        DailyRequestLimitService service = service(redis, database);

        service.admit(cap(2), USER_ID).block();

        assertThat(redis.admitted).hasSize(1);
        assertThat(redis.packages).containsExactly(PACKAGE_ID);
        assertThat(database.admitted).isEmpty();
    }

    @Test
    void exceedsTheCapWithTheExistingRateLimitShape() {
        RecordingStore redis = new RecordingStore(true, false);
        RecordingStore database = new RecordingStore(true, true);
        DailyRequestLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(cap(2), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded");
                });
        assertThat(database.admitted)
                .as("a reached cap is a decision; it must not be retried on the fallback store")
                .isEmpty();
    }

    @Test
    void packagesWithoutASoldDailyCapNeverTouchAStore() {
        RecordingStore redis = new RecordingStore(true, true);
        RecordingStore database = new RecordingStore(true, true);
        DailyRequestLimitService service = service(redis, database);

        service.admit(RequestLimitService.PackageDailyLimit.unlimited(), USER_ID).block();

        assertThat(redis.probes).isZero();
        assertThat(redis.admitted).isEmpty();
        assertThat(database.probes).isZero();
        assertThat(database.admitted).isEmpty();
    }

    @Test
    void unavailableRedisCountsSettledRequestsInTheDatabaseInstead() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, true);
        DailyRequestLimitService service = service(redis, database);

        service.admit(cap(2), USER_ID).block();

        assertThat(redis.admitted).isEmpty();
        assertThat(database.packages).containsExactly(PACKAGE_ID);
        assertThat(database.admitted).containsExactly(USER_ID);
    }

    @Test
    void databaseFallbackStillEnforcesTheCap() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, false);
        DailyRequestLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(cap(2), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded");
                });
    }

    @Test
    void ambiguousRedisFailureFailsClosedWithoutCountingInTheDatabase() {
        RecordingStore redis = new RecordingStore(true, new IllegalStateException("redis down"));
        RecordingStore database = new RecordingStore(true, true);
        DailyRequestLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(cap(2), USER_ID).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
        assertThat(database.admitted)
                .as("redis may have counted the request; the fallback must not count a second time")
                .isEmpty();
    }

    @Test
    void undecidableFallbackFailsClosed() {
        RecordingStore redis = new RecordingStore(false, true);
        RecordingStore database = new RecordingStore(true, new IllegalStateException("database down"));
        DailyRequestLimitService service = service(redis, database);

        assertThatThrownBy(() -> service.admit(cap(2), USER_ID).block())
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
        DailyRequestLimitService service = service(redis, database);

        long startedAt = System.nanoTime();
        service.admit(cap(2), USER_ID).block();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(database.packages).containsExactly(PACKAGE_ID);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void countsTheCapOnTheShanghaiCalendarDayRatherThanUtc() {
        RecordingStore redis = new RecordingStore(true, true);
        DailyRequestLimitService beforeMidnight = service(
                redis, new RecordingStore(true, true),
                Clock.fixed(Instant.parse("2026-09-16T15:59:59Z"), ZoneOffset.UTC));
        DailyRequestLimitService afterMidnight = service(
                redis, new RecordingStore(true, true),
                Clock.fixed(Instant.parse("2026-09-16T16:00:00Z"), ZoneOffset.UTC));

        beforeMidnight.admit(cap(2), USER_ID).block();
        afterMidnight.admit(cap(2), USER_ID).block();

        assertThat(redis.days).containsExactly(
                LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-17"));
    }

    @Test
    void rejectsIncompleteSoldCaps() {
        assertThatThrownBy(() -> RequestLimitService.PackageDailyLimit.of(null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_package_daily_limit");
        assertThatThrownBy(() -> RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_package_daily_limit");
        assertThat(RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, null))
                .isEqualTo(RequestLimitService.PackageDailyLimit.unlimited());
        assertThat(RequestLimitService.PackageDailyLimit.unlimited().enforced()).isFalse();
        assertThat(RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 20).enforced()).isTrue();
    }

    private static DailyRequestLimitService service(
            DailyRequestLimitService.Store redis, DailyRequestLimitService.Store database) {
        return service(redis, database, CLOCK);
    }

    private static DailyRequestLimitService service(
            DailyRequestLimitService.Store redis,
            DailyRequestLimitService.Store database,
            Clock clock) {
        return new DefaultDailyRequestLimitService(redis, database, REDIS_TIMEOUT, clock);
    }

    private static RequestLimitService.PackageDailyLimit cap(int limit) {
        return RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, limit);
    }

    private static final class RecordingStore implements DailyRequestLimitService.Store {

        private final boolean available;
        private final boolean admits;
        private final RuntimeException failure;
        final List<UUID> admitted = new ArrayList<>();
        final List<UUID> packages = new ArrayList<>();
        final List<LocalDate> days = new ArrayList<>();
        int probes;
        Duration probeDelay = Duration.ZERO;

        RecordingStore(boolean available, boolean admits) {
            this(available, admits, null);
        }

        RecordingStore(boolean available, RuntimeException failure) {
            this(available, false, failure);
        }

        private RecordingStore(boolean available, boolean admits, RuntimeException failure) {
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
                RequestLimitService.PackageDailyLimit limit, UUID userId, LocalDate day) {
            admitted.add(userId);
            packages.add(limit.packageInstanceId());
            days.add(day);
            return failure == null ? Mono.just(admits) : Mono.error(failure);
        }
    }
}
