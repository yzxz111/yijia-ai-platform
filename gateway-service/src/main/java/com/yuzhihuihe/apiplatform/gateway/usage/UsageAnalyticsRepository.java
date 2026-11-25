package com.yuzhihuihe.apiplatform.gateway.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class UsageAnalyticsRepository {

    private final DatabaseClient database;

    UsageAnalyticsRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<UsageAnalyticsService.UsageSummary> summary(
            Instant todayStart,
            Instant latencyStart,
            Instant fromInstant,
            Instant toInstant,
            UUID tenantId,
            UUID userId) {
        Scope scope = new Scope(tenantId, userId);
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                SELECT
                    count(*) FILTER (WHERE created_at >= :todayStart) AS today_requests,
                    count(*) FILTER (WHERE created_at >= :todayStart
                        AND outcome = 'SUCCEEDED') AS today_successful_requests,
                    count(*) FILTER (WHERE created_at >= :todayStart
                        AND outcome IS DISTINCT FROM 'SUCCEEDED') AS today_failed_requests,
                    COALESCE(sum(total_tokens) FILTER (
                        WHERE created_at >= :todayStart), 0) AS today_tokens,
                    count(*) AS period_requests,
                    COALESCE(sum(total_tokens), 0) AS period_tokens,
                    COALESCE(percentile_disc(0.95) WITHIN GROUP (
                        ORDER BY platform_added_latency_ms) FILTER (
                            WHERE created_at >= :latencyStart
                              AND platform_added_latency_ms IS NOT NULL), 0)
                        AS platform_added_p95_ms
                FROM gateway.usage_log
                WHERE created_at >= :fromInstant AND created_at < :toInstant
                """ + scope.filter())
                .bind("todayStart", todayStart)
                .bind("latencyStart", latencyStart)
                .bind("fromInstant", fromInstant)
                .bind("toInstant", toInstant);
        return bindScope(spec, scope)
                .map((row, metadata) -> new UsageAnalyticsService.UsageSummary(
                        number(row.get("today_requests")),
                        number(row.get("today_successful_requests")),
                        number(row.get("today_failed_requests")),
                        number(row.get("today_tokens")),
                        number(row.get("period_requests")),
                        number(row.get("period_tokens")),
                        number(row.get("platform_added_p95_ms"))))
                .one();
    }

    Mono<List<UsageAnalyticsService.DailyUsage>> daily(
            LocalDate from,
            LocalDate to,
            Instant fromInstant,
            Instant toInstant,
            UUID tenantId,
            UUID userId) {
        Scope scope = new Scope(tenantId, userId);
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                WITH days AS (
                    SELECT generate_series(
                        CAST(:fromDate AS date), CAST(:toDate AS date),
                        interval '1 day')::date AS day
                ), facts AS (
                    SELECT (created_at AT TIME ZONE 'Asia/Shanghai')::date AS day,
                           count(*) AS request_count,
                           COALESCE(sum(input_tokens), 0) AS input_tokens,
                           COALESCE(sum(output_tokens), 0) AS output_tokens,
                           COALESCE(sum(total_tokens), 0) AS total_tokens
                    FROM gateway.usage_log
                    WHERE created_at >= :fromInstant AND created_at < :toInstant
                """ + scope.filter() + """
                    GROUP BY 1
                )
                SELECT days.day, COALESCE(facts.request_count, 0) AS request_count,
                       COALESCE(facts.input_tokens, 0) AS input_tokens,
                       COALESCE(facts.output_tokens, 0) AS output_tokens,
                       COALESCE(facts.total_tokens, 0) AS total_tokens
                FROM days LEFT JOIN facts ON facts.day = days.day
                ORDER BY days.day
                """)
                .bind("fromDate", from)
                .bind("toDate", to)
                .bind("fromInstant", fromInstant)
                .bind("toInstant", toInstant);
        return bindScope(spec, scope)
                .map((row, metadata) -> new UsageAnalyticsService.DailyUsage(
                        row.get("day", LocalDate.class),
                        number(row.get("request_count")),
                        number(row.get("input_tokens")),
                        number(row.get("output_tokens")),
                        number(row.get("total_tokens"))))
                .all()
                .collectList();
    }

    Mono<List<UsageAnalyticsService.ModelUsage>> models(
            Instant fromInstant,
            Instant toInstant,
            UUID tenantId,
            UUID userId) {
        Scope scope = new Scope(tenantId, userId);
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                SELECT usage.model_code, provider.code AS provider_code,
                       usage.upstream_model, count(*) AS request_count,
                       COALESCE(sum(usage.total_tokens), 0) AS total_tokens
                FROM gateway.usage_log usage
                LEFT JOIN gateway.provider provider ON provider.id = usage.provider_id
                WHERE usage.created_at >= :fromInstant AND usage.created_at < :toInstant
                """ + scope.qualifiedFilter("usage") + """
                GROUP BY usage.model_code, provider.code, usage.upstream_model
                ORDER BY request_count DESC, total_tokens DESC,
                         usage.model_code, provider.code, usage.upstream_model NULLS LAST
                """)
                .bind("fromInstant", fromInstant)
                .bind("toInstant", toInstant);
        return bindScope(spec, scope)
                .map((row, metadata) -> new UsageAnalyticsService.ModelUsage(
                        row.get("model_code", String.class),
                        row.get("provider_code", String.class),
                        row.get("upstream_model", String.class),
                        number(row.get("request_count")),
                        number(row.get("total_tokens"))))
                .all()
                .collectList();
    }

    Mono<List<UsageAnalyticsService.PackageDistribution>> packages() {
        return database.sql("""
                        SELECT package_code, package_name, count(*) AS active_count
                        FROM gateway.package_instance
                        WHERE status = 'ACTIVE'
                        GROUP BY package_code, package_name
                        ORDER BY active_count DESC, package_code, package_name
                        """)
                .map((row, metadata) -> new UsageAnalyticsService.PackageDistribution(
                        row.get("package_code", String.class),
                        row.get("package_name", String.class),
                        number(row.get("active_count"))))
                .all()
                .collectList();
    }

    private static DatabaseClient.GenericExecuteSpec bindScope(
            DatabaseClient.GenericExecuteSpec spec, Scope scope) {
        if (scope.isAdmin()) {
            return spec;
        }
        return spec.bind("tenantId", scope.tenantId()).bind("userId", scope.userId());
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private record Scope(UUID tenantId, UUID userId) {
        boolean isAdmin() {
            return tenantId == null;
        }

        String filter() {
            return isAdmin() ? "" : "\n AND tenant_id = :tenantId AND user_id = :userId\n";
        }

        String qualifiedFilter(String alias) {
            return isAdmin() ? "" : "\n AND " + alias + ".tenant_id = :tenantId AND "
                    + alias + ".user_id = :userId\n";
        }
    }
}
