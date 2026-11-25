package com.yuzhihuihe.apiplatform.gateway.usage;

import static com.yuzhihuihe.apiplatform.gateway.usage.UsageQueryRows.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageQueryRepository {

    private static final Map<String, String> USAGE_SORTS = Map.of(
            "createdAt", "usage.created_at",
            "requestId", "usage.request_id",
            "modelCode", "usage.model_code",
            "totalTokens", "usage.total_tokens",
            "durationMs", "usage.duration_ms",
            "outcome", "usage.outcome");
    private static final Map<String, String> LEDGER_SORTS = Map.of(
            "createdAt", "ledger.created_at",
            "requestId", "reservation.request_id",
            "modelCode", "ledger.model_code",
            "entryType", "ledger.entry_type",
            "totalTokenDelta", "ledger.total_token_delta",
            "balanceAfter", "ledger.balance_after");
    private static final Map<String, String> RESERVATION_SORTS = Map.of(
            "createdAt", "reservation.created_at",
            "requestId", "reservation.request_id",
            "modelCode", "reservation.model_code",
            "status", "reservation.status",
            "reservedTokens", "reservation.reserved_tokens",
            "actualTotalTokens", "reservation.actual_total_tokens",
            "uncoveredTokens", "reservation.uncovered_tokens",
            "expiresAt", "reservation.expires_at");
    private static final Map<String, String> PROVIDER_PRICE_SORTS = Map.of(
            "effectiveFrom", "price.effective_from",
            "providerCode", "provider.code",
            "modelCode", "model.code",
            "inputPricePerMillion", "price.input_price_per_million",
            "outputPricePerMillion", "price.output_price_per_million",
            "cacheReadPricePerMillion", "price.cache_read_price_per_million",
            "cacheWritePricePerMillion", "price.cache_write_price_per_million",
            "reasoningPricePerMillion", "price.reasoning_price_per_million",
            "currency", "price.currency");

    private final DatabaseClient databaseClient;

    public UsageQueryRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<SummaryRow> summary(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        // 每个维度一行（V92 之后一个用户最多四个：
        // TOKEN / VIDEO_SECOND / IMAGE / AUDIO_SECOND），而这里刻意不枚举维度：
        // 查询按 usage_dimension 排序取全部行，因此新增一个维度不需要改本查询。
        // 兼容字段 activePackage 取「文本优先，否则唯一那个」，与这次改动之前
        // 每个消费者实际看到的那一个一致。
        Mono<List<ActivePackageRow>> active = databaseClient.sql("""
                        SELECT id, package_code, package_name, status, total_token_quota,
                               reserved_tokens, used_tokens, daily_request_limit,
                               expires_at, allowed_models, usage_dimension,
                               audio_seconds_quota, audio_seconds_reserved, audio_seconds_used
                        FROM gateway.package_instance
                        WHERE tenant_id = :tenantId AND user_id = :userId AND status = 'ACTIVE'
                        ORDER BY usage_dimension, activated_at DESC NULLS LAST, id
                        """)
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .map((row, metadata) -> new ActivePackageRow(
                        row.get("id", UUID.class), row.get("package_code", String.class),
                        row.get("package_name", String.class), row.get("status", String.class),
                        requiredLong(row.get("total_token_quota", Long.class)),
                        requiredLong(row.get("reserved_tokens", Long.class)),
                        requiredLong(row.get("used_tokens", Long.class)),
                        row.get("daily_request_limit", Integer.class),
                        row.get("expires_at", Instant.class),
                        row.get("allowed_models", String.class),
                        row.get("usage_dimension", String.class), List.of(),
                        // 方案 C：第二份额度（音频秒）。为空表示这个套餐不卖语音，
                        // 控制台据此**不渲染**这一块，而不是显示 0 秒。
                        row.get("audio_seconds_quota", Long.class),
                        row.get("audio_seconds_reserved", Long.class),
                        row.get("audio_seconds_used", Long.class)))
                .all()
                .collectList()
                .flatMap(rows -> Flux.fromIterable(rows)
                        .concatMap(value -> modelQuotas(value.packageInstanceId()).collectList()
                                .map(models -> withModels(value, models)))
                        .collectList());
        Mono<List<QueuedPackageRow>> queued = databaseClient.sql("""
                        SELECT id, package_code, package_name, total_token_quota, created_at,
                               usage_dimension
                        FROM gateway.package_instance
                        WHERE tenant_id = :tenantId AND user_id = :userId AND status = 'QUEUED'
                        ORDER BY created_at ASC, id ASC
                        """)
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .map((row, metadata) -> new QueuedPackageRow(
                        row.get("id", UUID.class), row.get("package_code", String.class),
                        row.get("package_name", String.class),
                        requiredLong(row.get("total_token_quota", Long.class)),
                        row.get("created_at", Instant.class),
                        row.get("usage_dimension", String.class)))
                .all().collectList();
        return Mono.zip(active, queued).map(result ->
                new SummaryRow(primaryActivePackage(result.getT1()), result.getT1(), result.getT2()));
    }

    /**
     * 兼容字段的选择规则：文本实例优先（历史上唯一的维度），否则取唯一/第一个实例。
     */
    private static ActivePackageRow primaryActivePackage(List<ActivePackageRow> rows) {
        return rows.stream()
                .filter(row -> "TOKEN".equals(row.usageDimension()))
                .findFirst()
                .orElseGet(() -> rows.isEmpty() ? null : rows.get(0));
    }

    /**
     * 单个套餐实例的实时配额，按实例 id 定位，而不是「该用户当前生效的套餐」：
     * {@code GET /v1/quota} 针对所出示 API key 绑定的那个实例作答，而一个用户可以持有多个
     * 实例（排队中、已过期、已被替换）。查询同时按租户和 id 限定，因此即使 id 被猜中，
     * 某个 key 也永远无法读取另一个租户的实例。
     *
     * <p>列与映射同 {@link #summary}，因为两个接口必须对「剩余」的含义保持一致。
     */
    Mono<Optional<ActivePackageRow>> packageInstanceQuota(UUID tenantId, UUID packageInstanceId) {
        requireIdentity(tenantId, packageInstanceId);
        return databaseClient.sql("""
                        SELECT id, package_code, package_name, status, total_token_quota,
                               reserved_tokens, used_tokens, daily_request_limit,
                               expires_at, allowed_models, usage_dimension,
                               audio_seconds_quota, audio_seconds_reserved, audio_seconds_used
                        FROM gateway.package_instance
                        WHERE tenant_id = :tenantId AND id = :packageInstanceId
                        """)
                .bind("tenantId", tenantId)
                .bind("packageInstanceId", packageInstanceId)
                .map((row, metadata) -> new ActivePackageRow(
                        row.get("id", UUID.class), row.get("package_code", String.class),
                        row.get("package_name", String.class), row.get("status", String.class),
                        requiredLong(row.get("total_token_quota", Long.class)),
                        requiredLong(row.get("reserved_tokens", Long.class)),
                        requiredLong(row.get("used_tokens", Long.class)),
                        row.get("daily_request_limit", Integer.class),
                        row.get("expires_at", Instant.class),
                        row.get("allowed_models", String.class),
                        row.get("usage_dimension", String.class), List.of(),
                        row.get("audio_seconds_quota", Long.class),
                        row.get("audio_seconds_reserved", Long.class),
                        row.get("audio_seconds_used", Long.class)))
                .one()
                .flatMap(value -> modelQuotas(value.packageInstanceId()).collectList()
                        .map(models -> Optional.of(withModels(value, models))))
                .defaultIfEmpty(Optional.empty());
    }

    Mono<PageRows<LedgerRow>> ledger(
            UUID tenantId, UUID userId, PageSpec page, String modelCode, String entryType) {
        requireIdentity(tenantId, userId);
        PageSpec valid = page;
        String where = """
                FROM gateway.quota_ledger ledger
                JOIN gateway.quota_reservation reservation ON reservation.id = ledger.reservation_id
                WHERE reservation.tenant_id = :tenantId AND reservation.user_id = :userId
                  AND (:modelCode IS NULL OR ledger.model_code = :modelCode)
                  AND (:entryType IS NULL OR ledger.entry_type = :entryType)
                """;
        DatabaseClient.GenericExecuteSpec items = databaseClient.sql("""
                        SELECT ledger.id, reservation.request_id, ledger.reservation_id,
                               ledger.package_instance_id, ledger.model_code, ledger.entry_type,
                               ledger.total_token_delta, ledger.model_token_delta,
                               ledger.balance_after, ledger.model_balance_after, ledger.created_at
                        """ + where + " ORDER BY ledger.created_at DESC, ledger.id DESC LIMIT :limit OFFSET :offset")
                .bind("tenantId", tenantId).bind("userId", userId)
                .bind("limit", valid.size()).bind("offset", valid.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + where)
                .bind("tenantId", tenantId).bind("userId", userId);
        items = bindNullable(items, "modelCode", modelCode, String.class);
        items = bindNullable(items, "entryType", entryType, String.class);
        count = bindNullable(count, "modelCode", modelCode, String.class);
        count = bindNullable(count, "entryType", entryType, String.class);
        Mono<List<LedgerRow>> rows = items.map((row, metadata) -> new LedgerRow(
                row.get("id", UUID.class), row.get("request_id", String.class),
                row.get("reservation_id", UUID.class), row.get("package_instance_id", UUID.class),
                row.get("model_code", String.class), row.get("entry_type", String.class),
                requiredLong(row.get("total_token_delta", Long.class)),
                row.get("model_token_delta", Long.class),
                requiredLong(row.get("balance_after", Long.class)),
                row.get("model_balance_after", Long.class), row.get("created_at", Instant.class)))
                .all().collectList();
        return page(rows, count, valid);
    }

    Mono<PageRows<UsageRow>> usage(
            UUID tenantId, UUID userId, PageSpec page,
            String modelCode, String outcome, String requestId) {
        return usage(tenantId, userId, page, modelCode, outcome, requestId, null, null);
    }

    Mono<PageRows<UsageRow>> usage(
            UUID tenantId, UUID userId, PageSpec page,
            String modelCode, String outcome, String requestId, Instant from, Instant to) {
        requireIdentity(tenantId, userId);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "usage time range is invalid");
        }
        return usagePage(page, tenantId, userId, modelCode, outcome, requestId,
                from, to, " ORDER BY usage.created_at DESC, usage.id DESC", false);
    }

    Mono<UsageMetricsRow> metrics(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        return databaseClient.sql("""
                        SELECT count(*) AS sample_count,
                               COALESCE(
                                   percentile_disc(0.95) WITHIN GROUP (
                                       ORDER BY platform_added_latency_ms),
                                   0
                               ) AS platform_added_p95_ms
                        FROM gateway.usage_log
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND created_at >= now() - interval '24 hours'
                          AND platform_added_latency_ms IS NOT NULL
                        """)
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .map((row, metadata) -> new UsageMetricsRow(
                        requiredLong(row.get("sample_count", Long.class)),
                        requiredLong(row.get("platform_added_p95_ms", Long.class))))
                .one();
    }

    Mono<PageRows<UsageRow>> adminUsage(
            PageSpec page, UUID userId, String modelCode, String outcome, String requestId,
            String sort, String direction) {
        PageSpec valid = page;
        String ordering = order(sort, direction, USAGE_SORTS, "createdAt", "DESC",
                " ORDER BY usage.created_at DESC, usage.id DESC", "usage.id");
        return usagePage(valid, null, userId, modelCode, outcome, requestId,
                null, null, ordering, true);
    }

    Mono<PageRows<AdminLedgerRow>> adminLedger(
            PageSpec page, UUID userId, String modelCode, String entryType,
            String sort, String direction) {
        PageSpec valid = page;
        String ordering = order(sort, direction, LEDGER_SORTS, "createdAt", "DESC",
                " ORDER BY ledger.created_at DESC, ledger.id DESC", "ledger.id");
        String where = """
                FROM gateway.quota_ledger ledger
                JOIN gateway.quota_reservation reservation ON reservation.id = ledger.reservation_id
                WHERE (:userId IS NULL OR reservation.user_id = :userId)
                  AND (:modelCode IS NULL OR ledger.model_code = :modelCode)
                  AND (:entryType IS NULL OR ledger.entry_type = :entryType)
                """;
        DatabaseClient.GenericExecuteSpec items = databaseClient.sql("""
                        SELECT ledger.id, reservation.request_id, reservation.tenant_id,
                               reservation.user_id, ledger.reservation_id,
                               ledger.package_instance_id, ledger.model_code, ledger.entry_type,
                               ledger.total_token_delta, ledger.model_token_delta,
                               ledger.balance_after, ledger.model_balance_after, ledger.created_at
                        """ + where + ordering + " LIMIT :limit OFFSET :offset")
                .bind("limit", valid.size()).bind("offset", valid.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + where);
        items = bindNullable(items, "userId", userId, UUID.class);
        items = bindNullable(items, "modelCode", modelCode, String.class);
        items = bindNullable(items, "entryType", entryType, String.class);
        count = bindNullable(count, "userId", userId, UUID.class);
        count = bindNullable(count, "modelCode", modelCode, String.class);
        count = bindNullable(count, "entryType", entryType, String.class);
        Mono<List<AdminLedgerRow>> rows = items.map((row, metadata) -> new AdminLedgerRow(
                row.get("id", UUID.class), row.get("request_id", String.class),
                row.get("tenant_id", UUID.class), row.get("user_id", UUID.class),
                row.get("reservation_id", UUID.class), row.get("package_instance_id", UUID.class),
                row.get("model_code", String.class), row.get("entry_type", String.class),
                requiredLong(row.get("total_token_delta", Long.class)),
                row.get("model_token_delta", Long.class),
                requiredLong(row.get("balance_after", Long.class)),
                row.get("model_balance_after", Long.class), row.get("created_at", Instant.class)))
                .all().collectList();
        return page(rows, count, valid);
    }

    Mono<PageRows<ReservationRow>> adminReservations(
            PageSpec page, UUID userId, String status, String requestId, boolean anomalyOnly,
            String sort, String direction) {
        PageSpec valid = page;
        String ordering = order(sort, direction, RESERVATION_SORTS, "createdAt", "DESC",
                " ORDER BY reservation.created_at DESC, reservation.id DESC", "reservation.id");
        String where = """
                FROM gateway.quota_reservation reservation
                WHERE (:userId IS NULL OR reservation.user_id = :userId)
                  AND (:status IS NULL OR reservation.status = :status)
                  AND (:requestId IS NULL OR reservation.request_id = :requestId)
                  AND (:anomalyOnly = false OR reservation.uncovered_tokens > 0)
                """;
        DatabaseClient.GenericExecuteSpec items = databaseClient.sql("""
                        SELECT reservation.id, reservation.request_id, reservation.tenant_id,
                               reservation.user_id, reservation.api_key_id,
                               reservation.package_instance_id, reservation.model_code,
                               reservation.reserved_tokens, reservation.actual_input_tokens,
                               reservation.actual_output_tokens, reservation.actual_total_tokens,
                               reservation.uncovered_tokens, reservation.usage_estimated, reservation.status,
                               reservation.expires_at, reservation.settled_at,
                               reservation.released_at, reservation.created_at
                        """ + where + ordering + " LIMIT :limit OFFSET :offset")
                .bind("limit", valid.size()).bind("offset", valid.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + where);
        items = bindNullable(items, "userId", userId, UUID.class);
        items = bindNullable(items, "status", status, String.class);
        items = bindNullable(items, "requestId", requestId, String.class);
        items = items.bind("anomalyOnly", anomalyOnly);
        count = bindNullable(count, "userId", userId, UUID.class);
        count = bindNullable(count, "status", status, String.class);
        count = bindNullable(count, "requestId", requestId, String.class);
        count = count.bind("anomalyOnly", anomalyOnly);
        Mono<List<ReservationRow>> rows = items.map((row, metadata) -> new ReservationRow(
                row.get("id", UUID.class), row.get("request_id", String.class),
                row.get("tenant_id", UUID.class), row.get("user_id", UUID.class),
                row.get("api_key_id", UUID.class), row.get("package_instance_id", UUID.class),
                row.get("model_code", String.class), requiredLong(row.get("reserved_tokens", Long.class)),
                row.get("actual_input_tokens", Long.class), row.get("actual_output_tokens", Long.class),
                row.get("actual_total_tokens", Long.class),
                requiredLong(row.get("uncovered_tokens", Long.class)),
                Boolean.TRUE.equals(row.get("usage_estimated", Boolean.class)),
                row.get("status", String.class), row.get("expires_at", Instant.class),
                row.get("settled_at", Instant.class), row.get("released_at", Instant.class),
                row.get("created_at", Instant.class)))
                .all().collectList();
        return page(rows, count, valid);
    }

    Mono<PageRows<ProviderPriceRow>> providerPrices(
            PageSpec page, String providerCode, String modelCode,
            String sort, String direction) {
        PageSpec valid = page;
        String ordering = order(sort, direction, PROVIDER_PRICE_SORTS, "effectiveFrom", "DESC",
                " ORDER BY price.effective_from DESC, provider.code ASC, model.code ASC, price.id ASC",
                "price.id");
        String where = """
                FROM gateway.provider_model_price price
                JOIN gateway.provider provider ON provider.id = price.provider_id
                JOIN gateway.model model ON model.id = price.model_id
                WHERE (:providerCode IS NULL OR provider.code = :providerCode)
                  AND (:modelCode IS NULL OR model.code = :modelCode)
                """;
        DatabaseClient.GenericExecuteSpec items = databaseClient.sql("""
                        SELECT price.id, provider.code AS provider_code, model.code AS model_code,
                               price.input_price_per_million, price.output_price_per_million,
                               price.cache_read_price_per_million,
                               price.cache_write_price_per_million,
                               price.reasoning_price_per_million,
                               price.currency, price.internal_exchange_rate, price.official_source,
                               price.effective_from, price.effective_to
                        """ + where + ordering + " LIMIT :limit OFFSET :offset")
                .bind("limit", valid.size()).bind("offset", valid.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + where);
        items = bindNullable(items, "providerCode", providerCode, String.class);
        items = bindNullable(items, "modelCode", modelCode, String.class);
        count = bindNullable(count, "providerCode", providerCode, String.class);
        count = bindNullable(count, "modelCode", modelCode, String.class);
        Mono<List<ProviderPriceRow>> rows = items.map((row, metadata) -> new ProviderPriceRow(
                row.get("id", UUID.class), row.get("provider_code", String.class),
                row.get("model_code", String.class),
                requiredLong(row.get("input_price_per_million", Long.class)),
                requiredLong(row.get("output_price_per_million", Long.class)),
                row.get("cache_read_price_per_million", Long.class),
                row.get("cache_write_price_per_million", Long.class),
                row.get("reasoning_price_per_million", Long.class),
                row.get("currency", String.class), row.get("internal_exchange_rate", BigDecimal.class),
                row.get("official_source", String.class), row.get("effective_from", Instant.class),
                row.get("effective_to", Instant.class)))
                .all().collectList();
        return page(rows, count, valid);
    }

    private Mono<PageRows<UsageRow>> usagePage(
            PageSpec page, UUID tenantId, UUID userId,
            String modelCode, String outcome, String requestId,
            Instant from, Instant to, String ordering,
            boolean includeStages) {
        String where = """
                FROM gateway.usage_log usage
                LEFT JOIN gateway.api_key api_key ON api_key.id = usage.api_key_id
                LEFT JOIN gateway.provider provider ON provider.id = usage.provider_id
                LEFT JOIN gateway.channel channel ON channel.id = usage.channel_id
                WHERE (:tenantId IS NULL OR usage.tenant_id = :tenantId)
                  AND (:userId IS NULL OR usage.user_id = :userId)
                  AND (:modelCode IS NULL OR usage.model_code = :modelCode)
                  AND (:outcome IS NULL OR usage.outcome = :outcome)
                  AND (:requestId IS NULL OR usage.request_id = :requestId)
                  AND (:fromTime IS NULL OR usage.created_at >= :fromTime)
                  AND (:toTime IS NULL OR usage.created_at < :toTime)
                """;
        DatabaseClient.GenericExecuteSpec items = databaseClient.sql("""
                        SELECT usage.id, usage.request_id, usage.reservation_id, usage.tenant_id,
                               usage.user_id, usage.api_key_id, api_key.key_prefix,
                               usage.package_instance_id, usage.model_code,
                               provider.code AS provider_code, channel.code AS channel_code,
                               usage.upstream_model,
                               usage.input_tokens, usage.output_tokens, usage.total_tokens,
                               usage.usage_dimension, usage.usage_units,
                               usage.usage_estimated, usage.stream, usage.first_token_ms,
                               usage.duration_ms, usage.http_status, usage.outcome,
                               usage.error_category, usage.error_summary,
                               usage.generation_status, usage.finish_reason,
                               usage.effective_max_output_tokens, usage.ceiling_source,
                               usage.attempt_count,
                               usage.created_at
                        """ + where + ordering + " LIMIT :limit OFFSET :offset")
                .bind("limit", page.size()).bind("offset", page.offset());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql("SELECT count(*) " + where);
        items = bindUsageFilters(items, tenantId, userId, modelCode, outcome, requestId, from, to);
        count = bindUsageFilters(count, tenantId, userId, modelCode, outcome, requestId, from, to);
        Mono<List<UsageRow>> rows = items.map((row, metadata) -> new UsageRow(
                row.get("id", UUID.class), row.get("request_id", String.class),
                row.get("reservation_id", UUID.class), row.get("tenant_id", UUID.class),
                row.get("user_id", UUID.class), row.get("api_key_id", UUID.class),
                row.get("key_prefix", String.class), row.get("package_instance_id", UUID.class),
                row.get("model_code", String.class), row.get("provider_code", String.class),
                row.get("channel_code", String.class), row.get("upstream_model", String.class),
                requiredLong(row.get("input_tokens", Long.class)),
                requiredLong(row.get("output_tokens", Long.class)),
                requiredLong(row.get("total_tokens", Long.class)),
                row.get("usage_dimension", String.class),
                requiredLong(row.get("usage_units", Long.class)),
                Boolean.TRUE.equals(row.get("usage_estimated", Boolean.class)),
                Boolean.TRUE.equals(row.get("stream", Boolean.class)),
                row.get("first_token_ms", Long.class), requiredLong(row.get("duration_ms", Long.class)),
                requiredInt(row.get("http_status", Integer.class)), row.get("outcome", String.class),
                row.get("error_category", String.class), row.get("error_summary", String.class),
                row.get("generation_status", String.class),
                row.get("finish_reason", String.class),
                row.get("effective_max_output_tokens", Long.class),
                row.get("ceiling_source", String.class),
                row.get("attempt_count", Integer.class),
                row.get("created_at", Instant.class), List.of(), null))
                .all().collectList();
        return page(enrich(rows, includeStages), count, page);
    }

    /**
     * 给这一页的用量行补上两张附表：调用阶段，以及真实扣量。
     *
     * <p><b>两张附表都是按当页的 ID 批量回查，而不是 JOIN 进主查询。</b>
     * 主查询的 {@code count(*)} 与分页偏移都建立在 {@code usage_log} 一行一条之上；
     * 用 LEFT JOIN 挂账本会让"多条 SETTLE 记录"这种数据问题变成用量行的重复，
     * 从而把该次调用在列表里显示两遍、并让 total 变大。这里先分页、再按主键补数据，
     * {@code (reservation_id, entry_type)} 上的唯一约束又保证一次预留每种账本行至多一条，
     * 因此补数据在结构上不可能改变行数。
     */
    private Mono<List<UsageRow>> enrich(Mono<List<UsageRow>> rows, boolean includeStages) {
        return rows.flatMap(entries -> {
            if (entries.isEmpty()) {
                return Mono.just(entries);
            }
            Mono<List<UsageChargeRow>> charges = quotaCharges(entries);
            Mono<List<UsageRow>> staged = includeStages
                    ? enrichWithStages(entries)
                    : Mono.just(entries);
            return Mono.zip(charges, staged)
                    .map(loaded -> attach(entries, loaded.getT1(), loaded.getT2()));
        });
    }

    private static List<UsageRow> attach(
            List<UsageRow> entries, List<UsageChargeRow> charges, List<UsageRow> staged) {
        Map<UUID, UsageChargeRow> chargesByReservation = new HashMap<>();
        charges.forEach(charge -> chargesByReservation.put(charge.reservationId(), charge));
        return staged.stream()
                .map(entry -> entry.withQuotaCharge(
                        entry.reservationId() == null
                                ? null
                                : chargesByReservation.get(entry.reservationId())))
                .toList();
    }

    /**
     * 这一页用量行各自关联的预占／结算记录。
     *
     * <p>取数完全按协议走：套餐实扣读预留行的 {@code package_settled_tokens}、钱包实扣读
     * {@code wallet_settled_amount}、状态与时间读 {@code status} / {@code settled_at}；
     * 只有"模型子额度实扣"要读账本，因为预留行上根本没有这个数——它只记在 SETTLE 行的
     * 差额里。
     *
     * <p>两个 {@code bool_or} 各自回答一个独立问题，且都只看账本、不看当前配置：
     * {@code has_reserve_model_delta} 是"这次消费当初是否占用了独立模型额度"
     * （RELEASED/EXPIRED 行要据此决定报 0 还是 null），{@code has_settle_ledger}
     * 是"结算差额是否真的记账了"（否则模型实扣只能是未知）。两者都不能用
     * {@code package_instance_model_quota} 现在的样子代替：那条额度行今天可能已经
     * 被删掉或改过，而账本是当时写下的。
     *
     * <p>{@code settle_model_delta} 取 {@code max(...)} 只是为了让分组查询里恰好有一个
     * 值；唯一约束保证每组至多一行，因此它不是聚合语义，也不会掩盖多行。
     */
    private Mono<List<UsageChargeRow>> quotaCharges(List<UsageRow> entries) {
        UUID[] reservationIds = entries.stream()
                .map(UsageRow::reservationId)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(UUID[]::new);
        if (reservationIds.length == 0) {
            return Mono.just(List.of());
        }
        return databaseClient.sql("""
                        SELECT reservation.id, reservation.status,
                               reservation.package_reserved_tokens,
                               reservation.package_settled_tokens,
                               reservation.wallet_settled_amount,
                               reservation.settled_at,
                               bool_or(ledger.entry_type = 'RESERVE'
                                       AND ledger.model_token_delta IS NOT NULL)
                                   AS has_reserve_model_delta,
                               bool_or(ledger.entry_type = 'SETTLE') AS has_settle_ledger,
                               max(CASE WHEN ledger.entry_type = 'SETTLE'
                                        THEN ledger.model_token_delta END)
                                   AS settle_model_delta
                        FROM gateway.quota_reservation reservation
                        LEFT JOIN gateway.quota_ledger ledger
                               ON ledger.reservation_id = reservation.id
                        WHERE reservation.id = ANY(:reservation_ids)
                        GROUP BY reservation.id, reservation.status,
                                 reservation.package_reserved_tokens,
                                 reservation.package_settled_tokens,
                                 reservation.wallet_settled_amount,
                                 reservation.settled_at
                        """)
                .bind("reservation_ids", reservationIds)
                .map((row, metadata) -> new UsageChargeRow(
                        row.get("id", UUID.class),
                        row.get("status", String.class),
                        requiredLong(row.get("package_reserved_tokens", Long.class)),
                        requiredLong(row.get("package_settled_tokens", Long.class)),
                        requiredLong(row.get("wallet_settled_amount", Long.class)),
                        row.get("settled_at", Instant.class),
                        Boolean.TRUE.equals(row.get("has_reserve_model_delta", Boolean.class)),
                        Boolean.TRUE.equals(row.get("has_settle_ledger", Boolean.class)),
                        row.get("settle_model_delta", Long.class)))
                .all().collectList();
    }

    private Mono<List<UsageRow>> enrichWithStages(List<UsageRow> entries) {
        if (entries.isEmpty()) {
            return Mono.just(entries);
        }
        UUID[] usageIds = entries.stream().map(UsageRow::id).toArray(UUID[]::new);
        return databaseClient.sql("""
                        SELECT stage.usage_log_id, stage.stage_index, stage.stage_type,
                               stage.logical_model_code, provider.code AS provider_code,
                               channel.code AS channel_code, stage.upstream_model,
                               stage.input_tokens, stage.output_tokens, stage.total_tokens,
                               stage.duration_ms, stage.outcome, stage.provider_cost_amount,
                               stage.provider_cost_currency, stage.price_estimated
                        FROM gateway.usage_stage stage
                        JOIN gateway.provider provider ON provider.id = stage.provider_id
                        JOIN gateway.channel channel ON channel.id = stage.channel_id
                        WHERE stage.usage_log_id = ANY(:usage_ids)
                        ORDER BY stage.usage_log_id, stage.stage_index
                        """)
                .bind("usage_ids", usageIds)
                .map((row, metadata) -> new IndexedUsageStageRow(
                        row.get("usage_log_id", UUID.class),
                        new UsageStageRow(
                                requiredInt(row.get("stage_index", Integer.class)),
                                row.get("stage_type", String.class),
                                row.get("logical_model_code", String.class),
                                row.get("provider_code", String.class),
                                row.get("channel_code", String.class),
                                row.get("upstream_model", String.class),
                                requiredLong(row.get("input_tokens", Long.class)),
                                requiredLong(row.get("output_tokens", Long.class)),
                                requiredLong(row.get("total_tokens", Long.class)),
                                requiredLong(row.get("duration_ms", Long.class)),
                                row.get("outcome", String.class),
                                row.get("provider_cost_amount", Long.class),
                                row.get("provider_cost_currency", String.class),
                                Boolean.TRUE.equals(row.get("price_estimated", Boolean.class)))))
                .all()
                .collectList()
                .map(stages -> {
                    Map<UUID, List<UsageStageRow>> byUsageId = new HashMap<>();
                    stages.forEach(stage -> byUsageId
                            .computeIfAbsent(stage.usageLogId(), ignored -> new ArrayList<>())
                            .add(stage.entry()));
                    return entries.stream()
                            .map(entry -> entry.withStages(
                                    byUsageId.getOrDefault(entry.id(), List.of())))
                            .toList();
                });
    }

    private DatabaseClient.GenericExecuteSpec bindUsageFilters(
            DatabaseClient.GenericExecuteSpec spec, UUID tenantId, UUID userId,
            String modelCode, String outcome, String requestId, Instant from, Instant to) {
        spec = bindNullable(spec, "tenantId", tenantId, UUID.class);
        spec = bindNullable(spec, "userId", userId, UUID.class);
        spec = bindNullable(spec, "modelCode", modelCode, String.class);
        spec = bindNullable(spec, "outcome", outcome, String.class);
        spec = bindNullable(spec, "requestId", requestId, String.class);
        spec = bindNullable(spec, "fromTime", from, Instant.class);
        return bindNullable(spec, "toTime", to, Instant.class);
    }

    private Flux<ModelQuotaRow> modelQuotas(UUID packageInstanceId) {
        return databaseClient.sql("""
                        SELECT model_code,
                               -- 一个维度一个计数列，因此读该行实际携带的那一列。
                               -- 只读 token_quota 会让按张（IMAGE）与按秒
                               -- （VIDEO_SECOND）计费的别名报出总额 0，而同一行的
                               -- reserved_tokens / used_tokens 却是真实的张数或秒数
                               -- ——客户在 /v1/quota 上会看到一个用掉了几十张、
                               -- 总额却是 0 的模型。ck_model_quota_values 保证
                               -- 两列恰好只有一个非空，所以 COALESCE 没有歧义。
                               -- QuotaService 的准入查询早已这样做了
                               -- （QuotaReservationRepository.lockModelQuota），
                               -- 这里是同一个修复在报表侧缺失的另一半。
                               COALESCE(token_quota, seconds_quota) AS effective_quota,
                               reserved_tokens, used_tokens,
                               -- 这一行计的是哪个桶。合并两个计数列之后单位就丢了，
                               -- 而客户端要按单位渲染（积分 / 秒 / 张），因此必须一并读出。
                               usage_dimension
                        FROM gateway.package_instance_model_quota
                        WHERE package_instance_id = :packageId ORDER BY model_code
                        """)
                .bind("packageId", packageInstanceId)
                .map((row, metadata) -> {
                    long total = requiredLong(row.get("effective_quota", Long.class));
                    long reserved = requiredLong(row.get("reserved_tokens", Long.class));
                    long used = requiredLong(row.get("used_tokens", Long.class));
                    return new ModelQuotaRow(
                            row.get("model_code", String.class),
                            row.get("usage_dimension", String.class), total, reserved, used);
                }).all();
    }

    private ActivePackageRow withModels(ActivePackageRow value, List<ModelQuotaRow> models) {
        return new ActivePackageRow(
                value.packageInstanceId(), value.packageCode(), value.packageName(), value.status(),
                value.totalTokens(), value.reservedTokens(), value.usedTokens(),
                value.dailyRequestLimit(), value.expiresAt(), value.allowedModelsJson(),
                value.usageDimension(), models,
                value.audioSecondsQuota(), value.audioSecondsReserved(), value.audioSecondsUsed());
    }

    private <T> Mono<PageRows<T>> page(
            Mono<List<T>> rows, DatabaseClient.GenericExecuteSpec count, PageSpec page) {
        Mono<Long> total = count.map((row, metadata) -> requiredLong(row.get(0, Long.class))).one();
        return Mono.zip(rows, total).map(result ->
                new PageRows<>(result.getT1(), result.getT2(), page.page(), page.size()));
    }

    private String order(
            String requestedSort, String requestedDirection, Map<String, String> allowed,
            String defaultSort, String defaultDirection, String defaultOrder, String tieBreaker) {
        boolean defaultRequest = blank(requestedSort) && blank(requestedDirection);
        String sort = blank(requestedSort) ? defaultSort : requestedSort.strip();
        String column = allowed.get(sort);
        if (column == null) {
            throw invalid("invalid_sort");
        }
        String direction = blank(requestedDirection)
                ? defaultDirection : requestedDirection.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) {
            throw invalid("invalid_sort_direction");
        }
        return defaultRequest ? defaultOrder
                : " ORDER BY " + column + " " + direction + ", " + tieBreaker + " " + direction;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_identity");
        }
    }

    private static long requiredLong(Long value) {
        return value == null ? 0 : value;
    }

    private static int requiredInt(Integer value) {
        return value == null ? 0 : value;
    }
}
