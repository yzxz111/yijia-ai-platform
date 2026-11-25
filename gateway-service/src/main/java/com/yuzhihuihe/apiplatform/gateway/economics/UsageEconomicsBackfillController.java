package com.yuzhihuihe.apiplatform.gateway.economics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 以流式方式输出不可变、已脱敏的用量经济性快照，用于历史数据回填。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageEconomicsBackfillController {

    static final String PATH = "/internal/usage-economics/backfill";
    private static final Instant FIRST_TIME = Instant.parse("1970-01-01T00:00:00Z");
    private static final UUID FIRST_ID = new UUID(0, 0);

    private final UsageEconomicsBackfillService service;

    public UsageEconomicsBackfillController(UsageEconomicsBackfillService service) {
        this.service = service;
    }

    @PostMapping(PATH)
    @PreAuthorize("hasRole('INTERNAL')")
    Mono<Response> backfill(@RequestBody Request request) {
        if (request == null)
            throw new IllegalArgumentException("usage_economics_backfill_request_invalid");
        validate(request.limit(), request.cursorCreatedAt(), request.cursorUsageId());
        Instant cursorTime =
                request.cursorCreatedAt() == null ? FIRST_TIME : request.cursorCreatedAt();
        UUID cursorId = request.cursorUsageId() == null ? FIRST_ID : request.cursorUsageId();
        return service.snapshots(cursorTime, cursorId, request.limit() + 1)
                .collectList()
                .map(fetched -> response(fetched, request.limit()));
    }

    static void validate(int limit, Instant cursorCreatedAt, UUID cursorUsageId) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("usage_economics_backfill_limit_invalid");
        }
        if ((cursorCreatedAt == null) != (cursorUsageId == null)) {
            throw new IllegalArgumentException("usage_economics_backfill_cursor_invalid");
        }
    }

    private static Response response(
            List<UsageEconomicsBackfillService.Snapshot> fetched, int limit) {
        boolean more = fetched.size() > limit;
        List<UsageEconomicsBackfillService.Snapshot> selected =
                more ? fetched.subList(0, limit) : fetched;
        List<UsageEconomicsEvent> events =
                selected.stream()
                        .map(UsageEconomicsBackfillService.Snapshot::event)
                        .map(UsageEconomicsBackfillController::sanitized)
                        .toList();
        List<EventCursor> cursors =
                selected.stream()
                        .map(value -> new EventCursor(value.createdAt(), value.event().eventId()))
                        .toList();
        UsageEconomicsBackfillService.Snapshot next =
                more && !selected.isEmpty() ? selected.getLast() : null;
        return new Response(
                events,
                cursors,
                more,
                next == null ? null : next.createdAt(),
                next == null ? null : next.event().eventId());
    }

    private static UsageEconomicsEvent sanitized(UsageEconomicsEvent event) {
        List<UsageEconomicsEvent.Stage> stages =
                event.stages().stream().map(UsageEconomicsBackfillController::sanitized).toList();
        boolean missing = event.priceMissing() || event.providerPriceId() == null;
        return new UsageEconomicsEvent(
                event.version(),
                event.eventId(),
                event.requestId(),
                event.reservationId(),
                event.tenantId(),
                event.userId(),
                event.packageInstanceId(),
                event.modelCode(),
                event.upstreamModel(),
                event.providerId(),
                event.channelId(),
                event.inputTokens(),
                event.outputTokens(),
                event.totalTokens(),
                event.usageDimension(),
                event.usageUnits(),
                event.billingMode(),
                event.packageSettledTokens(),
                event.packageSettledUnits(),
                event.walletSettledAmount(),
                event.providerPriceId(),
                missing ? null : event.providerCostCurrency(),
                missing ? null : event.providerExchangeRate(),
                missing ? null : event.providerInputCostAmount(),
                missing ? null : event.providerOutputCostAmount(),
                missing ? null : event.providerTotalCostAmount(),
                missing ? null : event.providerOriginalInputCostAmount(),
                missing ? null : event.providerOriginalOutputCostAmount(),
                missing ? null : event.providerOriginalCostAmount(),
                missing ? null : event.providerOriginalCostCurrency(),
                event.usageEstimated(),
                missing,
                stages);
    }

    private static UsageEconomicsEvent.Stage sanitized(UsageEconomicsEvent.Stage stage) {
        boolean missing = stage.priceMissing() || stage.providerPriceId() == null;
        return new UsageEconomicsEvent.Stage(
                stage.stageIndex(),
                stage.stageType(),
                stage.logicalModelCode(),
                stage.upstreamModel(),
                stage.providerId(),
                stage.channelId(),
                stage.inputTokens(),
                stage.outputTokens(),
                stage.totalTokens(),
                stage.providerPriceId(),
                missing ? null : stage.providerCostCurrency(),
                missing ? null : stage.providerExchangeRate(),
                missing ? null : stage.providerInputCostAmount(),
                missing ? null : stage.providerOutputCostAmount(),
                missing ? null : stage.providerTotalCostAmount(),
                missing ? null : stage.providerOriginalInputCostAmount(),
                missing ? null : stage.providerOriginalOutputCostAmount(),
                missing ? null : stage.providerOriginalCostAmount(),
                missing ? null : stage.providerOriginalCostCurrency(),
                stage.usageEstimated(),
                missing,
                stage.usageDimension(),
                stage.usageUnits());
    }

    record Request(int limit, Instant cursorCreatedAt, UUID cursorUsageId) {}

    record EventCursor(Instant createdAt, UUID usageId) {}

    record Response(
            List<UsageEconomicsEvent> events,
            List<EventCursor> eventCursors,
            boolean hasMore,
            Instant nextCreatedAt,
            UUID nextUsageId) {}
}
