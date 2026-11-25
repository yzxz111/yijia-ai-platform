package com.yuzhihuihe.apiplatform.platform.grant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackageFulfillmentService {

    private static final List<String> SOURCES = List.of("ADMIN", "ORDER", "SYSTEM");

    private final PackageFulfillmentRepository repository;
    private final ObjectMapper objectMapper;

    PackageFulfillmentService(PackageFulfillmentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GrantService.GrantResult fulfill(FulfillmentCommand command) {
        validate(command);
        repository.lockBusinessNumber(command.businessNo());
        PackageFulfillmentRepository.GrantUserRow user = repository
                .findActiveUser(command.userId(), command.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "grant_user_not_found"));
        PackageFulfillmentRepository.ExistingGrantRow existing = repository
                .findExistingGrant(command.businessNo())
                .orElse(null);
        if (existing != null) {
            return replay(command, user, existing);
        }

        UUID grantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxPayload payload = payload(command, user, grantId);
        String normalizedReason = command.reason().strip();
        repository.insertGrantRequest(
                grantId,
                command.businessNo(),
                command.tenantId(),
                command.userId(),
                command.packageVersionId(),
                command.source(),
                normalizedReason,
                command.actorId());
        repository.insertOutboxEvent(eventId, grantId, command.businessNo(), json(payload));
        repository.insertAdminAuditLog(command.tenantId(), command.actorId(), grantId, json(Map.of(
                        "businessNo", command.businessNo(),
                        "reason", normalizedReason,
                        "source", command.source())));
        return new GrantService.GrantResult(grantId, eventId, command.businessNo(), "QUEUED");
    }

    private GrantService.GrantResult replay(
            FulfillmentCommand command,
            PackageFulfillmentRepository.GrantUserRow user,
            PackageFulfillmentRepository.ExistingGrantRow existing) {
        boolean sameGrant = existing.tenantId().equals(command.tenantId())
                && existing.userId().equals(command.userId())
                && existing.packageVersionId().equals(command.packageVersionId())
                && existing.source().equals(command.source())
                && existing.reason().equals(command.reason().strip())
                && Objects.equals(existing.actorId(), command.actorId())
                && existing.eventId() != null
                && existing.payload() != null;
        OutboxPayload candidate = payload(command, user, existing.grantId());
        if (!sameGrant || !constantTimeEquals(payloadHash(candidate), payloadHash(existing.payload()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "grant_business_payload_mismatch");
        }
        return new GrantService.GrantResult(
                existing.grantId(), existing.eventId(), command.businessNo(), existing.status());
    }

    private OutboxPayload payload(
            FulfillmentCommand command, PackageFulfillmentRepository.GrantUserRow user, UUID grantId) {
        PackageSnapshotService.PackageSnapshot snapshot = command.snapshot();
        return new OutboxPayload(
                grantId,
                command.businessNo(),
                command.tenantId(),
                command.userId(),
                sha256(identitySource(user)),
                snapshot.packageVersionId(),
                snapshot.packageCode(),
                snapshot.packageName(),
                snapshot.packageKind(),
                snapshot.priceAmount(),
                snapshot.currency(),
                snapshot.totalTokenQuota(),
                snapshot.audioSecondsQuota(),
                snapshot.durationDays(),
                snapshot.maxApiKeys(),
                snapshot.requestsPerMinute(),
                snapshot.tokensPerMinute(),
                snapshot.maxConcurrency(),
                snapshot.dailyRequestLimit(),
                snapshot.maxOutputTokens(),
                snapshot.maxFileSizeBytes(),
                snapshot.oncePerUser(),
                snapshot.usageDimension(),
                snapshot.models());
    }

    private String identitySource(PackageFulfillmentRepository.GrantUserRow user) {
        String email = user.email();
        return email == null || email.isBlank()
                ? "user:" + user.id()
                : email.strip().toLowerCase(Locale.ROOT);
    }

    private void validate(FulfillmentCommand command) {
        if (command == null
                || command.tenantId() == null
                || command.userId() == null
                || command.packageVersionId() == null
                || command.snapshot() == null
                || !command.packageVersionId().equals(command.snapshot().packageVersionId())
                || blank(command.businessNo())
                || command.businessNo().length() > 64
                || !SOURCES.contains(command.source())
                || blank(command.reason())
                || command.reason().strip().length() > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_fulfillment_command");
        }
    }

    private String payloadHash(Object value) {
        JsonNode node;
        try {
            node = value instanceof String json ? objectMapper.readTree(json) : objectMapper.valueToTree(value);
            return sha256(objectMapper.writeValueAsString(canonical(node)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("grant_payload_hash_failed", exception);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            fields.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("grant_snapshot_serialization_failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("grant_payload_hash_failed", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record FulfillmentCommand(
            UUID tenantId,
            UUID userId,
            UUID packageVersionId,
            String businessNo,
            String source,
            String reason,
            UUID actorId,
            PackageSnapshotService.PackageSnapshot snapshot) {
    }

    /**
     * 网关消费的权益发放事件。
     *
     * <p>{@code usageDimension} 必须与网关的 {@code PackageActivationService.PackageSnapshot}
     * 记录保持相同的字段顺序，因为二者是通过 JSON 按字段名绑定的。它被显式携带，
     * 而不是交由网关的「缺省即 TOKEN」默认值：那个默认值会把每一次视频
     * 发放都变成 token 发放，网关随后正确地拒绝了它 ——
     * 而此时客户已经付过款了。
     *
     * <p>{@code audioSecondsQuota} 与 {@code models[].usageDimension} 是方案 C
     * 加上的两项，理由相同：网关无法从快照自行推导它们。前者是"这个版本还卖多少
     * 音频秒"，后者是"这个模型按什么单位计费"——混合版本里同一个版本的两个别名
     * 单位不同，缺了它网关只能按主维度（视频秒）给音频别名立额度行。
     */
    private record OutboxPayload(
            UUID grantRequestId,
            String businessNo,
            UUID tenantId,
            UUID userId,
            String identityKey,
            UUID packageVersionId,
            String packageCode,
            String packageName,
            String packageKind,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            Long audioSecondsQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            String usageDimension,
            List<PackageSnapshotService.ModelPermission> models) {
    }
}
