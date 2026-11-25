package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

class RequestLimitServiceTest {

    @Test
    void rejectsIncompleteOrNonPositiveLimitCommandsAtConstruction() {
        UUID apiKeyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        RequestLimitService.Thresholds thresholds = RequestLimitService.Thresholds.all(1);

        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                null, userId, "text-basic", channelId, thresholds, thresholds, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                apiKeyId, null, "text-basic", channelId, thresholds, thresholds, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                apiKeyId, userId, " ", channelId, thresholds, thresholds, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                apiKeyId, userId, "text-basic", null, thresholds, thresholds, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                apiKeyId, userId, "text-basic", channelId, null, thresholds, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                apiKeyId, userId, "text-basic", channelId, thresholds, null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
        assertThatThrownBy(() -> new RequestLimitService.LimitCommand(
                apiKeyId, userId, "text-basic", channelId, thresholds, thresholds, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_limit_command");
    }

    @Test
    void appliesMinimumRpmAndConcurrencyThresholdAtomically() {
        RecordingStore redis = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(redis, new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
                return Mono.error(new AssertionError("fallback must not be used"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        }, 2);
        RequestLimitService.LimitCommand command = command(
                new RequestLimitService.Thresholds(100, 80, 70, 60, 50),
                new RequestLimitService.Thresholds(20, 10, 8, 6, 4));

        RequestLimitService.LimitLease lease = service.acquire(command).block();

        assertThat(lease.rpmLimit()).isEqualTo(50);
        assertThat(lease.concurrencyLimit()).isEqualTo(4);
        assertThat(redis.acquired).singleElement().isEqualTo(command.effective());
    }

    @Test
    void completionFailureAndCancellationReleaseTheSameLeaseIdempotently() {
        RecordingStore redis = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(redis, redis, 2);
        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2))).block();

        service.release(lease).then(service.release(lease)).block();

        assertThat(redis.released).containsExactly(lease.leaseId());
        assertThat(redis.activeLeases).isZero();
    }

