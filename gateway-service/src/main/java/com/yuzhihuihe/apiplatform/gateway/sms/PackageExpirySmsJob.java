package com.yuzhihuihe.apiplatform.gateway.sms;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        name = "platform.sms.signals-enabled", havingValue = "true")
public final class PackageExpirySmsJob {

    private final PlatformSmsClient client;
    private final Supplier<Flux<PackageExpiryCandidate>> duePackages;
    private final PackageExpirySmsOutbox outbox;
    private final int batchSize;

    @Autowired
    public PackageExpirySmsJob(
            DatabaseClient databaseClient,
            PlatformSmsClient client,
            @Value("${platform.sms.package-expiry-batch-size:100}") int batchSize,
            @Value("${platform.sms.package-expiry-claim-timeout:5m}") Duration claimTimeout,
            @Value("${platform.sms.package-expiry-initial-backoff:1m}") Duration initialBackoff,
            @Value("${platform.sms.package-expiry-maximum-backoff:1h}") Duration maximumBackoff,
            @Value("${platform.sms.package-expiry-maximum-attempts:10}") int maximumAttempts) {
        this(new PackageExpirySmsOutbox(
                        databaseClient, Clock.systemUTC(), claimTimeout, initialBackoff,
                        maximumBackoff, maximumAttempts),
                client, batchSize);
    }

    PackageExpirySmsJob(DatabaseClient databaseClient, PlatformSmsClient client, int batchSize) {
        this(new PackageExpirySmsOutbox(
                        databaseClient, Clock.systemUTC(), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), Duration.ofHours(1), 10),
                client, batchSize);
    }

    private PackageExpirySmsJob(
            PackageExpirySmsOutbox outbox, PlatformSmsClient client, int batchSize) {
        validateBatchSize(batchSize);
        this.client = Objects.requireNonNull(client, "client");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.batchSize = batchSize;
        this.duePackages = null;
    }

    PackageExpirySmsJob(
            PlatformSmsClient client, Supplier<Flux<PackageExpiryCandidate>> duePackages) {
        this.client = Objects.requireNonNull(client, "client");
        this.duePackages = Objects.requireNonNull(duePackages, "duePackages");
        this.outbox = null;
        this.batchSize = 0;
    }

    @Scheduled(
            initialDelayString = "${platform.sms.package-expiry-initial-delay:60s}",
            fixedDelayString = "${platform.sms.package-expiry-fixed-delay:1h}")
    public Mono<Integer> scheduledReport() {
        return reportOnce();
    }

    public Mono<Integer> reportOnce() {
        if (outbox != null) {
            return outbox.materialize(batchSize)
                    .thenMany(outbox.claim(batchSize))
                    .concatMap(signal -> client.reportPackageExpiry(
                                    signal.tenantId(), signal.userId(),
                                    signal.packageId(), signal.expiresAt())
                            .then(outbox.markSent(signal))
                            .thenReturn(1)
                            .onErrorResume(error -> outbox.markFailed(signal).thenReturn(0)))
                    .reduce(0, Integer::sum);
        }
        return Flux.defer(duePackages)
                .concatMap(candidate -> client.reportPackageExpiry(
                                candidate.tenantId(), candidate.userId(),
                                candidate.packageId(), candidate.expiresAt())
                        .thenReturn(1)
                        .onErrorReturn(0))
                .reduce(0, Integer::sum);
    }

    private static void validateBatchSize(int batchSize) {
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("invalid_package_expiry_sms_batch_size");
        }
    }

    public record PackageExpiryCandidate(
            UUID packageId, UUID tenantId, UUID userId, Instant expiresAt) {
        public PackageExpiryCandidate {
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
