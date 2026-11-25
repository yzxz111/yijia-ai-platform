package com.yuzhihuihe.apiplatform.gateway.video;

import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code gateway.video_generation_task} 的持久化。
 *
 * <p>该表是视频计费的幂等锚点。视频生成在任务进入终态时才计费，
 * 而一个任务可能被轮询很多次，因此终态转换是带守卫写入的
 * （{@code WHERE status IN ('SUBMITTED','RUNNING')}），且只有在该写入
 * 报告恰好一行被改动时才会结算。这正是「把一个已完成任务轮询三次只计费一次」的原因。
 *
 * <p>{@code (provider_id, upstream_task_id)} 上的 {@code uq_video_task_upstream}
 * 是第二道守卫：一次被厂商去重的重试提交无法造出第二行
 * 可计费记录。
 *
 * <p>终态写入还会记录厂商的 {@code output_url}，内容端点解析的正是它。
 * 它与状态在同一条带守卫的 UPDATE 中写入，因此任务绝不可能在
 * 并发轮询尚未存入位置时就已经是 SUCCEEDED。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class VideoTaskRepository {

    private final DatabaseClient database;

    VideoTaskRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Long> insert(NewTask task) {
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
                        INSERT INTO gateway.video_generation_task(
                            id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                            package_instance_id, model_code, provider_id, channel_id,
                            upstream_model, resolution_tier, usage_dimension,
                            requested_seconds, reserved_seconds, upstream_task_id, status,
                            submitted_at, expires_at)
                        VALUES (
                            :id, :request_id, :reservation_id, :tenant_id, :user_id, :api_key_id,
                            :package_instance_id, :model_code, :provider_id, :channel_id,
                            :upstream_model, :resolution_tier, 'VIDEO_SECOND',
                            :requested_seconds, :reserved_seconds, :upstream_task_id, :status,
                            now(), :expires_at)
                        """)
                .bind("id", task.id())
                .bind("request_id", task.requestId())
                .bind("reservation_id", task.reservationId())
                .bind("tenant_id", task.tenantId())
                .bind("user_id", task.userId())
                .bind("model_code", task.modelCode())
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("upstream_model", task.upstreamModel())
                .bind("resolution_tier", task.resolutionTier())
                .bind("requested_seconds", task.requestedSeconds())
                .bind("reserved_seconds", task.reservedSeconds())
                .bind("status", task.status())
                .bind("expires_at", task.expiresAt())
                .bind("api_key_id", task.apiKeyId())
                .bind("package_instance_id", task.packageInstanceId());
        insert = task.upstreamTaskId() == null
                ? insert.bindNull("upstream_task_id", String.class)
                : insert.bind("upstream_task_id", task.upstreamTaskId());
        return insert.fetch().rowsUpdated();
    }

    /**
     * 把厂商 task id 附加到一条在外部调用之前就已持久化的提交意图上。
     * null 守卫使重试保持幂等，并防止并发的调用方
     * 替换掉赢得该 request id 的那个厂商任务。
     */
    Mono<Long> attachUpstreamTaskId(String requestId, String upstreamTaskId) {
        return database.sql("""
                        UPDATE gateway.video_generation_task
                        SET upstream_task_id = :upstream_task_id, updated_at = now()
                        WHERE request_id = :request_id
                          AND upstream_task_id IS NULL
                          AND status = 'SUBMITTED'
                        """)
                .bind("request_id", requestId)
                .bind("upstream_task_id", upstreamTaskId)
                .fetch()
                .rowsUpdated();
    }

    Mono<TaskRow> findByRequestId(String requestId) {
        return selectOne("WHERE request_id = :request_id")
                .bind("request_id", requestId)
                .map((row, metadata) -> TaskRow.of(row))
                .one();
    }

    Mono<TaskRow> findByUpstreamTaskId(String upstreamTaskId) {
        return selectOne("WHERE upstream_task_id = :upstream_task_id")
                .bind("upstream_task_id", upstreamTaskId)
                .map((row, metadata) -> TaskRow.of(row))
                .one();
    }

    /** 仍在等待终态的任务，最旧的在前。 */
    Flux<TaskRow> findOpen(int limit) {
        return database.sql(SELECT_COLUMNS + """
                WHERE status IN ('SUBMITTED', 'RUNNING')
                ORDER BY submitted_at
                LIMIT :limit
                """)
                .bind("limit", limit)
                .map((row, metadata) -> TaskRow.of(row))
                .all();
    }

    /**
     * 配额转换未被观测到完成的终态视频任务。
     *
     * <p>终态任务行是视频预留可被补偿的唯一安全证据。
     * 预留状态在同一条查询中检查，因此已结算或已释放的任务
     * 不会被恢复清扫重放。这里有意不包含打开状态的任务：
     * 打开的任务仍可能代表一次已被接受的生成，
     * 释放它会让一次幂等重试买下第二次生成。
     */
    Flux<TaskRow> findTerminalWithReservedQuota(int limit) {
        return database.sql(SELECT_COLUMNS + """
                WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                  AND usage_dimension = 'VIDEO_SECOND'
                  AND EXISTS (
                      SELECT 1
                      FROM gateway.quota_reservation reservation
                      WHERE reservation.id = gateway.video_generation_task.reservation_id
                        AND reservation.status = 'RESERVED'
                  )
                ORDER BY terminal_at, submitted_at
                LIMIT :limit
                """)
                .bind("limit", limit)
                .map((row, metadata) -> TaskRow.of(row))
                .all();
    }

    /**
     * 在向调用方暴露终态结果之前，读取该任务的配额状态。
     *
     * <p>当配额转换在任务更新提交之后失败时，终态任务可能短暂地
     * 领先于它的配额转换。调用方必须同步重试该转换，
     * 而不是收到一段未付费的成功视频。预留缺失属于不变式被破坏，
     * 会作为错误上报，而不是被当作已释放。
     */
    Mono<Boolean> reservationIsReserved(UUID reservationId) {
        return database.sql("""
                        SELECT status = 'RESERVED' AS reserved
                        FROM gateway.quota_reservation
                        WHERE id = :reservation_id
                        """)
                .bind("reservation_id", reservationId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("reserved", Boolean.class)))
                .one()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "video_reservation_missing:" + reservationId)));
    }

    /**
     * 把任务转为终态，但只能从非终态转入。
     *
     * @param outputUrl 厂商为已完成生成给出的位置，其他任何终态下为 null。
     *        {@code ck_video_task_output_url} 会拒绝非成功行上的位置，
     *        因此调用方无法记录一段并不存在的可获取
     *        视频。
     * @return 首次为 1，此后每次轮询都为 0，这正是结算
     *         恰好发生一次的原因
     */
    Mono<Long> completeTerminal(
            String requestId,
            String status,
            int billedSeconds,
            String errorCategory,
            String outputUrl) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.video_generation_task
                        SET status = :status, billed_seconds = :billed_seconds,
                            error_category = :error_category, output_url = :output_url,
                            terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status IN ('SUBMITTED', 'RUNNING')
                        """)
                .bind("status", status)
                .bind("billed_seconds", billedSeconds)
                .bind("request_id", requestId);
        // 成功的任务没有错误分类，而 R2DBC 会拒绝 null 的普通绑定。
        update = errorCategory == null
                ? update.bindNull("error_category", String.class)
                : update.bind("error_category", errorCategory);
        update = outputUrl == null
                ? update.bindNull("output_url", String.class)
                : update.bind("output_url", outputUrl);
        return update.fetch().rowsUpdated();
    }

    /**
     * 关闭一个结算被拒的任务，使一次未计费的生成无法被获取。
     *
     * <p>该任务已被赢得终态转换的调用方标记为 SUCCEEDED，而
     * {@link #completeTerminal} 不会再运行第二次。这里专门收窄到那个状态：
     * 它必须是 SUCCEEDED 且当前没有任何扣费，
     * 因此它绝不会重写一个已成功计费的任务。清除输出
     * 位置正是让 {@code GET /v1/videos/{id}/content} 回答
     * {@code 409 video_output_not_ready}、而不是重定向到一段平台
     * 从未收款过的视频的原因。
     *
     * @return 该任务确实是那条未计费记录时为 1，已经计费过时为 0
     */
    Mono<Long> markSettlementRefused(String requestId) {
        return database.sql("""
                        UPDATE gateway.video_generation_task
                        SET status = 'FAILED', billed_seconds = 0,
                            error_category = 'gateway_settlement_refused',
                            output_url = NULL, terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status = 'SUCCEEDED'
                          AND billed_seconds = 0
                        """)
                .bind("request_id", requestId)
                .fetch()
                .rowsUpdated();
    }

    /** 记录进度，不做任何结算。天然幂等。 */
    Mono<Long> markRunning(String requestId) {
        return database.sql("""
                        UPDATE gateway.video_generation_task
                        SET status = 'RUNNING', updated_at = now()
                        WHERE request_id = :request_id AND status = 'SUBMITTED'
                        """)
                .bind("request_id", requestId)
                .fetch()
                .rowsUpdated();
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                   package_instance_id, model_code, provider_id, channel_id, upstream_model,
                   resolution_tier, requested_seconds, reserved_seconds, billed_seconds,
                   upstream_task_id, status, error_category, output_url,
                   submitted_at, terminal_at, expires_at
            FROM gateway.video_generation_task
            """;

    private DatabaseClient.GenericExecuteSpec selectOne(String where) {
        return database.sql(SELECT_COLUMNS + where);
    }

    record NewTask(
            UUID id,
            String requestId,
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            UUID packageInstanceId,
            String modelCode,
            UUID providerId,
            UUID channelId,
            String upstreamModel,
            String resolutionTier,
            int requestedSeconds,
            int reservedSeconds,
            String upstreamTaskId,
            String status,
            Instant expiresAt) { }

    record TaskRow(
            UUID id,
            String requestId,
            UUID reservationId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            UUID packageInstanceId,
            String modelCode,
            UUID providerId,
            UUID channelId,
            String upstreamModel,
            String resolutionTier,
            int requestedSeconds,
            int reservedSeconds,
            Integer billedSeconds,
            String upstreamTaskId,
            String status,
            String errorCategory,
            String outputUrl,
            Instant submittedAt,
            Instant terminalAt,
            Instant expiresAt) {

        static TaskRow of(io.r2dbc.spi.Row row) {
            return new TaskRow(
                    row.get("id", UUID.class),
                    row.get("request_id", String.class),
                    row.get("reservation_id", UUID.class),
                    row.get("tenant_id", UUID.class),
                    row.get("user_id", UUID.class),
                    row.get("api_key_id", UUID.class),
                    row.get("package_instance_id", UUID.class),
                    row.get("model_code", String.class),
                    row.get("provider_id", UUID.class),
                    row.get("channel_id", UUID.class),
                    row.get("upstream_model", String.class),
                    row.get("resolution_tier", String.class),
                    row.get("requested_seconds", Integer.class),
                    row.get("reserved_seconds", Integer.class),
                    row.get("billed_seconds", Integer.class),
                    row.get("upstream_task_id", String.class),
                    row.get("status", String.class),
                    row.get("error_category", String.class),
                    row.get("output_url", String.class),
                    row.get("submitted_at", Instant.class),
                    row.get("terminal_at", Instant.class),
                    row.get("expires_at", Instant.class));
        }

        boolean terminal() {
            return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                    || "CANCELLED".equals(status);
        }
    }
}
