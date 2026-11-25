package com.yuzhihuihe.apiplatform.platform.admin.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminManagementResponses {

    private AdminManagementResponses() {}

    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    public record Overview(
            long users,
            long activeUsers,
            long grants,
            long outboxAttention,
            long orders,
            long paidOrders,
            long paidAmount) {}

    public record UserView(
            UUID id,
            UUID tenantId,
            String userNo,
            String email,
            String displayName,
            String status,
            String role,
            boolean globalAdmin,
            boolean statusManageable,
            boolean roleManageable,
            long authVersion,
            Instant lastLoginAt,
            Instant createdAt,
            Instant avatarUpdatedAt,
            String disabledReason,
            Instant disabledAt,
            String currentPackage,
            String currentPackageCode,
            Integer currentPackageVersion) {}

    public record UserDetailView(UserView user, List<UserPackageGrantView> grants) {}

    public record UserPackageGrantView(
            String businessNo,
            String packageCode,
            String packageName,
            int version,
            String status,
            String reason,
            Instant createdAt,
            Instant updatedAt) {}

    public record UserAvatarView(byte[] bytes, String contentType) {}

    public record UserIdentityView(UUID userId, String userNo, String displayName) {}

    public record GrantView(
            UUID id,
            String businessNo,
            UUID userId,
            String userNo,
            String userEmail,
            String userDisplayName,
            String packageCode,
            String packageName,
            int version,
            String status,
            String reason,
            UUID outboxId,
            String outboxStatus,
            int attemptCount,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {}

    public record PlanVersionView(
            UUID versionId,
            String code,
            String name,
            String description,
            String planStatus,
            int version,
            String status,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            /**
             * 方案 C 的第二份额度（音频秒）；null = 这个版本不卖语音。
             * 与 {@code totalTokenQuota}（主维度那份额度）并列显示，
             * 否则一个混合版本在管理端的版本列表里看起来只卖一份东西。
             */
            Long audioSecondsQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning,
            Long stockTotal,
            long stockSold,
            long stockReserved,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            Long remainingStock,
            boolean soldOut,
            boolean lowStock,
            int modelCount,
            Instant publishedAt,
            Instant createdAt) {}

    public record OrderView(
            UUID id,
            String orderNo,
            UUID userId,
            String userNo,
            String userEmail,
            String userDisplayName,
            String packageCode,
            String packageName,
            int packageVersion,
            long amount,
            String currency,
            String status,
            String settlementMethod,
            String paymentProvider,
            String paymentStatus,
            String refundStatus,
            Instant paidAt,
            Instant createdAt,
            Instant updatedAt,
            Instant avatarUpdatedAt,
            long totalTokensUsed,
            long cumulativeSpendAmount) {}

    public record PaymentConfigView(
            String provider,
            String environment,
            boolean enabled,
            boolean configured,
            String credentialFingerprint,
            String lastValidationStatus,
            Instant lastValidatedAt,
            Instant updatedAt) {}

    public record PaymentView(
            UUID id,
            String transactionNo,
            UUID orderId,
            String orderNo,
            UUID userId,
            String userEmail,
            String provider,
            String externalTradeNo,
            long amount,
            String status,
            Instant paidAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record RefundView(
            UUID id,
            String refundNo,
            UUID orderId,
            String orderNo,
            UUID userId,
            String userNo,
            String userEmail,
            String userDisplayName,
            String fundingSource,
            UUID paymentTransactionId,
            String externalRefundNo,
            long amount,
            String status,
            String reason,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record OutboxView(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String businessKey,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant deliveredAt,
            String lastError,
            Instant createdAt) {}

    public record OutboxHealth(long pending, long delivering, long failed, long oldestSeconds) {}
}
