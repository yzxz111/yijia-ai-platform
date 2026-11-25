package com.yuzhihuihe.apiplatform.platform.order;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderApplicationService {

    private static final Duration ORDER_TTL = Duration.ofMinutes(15);
    private static final String STANDARD_PACKAGE_KIND = "STANDARD";

    private final OrderApplicationRepository repository;
    private final ObjectMapper objectMapper;
    private final PackageSnapshotService snapshotService;
    private final PackagePurchasePolicyService purchasePolicyService;
    private final PackageInventoryService inventoryService;
    private final Clock clock;

    OrderApplicationService(
            OrderApplicationRepository repository,
            ObjectMapper objectMapper,
            PackageSnapshotService snapshotService,
            PackagePurchasePolicyService purchasePolicyService,
            PackageInventoryService inventoryService,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
        this.purchasePolicyService = purchasePolicyService;
        this.inventoryService = inventoryService;
        this.clock = clock;
    }

    @Transactional
    public OrderCreated create(
            UUID tenantId,
            UUID userId,
            String idempotencyKey,
            CreateOrderCommand command) {
        return createInternal(tenantId, userId, idempotencyKey, command).order();
    }

    @Transactional
    public CreationOutcome createWithOutcome(
            UUID tenantId,
            UUID userId,
            String idempotencyKey,
            CreateOrderCommand command) {
        return createInternal(tenantId, userId, idempotencyKey, command);
    }

    private CreationOutcome createInternal(
            UUID tenantId,
            UUID userId,
            String idempotencyKey,
            CreateOrderCommand command) {
        validate(tenantId, userId, idempotencyKey, command);
        repository.lock(userId, idempotencyKey);
        requireActiveUser(tenantId, userId);
        ExistingOrder existing = existing(userId, idempotencyKey);
        if (existing != null) {
            if (!existing.tenantId().equals(tenantId)
                    || !existing.packageVersionId().equals(command.packageVersionId())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "order_idempotency_payload_mismatch");
            }
            return new CreationOutcome(existing.order(), false);
        }

        PackageSnapshotService.PackageSnapshot snapshot =
                snapshotService.loadPublished(command.packageVersionId());
        if (snapshot.priceAmount() <= 0
                || !STANDARD_PACKAGE_KIND.equals(snapshot.packageKind())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "paid_package_required");
        }

        purchasePolicyService.assertCanCreate(tenantId, userId, snapshot);
        inventoryService.reserve(tenantId, userId, snapshot.packageVersionId());
        String orderNo = "ord_" + UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = clock.instant().plus(ORDER_TTL);
        String packageSnapshot = json(canonical(objectMapper.valueToTree(snapshot)));
        UUID orderId = UUID.randomUUID();
        repository.insertOrder(
                orderId, orderNo, tenantId, userId, snapshot.packageVersionId(),
                snapshot.priceAmount(), snapshot.currency(), expiresAt,
                packageSnapshot, idempotencyKey);
        repository.insertAudit(
                tenantId, userId, orderNo, snapshot.packageVersionId(),
                snapshot.priceAmount(), snapshot.currency(), idempotencyKey);
        return new CreationOutcome(existing(userId, idempotencyKey).order(), true);
    }

    private void validate(
            UUID tenantId,
            UUID userId,
            String idempotencyKey,
            CreateOrderCommand command) {
        if (tenantId == null || userId == null || command == null || command.packageVersionId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_order_request");
        }
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_idempotency_key");
        }
    }

    private void requireActiveUser(UUID tenantId, UUID userId) {
        if (!repository.hasActiveUser(tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order_user_not_found");
        }
    }

    private ExistingOrder existing(UUID userId, String idempotencyKey) {
        return repository.findExisting(userId, idempotencyKey)
                .map(row -> new ExistingOrder(
                        row.tenantId(), row.packageVersionId(),
                        new OrderCreated(
                                row.orderNo(), row.status(), row.amount(), row.currency(),
                                row.expiresAt(), readJson(row.packageSnapshot()))))
                .orElse(null);
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

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("order_snapshot_serialization_failed", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("order_snapshot_deserialization_failed", exception);
        }
    }

    public record CreateOrderCommand(UUID packageVersionId) {
    }

    public record OrderCreated(
            String orderNo,
            String status,
            long amount,
            String currency,
            Instant expiresAt,
            JsonNode packageSnapshot) {
    }

    public record CreationOutcome(OrderCreated order, boolean created) {
    }

    private record ExistingOrder(
            UUID tenantId,
            UUID packageVersionId,
            OrderCreated order) {
    }
}
