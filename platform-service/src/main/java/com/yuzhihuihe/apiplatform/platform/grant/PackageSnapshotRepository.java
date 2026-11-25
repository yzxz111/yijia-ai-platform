package com.yuzhihuihe.apiplatform.platform.grant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackageSnapshotRepository {

    private final JdbcTemplate jdbc;

    PackageSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PackageVersionRow> findPublishedVersion(UUID packageVersionId) {
        return jdbc
                .query(
                        """
                        SELECT v.id, p.code, p.name, p.kind, v.price_amount, v.currency,
                               v.total_token_quota, v.duration_days, v.max_api_keys,
                               v.requests_per_minute, v.tokens_per_minute, v.max_concurrency,
                               v.daily_request_limit, v.max_output_tokens, v.max_file_size_bytes,
                               v.once_per_user, v.usage_dimension, v.audio_seconds_quota
                        FROM platform.package_plan_version v
                        JOIN platform.package_plan p ON p.id = v.package_plan_id
                        WHERE v.id = ? AND v.status = 'PUBLISHED' AND p.status = 'ACTIVE'
                        FOR SHARE OF v, p
                        """,
                        this::packageVersionRow,
                        packageVersionId)
                .stream()
                .findFirst();
    }

    /**
     * 每个模型的**合法单位**。
     *
     * <p>方案 C 之前这里不需要维度：一个版本只卖一种单位，因此网关可以从
     * 版本级那一列反推出每个模型的口径。混合版本（视频秒 + 音频秒）打破了这个
     * 前提——同一个版本里，视频别名按视频秒计费、音频别名按音频秒计费，
     * 而网关没有 {@code platform.model_usage_dimension} 这张表，无法自己推断。
     *
     * <p>不带上它的后果是**静默的**：网关会把每个模型的额度行都写成版本的主维度
     * （视频秒），于是一次 ASR 请求要么被 {@code usage_dimension_mismatch} 拒绝，
     * 要么——如果维度判断被放宽——去扣视频秒，即 1:1 混用两个相差三个数量级的
     * 单位。这正是它必须随权益一起下发、而不是留给网关去猜的原因。
     *
     * <p>缺省是 {@code TOKEN}，与 {@code resolveDimension}
     * （PackageVersionService:86）以及平台 V54 的读取口径完全一致：一个没有登记
     * 维度的模型按 token 卖。
     */
    List<ModelPermissionRow> findModelPermissions(UUID packageVersionId) {
        return jdbc.query(
                """
                SELECT model.model_code, model.model_token_quota, model.capabilities::text,
                       COALESCE(dimension.usage_dimension, 'TOKEN') AS usage_dimension
                FROM platform.package_plan_version_model model
                LEFT JOIN platform.model_usage_dimension dimension
                  ON dimension.model_code = model.model_code
                WHERE model.package_plan_version_id = ?
                ORDER BY model.model_code
                """,
                (result, rowNumber) ->
                        new ModelPermissionRow(
                                result.getString("model_code"),
                                nullableLong(result, "model_token_quota"),
                                result.getString("capabilities"),
                                result.getString("usage_dimension")),
                packageVersionId);
    }

    private PackageVersionRow packageVersionRow(ResultSet result, int rowNumber)
            throws SQLException {
        return new PackageVersionRow(
                result.getObject("id", UUID.class),
                result.getString("code"),
                result.getString("name"),
                result.getString("kind"),
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
                result.getString("usage_dimension"),
                nullableLong(result, "audio_seconds_quota"));
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    /**
     * @param usageDimension TOKEN 或 VIDEO_SECOND/IMAGE。之所以显式携带，是因为
     *        网关必须拒绝用量维度与模型能力不一致的套餐，而网关自身无法
     *        重新推导该维度：本服务持有 {@code platform.model_usage_dimension}，
     *        网关既没有这张表，也没有从快照推断该维度的方法。省略它会静默地
     *        把每次权益发放都默认为 TOKEN，于是每个视频套餐都在激活时被
     *        以 {@code package_dimension_capability_mismatch} 拒绝，而此时客户
     *        已经完成付款。
     */
    record PackageVersionRow(
            UUID packageVersionId,
            String packageCode,
            String packageName,
            String packageKind,
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
            String usageDimension,
            /**
             * 方案 C 的第二份额度：只有当 {@code usageDimension} 是
             * {@code VIDEO_SECOND} 且该版本同时卖了音频秒时才有值。
             * 它随快照一路走到 {@code gateway.package_instance.audio_seconds_quota}，
             * 因为网关必须能独立地耗尽音频秒而不动视频秒。
             */
            Long audioSecondsQuota) {}

    record ModelPermissionRow(
            String modelCode, Long modelTokenQuota, String capabilitiesJson,
            /**
             * 该模型自己的单位（platform.model_usage_dimension，缺省 TOKEN）。
             * 它必须逐模型下发：混合版本里视频别名与音频别名的单位不同，
             * 而网关没有这张表。
             */
            String usageDimension) {}
}
