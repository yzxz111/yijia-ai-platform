package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletUsageNotificationService {

    public static final long LOW_BALANCE_THRESHOLD_FEN = 1000;
    private static final Duration LOW_BALANCE_COOLDOWN = Duration.ofHours(24);

    private final WalletUsageNotificationRepository repository;
    private final NotificationEventService events;
    private final Clock clock;

    WalletUsageNotificationService(
            WalletUsageNotificationRepository repository,
            NotificationEventService events,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void walletSettled(
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            String requestId,
            String logicalModel,
            long amountFen,
            long availableBalanceFen,
            long inputTokens,
            long outputTokens,
            String billingMode) {
        if (amountFen <= 0 || !("HYBRID".equals(billingMode)
                || "WALLET_ONLY".equals(billingMode))) {
            return;
        }
        validate(reservationId, tenantId, userId, requestId, logicalModel,
                availableBalanceFen, inputTokens, outputTokens);
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        events.recordAuthoritativeBusinessEvent(
                "wallet-usage-settled:" + reservationId,
                "WALLET_USAGE_SETTLED",
                tenantId,
                userId,
                "WALLET_USAGE_RESERVATION",
                reservationId.toString(),
                Map.of(
                        "logicalModel", logicalModel,
                        "amountFen", amountFen,
                        "availableBalanceFen", availableBalanceFen,
                        "inputTokens", inputTokens,
                        "outputTokens", outputTokens,
                        "requestId", requestId),
                occurredAt);
        lowBalance(reservationId, tenantId, userId,
                Math.addExact(availableBalanceFen, amountFen), availableBalanceFen, occurredAt);
    }

    @Transactional
    public void rearmAfterRecharge(UUID tenantId, UUID userId, long availableBalanceFen) {
        if (tenantId == null || userId == null || availableBalanceFen < 0) {
            throw new IllegalArgumentException("invalid_wallet_recharge_trigger_state");
        }
        if (availableBalanceFen < LOW_BALANCE_THRESHOLD_FEN) {
            return;
        }
        ensureLowBalanceState(tenantId, userId);
        repository.rearmLowBalance(userId);
    }

    private void lowBalance(
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            long balanceBeforeFen,
            long balanceAfterFen,
            OffsetDateTime occurredAt) {
        if (balanceBeforeFen < LOW_BALANCE_THRESHOLD_FEN
                || balanceAfterFen >= LOW_BALANCE_THRESHOLD_FEN) {
            return;
        }
        ensureLowBalanceState(tenantId, userId);
        WalletUsageNotificationRepository.TriggerState state =
                repository.lockLowBalanceState(userId);
        Instant now = clock.instant();
        boolean cooldownElapsed = state.lastTriggeredAt() == null
                || !now.isBefore(state.lastTriggeredAt().plus(LOW_BALANCE_COOLDOWN));
        if (!state.armed() || !cooldownElapsed) {
            return;
        }
        events.recordAuthoritativeBusinessEvent(
                "balance-low:" + reservationId,
                "BALANCE_LOW",
                tenantId,
                userId,
                "ACCOUNT_BALANCE",
                userId.toString(),
                Map.of("warnMoney", "10.00"),
                occurredAt);
        repository.disarmLowBalance(userId, now, now.plus(LOW_BALANCE_COOLDOWN));
    }

    private void ensureLowBalanceState(UUID tenantId, UUID userId) {
        repository.ensureLowBalanceState(tenantId, userId);
    }

    private void validate(
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            String requestId,
            String logicalModel,
            long availableBalanceFen,
            long inputTokens,
            long outputTokens) {
        if (reservationId == null || tenantId == null || userId == null
                || requestId == null || requestId.isBlank()
                || logicalModel == null || logicalModel.isBlank()
                || availableBalanceFen < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("invalid_wallet_usage_notification");
        }
    }
}
