package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.WalletUsageNotificationService;
import com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 网关模型计费所使用的账号级预留生命周期。 */
@Service
public class WalletUsageReservationService {

    private final WalletUsageReservationRepository repository;
    private final OperatingFactService operatingFacts;
    private final WalletUsageNotificationService notifications;

    @Autowired
    WalletUsageReservationService(
            WalletUsageReservationRepository repository,
            ObjectProvider<OperatingFactService> operatingFacts,
            ObjectProvider<WalletUsageNotificationService> notifications) {
        this(repository, operatingFacts.getIfAvailable(), notifications.getIfAvailable());
    }

    WalletUsageReservationService(
            WalletUsageReservationRepository repository,
            OperatingFactService operatingFacts,
            WalletUsageNotificationService notifications) {
        this.repository = repository;
        this.operatingFacts = operatingFacts;
        this.notifications = notifications;
    }

    @Transactional
    public WalletReservation reserve(ReserveCommand command) {
        validate(command);
        Account account = lockAccount(command.tenantId(), command.userId());
        Reservation existing = findExisting(account.id(), command);
        if (existing != null) {
            if (!existing.matches(command)) {
                throw conflict("wallet_usage_idempotency_mismatch");
            }
            return existing.view();
        }
        if (account.availableAmount() < command.estimatedAmount()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet_insufficient_balance");
        }
        int updated = repository.reserveBalance(account.id(), command.estimatedAmount());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet_insufficient_balance");
        }
        UUID id = command.reservationId();
        repository.insertReservation(
                id, command.tenantId(), command.userId(), account.id(), command.requestId(),
                command.estimatedAmount(), command.modelCode(), command.priceVersion(),
                command.inputTokens(), command.outputTokens(), command.idempotencyKey(),
                command.expiresAt());
        return new WalletReservation(id, command.requestId(), command.estimatedAmount(),
                null, "RESERVED", command.modelCode(), command.priceVersion());
    }

    @Transactional
    public WalletReservation settle(UUID reservationId, SettleCommand command) {
        if (reservationId == null || command == null || command.settledAmount() < 0
                || command.inputTokens() < 0 || command.outputTokens() < 0) {
            throw unprocessable("invalid_wallet_usage_settlement");
        }
        Reservation reservation = lockReservation(reservationId);
        if (!reservation.tenantId().equals(command.tenantId())
                || !reservation.userId().equals(command.userId())) {
            throw conflict("wallet_usage_idempotency_mismatch");
        }
        String billingMode = settlementBillingMode(command.billingMode());
        if ("SETTLED".equals(reservation.status())) {
            if (reservation.settledAmount() == command.settledAmount()
                    && java.util.Objects.equals(
                            reservation.actualInputTokens(), command.inputTokens())
                    && java.util.Objects.equals(
                            reservation.actualOutputTokens(), command.outputTokens())
                    && billingMode.equals(reservation.billingMode())) {
                return reservation.view();
            }
            throw conflict("wallet_usage_idempotency_mismatch");
        }
        if (!"RESERVED".equals(reservation.status())) {
            throw conflict("wallet_usage_not_settleable");
        }
        if (command.settledAmount() > reservation.estimatedAmount()) {
            throw conflict("billing_reconciliation_required");
        }
        Account account = lockAccountById(reservation.accountId());
        long release = reservation.estimatedAmount() - command.settledAmount();
        if (command.settledAmount() > 0) {
            insertUsageLedger(reservation, command.settledAmount(), account);
        }
        int updated = repository.settleBalance(
                account.id(), release, reservation.estimatedAmount());
        if (updated != 1) {
            throw new IllegalStateException("wallet_account_settlement_failed");
        }
        int reservationUpdated = repository.settleReservation(
                reservation.id(), command.settledAmount(), command.inputTokens(),
                command.outputTokens(), billingMode);
        if (reservationUpdated != 1) {
            throw new IllegalStateException("wallet_usage_settlement_state_changed");
        }
        if (operatingFacts != null && command.settledAmount() > 0) {
            operatingFacts.recordWalletUsage(new OperatingFactService.WalletUsage(
                    reservation.id(), reservation.tenantId(), reservation.userId(),
                    reservation.requestId(), reservation.modelCode(), reservation.priceVersion(),
                    command.settledAmount(), command.inputTokens(), command.outputTokens(),
                    billingMode, Instant.now()));
        }
        if (notifications != null) {
            notifications.walletSettled(
                    reservation.id(), reservation.tenantId(), reservation.userId(),
                    reservation.requestId(), reservation.modelCode(), command.settledAmount(),
                    Math.addExact(account.availableAmount(), release), command.inputTokens(),
                    command.outputTokens(), billingMode);
        }
        return new WalletReservation(reservation.id(), reservation.requestId(),
                reservation.estimatedAmount(), command.settledAmount(), "SETTLED",
                reservation.modelCode(), reservation.priceVersion());
    }

    @Transactional
    public WalletReservation release(UUID reservationId) {
        return release(reservationId, false);
    }

    @Transactional
    public WalletReservation expire(UUID reservationId) {
        return release(reservationId, true);
    }

    private WalletReservation release(UUID reservationId, boolean expired) {
        if (reservationId == null) {
            throw unprocessable("invalid_wallet_usage_release");
        }
        Reservation reservation = lockReservation(reservationId);
        if ("RELEASED".equals(reservation.status()) || "EXPIRED".equals(reservation.status())) {
            return reservation.view();
        }
        if (!"RESERVED".equals(reservation.status())) {
            throw conflict("wallet_usage_not_releasable");
        }
        Account account = lockAccountById(reservation.accountId());
        int updated = repository.releaseBalance(account.id(), reservation.estimatedAmount());
        if (updated != 1) {
            throw new IllegalStateException("wallet_account_release_failed");
        }
        repository.releaseReservation(reservation.id(), expired);
        return new WalletReservation(reservation.id(), reservation.requestId(),
                reservation.estimatedAmount(), null, expired ? "EXPIRED" : "RELEASED",
                reservation.modelCode(), reservation.priceVersion());
    }

    public java.util.List<UUID> expiredReservationIds(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("invalid_wallet_expiry_batch_size");
        }
        return repository.expiredReservationIds(limit);
    }

    @Transactional(readOnly = true)
    public WalletReservation get(UUID reservationId) {
        return findReservation(reservationId).view();
    }

    private void insertUsageLedger(Reservation reservation, long amount, Account account) {
        long balanceBefore = Math.addExact(account.availableAmount(), reservation.estimatedAmount());
        long balanceAfter = Math.subtractExact(balanceBefore, amount);
        repository.insertUsageLedger(
                UUID.randomUUID(), "wle_" + UUID.randomUUID().toString().replace("-", ""),
                reservation.tenantId(), reservation.userId(), reservation.accountId(), amount,
                balanceBefore, balanceAfter, reservation.id(),
                reservation.modelCode(), reservation.priceVersion());
    }

    private Account lockAccount(UUID tenantId, UUID userId) {
        return account(repository.lockAccount(tenantId, userId));
    }

    private Account lockAccountById(UUID accountId) {
        return account(repository.lockAccountById(accountId));
    }

    private Reservation lockReservation(UUID id) {
        return reservation(repository.lockReservation(id));
    }

    private Reservation findReservation(UUID id) {
        return reservation(repository.findReservation(id));
    }

    private Reservation findExisting(UUID accountId, ReserveCommand command) {
        WalletUsageReservationRepository.ReservationRow row = repository.findExisting(
                accountId, command.requestId(), command.idempotencyKey());
        return row == null ? null : reservation(row);
    }

    private Account account(WalletUsageReservationRepository.AccountRow row) {
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "wallet_not_found");
        }
        return new Account(
                row.id(), row.tenantId(), row.userId(),
                row.availableAmount(), row.reservedAmount());
    }

    private Reservation reservation(WalletUsageReservationRepository.ReservationRow row) {
        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "wallet_usage_reservation_not_found");
        }
        return new Reservation(
                row.id(), row.tenantId(), row.userId(), row.accountId(), row.requestId(),
                row.estimatedAmount(), row.settledAmount(), row.status(), row.modelCode(),
                row.priceVersion(), row.actualInputTokens(), row.actualOutputTokens(),
                row.billingMode(), row.expiresAt());
    }

    private void validate(ReserveCommand command) {
        if (command == null || command.reservationId() == null || command.tenantId() == null
                || command.userId() == null || command.requestId() == null || command.requestId().isBlank()
                || command.modelCode() == null || command.modelCode().isBlank()
                || command.estimatedAmount() <= 0 || command.inputTokens() < 0 || command.outputTokens() < 0
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.expiresAt() == null || !command.expiresAt().isAfter(Instant.now())) {
            throw unprocessable("invalid_wallet_usage_reservation");
        }
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private String settlementBillingMode(String billingMode) {
        if ("HYBRID".equals(billingMode) || "WALLET_ONLY".equals(billingMode)
                || "PACKAGE_ONLY".equals(billingMode)) {
            return billingMode;
        }
        throw unprocessable("invalid_wallet_usage_settlement");
    }

    public record ReserveCommand(UUID reservationId, String requestId, UUID tenantId, UUID userId,
            String modelCode, long estimatedAmount, long inputTokens, long outputTokens,
            String priceVersion, String idempotencyKey, Instant expiresAt) {
        public ReserveCommand {
            expiresAt = expiresAt == null ? Instant.now().plus(Duration.ofMinutes(10)) : expiresAt;
        }

        public ReserveCommand(UUID reservationId, String requestId, UUID tenantId, UUID userId,
                String modelCode, long estimatedAmount, long inputTokens, long outputTokens,
                String priceVersion, String idempotencyKey) {
            this(reservationId, requestId, tenantId, userId, modelCode, estimatedAmount,
                    inputTokens, outputTokens, priceVersion, idempotencyKey, null);
        }
    }

    public record SettleCommand(UUID tenantId, UUID userId, long settledAmount,
            long inputTokens, long outputTokens, String billingMode) {
        public SettleCommand(UUID tenantId, UUID userId, long settledAmount,
                long inputTokens, long outputTokens) {
            this(tenantId, userId, settledAmount, inputTokens, outputTokens, "WALLET_ONLY");
        }
    }

    public record WalletReservation(UUID id, String requestId, long estimatedAmount,
            Long settledAmount, String status, String modelCode, String priceVersion) { }

    private record Account(UUID id, UUID tenantId, UUID userId, long availableAmount, long reservedAmount) { }

    private record Reservation(UUID id, UUID tenantId, UUID userId, UUID accountId, String requestId,
            long estimatedAmount, Long settledAmount, String status, String modelCode, String priceVersion,
            Long actualInputTokens, Long actualOutputTokens, String billingMode, Instant expiresAt) {
        boolean matches(ReserveCommand command) {
            return id.equals(command.reservationId()) && tenantId.equals(command.tenantId())
                    && userId.equals(command.userId()) && requestId.equals(command.requestId())
                    && estimatedAmount == command.estimatedAmount() && modelCode.equals(command.modelCode())
                    && java.util.Objects.equals(priceVersion, command.priceVersion());
        }
        WalletReservation view() {
            return new WalletReservation(id, requestId, estimatedAmount, settledAmount,
                    status, modelCode, priceVersion);
        }
    }
}
