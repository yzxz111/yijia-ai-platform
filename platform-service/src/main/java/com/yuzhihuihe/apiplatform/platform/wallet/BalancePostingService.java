package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BalancePostingService {

    private final BalancePostingRepository repository;
    private final CommercialTransactionMetrics metrics;

    @Autowired
    BalancePostingService(
            BalancePostingRepository repository,
            CommercialTransactionMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Transactional
    public PostingResult post(PostingCommand command) {
        validate(command);
        Account account = lockAccount(command.tenantId(), command.userId());
        List<LedgerPosting> existing = findExisting(account.id(), command);
        if (!existing.isEmpty()) {
            if (existing.size() == 1 && existing.getFirst().matches(command)) {
                return existing.getFirst().result();
            }
            throw conflict();
        }

        long balanceAfter;
        try {
            balanceAfter = Math.addExact(account.availableAmount(), command.amountDelta());
        } catch (ArithmeticException error) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "wallet_balance_overflow", error);
        }
        if (balanceAfter < 0) {
            metrics.insufficientBalance(command.entryType());
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "wallet_insufficient_balance");
        }

        PostingResult result = insertLedger(account, command, balanceAfter);
        int updated = repository.updateAccount(
                command.tenantId(), command.userId(), account.id(), balanceAfter);
        if (updated != 1) {
            throw new IllegalStateException("wallet_account_update_failed");
        }
        recordWalletMutation(command.entryType());
        return result;
    }

    private void recordWalletMutation(String entryType) {
        if ("PACKAGE_PURCHASE".equals(entryType)) {
            metrics.walletDebit(entryType);
        } else if ("PACKAGE_REFUND".equals(entryType)) {
            metrics.walletRefund(entryType);
        }
    }

    private void validate(PostingCommand command) {
        if (command == null
                || command.tenantId() == null
                || command.userId() == null
                || command.businessId() == null
                || command.entryType() == null
                || command.businessType() == null
                || !bounded(command.businessNo(), 64)
                || !bounded(command.idempotencyKey(), 128)
                || !validPostingShape(command)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_wallet_posting");
        }
    }

    private boolean bounded(String value, int maximumCharacters) {
        return value != null
                && !value.isBlank()
                && value.codePointCount(0, value.length()) <= maximumCharacters;
    }

    private boolean validPostingShape(PostingCommand command) {
        return switch (command.entryType()) {
            case "RECHARGE" -> validRecharge(command);
            case "PACKAGE_PURCHASE" -> command.businessType().equals("ORDER")
                    && command.amountDelta() < 0
                    && command.paidAmount() == 0
                    && command.bonusAmount() == 0;
            case "PACKAGE_REFUND" -> command.businessType().equals("REFUND")
                    && command.amountDelta() > 0
                    && command.paidAmount() == 0
                    && command.bonusAmount() == 0;
            default -> false;
        };
    }

    private boolean validRecharge(PostingCommand command) {
        if (!command.businessType().equals("RECHARGE_ORDER")
                || command.amountDelta() <= 0
                || command.paidAmount() <= 0
                || command.bonusAmount() != 0) {
            return false;
        }
        try {
            return command.amountDelta()
                    == Math.addExact(command.paidAmount(), command.bonusAmount());
        } catch (ArithmeticException error) {
            return false;
        }
    }

    private Account lockAccount(UUID tenantId, UUID userId) {
        BalancePostingRepository.AccountRow row = repository.lockAccount(tenantId, userId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "wallet_not_found");
        }
        return new Account(row.id(), row.availableAmount());
    }

    private List<LedgerPosting> findExisting(UUID accountId, PostingCommand command) {
        return repository.findExisting(
                        command.tenantId(), command.userId(), accountId, command.entryType(),
                        command.businessType(), command.businessId(), command.idempotencyKey())
                .stream()
                .map(row -> new LedgerPosting(
                        row.id(), row.entryNo(), row.entryType(), row.amountDelta(),
                        row.paidAmount(), row.bonusAmount(), row.balanceBefore(),
                        row.balanceAfter(), row.businessType(), row.businessId(),
                        row.businessNo(), row.idempotencyKey(), row.createdAt()))
                .toList();
    }

    private PostingResult insertLedger(
            Account account, PostingCommand command, long balanceAfter) {
        UUID ledgerId = UUID.randomUUID();
        String entryNo = "wle_" + UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = repository.insertLedger(
                ledgerId, entryNo, command.tenantId(), command.userId(), account.id(),
                command.entryType(), command.amountDelta(), command.paidAmount(),
                command.bonusAmount(), account.availableAmount(), balanceAfter,
                command.businessType(), command.businessId(), command.businessNo(),
                command.idempotencyKey());
        return new PostingResult(
                ledgerId, entryNo, account.availableAmount(), balanceAfter, createdAt);
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "wallet_ledger_conflict");
    }

    public record PostingCommand(
            UUID tenantId,
            UUID userId,
            String entryType,
            long amountDelta,
            long paidAmount,
            long bonusAmount,
            String businessType,
            UUID businessId,
            String businessNo,
            String idempotencyKey) {
    }

    public record PostingResult(
            UUID ledgerId,
            String entryNo,
            long balanceBefore,
            long balanceAfter,
            Instant createdAt) {
    }

    private record Account(UUID id, long availableAmount) {
    }

    private record LedgerPosting(
            UUID id,
            String entryNo,
            String entryType,
            long amountDelta,
            long paidAmount,
            long bonusAmount,
            long balanceBefore,
            long balanceAfter,
            String businessType,
            UUID businessId,
            String businessNo,
            String idempotencyKey,
            Instant createdAt) {

        boolean matches(PostingCommand command) {
            return entryType.equals(command.entryType())
                    && amountDelta == command.amountDelta()
                    && paidAmount == command.paidAmount()
                    && bonusAmount == command.bonusAmount()
                    && businessType.equals(command.businessType())
                    && businessId.equals(command.businessId())
                    && businessNo.equals(command.businessNo())
                    && idempotencyKey.equals(command.idempotencyKey());
        }

        PostingResult result() {
            return new PostingResult(id, entryNo, balanceBefore, balanceAfter, createdAt);
        }
    }
}
