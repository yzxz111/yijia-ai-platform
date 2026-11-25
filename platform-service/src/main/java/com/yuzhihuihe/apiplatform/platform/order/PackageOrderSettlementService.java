package com.yuzhihuihe.apiplatform.platform.order;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.grant.GrantService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageFulfillmentService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactService;
import com.yuzhihuihe.apiplatform.platform.reporting.PaymentFeeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackageOrderSettlementService {

    private final PackageOrderSettlementRepository repository;
    private final ObjectMapper objectMapper;
    private final PackageSnapshotService snapshotService;
    private final PackageFulfillmentService fulfillmentService;
    private final PackageInventoryService inventoryService;
    private final CommercialNotificationService notifications;
    private final OperatingFactService operatingFacts;
    private final PaymentFeeService paymentFees;

    @Autowired
    PackageOrderSettlementService(
            PackageOrderSettlementRepository repository,
            ObjectMapper objectMapper,
            PackageSnapshotService snapshotService,
            PackageFulfillmentService fulfillmentService,
            ObjectProvider<PackageInventoryService> inventoryService,
            ObjectProvider<CommercialNotificationService> notifications,
            ObjectProvider<OperatingFactService> operatingFacts,
            ObjectProvider<PaymentFeeService> paymentFees) {
        this(repository, objectMapper, snapshotService, fulfillmentService,
                inventoryService.getIfAvailable(), notifications.getIfAvailable(),
                operatingFacts.getIfAvailable(), paymentFees.getIfAvailable());
    }

    PackageOrderSettlementService(
            PackageOrderSettlementRepository repository,
            ObjectMapper objectMapper,
            PackageSnapshotService snapshotService,
            PackageFulfillmentService fulfillmentService,
            PackageInventoryService inventoryService,
            CommercialNotificationService notifications,
            OperatingFactService operatingFacts,
            PaymentFeeService paymentFees) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
        this.fulfillmentService = fulfillmentService;
        this.inventoryService = inventoryService;
        this.notifications = notifications;
        this.operatingFacts = operatingFacts;
        this.paymentFees = paymentFees;
    }

    @Transactional
    public LockedOrder lockOrder(UUID orderId) {
        return lockedOrder(repository.lockOrder(orderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_order_not_found")));
    }

    @Transactional
    public LockedOrder lockOwnedOrder(UUID tenantId, UUID userId, String orderNo) {
        return lockedOrder(repository.lockOwnedOrder(tenantId, userId, orderNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "order_not_found")));
    }

    @Transactional
    public SettlementResult settle(UUID orderId, SettlementCommand command) {
        validate(command);
        LockedOrder order = lockOrder(orderId);
        if (!"PENDING".equals(order.status())) {
            throw conflict("order_not_payable");
        }

        SnapshotResolution snapshot = resolveSnapshot(order);
        String provider = command.provider() == null
                ? "UNSELECTED" : command.provider().name();
        int updated = repository.markPaid(
                order.id(), command.paidAt(), command.settlementMethod(), provider,
                command.paymentTransactionId(), command.balanceDebitLedgerId());
        if (updated != 1) {
            throw new IllegalStateException("payment_order_state_changed");
        }
        if (inventoryService != null) {
            inventoryService.consume(order.id(), order.packageVersionId());
        }
        if (operatingFacts != null) {
            operatingFacts.recordPackagePurchase(new OperatingFactService.PackagePurchase(
                    order.id(), order.tenantId(), order.userId(), order.packageVersionId(),
                    command.paymentTransactionId(), order.amount(), order.currency(),
                    command.settlementMethod(), command.paidAt()));
        }
        if (paymentFees != null && "EXTERNAL".equals(command.settlementMethod())) {
            paymentFees.recordEstimatedPayment(
                    command.paymentTransactionId(), command.settlementMethod(), command.paidAt());
        }

        GrantService.GrantResult grant = fulfillmentService.fulfill(
                new PackageFulfillmentService.FulfillmentCommand(
                        order.tenantId(), order.userId(), order.packageVersionId(),
                        "order:" + order.orderNo(), "ORDER", "payment posted", null,
                        snapshot.snapshot()));
        if ("ACTIVE".equals(grant.status()) && notifications != null) {
            notifications.notifyGrantActive(grant.id());
        }
        repository.insertAudit(
                order.tenantId(), order.orderNo(), command.transactionNo(), provider,
                order.amount(), order.currency(), command.settlementMethod(),
                snapshot.legacyBackfill());
        return new SettlementResult(
                order.orderNo(), "PAID", grant, snapshot.legacyBackfill());
    }

    private SnapshotResolution resolveSnapshot(LockedOrder order) {
        if (order.packageSnapshot() != null) {
            PackageSnapshotService.PackageSnapshot snapshot =
                    readSnapshot(order.packageSnapshot());
            if (!order.packageVersionId().equals(snapshot.packageVersionId())
                    || order.amount() != snapshot.priceAmount()
                    || !order.currency().equals(snapshot.currency())) {
                throw conflict("order_package_snapshot_mismatch");
            }
            return new SnapshotResolution(snapshot, false);
        }
        if (order.idempotencyKey() != null) {
            throw conflict("order_package_snapshot_missing");
        }
        PackageSnapshotService.PackageSnapshot published =
                snapshotService.loadPublished(order.packageVersionId());
        PackageSnapshotService.PackageSnapshot backfilled =
                new PackageSnapshotService.PackageSnapshot(
                        published.packageVersionId(), published.packageCode(),
                        published.packageName(), published.packageKind(),
                        order.amount(), order.currency(),
                        published.totalTokenQuota(), published.audioSecondsQuota(),
                        published.durationDays(),
                        published.maxApiKeys(), published.requestsPerMinute(),
                        published.tokensPerMinute(), published.maxConcurrency(),
                        published.dailyRequestLimit(), published.maxOutputTokens(),
                        published.maxFileSizeBytes(), published.oncePerUser(),
                        // 若没有这一项，回填的视频订单会记录成 token
                        // 维度，并按 token 套餐被激活。
                        published.usageDimension(),
                        published.models());
        String json = writeSnapshot(backfilled);
        int updated = repository.backfillSnapshot(order.id(), json);
        if (updated != 1) {
            throw new IllegalStateException("order_package_snapshot_state_changed");
        }
        return new SnapshotResolution(backfilled, true);
    }

    private void validate(SettlementCommand command) {
        if (command == null || command.paidAt() == null) {
            throw new IllegalArgumentException("invalid_package_settlement");
        }
        boolean external = "EXTERNAL".equals(command.settlementMethod())
                && command.provider() != null
                && command.paymentTransactionId() != null
                && command.balanceDebitLedgerId() == null
                && bounded(command.transactionNo());
        boolean balance = "BALANCE".equals(command.settlementMethod())
                && command.provider() == null
                && command.paymentTransactionId() == null
                && command.balanceDebitLedgerId() != null
                && command.transactionNo() == null;
        if (!external && !balance) {
            throw new IllegalArgumentException("invalid_package_settlement");
        }
    }

    private boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }

    private PackageSnapshotService.PackageSnapshot readSnapshot(String value) {
        try {
            return objectMapper.readValue(
                    value, PackageSnapshotService.PackageSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "order_package_snapshot_deserialization_failed", exception);
        }
    }

    private String writeSnapshot(PackageSnapshotService.PackageSnapshot value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "order_package_snapshot_serialization_failed", exception);
        }
    }

    private LockedOrder lockedOrder(PackageOrderSettlementRepository.OrderRow row) {
        return new LockedOrder(
                row.id(), row.orderNo(), row.tenantId(), row.userId(), row.packageVersionId(),
                row.amount(), row.currency(), row.status(), row.packageSnapshot(),
                row.idempotencyKey(), row.successfulPaymentTransactionId(),
                row.settlementMethod(), row.balanceDebitLedgerId(), row.paymentProvider(),
                row.paidAt(), row.expiresAt(), row.lockVersion());
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record SettlementCommand(
            Instant paidAt,
            String settlementMethod,
            PaymentProvider provider,
            UUID paymentTransactionId,
            UUID balanceDebitLedgerId,
            String transactionNo) {

        public static SettlementCommand external(
                Instant paidAt,
                PaymentProvider provider,
                UUID paymentTransactionId,
                String transactionNo) {
            return new SettlementCommand(
                    paidAt, "EXTERNAL", provider,
                    paymentTransactionId, null, transactionNo);
        }

        public static SettlementCommand balance(
                Instant paidAt, UUID balanceDebitLedgerId) {
            return new SettlementCommand(
                    paidAt, "BALANCE", null,
                    null, balanceDebitLedgerId, null);
        }
    }

    public record SettlementResult(
            String orderNo,
            String orderStatus,
            GrantService.GrantResult grant,
            boolean legacySnapshotBackfill) {
    }

    public record LockedOrder(
            UUID id,
            String orderNo,
            UUID tenantId,
            UUID userId,
            UUID packageVersionId,
            long amount,
            String currency,
            String status,
            String packageSnapshot,
            String idempotencyKey,
            UUID successfulPaymentTransactionId,
            String settlementMethod,
            UUID balanceDebitLedgerId,
            String paymentProvider,
            Instant paidAt,
            Instant expiresAt,
            long lockVersion) {
    }

    private record SnapshotResolution(
            PackageSnapshotService.PackageSnapshot snapshot,
            boolean legacyBackfill) {
    }
}
