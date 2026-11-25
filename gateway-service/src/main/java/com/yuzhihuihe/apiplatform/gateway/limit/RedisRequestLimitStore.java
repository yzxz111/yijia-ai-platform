package com.yuzhihuihe.apiplatform.gateway.limit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

public final class RedisRequestLimitStore implements RequestLimitService.LimitStore {

    private static final String KEY_PREFIX = "gateway:limit:{gateway-limits}:";

    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local clock = redis.call('TIME')
            local now_ms = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)
            local window = math.floor(tonumber(clock[1]) / 60)
            local rpm_limit = tonumber(ARGV[1])
            local concurrency_limit = tonumber(ARGV[2])
            local lease_id = ARGV[3]
            local expires_ms = now_ms + tonumber(ARGV[4])

            if redis.call('EXISTS', KEYS[8]) == 1 then
                return 1
            end

            for index = 1, 3 do
                local stored_window = tonumber(redis.call('HGET', KEYS[index], 'window') or '-1')
                local count = 0
                if stored_window == window then
                    count = tonumber(redis.call('HGET', KEYS[index], 'count') or '0')
                end
                if count >= rpm_limit then
                    return 0
                end
            end

            for index = 4, 7 do
                redis.call('ZREMRANGEBYSCORE', KEYS[index], '-inf', now_ms)
                if redis.call('ZCARD', KEYS[index]) >= concurrency_limit then
                    return 0
                end
            end

            for index = 1, 3 do
                local stored_window = tonumber(redis.call('HGET', KEYS[index], 'window') or '-1')
                local count = 0
                if stored_window == window then
                    count = tonumber(redis.call('HGET', KEYS[index], 'count') or '0')
                end
                redis.call('HSET', KEYS[index], 'window', window, 'count', count + 1)
                redis.call('EXPIRE', KEYS[index], 120)
            end

            for index = 4, 7 do
                -- A short request must never expire the shared set while a longer lease is active.
                local previous_ttl = redis.call('PTTL', KEYS[index])
                redis.call('ZADD', KEYS[index], expires_ms, lease_id)
                if previous_ttl ~= -1 then
                    redis.call('PEXPIRE', KEYS[index],
                        math.max(math.max(0, previous_ttl), tonumber(ARGV[4]) + 60000))
                end
            end
            redis.call('SET', KEYS[8], ARGV[5], 'PX', tonumber(ARGV[4]) + 60000)
            return 1
            """, Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local removed = 0
            for index = 1, 4 do
                removed = removed + redis.call('ZREM', KEYS[index], ARGV[1])
            end
            local current_channel_key = redis.call('GET', KEYS[5])
            if current_channel_key and current_channel_key ~= KEYS[4] then
                removed = removed + redis.call('ZREM', current_channel_key, ARGV[1])
            end
            redis.call('DEL', KEYS[5])
            return removed
            """, Long.class);

    private static final RedisScript<Long> REBIND_CHANNEL = RedisScript.of("""
            local clock = redis.call('TIME')
            local now_ms = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)
            local current_channel_key = redis.call('GET', KEYS[1])
            if not current_channel_key then
                current_channel_key = KEYS[2]
            end
            local expires_ms = redis.call('ZSCORE', current_channel_key, ARGV[1])
            if not expires_ms then
                redis.call('DEL', KEYS[1])
                return -1
            end
            if tonumber(expires_ms) <= now_ms then
                redis.call('ZREM', current_channel_key, ARGV[1])
                redis.call('DEL', KEYS[1])
                return -1
            end
            if current_channel_key == KEYS[3] then
                return 1
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now_ms)
            local target_ttl = redis.call('PTTL', KEYS[3])
            if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[2]) then
                return 0
            end
            redis.call('ZREM', current_channel_key, ARGV[1])
            redis.call('ZADD', KEYS[3], expires_ms, ARGV[1])
            if target_ttl ~= -1 then
                local moved_ttl = math.max(0, expires_ms - now_ms) + 60000
                redis.call('PEXPIRE', KEYS[3], math.max(math.max(0, target_ttl), moved_ttl))
            end
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('SET', KEYS[1], KEYS[3], 'KEEPTTL')
            else
                redis.call('SET', KEYS[1], KEYS[3], 'PX', math.max(0, expires_ms - now_ms) + 60000)
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> ACTIVATE_ROUTE = RedisScript.of("""
            local clock = redis.call('TIME')
            local now_ms = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)
            local window = math.floor(tonumber(clock[1]) / 60)
            local current_channel_key = redis.call('GET', KEYS[1])
            if not current_channel_key then
                current_channel_key = KEYS[2]
            end
            local expires_ms = redis.call('ZSCORE', current_channel_key, ARGV[1])
            if not expires_ms then
                redis.call('DEL', KEYS[1])
                return -1
            end
            if tonumber(expires_ms) <= now_ms then
                redis.call('ZREM', current_channel_key, ARGV[1])
                redis.call('DEL', KEYS[1])
                return -1
            end

            for index = 4, 6 do
                local stored_window = tonumber(redis.call('HGET', KEYS[index], 'window') or '-1')
                local count = 0
                if stored_window == window then
                    count = tonumber(redis.call('HGET', KEYS[index], 'count') or '0')
                end
                if count > tonumber(ARGV[2]) then
                    return 0
                end
            end

            if current_channel_key == KEYS[3] then
                return 1
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now_ms)
            local target_ttl = redis.call('PTTL', KEYS[3])
            if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[3]) then
                return 0
            end
            redis.call('ZREM', current_channel_key, ARGV[1])
            redis.call('ZADD', KEYS[3], expires_ms, ARGV[1])
            if target_ttl ~= -1 then
                local moved_ttl = math.max(0, expires_ms - now_ms) + 60000
                redis.call('PEXPIRE', KEYS[3], math.max(math.max(0, target_ttl), moved_ttl))
            end
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('SET', KEYS[1], KEYS[3], 'KEEPTTL')
            else
                redis.call('SET', KEYS[1], KEYS[3], 'PX', math.max(0, expires_ms - now_ms) + 60000)
            end
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisRequestLimitStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return redis.hasKey(KEY_PREFIX + "availability-probe")
                .map(ignored -> true)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<RequestLimitService.LimitLease> acquire(RequestLimitService.EffectiveLimit command) {
        UUID leaseId = UUID.randomUUID();
        List<String> keys = acquireKeys(command, leaseId);
        List<String> arguments = List.of(
                Integer.toString(command.rpmLimit()),
                Integer.toString(command.concurrencyLimit()),
                leaseId.toString(),
                Long.toString(command.leaseTtl().toMillis()),
                channelConcurrencyKey(command.channelId()));
        return redis.execute(ACQUIRE, keys, arguments)
                .next()
                .filter(result -> result == 1L)
                .switchIfEmpty(Mono.error(rateLimited()))
                .map(ignored -> new RequestLimitService.LimitLease(
                        leaseId, command.apiKeyId(), command.userId(), command.modelCode(),
                        command.channelId(), command.rpmLimit(), command.concurrencyLimit(),
                        RequestLimitService.Store.REDIS));
    }

    @Override
    public Mono<Void> release(RequestLimitService.LimitLease lease) {
        List<String> keys = new java.util.ArrayList<>(concurrencyKeys(
                lease.apiKeyId(), lease.userId(), lease.modelCode(), lease.channelId()));
        keys.add(leaseIdentityKey(lease.leaseId()));
        return redis.execute(RELEASE, keys,
                        List.of(lease.leaseId().toString()))
                .then();
    }

    @Override
    public Mono<RequestLimitService.LimitLease> rebindChannel(
            RequestLimitService.LimitLease lease, UUID channelId) {
        if (lease == null || channelId == null) {
            return Mono.error(new IllegalArgumentException("invalid_limit_channel_rebind"));
        }
        List<String> keys = List.of(
                leaseIdentityKey(lease.leaseId()), channelConcurrencyKey(lease.channelId()),
                channelConcurrencyKey(channelId));
        return redis.execute(REBIND_CHANNEL, keys, List.of(
                        lease.leaseId().toString(), Integer.toString(lease.concurrencyLimit())))
                .next()
                .switchIfEmpty(Mono.error(limitLeaseUnavailable()))
                .flatMap(result -> result == 1L
                        ? Mono.just(withChannel(lease, channelId))
                        : result == 0L ? Mono.error(rateLimited()) : Mono.error(limitLeaseUnavailable()));
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
        List<String> keys = List.of(
                leaseIdentityKey(lease.leaseId()), channelConcurrencyKey(lease.channelId()),
                channelConcurrencyKey(channelId),
                KEY_PREFIX + "rpm:api:" + lease.apiKeyId(),
                KEY_PREFIX + "rpm:user:" + lease.userId(),
                KEY_PREFIX + "rpm:model:" + lease.modelCode());
        return redis.execute(ACTIVATE_ROUTE, keys, List.of(
                        lease.leaseId().toString(), Integer.toString(rpmLimit),
                        Integer.toString(concurrencyLimit)))
                .next()
                .switchIfEmpty(Mono.error(limitLeaseUnavailable()))
                .flatMap(result -> result == 1L
                        ? Mono.just(withChannel(lease, channelId))
                        : result == 0L ? Mono.error(rateLimited()) : Mono.error(limitLeaseUnavailable()));
    }

    private List<String> acquireKeys(RequestLimitService.EffectiveLimit command, UUID leaseId) {
        return List.of(
                KEY_PREFIX + "rpm:api:" + command.apiKeyId(),
                KEY_PREFIX + "rpm:user:" + command.userId(),
                KEY_PREFIX + "rpm:model:" + command.modelCode(),
                KEY_PREFIX + "concurrency:api:" + command.apiKeyId(),
                KEY_PREFIX + "concurrency:user:" + command.userId(),
                KEY_PREFIX + "concurrency:model:" + command.modelCode(),
                KEY_PREFIX + "concurrency:channel:" + command.channelId(),
                leaseIdentityKey(leaseId));
    }

    private List<String> concurrencyKeys(UUID apiKeyId, UUID userId, String modelCode, UUID channelId) {
        return List.of(
                KEY_PREFIX + "concurrency:api:" + apiKeyId,
                KEY_PREFIX + "concurrency:user:" + userId,
                KEY_PREFIX + "concurrency:model:" + modelCode,
                channelConcurrencyKey(channelId));
    }

    private String channelConcurrencyKey(UUID channelId) {
        return KEY_PREFIX + "concurrency:channel:" + channelId;
    }

    private String leaseIdentityKey(UUID leaseId) {
        return KEY_PREFIX + "lease:" + leaseId;
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