    @Test
    void rebindsOnlyTheChannelDimensionOnTheOwningStore() {
        RecordingStore redis = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(redis, redis, 2);
        RequestLimitService.LimitLease original = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2))).block();
        UUID selectedChannel = UUID.randomUUID();

        RequestLimitService.LimitLease rebound = service.rebindChannel(original, selectedChannel).block();

        assertThat(rebound.leaseId()).isEqualTo(original.leaseId());
        assertThat(rebound.channelId()).isEqualTo(selectedChannel);
        assertThat(redis.rebound).containsExactly(selectedChannel);
        assertThat(redis.activeLeases).isEqualTo(1);
    }

    @Test
    void activatesRouteWithNarrowerLimitsWithoutReplacingThePublicLeaseLimits() {
        RecordingStore redis = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(redis, redis, 2);
        RequestLimitService.LimitLease publicLease = service.acquire(command(
                RequestLimitService.Thresholds.all(60),
                RequestLimitService.Thresholds.all(10))).block();
        UUID selectedChannel = UUID.randomUUID();

        RequestLimitService.LimitLease activated = service.activateRoute(
                publicLease, selectedChannel, 7, 3).block();

        assertThat(redis.activations).containsExactly(
                new RouteActivation(selectedChannel, 7, 3));
        assertThat(activated.channelId()).isEqualTo(selectedChannel);
        assertThat(activated.rpmLimit()).isEqualTo(60);
        assertThat(activated.concurrencyLimit()).isEqualTo(10);
        assertThat(redis.activeLeases).isEqualTo(1);
    }

    @Test
    void sameChannelRebindStillDelegatesToTheOwningStoreForLeaseValidation() {
        RecordingStore redis = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(redis, redis, 2);
        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2))).block();

        service.rebindChannel(lease, lease.channelId()).block();

        assertThat(redis.rebound).containsExactly(lease.channelId());
    }

    @Test
    void redisFailureUsesOnlyConfiguredLowConcurrencyFallback() {
        RequestLimitService.LimitStore unavailableRedis = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
                return Mono.error(new IllegalStateException("redis unavailable"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.error(new IllegalStateException("redis unavailable"));
            }
        };
        RecordingStore databaseFallback = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(unavailableRedis, databaseFallback, 2);

        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2))).block();
        assertThat(lease.store()).isEqualTo(RequestLimitService.Store.DATABASE);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(3))).block())
                .satisfies(error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getReason()).isEqualTo("dependency_unavailable");
                });
    }

    @Test
    void redisDelayUsesTheBoundedDatabaseFallback() {
        RequestLimitService.LimitStore delayedRedis = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
                return Mono.delay(Duration.ofSeconds(2))
                        .then(Mono.error(new IllegalStateException("redis unavailable")));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        RecordingStore databaseFallback = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(delayedRedis, databaseFallback, 2);

        long startedAt = System.nanoTime();
        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2))).block();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(lease.store()).isEqualTo(RequestLimitService.Store.DATABASE);
        assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void publicAdmissionTimeoutFailsClosedAfterRedisMayHaveCommitted() {
        RecordingStore committedRedis = new RecordingStore();
        RequestLimitService.LimitStore ambiguousRedis = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return committedRedis.acquire(command).delayElement(Duration.ofSeconds(1));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return committedRedis.release(lease);
            }
        };
        RecordingStore databaseFallback = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(
                ambiguousRedis, databaseFallback, 2, Duration.ofMillis(25));

        assertThatThrownBy(() -> service.acquirePublic(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2))).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
        assertThat(committedRedis.acquired).hasSize(1);
        assertThat(committedRedis.activeLeases).isEqualTo(1);
        assertThat(databaseFallback.acquired).isEmpty();
        assertThat(databaseFallback.activeLeases).isZero();
    }

    @Test
    void publicAdmissionUnavailableRedisProbeUsesBoundedDatabaseFallback() {
        RequestLimitService.LimitStore unavailableRedis = new RequestLimitService.LimitStore() {
            @Override
            public Mono<Boolean> isAvailable() {
                return Mono.just(false);
            }

            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return Mono.error(new AssertionError("acquire must not run while unavailable"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        RecordingStore databaseFallback = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(
                unavailableRedis, databaseFallback, 2, Duration.ofMillis(25));

        RequestLimitService.LimitLease lease = service.acquirePublic(command(
                RequestLimitService.Thresholds.all(10),
                RequestLimitService.Thresholds.all(2))).block(Duration.ofSeconds(3));

        assertThat(lease.store()).isEqualTo(RequestLimitService.Store.DATABASE);
        assertThat(databaseFallback.acquired).hasSize(1);
        assertThat(databaseFallback.activeLeases).isEqualTo(1);
    }

    @Test
    void publicAdmissionPreservesRedisRateLimitWithoutDatabaseFallback() {
        RequestLimitService.LimitStore rateLimitedRedis = failingStore(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"));
        RecordingStore databaseFallback = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(
                rateLimitedRedis, databaseFallback, 2, Duration.ofMillis(25));

        assertThatThrownBy(() -> service.acquirePublic(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2))).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("rate_limit_exceeded");
                });
        assertThat(databaseFallback.acquired).isEmpty();
    }

    @Test
    void databaseFallbackInfrastructureFailureBecomesDependencyUnavailable() {
        RequestLimitService.LimitStore unavailableRedis = failingStore(
                new IllegalStateException("redis unavailable"));
        RequestLimitService.LimitStore unavailableDatabase = failingStore(
                new IllegalStateException("database unavailable"));
        RequestLimitService service = new DefaultRequestLimitService(
                unavailableRedis, unavailableDatabase, 2);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
    }

    @Test
    void databaseFallbackRateLimitDecisionRemainsTooManyRequests() {
        RequestLimitService.LimitStore unavailableRedis = failingStore(
                new IllegalStateException("redis unavailable"));
        RequestLimitService.LimitStore rateLimitedDatabase = failingStore(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"));
        RequestLimitService service = new DefaultRequestLimitService(
                unavailableRedis, rateLimitedDatabase, 2);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void failedReleaseCanBeRetriedAndStillDecrementsConcurrencyOnce() {
        RecordingStore store = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(store, store, 2);
        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(1))).block();
        store.failNextRelease = true;

        assertThatThrownBy(() -> service.release(lease).block())
                .hasMessage("release unavailable");
        service.release(lease).block();

        assertThat(store.releaseAttempts).isEqualTo(2);
        assertThat(store.activeLeases).isZero();
    }

    @Test
    void redisRateLimitDecisionNeverFallsBackToDatabase() {
        RequestLimitService.LimitStore rateLimitedRedis = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        RecordingStore fallback = new RecordingStore();
        RequestLimitService service = new DefaultRequestLimitService(rateLimitedRedis, fallback, 2);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(1))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(fallback.acquired).isEmpty();
    }

    @Test
    void dailyCapIsCheckedOnlyAfterTheMinuteLeaseIsGranted() {
        RecordingStore redis = new RecordingStore();
        RecordingDailyLimit daily = new RecordingDailyLimit(Mono.empty());
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null, daily);

        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2),
                RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 5))).block();

        assertThat(daily.admitted).containsExactly(PACKAGE_ID);
        assertThat(daily.userIds).containsExactly(lease.userId());
        assertThat(redis.released).isEmpty();
        assertThat(redis.activeLeases).isEqualTo(1);

        service.release(lease).block();
        assertThat(redis.released).containsExactly(lease.leaseId());
    }

    @Test
    void packageWithoutASoldDailyCapSkipsTheDailyCounter() {
        RecordingStore redis = new RecordingStore();
        RecordingDailyLimit daily = new RecordingDailyLimit(Mono.empty());
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null, daily);

        service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2))).block();

        assertThat(daily.admitted).isEmpty();
    }

    @Test
    void reachedDailyCapReleasesTheMinuteLeaseAndKeepsTheRateLimitDecision() {
        RecordingStore redis = new RecordingStore();
        RecordingDailyLimit daily = new RecordingDailyLimit(Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "daily_request_limit_exceeded")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null, daily);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 5))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded");
                });
        assertThat(redis.activeLeases).isZero();
        assertThat(redis.released).hasSize(1);
    }

    @Test
    void dailyCapDecisionAlsoAppliesToThePublicAdmissionPath() {
        RecordingStore redis = new RecordingStore();
        RecordingDailyLimit daily = new RecordingDailyLimit(Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "daily_request_limit_exceeded")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null, daily);

        assertThatThrownBy(() -> service.acquirePublic(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 5))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded"));
        assertThat(redis.activeLeases).isZero();
    }

    @Test
    void undecidableDailyCapFailsClosedAndReleasesTheMinuteLease() {
        RecordingStore redis = new RecordingStore();
        RecordingDailyLimit daily = new RecordingDailyLimit(Mono.error(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null, daily);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 5))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("dependency_unavailable");
                });
        assertThat(redis.activeLeases).isZero();
    }

    @Test
    void failedLeaseReleaseDoesNotMaskTheDailyCapDecision() {
        RecordingStore redis = new RecordingStore();
        redis.failNextRelease = true;
        RecordingDailyLimit daily = new RecordingDailyLimit(Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "daily_request_limit_exceeded")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null, daily);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.of(PACKAGE_ID, 5))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("daily_request_limit_exceeded"));
    }

    private static final UUID PACKAGE_ID = UUID.fromString("99000000-0000-4000-8000-000000000001");

    @Test
    void soldCreditRateIsCheckedOnlyAfterTheMinuteLeaseIsGranted() {
        RecordingStore redis = new RecordingStore();
        RecordingCreditRate rate = new RecordingCreditRate(Mono.empty());
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null,
                DailyRequestLimitService.unlimited(), rate);

        RequestLimitService.LimitLease lease = service.acquire(command(
                RequestLimitService.Thresholds.all(10), RequestLimitService.Thresholds.all(2),
                RequestLimitService.PackageDailyLimit.unlimited(),
                RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 30L))).block();

        assertThat(rate.admitted).containsExactly(PACKAGE_ID);
        assertThat(rate.userIds).containsExactly(lease.userId());
        assertThat(redis.released).isEmpty();
    }

    @Test
    void packageWithoutASoldCreditRateSkipsTheCreditCounter() {
        RecordingStore redis = new RecordingStore();
        RecordingCreditRate rate = new RecordingCreditRate(Mono.empty());
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null,
                DailyRequestLimitService.unlimited(), rate);

        service.acquire(command(
                RequestLimitService.Thresholds.all(10),
                RequestLimitService.Thresholds.all(2))).block();

        assertThat(rate.admitted).isEmpty();
    }

    @Test
    void reachedCreditRateReleasesTheMinuteLeaseAndKeepsTheRateLimitDecision() {
        RecordingStore redis = new RecordingStore();
        RecordingCreditRate rate = new RecordingCreditRate(Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "tokens_per_minute_exceeded")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null,
                DailyRequestLimitService.unlimited(), rate);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.unlimited(),
                        RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 30L))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded");
                });
        assertThat(redis.released).hasSize(1);
        assertThat(redis.activeLeases).isZero();
    }

    @Test
    void undecidableCreditRateFailsClosedAndReleasesTheMinuteLease() {
        RecordingStore redis = new RecordingStore();
        RecordingCreditRate rate = new RecordingCreditRate(Mono.error(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null,
                DailyRequestLimitService.unlimited(), rate);

        assertThatThrownBy(() -> service.acquire(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.unlimited(),
                        RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 30L))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("dependency_unavailable"));
        assertThat(redis.activeLeases).isZero();
    }

    @Test
    void soldCreditRateAlsoAppliesToThePublicAdmissionPath() {
        RecordingStore redis = new RecordingStore();
        RecordingCreditRate rate = new RecordingCreditRate(Mono.error(new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "tokens_per_minute_exceeded")));
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null,
                DailyRequestLimitService.unlimited(), rate);

        assertThatThrownBy(() -> service.acquirePublic(command(
                        RequestLimitService.Thresholds.all(10),
                        RequestLimitService.Thresholds.all(2),
                        RequestLimitService.PackageDailyLimit.unlimited(),
                        RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 30L))).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("tokens_per_minute_exceeded"));
    }

    @Test
    void settledCreditsAreRecordedOnTheCreditCounter() {
        RecordingStore redis = new RecordingStore();
        RecordingCreditRate rate = new RecordingCreditRate(Mono.empty());
        RequestLimitService service = new DefaultRequestLimitService(
                redis, redis, 2, Duration.ofSeconds(1), null,
                DailyRequestLimitService.unlimited(), rate);
        RequestLimitService.PackageTokenLimit limit =
                RequestLimitService.PackageTokenLimit.of(PACKAGE_ID, 30L);

        service.recordPackageConsumption(limit, 30);
        service.recordPackageConsumption(limit, 0);
        service.recordPackageConsumption(RequestLimitService.PackageTokenLimit.unlimited(), 30);

        assertThat(rate.recorded).containsExactly(30L);
        assertThat(rate.recordedPackages).containsExactly(PACKAGE_ID);
    }

    private static RequestLimitService.LimitCommand command(
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency,
            RequestLimitService.PackageDailyLimit dailyLimit,
            RequestLimitService.PackageTokenLimit tokenLimit) {
        return new RequestLimitService.LimitCommand(
                UUID.randomUUID(), UUID.randomUUID(), "text-basic", UUID.randomUUID(),
                rpm, concurrency, Duration.ofMinutes(1), dailyLimit, tokenLimit);
    }

    private static RequestLimitService.LimitCommand command(
            RequestLimitService.Thresholds rpm, RequestLimitService.Thresholds concurrency) {
        return command(rpm, concurrency, RequestLimitService.PackageDailyLimit.unlimited());
    }

    private static RequestLimitService.LimitCommand command(
            RequestLimitService.Thresholds rpm,
            RequestLimitService.Thresholds concurrency,
            RequestLimitService.PackageDailyLimit dailyLimit) {
        return new RequestLimitService.LimitCommand(
                UUID.randomUUID(), UUID.randomUUID(), "text-basic", UUID.randomUUID(),
                rpm, concurrency, Duration.ofMinutes(1), dailyLimit);
    }

    private static final class RecordingDailyLimit implements DailyRequestLimitService {
        private final Mono<Void> decision;
        final List<UUID> admitted = new ArrayList<>();
        final List<UUID> userIds = new ArrayList<>();

        private RecordingDailyLimit(Mono<Void> decision) {
            this.decision = decision;
        }

        @Override
        public Mono<Void> admit(
                RequestLimitService.PackageDailyLimit limit, UUID userId) {
            admitted.add(limit.packageInstanceId());
            userIds.add(userId);
            return decision;
        }

        @Override
        public Mono<Long> used(
                RequestLimitService.PackageDailyLimit limit, UUID userId) {
            return Mono.just(0L);
        }
    }

    private static final class RecordingCreditRate implements PackageTokenRateLimitService {
        private final Mono<Void> decision;
        final List<UUID> admitted = new ArrayList<>();
        final List<UUID> userIds = new ArrayList<>();
        final List<Long> recorded = new ArrayList<>();
        final List<UUID> recordedPackages = new ArrayList<>();

        private RecordingCreditRate(Mono<Void> decision) {
            this.decision = decision;
        }

        @Override
        public Mono<Void> admit(RequestLimitService.PackageTokenLimit limit, UUID userId) {
            admitted.add(limit.packageInstanceId());
            userIds.add(userId);
            return decision;
        }

        @Override
        public void record(RequestLimitService.PackageTokenLimit limit, long chargedCredits) {
            recordedPackages.add(limit.packageInstanceId());
            recorded.add(chargedCredits);
        }

        @Override
        public Mono<Long> used(RequestLimitService.PackageTokenLimit limit, UUID userId) {
            return Mono.just(0L);
        }
    }

    private static RequestLimitService.LimitStore failingStore(RuntimeException failure) {        return new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
                return Mono.error(failure);
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.error(failure);
            }
        };
    }

    private static final class RecordingStore implements RequestLimitService.LimitStore {
        final List<RequestLimitService.EffectiveLimit> acquired = new ArrayList<>();
        final List<UUID> released = new ArrayList<>();
        final java.util.Set<UUID> releasedOnce = new java.util.HashSet<>();
        int activeLeases;
        int releaseAttempts;
        boolean failNextRelease;
        final List<UUID> rebound = new ArrayList<>();
        final List<RouteActivation> activations = new ArrayList<>();

        @Override
        public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
            acquired.add(command);
            activeLeases++;
            return Mono.just(new RequestLimitService.LimitLease(
                    UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                    command.channelId(), command.rpmLimit(), command.concurrencyLimit(),
                    RequestLimitService.Store.DATABASE));
        }

        @Override
        public Mono<Void> release(RequestLimitService.LimitLease lease) {
            releaseAttempts++;
            if (failNextRelease) {
                failNextRelease = false;
                return Mono.error(new IllegalStateException("release unavailable"));
            }
            if (releasedOnce.add(lease.leaseId())) {
                released.add(lease.leaseId());
                activeLeases = Math.max(0, activeLeases - 1);
            }
            return Mono.empty();
        }

        @Override
        public Mono<RequestLimitService.LimitLease> rebindChannel(
                RequestLimitService.LimitLease lease, UUID channelId) {
            rebound.add(channelId);
            return Mono.just(new RequestLimitService.LimitLease(
                    lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(),
                    channelId, lease.rpmLimit(), lease.concurrencyLimit(), lease.store()));
        }

        @Override
        public Mono<RequestLimitService.LimitLease> activateRoute(
                RequestLimitService.LimitLease lease,
                UUID channelId,
                int rpmLimit,
                int concurrencyLimit) {
            activations.add(new RouteActivation(channelId, rpmLimit, concurrencyLimit));
            return Mono.just(new RequestLimitService.LimitLease(
                    lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(),
                    channelId, rpmLimit, concurrencyLimit, lease.store()));
        }
    }

    private record RouteActivation(UUID channelId, int rpmLimit, int concurrencyLimit) { }
}
