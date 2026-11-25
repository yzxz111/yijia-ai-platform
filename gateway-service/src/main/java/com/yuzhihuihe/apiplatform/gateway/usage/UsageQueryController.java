package com.yuzhihuihe.apiplatform.gateway.usage;

import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/console/gateway")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageQueryController {

    private final UsageQueryService service;
    private final UsageAnalyticsService analyticsService;
    private final QuotaEntitlementsService entitlementsService;

    public UsageQueryController(
            UsageQueryService service,
            UsageAnalyticsService analyticsService,
            QuotaEntitlementsService entitlementsService) {
        this.service = service;
        this.analyticsService = analyticsService;
        this.entitlementsService = entitlementsService;
    }

    @GetMapping("/quota/summary")
    Mono<UsageQueryService.QuotaSummary> summary(@AuthenticationPrincipal Jwt jwt) {
        return service.summary(tenantId(jwt), userId(jwt));
    }

    /**
     * 账号级套餐权益明细：每个模型的额度与每次调用消耗。
     *
     * <p>额度是余额类数据，因此显式禁止缓存——客户端拿到的是一个会随流量变化的读数，
     * 一个被中间层缓存的旧值会让"还剩多少"直接说错。
     */
    @GetMapping("/quota/entitlements")
    Mono<ResponseEntity<QuotaEntitlementsService.Entitlements>> quotaEntitlements(
            @AuthenticationPrincipal Jwt jwt) {
        return entitlementsService.entitlements(tenantId(jwt), userId(jwt))
                .map(body -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(body));
    }

    @GetMapping("/quota/ledger")
    Mono<UsageQueryService.PageResult<UsageQueryService.LedgerEntry>> ledger(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String entryType) {
        return service.ledger(tenantId(jwt), userId(jwt), page(page, size), modelCode, entryType);
    }

    @GetMapping("/usage")
    Mono<UsageQueryService.PageResult<UsageQueryService.UsageEntry>> usage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.usage(tenantId(jwt), userId(jwt), page(page, size),
                modelCode, outcome, requestId, from, to);
    }

    @GetMapping("/usage/metrics")
    Mono<UsageQueryService.UsageMetrics> usageMetrics(@AuthenticationPrincipal Jwt jwt) {
        return service.metrics(tenantId(jwt), userId(jwt));
    }

    @GetMapping("/usage/analytics")
    Mono<UsageAnalyticsService.UsageAnalytics> analytics(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "30") int days) {
        return analyticsService.userAnalytics(tenantId(jwt), userId(jwt), days);
    }

    @GetMapping("/admin/usage")
    Mono<UsageQueryService.PageResult<UsageQueryService.UsageEntry>> adminUsage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.adminUsage(page(page, size), userId, modelCode, outcome, requestId,
                sort, direction);
    }

    @GetMapping("/admin/analytics")
    Mono<UsageAnalyticsService.GatewayAdminAnalytics> adminAnalytics(
            @RequestParam(defaultValue = "30") int days) {
        return analyticsService.adminAnalytics(days);
    }

    @GetMapping("/admin/quota-ledger")
    Mono<UsageQueryService.PageResult<UsageQueryService.AdminLedgerEntry>> adminLedger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.adminLedger(page(page, size), userId, modelCode, entryType, sort, direction);
    }

    @GetMapping("/admin/reservations")
    Mono<UsageQueryService.PageResult<UsageQueryService.ReservationEntry>> adminReservations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String requestId,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.adminReservations(
                page(page, size), userId, status, requestId, anomalyOnly, sort, direction);
    }

    @GetMapping("/admin/provider-prices")
    Mono<UsageQueryService.PageResult<UsageQueryService.ProviderPriceEntry>> providerPrices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.providerPrices(page(page, size), providerCode, modelCode, sort, direction);
    }

    private UsageQueryService.PageRequest page(int page, int size) {
        return new UsageQueryService.PageRequest(page, size);
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
