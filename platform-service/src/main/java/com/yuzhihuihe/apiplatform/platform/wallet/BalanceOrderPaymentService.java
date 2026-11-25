package com.yuzhihuihe.apiplatform.platform.wallet;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.grant.GrantService;
import com.yuzhihuihe.apiplatform.platform.order.PackageOrderSettlementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BalanceOrderPaymentService {

    private static final String INITIAL_GRANT_STATUS = "QUEUED";

    private final BalanceOrderPaymentRepository repository;
    private final BalancePostingService balancePostingService;
    private final PackageOrderSettlementService settlementService;
    private final Clock clock;

    BalanceOrderPaymentService(
            BalanceOrderPaymentRepository repository,
            BalancePostingService balancePostingService,
            PackageOrderSettlementService settlementService,
            Clock clock) {
        this.repository = repository;
        this.balancePostingService = balancePostingService;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    @Transactional
    public BalancePaymentResult pay(
            UUID tenantId,
            UUID userId,
            String orderNo,
            String idempotencyKey) {
        validate(tenantId, userId, orderNo, idempotencyKey);
        PackageOrderSettlementService.LockedOrder order =
                settlementService.lockOwnedOrder(tenantId, userId, orderNo);
        if ("PAID".equals(order.status())
                && "BALANCE".equals(order.settlementMethod())) {
            return replay(order, idempotencyKey);
        }
        if (!"PENDING".equals(order.status())) {
            throw conflict("order_not_payable");
        }
        if (!order.expiresAt().isAfter(clock.instant())) {
            throw conflict("order_expired");
        }
        if (order.amount() <= 0 || !"CNY".equals(order.currency())) {
            throw conflict("order_not_payable");
        }

        BalancePostingService.PostingResult debit = postDebit(order, idempotencyKey);
        PackageOrderSettlementService.SettlementResult settlement =
                settlementService.settle(
                        order.id(),
                        PackageOrderSettlementService.SettlementCommand.balance(
                                clock.instant(), debit.ledgerId()));
        return result(settlement, debit);
    }

    private BalancePaymentResult replay(
            PackageOrderSettlementService.LockedOrder order,
            String idempotencyKey) {
        if (order.balanceDebitLedgerId() == null) {
            throw new IllegalStateException("wallet_payment_ledger_missing");
        }
        BalancePostingService.PostingResult debit = postDebit(order, idempotencyKey);
        if (!order.balanceDebitLedgerId().equals(debit.ledgerId())) {
            throw conflict("wallet_payment_idempotency_payload_mismatch");
        }
        GrantService.GrantResult grant = loadOriginalGrant(order);
        return new BalancePaymentResult(
                order.orderNo(), order.status(), order.settlementMethod(),
                debit.balanceBefore(), debit.balanceAfter(), grant.id(), grant.status());
    }

    private BalancePostingService.PostingResult postDebit(
            PackageOrderSettlementService.LockedOrder order,
            String idempotencyKey) {
        try {
            return balancePostingService.post(new BalancePostingService.PostingCommand(
                    order.tenantId(), order.userId(), "PACKAGE_PURCHASE",
                    -order.amount(), 0, 0, "ORDER", order.id(),
                    order.orderNo(), idempotencyKey));
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == HttpStatus.CONFLICT.value()
                    && "wallet_ledger_conflict".equals(error.getReason())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "wallet_payment_idempotency_payload_mismatch",
                        error);
            }
            throw error;
        }
    }

    private GrantService.GrantResult loadOriginalGrant(
            PackageOrderSettlementService.LockedOrder order) {
        BalanceOrderPaymentRepository.GrantRow row = repository.findOriginalGrant(
                order.tenantId(), order.userId(), "order:" + order.orderNo());
        if (row == null) {
            throw new IllegalStateException("wallet_payment_fulfillment_missing");
        }
        return new GrantService.GrantResult(
                row.id(), row.eventId(), row.businessNo(), INITIAL_GRANT_STATUS);
    }

    private BalancePaymentResult result(
            PackageOrderSettlementService.SettlementResult settlement,
            BalancePostingService.PostingResult debit) {
        if (!INITIAL_GRANT_STATUS.equals(settlement.grant().status())) {
            throw new IllegalStateException("wallet_payment_initial_grant_status_invalid");
        }
        return new BalancePaymentResult(
                settlement.orderNo(), settlement.orderStatus(), "BALANCE",
                debit.balanceBefore(), debit.balanceAfter(),
                settlement.grant().id(), INITIAL_GRANT_STATUS);
    }

    private void validate(
            UUID tenantId,
            UUID userId,
            String orderNo,
            String idempotencyKey) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "authentication_required");
        }
        if (orderNo == null || orderNo.isBlank() || orderNo.length() > 64) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_idempotency_key");
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record BalancePaymentResult(
            String orderNo,
            String orderStatus,
            String settlementMethod,
            long balanceBefore,
            long balanceAfter,
            UUID grantRequestId,
            String grantStatus) {
    }
}
