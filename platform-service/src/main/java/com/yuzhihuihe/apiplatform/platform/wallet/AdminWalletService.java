package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminWalletService {

    private static final Set<String> LEDGER_ENTRY_TYPES = Set.of(
            "RECHARGE", "PACKAGE_PURCHASE", "PACKAGE_REFUND", "MODEL_USAGE");
    private static final Set<String> RECHARGE_STATUSES = Set.of(
            "PENDING", "PAID", "CLOSED", "FAILED");
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");

    private final AdminWalletRepository repository;

    AdminWalletService(AdminWalletRepository repository) {
        this.repository = repository;
    }

    public PageResult<AccountView> accounts(UUID tenantId, UUID actorUserId, AccountQuery request) {
        requireTenant(tenantId);
        requireQuery(request);
        PageRequest page = page(request.page(), request.size());
        AdminWalletRepository.PageRows<AdminWalletRepository.AccountRow> rows =
                repository.findAccounts(
                        readTenant(tenantId, actorUserId),
                        query(request.query()),
                        enumFilter(request.status(), USER_STATUSES, "invalid_user_status"),
                        request.from(),
                        request.to(),
                        page.size(),
                        page.offset(),
                        accountSort(request.sort()),
                        direction(request.direction()));
        List<AccountView> items = new ArrayList<>();
        for (AdminWalletRepository.AccountRow row : rows.items()) {
            items.add(new AccountView(
                    row.id(), row.userId(), row.userNo(), row.email(), row.displayName(),
                    row.currency(), row.availableAmount(), row.version(),
                    row.createdAt(), row.updatedAt()));
        }
        return new PageResult<>(items, rows.total(), page.page(), page.size());
    }

    public PageResult<LedgerView> ledger(UUID tenantId, UUID actorUserId, LedgerQuery request) {
        requireTenant(tenantId);
        requireQuery(request);
        PageRequest page = page(request.page(), request.size());
        AdminWalletRepository.PageRows<AdminWalletRepository.LedgerRow> rows =
                repository.findLedger(
                        readTenant(tenantId, actorUserId),
                        query(request.query()),
                        enumFilter(
                                request.entryType(), LEDGER_ENTRY_TYPES,
                                "invalid_ledger_entry_type"),
                        request.from(),
                        request.to(),
                        page.size(),
                        page.offset(),
                        ledgerSort(request.sort()),
                        direction(request.direction()));
        List<LedgerView> items = new ArrayList<>();
        for (AdminWalletRepository.LedgerRow row : rows.items()) {
            items.add(new LedgerView(
                    row.entryNo(), row.userId(), row.userNo(), row.email(), row.displayName(),
                    row.entryType(), row.amountDelta(), row.balanceAfter(), row.businessNo(),
                    row.description(), row.createdAt()));
        }
        return new PageResult<>(items, rows.total(), page.page(), page.size());
    }

    public PageResult<RechargeView> recharges(UUID tenantId, UUID actorUserId, RechargeQuery request) {
        requireTenant(tenantId);
        requireQuery(request);
        PageRequest page = page(request.page(), request.size());
        AdminWalletRepository.PageRows<AdminWalletRepository.RechargeRow> rows =
                repository.findRecharges(
                        readTenant(tenantId, actorUserId),
                        query(request.query()),
                        enumFilter(
                                request.status(), RECHARGE_STATUSES,
                                "invalid_recharge_status"),
                        request.from(),
                        request.to(),
                        page.size(),
                        page.offset(),
                        rechargeSort(request.sort()),
                        direction(request.direction()));
        List<RechargeView> items = new ArrayList<>();
        for (AdminWalletRepository.RechargeRow row : rows.items()) {
            items.add(new RechargeView(
                    row.rechargeNo(), row.userId(), row.userNo(), row.email(), row.displayName(),
                    row.optionId(), row.paidAmount(), row.bonusAmount(), row.creditedAmount(),
                    row.currency(), row.status(), row.expiresAt(), row.paidAt(), row.closedAt(),
                    row.createdAt(), row.updatedAt()));
        }
        return new PageResult<>(items, rows.total(), page.page(), page.size());
    }

    private UUID readTenant(UUID tenantId, UUID actorUserId) {
        requireTenant(tenantId);
        requireTenant(actorUserId);
        // Only a current, active global administrator may read across tenants.
        // Never accept this scope from query parameters or a client-supplied flag.
        return repository.isGlobalAdmin(tenantId, actorUserId) ? null : tenantId;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Overview overview(UUID tenantId, UUID actorUserId) {
        UUID scope = readTenant(tenantId, actorUserId);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        var balances = repository.balanceTotals(scope);
        var flows = repository.dailyFlows(scope, today.minusDays(6).atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant());
        List<TrendDay> trend = new ArrayList<>();
        for (LocalDate day = today.minusDays(6); !day.isAfter(today); day = day.plusDays(1)) {
            LocalDate date = day;
            var flow = flows.stream().filter(row -> row.date().equals(date)).findFirst();
            trend.add(new TrendDay(date, flow.map(AdminWalletRepository.DailyFlow::recharge).orElse(0L),
                    flow.map(AdminWalletRepository.DailyFlow::debit).orElse(0L)));
        }
        TrendDay current = trend.getLast();
        return new Overview(balances.accounts(), balances.totalBalance(), current.recharge(),
                current.debit(), zone.getId(), trend);
    }

    public record Overview(long accounts, long totalBalance, long todayRecharge, long todayDebit,
            String timezone, List<TrendDay> trend) {}

    public record TrendDay(LocalDate date, long recharge, long debit) {}

    public List<OptionView> options(UUID tenantId) {
        requireTenant(tenantId);
        return optionViews(repository.findOptions(tenantId));
    }

    @Transactional
    public List<OptionView> replaceOptions(
            UUID tenantId, UUID actorUserId, List<OptionReplacement> replacements) {
        validateReplacements(tenantId, actorUserId, replacements);
        repository.lockOptions(tenantId);
        requireActor(tenantId, actorUserId);

        repository.disableOptions(tenantId, actorUserId);
        for (OptionReplacement replacement : replacements) {
            repository.upsertOption(
                    UUID.randomUUID(), tenantId, replacement.amount(), replacement.enabled(),
                    replacement.displayOrder(), actorUserId);
        }
        long enabledCount = replacements.stream().filter(OptionReplacement::enabled).count();
        repository.insertReplacementAudit(
                tenantId, actorUserId, replacements.size(), enabledCount);
        return optionViews(repository.findOptions(tenantId));
    }

    private List<OptionView> optionViews(List<AdminWalletRepository.OptionRow> rows) {
        List<OptionView> options = new ArrayList<>();
        for (AdminWalletRepository.OptionRow row : rows) {
            options.add(new OptionView(
                    row.id(), row.amount(), row.enabled(), row.displayOrder(),
                    row.createdAt(), row.updatedAt()));
        }
        return options;
    }

    private void validateReplacements(
            UUID tenantId, UUID actorUserId, List<OptionReplacement> replacements) {
        if (tenantId == null || actorUserId == null || replacements == null
                || replacements.size() > 20) {
            throw invalid("invalid_recharge_options");
        }
        Set<Long> amounts = new HashSet<>();
        for (OptionReplacement replacement : replacements) {
            if (replacement == null
                    || replacement.amount() < RechargeOrderService.MINIMUM_AMOUNT
                    || replacement.amount() > RechargeOrderService.MAXIMUM_AMOUNT
                    || replacement.displayOrder() < 0
                    || !amounts.add(replacement.amount())) {
                throw invalid("invalid_recharge_options");
            }
        }
    }

    private void requireActor(UUID tenantId, UUID actorUserId) {
        if (!repository.activeActor(tenantId, actorUserId)) {
            throw invalid("invalid_admin_actor");
        }
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("invalid_pagination");
        }
        try {
            return new PageRequest(page, size, Math.multiplyExact(page, size));
        } catch (ArithmeticException overflow) {
            throw invalid("invalid_pagination");
        }
    }

    private AdminWalletRepository.AccountSort accountSort(String requestedSort) {
        String sort = blank(requestedSort) ? "createdAt" : requestedSort.strip();
        return switch (sort) {
            case "createdAt" -> AdminWalletRepository.AccountSort.CREATED_AT;
            case "updatedAt" -> AdminWalletRepository.AccountSort.UPDATED_AT;
            case "availableAmount" -> AdminWalletRepository.AccountSort.AVAILABLE_AMOUNT;
            case "email" -> AdminWalletRepository.AccountSort.EMAIL;
            case "userNo" -> AdminWalletRepository.AccountSort.USER_NO;
            default -> throw invalid("invalid_admin_wallet_sort");
        };
    }

    private AdminWalletRepository.LedgerSort ledgerSort(String requestedSort) {
        String sort = blank(requestedSort) ? "createdAt" : requestedSort.strip();
        return switch (sort) {
            case "createdAt" -> AdminWalletRepository.LedgerSort.CREATED_AT;
            case "amountDelta" -> AdminWalletRepository.LedgerSort.AMOUNT_DELTA;
            case "balanceAfter" -> AdminWalletRepository.LedgerSort.BALANCE_AFTER;
            case "entryType" -> AdminWalletRepository.LedgerSort.ENTRY_TYPE;
            default -> throw invalid("invalid_admin_wallet_sort");
        };
    }

    private AdminWalletRepository.RechargeSort rechargeSort(String requestedSort) {
        String sort = blank(requestedSort) ? "createdAt" : requestedSort.strip();
        return switch (sort) {
            case "createdAt" -> AdminWalletRepository.RechargeSort.CREATED_AT;
            case "updatedAt" -> AdminWalletRepository.RechargeSort.UPDATED_AT;
            case "paidAmount" -> AdminWalletRepository.RechargeSort.PAID_AMOUNT;
            case "status" -> AdminWalletRepository.RechargeSort.STATUS;
            default -> throw invalid("invalid_admin_wallet_sort");
        };
    }

    private AdminWalletRepository.SortDirection direction(String requestedDirection) {
        String direction = blank(requestedDirection)
                ? "DESC" : requestedDirection.strip().toUpperCase(Locale.ROOT);
        return switch (direction) {
            case "ASC" -> AdminWalletRepository.SortDirection.ASC;
            case "DESC" -> AdminWalletRepository.SortDirection.DESC;
            default -> throw invalid("invalid_sort_direction");
        };
    }

    private String query(String value) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 128) {
            throw invalid("invalid_admin_wallet_query");
        }
        return normalized;
    }

    private String enumFilter(String value, Set<String> allowed, String reason) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid(reason);
        }
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
    }

    private void requireQuery(Object request) {
        if (request == null) {
            throw invalid("invalid_admin_wallet_query");
        }
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record AccountQuery(
            int page, int size, String query, String status, Instant from, Instant to,
            String sort, String direction) {
    }

    public record LedgerQuery(
            int page, int size, String query, String entryType, Instant from, Instant to,
            String sort, String direction) {
    }

    public record RechargeQuery(
            int page, int size, String query, String status, Instant from, Instant to,
            String sort, String direction) {
    }

    public record OptionReplacement(long amount, boolean enabled, int displayOrder) {
    }

    public record AccountView(
            UUID id,
            UUID userId,
            String userNo,
            String email,
            String displayName,
            String currency,
            long availableAmount,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record LedgerView(
            String entryNo,
            UUID userId,
            String userNo,
            String email,
            String displayName,
            String entryType,
            long amountDelta,
            long balanceAfter,
            String businessNo,
            String description,
            Instant createdAt) {
    }

    public record RechargeView(
            String rechargeNo,
            UUID userId,
            String userNo,
            String email,
            String displayName,
            UUID optionId,
            long paidAmount,
            long bonusAmount,
            long creditedAmount,
            String currency,
            String status,
            Instant expiresAt,
            Instant paidAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record OptionView(
            UUID id,
            long amount,
            boolean enabled,
            int displayOrder,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    private record PageRequest(int page, int size, int offset) {
    }
}
