package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackageVersionRepository {

    private final JdbcTemplate jdbc;

    PackageVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PublishedPlanRow> findPublishedPlans() {
        return jdbc.query("""
                SELECT DISTINCT ON (p.id)
                       p.code, p.name, p.description, p.kind, p.display_order,
                       v.id, v.version, v.price_amount,
                       v.currency, v.total_token_quota, v.duration_days, v.max_api_keys,
                       v.requests_per_minute, v.tokens_per_minute, v.max_concurrency,
                       v.daily_request_limit, v.max_output_tokens, v.max_file_size_bytes,
                       v.once_per_user, v.estimated_cost_amount, v.estimated_margin_ratio,
                       v.margin_warning, v.stock_total, v.stock_sold, v.stock_reserved,
                       v.low_stock_threshold, v.max_purchases_per_user, v.usage_dimension,
                       v.audio_seconds_quota
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.status = 'ACTIVE' AND v.status = 'PUBLISHED'
                ORDER BY p.id, v.version DESC
                """, (result, rowNumber) -> publishedPlan(result));
    }

    Optional<UUID> lockPlanId(String planCode) {
        return jdbc.query("""
                        SELECT id FROM platform.package_plan WHERE code = ? FOR UPDATE
                        """, (result, rowNumber) -> result.getObject("id", UUID.class), planCode)
                .stream().findFirst();
    }

    Optional<PlanRow> lockPlan(String planCode) {
        return jdbc.query("""
                        SELECT id, code, name, description, kind, status, display_order
                        FROM platform.package_plan
                        WHERE code = ?
                        FOR UPDATE
                        """, (result, rowNumber) -> new PlanRow(
                result.getObject("id", UUID.class),
                result.getString("code"),
                result.getString("name"),
                result.getString("description"),
                result.getString("kind"),
                result.getString("status"),
                result.getInt("display_order")), planCode).stream().findFirst();
    }

    void lockFreeClaimCatalog() {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended('package-plan:free-claim', 0))");
    }

    boolean activeFreeClaimExists(UUID excludedPlanId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.package_plan
                    WHERE kind = 'FREE_CLAIM' AND status = 'ACTIVE'
                      AND (?::uuid IS NULL OR id <> ?::uuid)
                )
                """, Boolean.class, excludedPlanId, excludedPlanId));
    }

    int nextDisplayOrder() {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(max(display_order), 0) + 10 FROM platform.package_plan",
                Integer.class);
        return value == null ? 1000 : value;
    }

    void insertPlan(
            UUID planId,
            String code,
            String name,
            String description,
            String kind,
            int displayOrder) {
        jdbc.update("""
                INSERT INTO platform.package_plan(
                    id, code, name, description, kind, status, display_order)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, planId, code, name, description, kind, displayOrder);
    }

    void insertPlanCreatedAudit(UUID actorUserId, PlanRow plan) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                SELECT tenant_id, ?, 'PACKAGE_PLAN_CREATED', 'PACKAGE_PLAN', ?, 'SUCCESS',
                       jsonb_build_object('after', jsonb_build_object(
                           'planCode', ?, 'name', ?, 'description', CAST(? AS text),
                           'kind', ?, 'status', ?, 'displayOrder', ?))
                FROM platform.app_user WHERE id = ?
                """, actorUserId, plan.id().toString(), plan.code(), plan.name(),
                plan.description(), plan.kind(), plan.status(), plan.displayOrder(), actorUserId);
    }

    void updatePlanDetails(UUID planId, String name, String description) {
        jdbc.update("""
                UPDATE platform.package_plan
                SET name = ?, description = ?, updated_at = now()
                WHERE id = ?
                """, name, description, planId);
    }

    void updatePlanStatus(UUID planId, String status) {
        jdbc.update("""
                UPDATE platform.package_plan
                SET status = ?, updated_at = now()
                WHERE id = ?
                """, status, planId);
    }

    void insertPlanAudit(
            UUID actorUserId,
            PlanRow before,
            String afterName,
            String afterDescription,
            String afterStatus,
            String action) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                SELECT tenant_id, ?, ?, 'PACKAGE_PLAN', ?, 'SUCCESS',
                       jsonb_build_object(
                           'planCode', ?,
                           'before', jsonb_build_object(
                               'name', ?, 'description', CAST(? AS text), 'status', ?),
                           'after', jsonb_build_object(
                               'name', ?, 'description', CAST(? AS text), 'status', ?))
                FROM platform.app_user WHERE id = ?
                """, actorUserId, action, before.id().toString(), before.code(),
                before.name(), before.description(), before.status(), afterName,
                afterDescription, afterStatus, actorUserId);
    }

    long countCatalogPlans(String search, String status) {
        List<Object> arguments = new ArrayList<>();
        String where = catalogWhere(search, status, arguments);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform.package_plan p" + where,
                Long.class,
                arguments.toArray());
        return total == null ? 0 : total;
    }

    List<CatalogPlanRow> findCatalogPlans(
            String search,
            String status,
            int limit,
            long offset) {
        List<Object> arguments = new ArrayList<>();
        String where = catalogWhere(search, status, arguments);
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query("""
                SELECT p.id, p.code, p.name, p.description, p.kind, p.status,
                       p.display_order, p.created_at, p.updated_at
                FROM platform.package_plan p
                """ + where + " ORDER BY p.display_order, lower(p.name), p.code LIMIT ? OFFSET ?",
                (result, rowNumber) -> new CatalogPlanRow(
                        result.getObject("id", UUID.class),
                        result.getString("code"),
                        result.getString("name"),
                        result.getString("description"),
                        result.getString("kind"),
                        result.getString("status"),
                        result.getInt("display_order"),
                        result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("updated_at").toInstant()),
                arguments.toArray());
    }

    List<CatalogVersionRow> findCatalogVersions(List<UUID> planIds) {
        if (planIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(planIds.size(), "?"));
        return jdbc.query("""
                SELECT v.id, v.package_plan_id, v.version, v.status, v.price_amount, v.currency,
                       v.total_token_quota, v.duration_days, v.max_api_keys,
                       v.requests_per_minute, v.tokens_per_minute, v.max_concurrency,
                       v.daily_request_limit, v.max_output_tokens, v.max_file_size_bytes,
                       v.once_per_user, v.estimated_cost_amount, v.estimated_margin_ratio,
                       v.margin_warning, v.stock_total, v.stock_sold, v.stock_reserved,
                       v.low_stock_threshold, v.max_purchases_per_user,
                       (SELECT count(*) FROM platform.package_plan_version_model m
                        WHERE m.package_plan_version_id = v.id) AS model_count,
                       v.published_at, v.retired_at, v.created_at, v.usage_dimension,
                       v.audio_seconds_quota
                FROM platform.package_plan_version v
                WHERE v.package_plan_id IN (""" + placeholders + ")"
                + " ORDER BY v.package_plan_id, v.version DESC",
                (result, rowNumber) -> catalogVersion(result),
                planIds.toArray());
    }

    long countModelReferences(String modelCode) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.package_plan_version_model
                WHERE model_code = ?
                """, Long.class, modelCode);
        return count == null ? 0 : count;
    }

    List<ModelReferenceRow> findModelReferences(String modelCode, int limit) {
        return jdbc.query("""
                SELECT p.code AS plan_code, p.name AS plan_name,
                       v.version, v.status AS version_status
                FROM platform.package_plan_version_model m
                JOIN platform.package_plan_version v ON v.id = m.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                WHERE m.model_code = ?
                ORDER BY lower(p.name), p.code, v.version DESC
                LIMIT ?
                """, (result, rowNumber) -> new ModelReferenceRow(
                        result.getString("plan_code"),
                        result.getString("plan_name"),
                        result.getInt("version"),
                        result.getString("version_status")),
                modelCode, limit);
    }

    private String catalogWhere(String search, String status, List<Object> arguments) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            where.append(" AND p.status = ?");
            arguments.add(status);
        }
        if (search != null) {
            where.append(" AND (lower(p.code) LIKE ? ESCAPE '!'"
                    + " OR lower(p.name) LIKE ? ESCAPE '!')");
            arguments.add(search);
            arguments.add(search);
        }
        return where.toString();
    }

    Integer nextVersion(UUID planId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(max(version), 0) + 1
                FROM platform.package_plan_version
                WHERE package_plan_id = ?
                """, Integer.class, planId);
    }

    void insertVersion(
            UUID versionId,
            UUID planId,
            int version,
            long priceAmount,
            long totalTokenQuota,
            Long audioSecondsQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            BigDecimal targetMargin,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning,
            Long stockTotal,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            String usageDimension) {
        jdbc.update("""
                INSERT INTO platform.package_plan_version(
                    id, package_plan_id, version, price_amount, currency, total_token_quota,
                    audio_seconds_quota,
                    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
                    max_concurrency, target_margin_ratio, status, daily_request_limit,
                    max_output_tokens, max_file_size_bytes, once_per_user,
                    estimated_cost_amount, estimated_margin_ratio, margin_warning,
                    stock_total, low_stock_threshold, max_purchases_per_user,
                    usage_dimension)
                VALUES (?, ?, ?, ?, 'CNY', ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, versionId, planId, version, priceAmount, totalTokenQuota, audioSecondsQuota,
                durationDays,
                maxApiKeys, requestsPerMinute, tokensPerMinute, maxConcurrency, targetMargin,
                dailyRequestLimit, maxOutputTokens, maxFileSizeBytes, oncePerUser,
                estimatedCostAmount, estimatedMarginRatio, marginWarning, stockTotal,
                lowStockThreshold, maxPurchasesPerUser, usageDimension);
    }

    void insertModel(
            UUID id, UUID versionId, String modelCode, Long modelTokenQuota, String capabilities) {
        jdbc.update("""
                INSERT INTO platform.package_plan_version_model(
                    id, package_plan_version_id, model_code, model_token_quota, capabilities)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, id, versionId, modelCode, modelTokenQuota, capabilities);
    }

    /**
     * {@code versionIds} 中有哪些仍处于 PUBLISHED 状态，无论所属套餐方案自身的
     * 状态如何。某个毛利告警若其版本不在该集合中，说明该版本
     * 已不再销售（已被取代或已退役），而这是监控唯一可以在不重新测量的前提下
     * 关闭告警的情形：处于 ARCHIVED 套餐方案下的 PUBLISHED 版本
     * 仍是已发布版本，而归档是可逆的，
     * 因此其告警不予处理。
     */
    Set<UUID> findPublishedVersionIds(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(versionIds.size(), "?"));
        return Set.copyOf(jdbc.query("""
                SELECT id FROM platform.package_plan_version
                WHERE status = 'PUBLISHED' AND id IN (""" + placeholders + ")",
                (result, rowNumber) -> result.getObject("id", UUID.class),
                versionIds.toArray()));
    }

    List<ModelRow> findModels(UUID versionId) {
        return jdbc.query("""
                SELECT model_code, model_token_quota, capabilities::text
                FROM platform.package_plan_version_model
                WHERE package_plan_version_id = ?
                ORDER BY model_code
                """, (result, rowNumber) -> new ModelRow(
                result.getString("model_code"),
                nullableLong(result, "model_token_quota"),
                result.getString("capabilities")), versionId);
    }

    void insertCreatedAudit(
            UUID actorUserId, UUID versionId, String planCode, int version) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                SELECT tenant_id, ?, 'PACKAGE_VERSION_CREATED', 'PACKAGE_VERSION', ?, 'SUCCESS',
                       jsonb_build_object('after', jsonb_build_object(
                           'planCode', ?, 'version', ?, 'status', 'DRAFT'))
                FROM platform.app_user WHERE id = ?
                """, actorUserId, versionId.toString(), planCode, version, actorUserId);
    }

    Optional<DraftRow> lockDraft(String planCode, int version) {
        return jdbc.query("""
                        SELECT v.id, p.id AS plan_id, p.kind, v.price_amount,
                               v.total_token_quota, v.audio_seconds_quota, v.once_per_user,
                               v.usage_dimension, v.tokens_per_minute
                        FROM platform.package_plan p
                        JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                        WHERE p.code = ? AND v.version = ? AND v.status = 'DRAFT'
                        FOR UPDATE OF p, v
                        """, (result, rowNumber) -> new DraftRow(
                result.getObject("id", UUID.class),
                result.getObject("plan_id", UUID.class),
                result.getString("kind"),
                result.getLong("price_amount"),
                result.getLong("total_token_quota"),
                nullableLong(result, "audio_seconds_quota"),
                result.getBoolean("once_per_user"),
                result.getString("usage_dimension"),
                result.getLong("tokens_per_minute")), planCode, version).stream().findFirst();
    }

    void publishVersion(
            UUID versionId,
            Instant publishedAt,
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning) {
        jdbc.update("""
                UPDATE platform.package_plan_version
                SET status = 'PUBLISHED', published_at = ?, estimated_cost_amount = ?,
                    estimated_margin_ratio = ?, margin_warning = ?
                WHERE id = ? AND status = 'DRAFT'
                """, Timestamp.from(publishedAt), estimatedCostAmount,
                estimatedMarginRatio, marginWarning, versionId);
    }

    int retirePublishedVersions(UUID planId, UUID publishedVersionId, Instant retiredAt) {
        return jdbc.update("""
                UPDATE platform.package_plan_version
                SET status = 'RETIRED', retired_at = ?
                WHERE package_plan_id = ? AND id <> ? AND status = 'PUBLISHED'
                """, Timestamp.from(retiredAt), planId, publishedVersionId);
    }

    void insertPublishedAudit(
            UUID actorUserId, UUID versionId, String planCode, int version) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                SELECT tenant_id, ?, 'PACKAGE_VERSION_PUBLISHED', 'PACKAGE_VERSION', ?, 'SUCCESS',
                       jsonb_build_object(
                           'planCode', ?, 'version', ?,
                           'before', jsonb_build_object('status', 'DRAFT'),
                           'after', jsonb_build_object('status', 'PUBLISHED'))
                FROM platform.app_user WHERE id = ?
                """, actorUserId, versionId.toString(), planCode, version, actorUserId);
    }

    private static PublishedPlanRow publishedPlan(ResultSet result) throws SQLException {
        Long stockTotal = nullableLong(result, "stock_total");
        Long remainingStock = stockTotal == null ? null
                : stockTotal - result.getLong("stock_sold") - result.getLong("stock_reserved");
        return new PublishedPlanRow(
                result.getString("code"),
                result.getString("name"),
                result.getString("description"),
                result.getString("kind"),
                result.getInt("display_order"),
                result.getObject("id", UUID.class),
                result.getInt("version"),
                result.getLong("price_amount"),
                result.getString("currency"),
                result.getLong("total_token_quota"),
                result.getInt("duration_days"),
                result.getInt("max_api_keys"),
                result.getInt("requests_per_minute"),
                result.getLong("tokens_per_minute"),
                result.getInt("max_concurrency"),
                nullableInteger(result, "daily_request_limit"),
                nullableInteger(result, "max_output_tokens"),
                result.getLong("max_file_size_bytes"),
                result.getBoolean("once_per_user"),
                nullableLong(result, "estimated_cost_amount"),
                result.getBigDecimal("estimated_margin_ratio"),
                result.getBoolean("margin_warning"),
                stockTotal,
                result.getLong("stock_sold"),
                result.getLong("stock_reserved"),
                result.getLong("low_stock_threshold"),
                nullableInteger(result, "max_purchases_per_user"),
                remainingStock,
                remainingStock != null && remainingStock == 0,
                remainingStock != null && remainingStock > 0
                        && remainingStock <= result.getLong("low_stock_threshold"),
                result.getString("usage_dimension"),
                nullableLong(result, "audio_seconds_quota"));
    }

    private static CatalogVersionRow catalogVersion(ResultSet result) throws SQLException {
        Long stockTotal = nullableLong(result, "stock_total");
        long stockSold = result.getLong("stock_sold");
        long stockReserved = result.getLong("stock_reserved");
        Long remainingStock = stockTotal == null ? null : stockTotal - stockSold - stockReserved;
        long lowStockThreshold = result.getLong("low_stock_threshold");
        return new CatalogVersionRow(
                result.getObject("id", UUID.class),
                result.getObject("package_plan_id", UUID.class),
                result.getInt("version"),
                result.getString("status"),
                result.getLong("price_amount"),
                result.getString("currency"),
                result.getLong("total_token_quota"),
                result.getInt("duration_days"),
                result.getInt("max_api_keys"),
                result.getInt("requests_per_minute"),
                result.getLong("tokens_per_minute"),
                result.getInt("max_concurrency"),
                nullableInteger(result, "daily_request_limit"),
                nullableInteger(result, "max_output_tokens"),
                result.getLong("max_file_size_bytes"),
                result.getBoolean("once_per_user"),
                nullableLong(result, "estimated_cost_amount"),
                result.getBigDecimal("estimated_margin_ratio"),
                result.getBoolean("margin_warning"),
                stockTotal,
                stockSold,
                stockReserved,
                lowStockThreshold,
                nullableInteger(result, "max_purchases_per_user"),
                remainingStock,
                remainingStock != null && remainingStock == 0,
                remainingStock != null && remainingStock > 0 && remainingStock <= lowStockThreshold,
                result.getInt("model_count"),
                timestamp(result, "published_at"),
                timestamp(result, "retired_at"),
                result.getTimestamp("created_at").toInstant(),
                result.getString("usage_dimension"),
                nullableLong(result, "audio_seconds_quota"));
    }

    private static Instant timestamp(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    record ModelRow(String modelCode, Long modelTokenQuota, String capabilities) {
    }

    record ModelReferenceRow(
            String planCode, String planName, int version, String versionStatus) {
    }

    /**
     * 从发布流程视角看到的 DRAFT 版本。
     *
     * <p>{@code tokensPerMinute} 从数据行读取，而不是取自发布调用，
     * 因为被发布的那个速率就是将被强制执行的那个：单位守卫必须
     * 判断已存储的值，而不是调用方对该值的复述。
     */
    record DraftRow(
            UUID id,
            UUID planId,
            String planKind,
            long priceAmount,
            long totalTokenQuota,
            /** 方案 C 的第二份额度；为 null 表示这个草稿不卖语音。 */
            Long audioSecondsQuota,
            boolean oncePerUser,
            String usageDimension,
            long tokensPerMinute) {
    }

    record PlanRow(
            UUID id,
            String code,
            String name,
            String description,
            String kind,
            String status,
            int displayOrder) {
    }

    record CatalogPlanRow(
            UUID id,
            String code,
            String name,
            String description,
            String kind,
            String status,
            int displayOrder,
            Instant createdAt,
            Instant updatedAt) {
    }

    record CatalogVersionRow(
            UUID id,
            UUID planId,
            int version,
            String status,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning,
            Long stockTotal,
            long stockSold,
            long stockReserved,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            Long remainingStock,
            boolean soldOut,
            boolean lowStock,
            int modelCount,
            Instant publishedAt,
            Instant retiredAt,
            Instant createdAt,
            /**
             * TOKEN 或 VIDEO_SECOND/IMAGE。之所以读入管理后台目录，是因为
             * {@code tokensPerMinute} 在两者中表示不同的单位（一个是令牌速率，另一个是
             * 每分钟秒数的上限），因此不带它渲染出的版本行，是一个
             * 读者无法标注其含义的数字 —— 正是这种歧义让 V63 把视频
             * 套餐方案的上限改写成了令牌速率。
             */
            String usageDimension,
            /**
             * 方案 C 的第二份额度（音频秒）；null = 这个版本不卖语音。
             * 目录必须显示它：一个混合版本的语音部分在目录里完全不可见时，
             * 运维会以为那个版本只卖视频秒。
             */
            Long audioSecondsQuota) {
    }

    record PublishedPlanRow(
            String code,
            String name,
            String description,
            String kind,
            int displayOrder,
            UUID versionId,
            int version,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning,
            Long stockTotal,
            long stockSold,
            long stockReserved,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            Long remainingStock,
            boolean soldOut,
            boolean lowStock,
            String usageDimension,
            /**
             * 方案 C 的第二份额度。`totalTokenQuota` 是主维度那份额度（混合版本里
             * 是视频秒），这一个才是音频秒那一份。重算毛利的巡检必须能分别拿到
             * 两者，否则它会用视频秒去摊音频那一半的成本——少算成本，于是
             * 一个音频那半已经亏损的混合版本永远不会触发低毛利告警。
             */
            Long audioSecondsQuota) {
    }
}
