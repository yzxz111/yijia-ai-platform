package com.yuzhihuihe.apiplatform.platform.notification;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class CommercialNotificationService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final CommercialNotificationRepository repository;
    private final NotificationEventService events;
    private final Clock clock;

    CommercialNotificationService(
            CommercialNotificationRepository repository,
            NotificationEventService events,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    public boolean notifyPaymentFailed(
            UUID paymentId, UUID tenantId, UUID userId, long amountFen) {
        return record(
                "payment-failed:" + paymentId, "ORDER_PAYMENT_FAILED",
                tenantId, userId, "PAYMENT_TRANSACTION", id(paymentId),
                Map.of("payMoney", yuan(amountFen)));
    }

    public boolean notifyOrderTimedOut(
            UUID orderId, UUID tenantId, UUID userId, String orderNo) {
        return record(
                "order-timeout:" + orderId, "ORDER_TIMED_OUT",
                tenantId, userId, "CUSTOMER_ORDER", id(orderId),
                Map.of("orderNo", orderNo));
    }

    public boolean notifyRefundSucceeded(
            UUID refundId, UUID tenantId, UUID userId, long amountFen) {
        return record(
                "refund-completed:" + refundId, "REFUND_SUCCEEDED",
                tenantId, userId, "REFUND", id(refundId),
                Map.of("refundMoney", yuan(amountFen)));
    }

    public boolean notifyRechargeSucceeded(
            String rechargeNo,
            UUID tenantId,
            UUID userId,
            long amountFen,
            long balanceFen) {
        if (rechargeNo == null || !rechargeNo.matches("[A-Za-z0-9_-]{1,64}")
                || amountFen <= 0 || balanceFen < 0) {
            throw new IllegalArgumentException("invalid_recharge_notification");
        }
        return record(
                "recharge-succeeded:" + rechargeNo, "BALANCE_RECHARGE_SUCCEEDED",
                tenantId, userId, "RECHARGE_ORDER", rechargeNo,
                Map.of("payMoney", yuan(amountFen), "balance", yuan(balanceFen)));
    }

    public boolean notifyGrantActive(UUID grantId) {
        CommercialNotificationRepository.GrantNotification grant =
                repository.findActiveGrant(grantId).orElse(null);
        if (grant == null) {
            return false;
        }
        String expireDate = grant.completedAt().atZoneSameInstant(SHANGHAI).toLocalDate()
                .plusDays(grant.durationDays()).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return record(
                "grant-active:" + grantId, "PACKAGE_PAYMENT_SUCCEEDED",
                grant.tenantId(), grant.userId(), "GRANT_REQUEST", id(grantId),
                Map.of("payMoney", yuan(grant.amountFen()), "expireDate", expireDate));
    }

    public boolean notifyPackageExpiry(
            UUID tenantId, UUID userId, UUID packageId, Instant expiresAt) {
        if (tenantId == null || userId == null || packageId == null || expiresAt == null) {
            throw new IllegalArgumentException("invalid_package_expiry_notification");
        }
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            return record(
                    "package-expiry:" + packageId + ":expired", "PACKAGE_EXPIRED",
                    tenantId, userId, "PACKAGE_INSTANCE", packageId.toString(), Map.of());
        }
        if (!expiresAt.isAfter(now.plus(Duration.ofDays(3)))) {
            String expireDate = expiresAt.atZone(SHANGHAI).toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            return record(
                    "package-expiry:" + packageId + ":expires-in-3-days",
                    "PACKAGE_EXPIRES_IN_3_DAYS", tenantId, userId,
                    "PACKAGE_INSTANCE", packageId.toString(), Map.of("expireDate", expireDate));
        }
        throw new IllegalArgumentException("invalid_package_expiry_notification");
    }

    public boolean notifyApiRateLimited(
            UUID tenantId,
            UUID userId,
            String modelCode,
            long cooldownBucket) {
        if (tenantId == null || userId == null || modelCode == null
                || !modelCode.matches("[A-Za-z0-9._:/-]{1,128}") || cooldownBucket < 0) {
            throw new IllegalArgumentException("invalid_rate_limit_notification");
        }
        String modelHash = sha256(modelCode);
        String aggregateId = userId + ":" + modelHash + ":" + cooldownBucket;
        return record(
                "rate-limit:" + aggregateId, "API_RATE_LIMITED",
                tenantId, userId, "API_RATE_LIMIT", aggregateId, Map.of());
    }

    private boolean record(
            String eventKey,
            String eventType,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            Map<String, ?> payload) {
        NotificationEventService.RecordOutcome outcome =
                events.recordAuthoritativeBusinessEvent(
                        eventKey, eventType, tenantId, userId, aggregateType, aggregateId,
                        payload, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return outcome.created();
    }

    private String id(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("invalid_notification_aggregate_id");
        }
        return value.toString();
    }

    private String yuan(long amountFen) {
        return BigDecimal.valueOf(amountFen, 2).setScale(2).toPlainString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("notification_business_key_hash_failed", exception);
        }
    }
}
