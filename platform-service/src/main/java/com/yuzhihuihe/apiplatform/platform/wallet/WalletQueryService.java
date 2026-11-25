package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WalletQueryService {

    private final WalletQueryRepository repository;

    WalletQueryService(WalletQueryRepository repository) {
        this.repository = repository;
    }

    public WalletSummary summary(UUID tenantId, UUID userId) {
        requireOwner(tenantId, userId);
        WalletQueryRepository.SummaryRow row = repository.findSummary(tenantId, userId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "wallet_not_found");
        }
        return new WalletSummary(row.availableAmount(), row.currency(), row.updatedAt());
    }

    public PageResult<LedgerView> ledger(
            UUID tenantId, UUID userId, int page, int size,
            String requestedSort, String requestedDirection) {
        requireOwner(tenantId, userId);
        PageRequest pagination = new PageRequest(page, size).validated();
        WalletQueryRepository.LedgerSort sort = sort(requestedSort);
        WalletQueryRepository.SortDirection direction = direction(requestedDirection);
        long total = repository.countLedger(tenantId, userId);
        List<LedgerView> items = new ArrayList<>();
        for (WalletQueryRepository.LedgerRow row : repository.findLedger(
                tenantId, userId, pagination.size(), pagination.offset(), sort, direction)) {
            items.add(new LedgerView(
                    row.entryNo(), row.entryType(), row.amountDelta(), row.balanceAfter(),
                    row.businessNo(), row.description(), row.createdAt()));
        }
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    private WalletQueryRepository.LedgerSort sort(String requestedSort) {
        String sort = normalized(requestedSort) == null ? "createdAt" : requestedSort.strip();
        return switch (sort) {
            case "createdAt" -> WalletQueryRepository.LedgerSort.CREATED_AT;
            case "amountDelta" -> WalletQueryRepository.LedgerSort.AMOUNT_DELTA;
            default -> throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sort");
        };
    }

    private WalletQueryRepository.SortDirection direction(String requestedDirection) {
        String direction = normalized(requestedDirection) == null
                ? "DESC" : requestedDirection.strip().toUpperCase(Locale.ROOT);
        return switch (direction) {
            case "ASC" -> WalletQueryRepository.SortDirection.ASC;
            case "DESC" -> WalletQueryRepository.SortDirection.DESC;
            default -> throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sort_direction");
        };
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireOwner(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
    }

    public record WalletSummary(long availableAmount, String currency, Instant updatedAt) {
    }

    public record LedgerView(
            String entryNo,
            String entryType,
            long amountDelta,
            long balanceAfter,
            String businessNo,
            String description,
            Instant createdAt) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    private record PageRequest(int page, int size) {

        PageRequest validated() {
            if (page < 0 || size < 1 || size > 100
                    || (long) page * size > Integer.MAX_VALUE) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination");
            }
            return this;
        }

        int offset() {
            return Math.multiplyExact(page, size);
        }
    }
}
