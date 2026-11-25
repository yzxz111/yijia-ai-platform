package com.yuzhihuihe.apiplatform.platform.wallet;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RechargeOrderService {

    public static final long MINIMUM_AMOUNT = 100;
    public static final long MAXIMUM_AMOUNT = 5_000_000;

    private static final Duration RECHARGE_TTL = Duration.ofMinutes(15);
    private final RechargeOrderRepository repository;
    private final Clock clock;
    private final CommercialTransactionMetrics metrics;

    RechargeOrderService(
            RechargeOrderRepository repository,
            Clock clock,
            CommercialTransactionMetrics metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public CreationOutcome createWithOutcome(
            UUID tenantId,
            UUID userId,
            String idempotencyKey,
            CreateRechargeCommand command) {
        validate(tenantId, userId, idempotencyKey, command);
        requireActiveUser(tenantId, userId);
        lock(userId, idempotencyKey);

        StoredOrder existing = existing(tenantId, userId, idempotencyKey);
        if (existing != null) {
            if (existing.order().paidAmount() != command.amount()
                    || !java.util.Objects.equals(existing.order().optionId(), command.optionId())) {
                throw conflict("recharge_idempotency_payload_mismatch");
            }
            metrics.rechargeCreated("REPLAY");
            return new CreationOutcome(existing.order(), false);
        }

        validateOption(tenantId, command);
        UUID id = UUID.randomUUID();
        String rechargeNo = "rch_" + id.toString().replace("-", "");
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(RECHARGE_TTL);
        repository.insert(
                id, rechargeNo, tenantId, userId, command.optionId(), command.amount(),
                idempotencyKey, expiresAt, createdAt);
        RechargeOrderView created = detail(tenantId, userId, rechargeNo);
        metrics.rechargeCreated("CREATED");
        return new CreationOutcome(created, true);
    }

    @Transactional
    public PageResult<RechargeOrderView> list(
            UUID tenantId,
            UUID userId,
            int page,
            int size,
            String requestedSort,
            String requestedDirection) {
        requireOwner(tenantId, userId);
        repository.closeExpiredForOwner(tenantId, userId, clock.instant());
        PageRequest pagination = page(page, size);
        RechargeOrderRepository.OrderSort sort = sort(requestedSort);
        RechargeOrderRepository.SortDirection direction = direction(requestedDirection);
        long total = repository.count(tenantId, userId);
        List<RechargeOrderView> items = new ArrayList<>();
        for (RechargeOrderRepository.OrderRow row : repository.findAll(
                tenantId, userId, pagination.size(), pagination.offset(), sort, direction)) {
            items.add(order(row));
        }
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public RechargeOptionCatalog options(UUID tenantId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
        List<RechargeOptionView> options = new ArrayList<>();
        for (RechargeOrderRepository.OptionRow row : repository.findOptions(tenantId)) {
            options.add(new RechargeOptionView(row.id(), row.amount(), row.displayOrder()));
        }
        return new RechargeOptionCatalog(
                options,
                new CustomAmountRange(MINIMUM_AMOUNT, MAXIMUM_AMOUNT),
                "CNY");
    }

    @Transactional
    public RechargeOrderView detail(UUID tenantId, UUID userId, String rechargeNo) {
        requireOwner(tenantId, userId);
        if (rechargeNo == null || rechargeNo.isBlank() || rechargeNo.length() > 64) {
            throw notFound();
        }
        repository.closeExpiredForOwner(tenantId, userId, clock.instant());
        RechargeOrderRepository.OrderRow row = repository.findByNumber(
                tenantId, userId, rechargeNo);
        if (row == null) {
            throw notFound();
        }
        return order(row);
    }

    @Transactional
    public RechargeOrderView requirePayable(UUID tenantId, UUID userId, String rechargeNo) {
        RechargeOrderView order = detail(tenantId, userId, rechargeNo);
        if (!"PENDING".equals(order.status()) || !clock.instant().isBefore(order.expiresAt())) {
            throw conflict("recharge_order_not_payable");
        }
        return order;
    }

    private void validate(
            UUID tenantId,
            UUID userId,
            String idempotencyKey,
            CreateRechargeCommand command) {
        if (tenantId == null || userId == null || command == null) {
            throw invalid("invalid_recharge_request");
        }
        if (command.amount() < MINIMUM_AMOUNT || command.amount() > MAXIMUM_AMOUNT) {
            throw invalid("invalid_recharge_amount");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw invalid("invalid_idempotency_key");
        }
    }

    private void validateOption(UUID tenantId, CreateRechargeCommand command) {
        if (command.optionId() == null) {
            return;
        }
        if (!repository.optionAvailable(tenantId, command.optionId(), command.amount())) {
            throw invalid("recharge_option_unavailable");
        }
    }

    private void requireActiveUser(UUID tenantId, UUID userId) {
        if (!repository.activeUserExists(tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recharge_user_not_found");
        }
    }

    private void requireOwner(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
    }

    private void lock(UUID userId, String idempotencyKey) {
        repository.lock(userId, idempotencyKey);
    }

    private StoredOrder existing(UUID tenantId, UUID userId, String idempotencyKey) {
        RechargeOrderRepository.OrderRow row = repository.findExisting(
                tenantId, userId, idempotencyKey);
        return row == null ? null : new StoredOrder(order(row));
    }

    private RechargeOrderView order(RechargeOrderRepository.OrderRow row) {
        return new RechargeOrderView(
                row.rechargeNo(), row.optionId(), row.paidAmount(), row.bonusAmount(),
                row.creditedAmount(), row.currency(), row.status(), row.expiresAt(),
                row.paidAt(), row.closedAt(), row.createdAt(), row.updatedAt());
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

    private RechargeOrderRepository.OrderSort sort(String requestedSort) {
        String sort = blank(requestedSort) ? "createdAt" : requestedSort.strip();
        return switch (sort) {
            case "createdAt" -> RechargeOrderRepository.OrderSort.CREATED_AT;
            case "paidAmount" -> RechargeOrderRepository.OrderSort.PAID_AMOUNT;
            case "status" -> RechargeOrderRepository.OrderSort.STATUS;
            default -> throw invalid("invalid_recharge_sort");
        };
    }

    private RechargeOrderRepository.SortDirection direction(String requestedDirection) {
        String direction = blank(requestedDirection)
                ? "DESC" : requestedDirection.strip().toUpperCase(Locale.ROOT);
        return switch (direction) {
            case "ASC" -> RechargeOrderRepository.SortDirection.ASC;
            case "DESC" -> RechargeOrderRepository.SortDirection.DESC;
            default -> throw invalid("invalid_sort_direction");
        };
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "recharge_order_not_found");
    }

    public record CreateRechargeCommand(long amount, UUID optionId) {
    }

    public record CreationOutcome(RechargeOrderView order, boolean created) {
    }

    public record RechargeOrderView(
            String rechargeNo,
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

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    public record RechargeOptionCatalog(
            List<RechargeOptionView> options,
            CustomAmountRange custom,
            String currency) {
    }

    public record RechargeOptionView(UUID id, long amount, int displayOrder) {
    }

    public record CustomAmountRange(long minimumAmount, long maximumAmount) {
    }

    private record PageRequest(int page, int size, int offset) {
    }

    private record StoredOrder(RechargeOrderView order) {
    }
}
