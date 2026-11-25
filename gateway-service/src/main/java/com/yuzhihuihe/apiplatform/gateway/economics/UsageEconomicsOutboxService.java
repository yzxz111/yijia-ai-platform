package com.yuzhihuihe.apiplatform.gateway.economics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public final class UsageEconomicsOutboxService {

    private static final HexFormat HEX = HexFormat.of();

    private final UsageEconomicsOutboxRepository repository;
    private final ObjectMapper canonicalMapper;

    public UsageEconomicsOutboxService(
            UsageEconomicsOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Mono<Void> enqueue(UsageEconomicsEvent event) {
        Objects.requireNonNull(event, "event");
        if (!UsageEconomicsEvent.VERSION.equals(event.version()) || event.eventId() == null
                || event.requestId() == null || event.reservationId() == null) {
            return Mono.error(new IllegalArgumentException("invalid_usage_economics_event"));
        }
        byte[] payload = canonicalBytes(event);
        String payloadHash = sha256(payload);
        String fingerprint = sha256((event.version() + "\n" + event.requestId() + "\n"
                + event.reservationId()).getBytes(StandardCharsets.UTF_8));
        return repository
                .insert(
                        event.eventId(),
                        fingerprint,
                        new String(payload, StandardCharsets.UTF_8),
                        payloadHash)
                .flatMap(rows -> rows > 0 ? Mono.empty()
                        : verifyExisting(event.eventId(), fingerprint, payloadHash));
    }

    public Mono<Void> enqueueUsage(java.util.UUID usageLogId) {
        return snapshotUsage(usageLogId).flatMap(this::enqueue);
    }

    Mono<UsageEconomicsEvent> snapshotUsage(java.util.UUID usageLogId) {
        return Mono.zip(repository.findUsageSnapshot(usageLogId), repository.findStages(usageLogId))
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("usage_economics_snapshot_missing")))
                .map(tuple -> toEvent(tuple.getT1(), tuple.getT2()));
    }

    public Mono<Integer> enqueueRecoveredSettledUsage(int limit) {
        if (limit <= 0 || limit > 1000) {
            return Mono.error(new IllegalArgumentException("invalid_usage_economics_recovery_limit"));
        }
        return repository.findPendingUsageIds(limit)
                .concatMap(usageId -> enqueueUsage(usageId)
                        .then(repository.markEnqueued(usageId))
                        .flatMap(rows -> rows == 1 ? Mono.just(1) : Mono.empty()))
                .reduce(0, Integer::sum);
    }

    private Mono<Void> verifyExisting(java.util.UUID eventId, String fingerprint, String payloadHash) {
        return repository.existingMatches(eventId, fingerprint, payloadHash)
                .flatMap(same -> same ? Mono.<Void>empty()
                        : Mono.<Void>error(new IllegalStateException("usage_economics_outbox_conflict")));
    }

    private byte[] canonicalBytes(UsageEconomicsEvent event) {
        try {
            return canonicalMapper.writeValueAsBytes(event);
        } catch (Exception error) {
            throw new IllegalStateException("usage_economics_payload_serialization_failed", error);
        }
    }

    static String sha256(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("usage_economics_sha256_unavailable", error);
        }
    }

    UsageEconomicsEvent toEvent(
            UsageEconomicsOutboxRepository.UsageSnapshotRow snapshot,
            java.util.List<UsageEconomicsEvent.Stage> stages) {
        // 计量后的单位数就是该行自身的单位计数，而已结算单位数则是
        // 预留记录在同一维度上的已结算数量：quota_reservation
        // .package_settled_tokens 携带维度，对秒套餐而言存的就是秒数
        // （V41/V42），对按张计费的图片套餐而言存的就是张数。
        //
        // 但 **V3 事件里那个同名的字段不是这个含义**：平台侧的接入校验
        // （OperatingFactIngestionService#validDimensionedQuantities）要求
        // 非 TOKEN 维度只在 package_settled_units 里携带数量，且
        // package_settled_tokens 必须为 0——否则一次已结算的张数/秒数会
        // 被读成 token，两个维度就共用了一份载荷。把预留的那个数字原样
        // 塞进两个字段会让整条事件被拒（400 invalid_event_contract），
        // 于是生图的收入与成本永远进不了运营报表。因此这里按维度分派：
        // 只有 TOKEN 维度才让它等于已结算 token 数。
        boolean tokenDimension =
                UsageEconomicsEvent.DIMENSION_TOKEN.equals(snapshot.usageDimension());
        long settledUnits = snapshot.packageSettledTokens();
        long settledTokens = tokenDimension ? settledUnits : 0L;
        return new UsageEconomicsEvent(
                UsageEconomicsEvent.VERSION,
                snapshot.id(),
                snapshot.requestId(),
                snapshot.reservationId(),
                snapshot.tenantId(),
                snapshot.userId(),
                snapshot.packageId(),
                snapshot.modelCode(),
                snapshot.upstreamModel(),
                snapshot.providerId(),
                snapshot.channelId(),
                snapshot.inputTokens(),
                snapshot.outputTokens(),
                snapshot.totalTokens(),
                snapshot.usageDimension(),
                snapshot.usageUnits(),
                snapshot.billingMode(),
                settledTokens,
                settledUnits,
                snapshot.walletSettledAmount(),
                snapshot.providerPriceId(),
                snapshot.providerCostCurrency(),
                snapshot.providerExchangeRate(),
                snapshot.providerInputCostAmount(),
                snapshot.providerOutputCostAmount(),
                snapshot.providerTotalCostAmount(),
                snapshot.providerOriginalInputCostAmount(),
                snapshot.providerOriginalOutputCostAmount(),
                snapshot.providerOriginalCostAmount(),
                snapshot.providerOriginalCostCurrency(),
                snapshot.usageEstimated(),
                snapshot.providerPriceId() == null,
                stages);
    }
}
