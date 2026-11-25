package com.yuzhihuihe.apiplatform.gateway.usage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageAnalyticsService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<Integer> ALLOWED_DAY_RANGES = Set.of(7, 30);

    private final UsageAnalyticsRepository repository;
    private final Clock clock;

    @Autowired
    UsageAnalyticsService(UsageAnalyticsRepository repository) {
        this(repository, Clock.systemUTC());
    }

    UsageAnalyticsService(UsageAnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<UsageAnalytics> userAnalytics(
            UUID tenantId, UUID userId, int days) {
        requireIdentity(tenantId, userId);
        Bounds bounds = bounds(days);
        Scope scope = new Scope(tenantId, userId);
        return Mono.zip(summary(bounds, scope), daily(bounds, scope), models(bounds, scope))
                .map(result -> new UsageAnalytics(
                        bounds.range(), result.getT1(), result.getT2(), result.getT3()));
    }

    public Mono<GatewayAdminAnalytics> adminAnalytics(int days) {
        Bounds bounds = bounds(days);
        Scope scope = Scope.administrator();
        return Mono.zip(summary(bounds, scope), daily(bounds, scope),
                        models(bounds, scope), repository.packages())
                .map(result -> new GatewayAdminAnalytics(
                        bounds.range(), result.getT1(), result.getT2(), result.getT3(),
                        result.getT4()));
    }

    private Mono<UsageSummary> summary(Bounds bounds, Scope scope) {
        return repository.summary(
                bounds.todayStart(),
                bounds.now().minusSeconds(24 * 60 * 60),
                bounds.fromInstant(),
                bounds.toInstant(),
                scope.tenantId(),
                scope.userId());
    }

    private Mono<List<DailyUsage>> daily(Bounds bounds, Scope scope) {
        return repository.daily(
                bounds.from(),
                bounds.to(),
                bounds.fromInstant(),
                bounds.toInstant(),
                scope.tenantId(),
                scope.userId());
    }

    private Mono<List<ModelUsage>> models(Bounds bounds, Scope scope) {
        return repository.models(
                bounds.fromInstant(),
                bounds.toInstant(),
                scope.tenantId(),
                scope.userId());
    }

    private Bounds bounds(int days) {
        if (!ALLOWED_DAY_RANGES.contains(days)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_days");
        }
        Instant now = clock.instant();
        LocalDate to = LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate from = to.minusDays(days - 1L);
        Instant fromInstant = from.atStartOfDay(SHANGHAI).toInstant();
        Instant todayStart = to.atStartOfDay(SHANGHAI).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        return new Bounds(days, from, to, fromInstant, todayStart, toInstant, now);
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_identity");
        }
    }

    private record Bounds(
            int days, LocalDate from, LocalDate to, Instant fromInstant,
            Instant todayStart, Instant toInstant, Instant now) {
        AnalyticsRange range() {
            return new AnalyticsRange(days, SHANGHAI.getId(), from, to);
        }
    }

    private record Scope(UUID tenantId, UUID userId) {
        static Scope administrator() {
            return new Scope(null, null);
        }

    }

    public record AnalyticsRange(
            int days, String timezone, LocalDate from, LocalDate to) { }

    public record UsageSummary(
            long todayRequests, long todaySuccessfulRequests, long todayFailedRequests,
            long todayTokens, long periodRequests, long periodTokens,
            long platformAddedP95Ms) { }

    public record DailyUsage(
            LocalDate date, long requestCount, long inputTokens,
            long outputTokens, long totalTokens) { }

    public record ModelUsage(
            String modelCode, String providerCode, String upstreamModel,
            long requestCount, long totalTokens) { }

    public record PackageDistribution(
            String packageCode, String packageName, long activeCount) { }

    public record UsageAnalytics(
            AnalyticsRange range, UsageSummary summary,
            List<DailyUsage> daily, List<ModelUsage> models) { }

    public record GatewayAdminAnalytics(
            AnalyticsRange range, UsageSummary summary,
            List<DailyUsage> daily, List<ModelUsage> models,
            List<PackageDistribution> packages) { }
}
