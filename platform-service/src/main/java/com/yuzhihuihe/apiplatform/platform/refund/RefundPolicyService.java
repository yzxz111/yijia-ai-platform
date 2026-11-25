package com.yuzhihuihe.apiplatform.platform.refund;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundPolicyService {

    private static final int MAXIMUM_REFUND_WINDOW_DAYS = 365;

    private final RefundPolicyRepository repository;
    private final Clock clock;

    RefundPolicyService(RefundPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public RefundPolicy effectiveAt(Instant requestedAt) {
        if (requestedAt == null) {
            throw unprocessable("invalid_refund_policy_request_time");
        }
        return repository.findEffectiveAt(requestedAt)
                .map(this::policy)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "refund_policy_not_found"));
    }

    public PageResult<RefundPolicy> listPublished(int page, int size) {
        Page bounds = page(page, size);
        List<RefundPolicy> items = repository.findPublished(bounds.size(), bounds.offset())
                .stream().map(this::policy).toList();
        return new PageResult<>(List.copyOf(items), repository.countPublished(),
                bounds.number(), bounds.size());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RefundPolicy publish(UUID actorId, PublishPolicyCommand command) {
        validate(actorId, command);
        repository.lockVersion(command.version());
        boolean exists = repository.versionExists(command.version());
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "refund_policy_version_exists");
        }
        repository.insertPolicy(
                command.version(), command.feeBps(), command.minimumFee(),
                command.refundWindowDays(), command.maximumUsageBps(),
                command.effectiveAt(), actorId);
        repository.auditPublish(
                actorId, command.version(), command.feeBps(),
                command.minimumFee(), command.refundWindowDays(),
                command.maximumUsageBps(), command.effectiveAt());
        return byVersion(command.version());
    }

    private RefundPolicy byVersion(int version) {
        return repository.findByVersion(version)
                .map(this::policy)
                .orElseThrow(() -> new IllegalStateException("refund_policy_insert_failed"));
    }

    private RefundPolicy policy(RefundPolicyRepository.PolicyRow row) {
        return new RefundPolicy(
                row.version(), row.feeBps(), row.minimumFee(), row.refundWindowDays(),
                row.maximumUsageBps(), row.effectiveAt(), row.published(),
                row.createdBy(), row.createdAt());
    }

    private void validate(UUID actorId, PublishPolicyCommand command) {
        if (actorId == null
                || command == null
                || command.version() <= 0
                || command.feeBps() < 0
                || command.feeBps() > 10_000
                || command.minimumFee() < 0
                || command.refundWindowDays() <= 0
                || command.refundWindowDays() > MAXIMUM_REFUND_WINDOW_DAYS
                || command.maximumUsageBps() < 0
                || command.maximumUsageBps() > 10_000
                || command.effectiveAt() == null) {
            throw unprocessable("invalid_refund_policy");
        }
        if (!command.effectiveAt().isAfter(clock.instant())) {
            throw unprocessable("refund_policy_effective_at_must_be_future");
        }
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private Page page(int number, int size) {
        if (number < 0 || size < 1 || size > 100) {
            throw unprocessable("invalid_refund_policy_page");
        }
        long offset = (long) number * size;
        if (offset > Integer.MAX_VALUE) {
            throw unprocessable("invalid_refund_policy_page");
        }
        return new Page(number, size, (int) offset);
    }

    public record PublishPolicyCommand(
            int version,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            Instant effectiveAt) {
    }

    public record RefundPolicy(
            int version,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            Instant effectiveAt,
            boolean published,
            UUID createdBy,
            Instant createdAt) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    private record Page(int number, int size, int offset) {
    }
}
