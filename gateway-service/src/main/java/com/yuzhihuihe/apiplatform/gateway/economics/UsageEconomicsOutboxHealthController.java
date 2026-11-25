package com.yuzhihuihe.apiplatform.gateway.economics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageEconomicsOutboxHealthController {

    static final String PATH = "/internal/usage-economics/outbox-health";
    private static final UUID FIRST_ID = new UUID(0, 0);
    private final UsageEconomicsOutboxHealthService service;

    public UsageEconomicsOutboxHealthController(UsageEconomicsOutboxHealthService service) {
        this.service = service;
    }

    @GetMapping(PATH)
    @PreAuthorize("hasRole('INTERNAL')")
    Mono<Response> health(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Instant cursorCreatedAt,
            @RequestParam(required = false) UUID cursorEventId,
            @RequestParam(defaultValue = "true") boolean includeStatus) {
        validate(from, to, limit, cursorCreatedAt, cursorEventId);
        Instant cursorTime = cursorCreatedAt == null ? from : cursorCreatedAt;
        UUID cursorId = cursorEventId == null ? FIRST_ID : cursorEventId;
        Mono<Status> status =
                includeStatus
                        ? service.status().map(UsageEconomicsOutboxHealthController::status)
                        : Mono.just(Status.EMPTY);
        Mono<List<SourceEvent>> events =
                service.events(from, to, cursorTime, cursorId, limit + 1)
                        .map(UsageEconomicsOutboxHealthController::sourceEvent)
                        .collectList();
        return Mono.zip(status, events).map(tuple -> response(tuple.getT1(), tuple.getT2(), limit));
    }

    static void validate(
            Instant from, Instant to, int limit, Instant cursorCreatedAt, UUID cursorEventId) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("outbox_health_range_invalid");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("outbox_health_range_exceeds_366_days");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("outbox_health_page_limit_invalid");
        }
        if ((cursorCreatedAt == null) != (cursorEventId == null)
                || (cursorCreatedAt != null
                        && (cursorCreatedAt.isBefore(from) || !cursorCreatedAt.isBefore(to)))) {
            throw new IllegalArgumentException("outbox_health_cursor_invalid");
        }
    }

    private static Response response(Status status, List<SourceEvent> fetched, int limit) {
        boolean more = fetched.size() > limit;
        List<SourceEvent> page =
                more ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        SourceEvent next = more ? page.getLast() : null;
        return new Response(
                status.pending(),
                status.sending(),
                status.failed(),
                status.dead(),
                status.oldestPending(),
                page,
                more,
                next == null ? null : next.createdAt(),
                next == null ? null : next.eventId());
    }

    private static Status status(UsageEconomicsOutboxHealthService.Status value) {
        return new Status(
                value.pending(),
                value.sending(),
                value.failed(),
                value.dead(),
                value.oldestPending());
    }

    private static SourceEvent sourceEvent(UsageEconomicsOutboxHealthService.SourceEvent value) {
        return new SourceEvent(value.eventId(), value.payloadHash(), value.createdAt());
    }

    record Status(long pending, long sending, long failed, long dead, Instant oldestPending) {
        private static final Status EMPTY = new Status(0, 0, 0, 0, null);
    }

    record SourceEvent(UUID eventId, String payloadHash, Instant createdAt) {}

    record Response(
            long pending,
            long sending,
            long failed,
            long dead,
            Instant oldestPending,
            List<SourceEvent> events,
            boolean hasMore,
            Instant nextCreatedAt,
            UUID nextEventId) {}
}
