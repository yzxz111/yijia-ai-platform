package com.yuzhihuihe.apiplatform.gateway.limit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

public final class PostgresRequestLimitStore implements RequestLimitService.LimitStore {

    private final DatabaseClient databaseClient;

    public PostgresRequestLimitStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
        UUID leaseId = UUID.randomUUID();
        return databaseClient.sql("""
                        SELECT gateway.acquire_request_limit_fallback(
                            :lease_id, :api_key_id, :user_id, :model_code, :channel_id,
                            :rpm_limit, :concurrency_limit, :expires_at) AS acquired
                        """)
                .bind("lease_id", leaseId)
                .bind("api_key_id", command.apiKeyId())
                .bind("user_id", command.userId())
                .bind("model_code", command.modelCode())
                .bind("channel_id", command.channelId())
                .bind("rpm_limit", command.rpmLimit())
                .bind("concurrency_limit", command.concurrencyLimit())
                .bind("expires_at", Instant.now().plus(command.leaseTtl()))
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("acquired", Boolean.class)))
                .one()
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(rateLimited()))
                .map(ignored -> new RequestLimitService.LimitLease(
                        leaseId, command.apiKeyId(), command.userId(), command.modelCode(),
                        command.channelId(), command.rpmLimit(), command.concurrencyLimit(),
                        RequestLimitService.Store.DATABASE));
    }

    @Override
    public Mono<Void> release(RequestLimitService.LimitLease lease) {
        return databaseClient.sql("""
                        UPDATE gateway.request_limit_fallback_lease
                        SET released_at = now()
                        WHERE lease_id = :lease_id AND released_at IS NULL
                        """)
                .bind("lease_id", lease.leaseId())
                .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<RequestLimitService.LimitLease> rebindChannel(
            RequestLimitService.LimitLease lease, UUID channelId) {
        if (lease == null || channelId == null) {
            return Mono.error(new IllegalArgumentException("invalid_limit_channel_rebind"));
        }
        return databaseClient.sql("""
                        SELECT gateway.rebind_request_limit_fallback_channel(
                            :lease_id, :channel_id, :concurrency_limit) AS status
                        """)
                .bind("lease_id", lease.leaseId())
                .bind("channel_id", channelId)
                .bind("concurrency_limit", lease.concurrencyLimit())
                .map((row, metadata) -> row.get("status", Integer.class))
                .one()
                .switchIfEmpty(Mono.error(limitLeaseUnavailable()))
                .flatMap(status -> status != null && status == 1
                        ? Mono.just(withChannel(lease, channelId))
                        : status != null && status == 0
                                ? Mono.error(rateLimited()) : Mono.error(limitLeaseUnavailable()));
    }

    @Override
    public Mono<RequestLimitService.LimitLease> activateRoute(
            RequestLimitService.LimitLease lease,
            UUID channelId,
            int rpmLimit,
            int concurrencyLimit) {
        if (lease == null || channelId == null || rpmLimit <= 0 || concurrencyLimit <= 0) {
            return Mono.error(new IllegalArgumentException("invalid_limit_route_activation"));
        }
        return databaseClient.sql("""
                        SELECT gateway.activate_request_limit_fallback_route(
                            :lease_id, :channel_id, :rpm_limit, :concurrency_limit) AS status
                        """)
                .bind("lease_id", lease.leaseId())
                .bind("channel_id", channelId)
                .bind("rpm_limit", rpmLimit)
                .bind("concurrency_limit", concurrencyLimit)
                .map((row, metadata) -> row.get("status", Integer.class))
                .one()
                .switchIfEmpty(Mono.error(limitLeaseUnavailable()))
                .flatMap(status -> status != null && status == 1
                        ? Mono.just(withChannel(lease, channelId))
                        : status != null && status == 0
                                ? Mono.error(rateLimited()) : Mono.error(limitLeaseUnavailable()));
    }

    private RequestLimitService.LimitLease withChannel(
            RequestLimitService.LimitLease lease, UUID channelId) {
        return new RequestLimitService.LimitLease(
                lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(), channelId,
                lease.rpmLimit(), lease.concurrencyLimit(), lease.store());
    }

    private ResponseStatusException rateLimited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded");
    }

    private ResponseStatusException limitLeaseUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "limit_lease_unavailable");
    }
}
