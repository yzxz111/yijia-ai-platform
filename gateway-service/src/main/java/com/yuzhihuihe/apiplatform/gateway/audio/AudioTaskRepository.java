package com.yuzhihuihe.apiplatform.gateway.audio;

import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code gateway.audio_generation_task} 的持久化。
 *
 * <p>该表是音频计费的幂等锚点，与 {@code image_generation_task} 同形——
 * 包括它覆盖**同步**能力这一点：语音转写与配音都是一次请求一次响应，
 * 预留与结算在同一次调用内完成，因此它们没有可轮询的任务。
 * 这张表存在的唯一理由是：客户端在超时后重试时，必须拿回已经付过费的那次调用，
 * 而不是再买一次。{@code request_id} 上的唯一约束加上带守卫的终态写入
 * 使这一点成立。
 *
 * <p>换口型（{@code LIP_SYNC}）是三者中唯一的异步能力，它额外使用
 * {@code upstream_task_id} 与 {@code RUNNING} 状态；本仓库对它只提供
 * 落库与推进状态的能力，轮询与恢复扫描属于服务层与独立的作业。
 *
 * <p>终态写入同时记录厂商返回的产物，重放解析的正是它。
 * 它与状态在同一条带守卫的 UPDATE 中写入，因此一行绝不可能在结果尚未存入时
 * 就已经是 SUCCEEDED——那会让一次重放交付一个空结果。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class AudioTaskRepository {

    /** 三个能力共用的判别值，与 V94 的 {@code ck_audio_task_kind} 一一对应。 */
    static final String KIND_TRANSCRIPTION = "TRANSCRIPTION";
    static final String KIND_SYNTHESIS = "SYNTHESIS";
    static final String KIND_LIP_SYNC = "LIP_SYNC";

    private final DatabaseClient database;

    AudioTaskRepository(DatabaseClient database) {
        this.database = database;
    }

    /**
     * 创建幂等锚点。
     *
     * <p>在调用非幂等的厂商 API **之前**执行。若插入在唯一键竞争中落败
     * （rowsUpdated = 0），调用方就是一次重放，绝不能向上游提交。
     */
    Mono<Long> insert(NewTask task) {
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
                        INSERT INTO gateway.audio_generation_task(
                            id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                            package_instance_id, model_code, provider_id, channel_id,
                            upstream_model, usage_dimension, kind,
                            requested_seconds, reserved_seconds,
                            status, submitted_at, expires_at)
                        VALUES (
                            :id, :request_id, :reservation_id, :tenant_id, :user_id, :api_key_id,
                            :package_instance_id, :model_code, :provider_id, :channel_id,
                            :upstream_model, 'AUDIO_SECOND', :kind,
                            :requested_seconds, :reserved_seconds,
                            :status, now(), :expires_at)
                        """)
                .bind("id", task.id())
                .bind("request_id", task.requestId())
                .bind("reservation_id", task.reservationId())
                .bind("tenant_id", task.tenantId())
                .bind("user_id", task.userId())
                .bind("package_instance_id", task.packageInstanceId())
                .bind("model_code", task.modelCode())
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("upstream_model", task.upstreamModel())
                .bind("kind", task.kind())
                .bind("requested_seconds", task.requestedSeconds())
                .bind("reserved_seconds", task.reservedSeconds())
                .bind("status", task.status())
                .bind("expires_at", task.expiresAt());
        // 钱包 key 没有套餐实例，而 R2DBC 拒绝 null 的普通绑定。
        insert = task.apiKeyId() == null
                ? insert.bindNull("api_key_id", UUID.class)
                : insert.bind("api_key_id", task.apiKeyId());
        insert = task.packageInstanceId() == null
                ? insert.bindNull("package_instance_id", UUID.class)
                : insert.bind("package_instance_id", task.packageInstanceId());
        return insert.fetch().rowsUpdated();
    }

    Mono<TaskRow> findByRequestId(String requestId) {
        return database.sql(SELECT_COLUMNS + "WHERE request_id = :request_id")
                .bind("request_id", requestId)
                .map((row, metadata) -> TaskRow.of(row))
                .one();
    }

    Mono<TaskRow> findById(UUID id) {
        return database.sql(SELECT_COLUMNS + "WHERE id = :id")
                .bind("id", id)
                .map((row, metadata) -> TaskRow.of(row))
                .one();
    }

    /**
     * 记录厂商已接受一个异步作业。
     *
     * <p>只对 {@code LIP_SYNC} 有意义的推进，因此收窄到那个 kind 与非终态。
     * {@code ck_audio_task_upstream_kind} 会在数据库侧再拒一次——
     * 两处都答，是因为一处是约定、一处是结构。
     */
    Mono<Long> markRunning(
            String requestId, String upstreamTaskId, String outputUrl) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.audio_generation_task
                        SET status = 'RUNNING',
                            upstream_task_id = :upstream_task_id,
                            output_url = :output_url,
                            updated_at = now()
                        WHERE request_id = :request_id
                          AND kind = 'LIP_SYNC'
                          AND status = 'SUBMITTED'
                        """)
                .bind("request_id", requestId)
                .bind("upstream_task_id", upstreamTaskId);
        update = outputUrl == null
                ? update.bindNull("output_url", String.class)
                : update.bind("output_url", outputUrl);
        return update.fetch().rowsUpdated();
    }

    /**
     * 把一次生成转为终态，但只能从非终态转入。
     *
     * @param resultPayload 厂商结果的 JSON 文本，非成功或载荷过大无法镜像时为 null。
     *        {@code ck_audio_task_result} 会拒绝非成功行上的产物，
     *        因此调用方无法记录一次并不存在的生成
     * @param outputUrl 产出的可交付位置，没有时为 null
     * @return 首次为 1，此后每次重放都为 0，这正是结算恰好发生一次的原因
     */
    Mono<Long> completeTerminal(
            String requestId,
            String status,
            int billedSeconds,
            String errorCategory,
            String resultPayload,
            String outputUrl) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.audio_generation_task
                        SET status = :status, billed_seconds = :billed_seconds,
                            error_category = :error_category,
                            result_payload = CAST(:result_payload AS jsonb),
                            output_url = :output_url,
                            terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status IN ('SUBMITTED', 'RUNNING')
                        """)
                .bind("status", status)
                .bind("billed_seconds", billedSeconds)
                .bind("request_id", requestId);
        // 成功的一次调用没有错误分类，而 R2DBC 会拒绝 null 的普通绑定。
        update = errorCategory == null
                ? update.bindNull("error_category", String.class)
                : update.bind("error_category", errorCategory);
        update = resultPayload == null
                ? update.bindNull("result_payload", String.class)
                : update.bind("result_payload", resultPayload);
        update = outputUrl == null
                ? update.bindNull("output_url", String.class)
                : update.bind("output_url", outputUrl);
        return update.fetch().rowsUpdated();
    }

    /**
     * 关闭一个结算被拒的作业，使一次未计费的产出无法被交付。
     *
     * <p>该作业已被赢得终态转换的调用方标记为 SUCCEEDED，而
     * {@link #completeTerminal} 不会再运行第二次。这里专门收窄到那个状态：
     * 它必须是 SUCCEEDED 且当前没有任何扣费，因此它绝不会重写一个已成功
     * 计费的作业。清除产物正是让一次重放回答
     * {@code audio_generation_previously_failed}、而不是交付一段平台
     * 从未收款过的视频的原因。
     *
     * <p>对同步能力而言这个窗口不可达——它们的结算发生在同一次调用里——
     * 但换口型是异步的：胜出者可能在转换之后、扣费之前失败，
     * 而那正是这条路径存在的理由。
     *
     * @return 该作业确实是那条未计费记录时为 1，已经计费过时为 0
     */
    Mono<Long> markSettlementRefused(String requestId) {
        return database.sql("""
                        UPDATE gateway.audio_generation_task
                        SET status = 'FAILED', billed_seconds = 0,
                            error_category = 'gateway_settlement_refused',
                            result_payload = NULL, output_url = NULL,
                            terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status = 'SUCCEEDED'
                          AND billed_seconds = 0
                        """)
                .bind("request_id", requestId)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 尚未到终态、且即将或已经过期的作业，供恢复清扫使用。
     *
     * <p>按 {@code expires_at} 升序：最紧急的排在最前，因此即使一次清扫的批次
     * 装不下全部，先被处理的也是那些预留最快被释放的行。命中
     * {@code ix_audio_task_open} 那个部分索引。
     */
    Flux<TaskRow> openTasksAboutToExpire(int limit) {
        return database.sql(SELECT_COLUMNS + """
                        WHERE status IN ('SUBMITTED', 'RUNNING')
                        ORDER BY expires_at
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, metadata) -> TaskRow.of(row))
                .all();
    }

    /**
     * 为一次已经不可能是成功结果的作业释放预留，把它落到 FAILED。
     *
     * <p>收窄到非终态，因此它绝不会重写一个已经结算过的作业——
     * 那正是「与实时轮询竞争时仍只结算一次」这条保证的依赖。
     *
     * @return 该作业确实是非终态时为 1，已经到终态时为 0
     */
    Mono<Long> markExpired(String requestId, String errorCategory) {
        return database.sql("""
                        UPDATE gateway.audio_generation_task
                        SET status = 'FAILED', billed_seconds = 0,
                            error_category = :error_category,
                            terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status IN ('SUBMITTED', 'RUNNING')
                        """)
                .bind("request_id", requestId)
                .bind("error_category", errorCategory)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 在向调用方交付终态结果之前，读取该次调用的配额状态。
     *
     * <p>当配额转换在任务更新提交之后失败时，终态行可能短暂地领先于它的配额
     * 转换——尤其是同一个幂等键的并发重放，可能在赢家完成扣费之前就读到
     * SUCCEEDED。调用方必须同步重试该转换，而不是交付一份未付费的结果。
     * 预留缺失属于不变式被破坏，会作为错误上报，而不是被当作已释放。
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
                        "audio_reservation_missing:" + reservationId)));
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                   package_instance_id, model_code, provider_id, channel_id, upstream_model,
                   usage_dimension, kind, requested_seconds, reserved_seconds, billed_seconds,
                   upstream_task_id, result_payload::text AS result_payload, output_url,
                   status, error_category, submitted_at, terminal_at, expires_at
            FROM gateway.audio_generation_task
            """;

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
            String kind,
            int requestedSeconds,
            int reservedSeconds,
            String status,
            Instant expiresAt) {

        NewTask {
            if (id == null || requestId == null || requestId.isBlank()
                    || reservationId == null || tenantId == null || userId == null
                    || modelCode == null || modelCode.isBlank()
                    || providerId == null || channelId == null
                    || upstreamModel == null || upstreamModel.isBlank()
                    || kind == null || kind.isBlank()
                    || requestedSeconds <= 0 || reservedSeconds <= 0
                    || status == null || status.isBlank()
                    || expiresAt == null) {
                throw new IllegalArgumentException("invalid_audio_task");
            }
        }
    }

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
            String usageDimension,
            String kind,
            int requestedSeconds,
            int reservedSeconds,
            Integer billedSeconds,
            String upstreamTaskId,
            String resultPayload,
            String outputUrl,
            String status,
            String errorCategory,
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
                    row.get("usage_dimension", String.class),
                    row.get("kind", String.class),
                    row.get("requested_seconds", Integer.class),
                    row.get("reserved_seconds", Integer.class),
                    row.get("billed_seconds", Integer.class),
                    row.get("upstream_task_id", String.class),
                    row.get("result_payload", String.class),
                    row.get("output_url", String.class),
                    row.get("status", String.class),
                    row.get("error_category", String.class),
                    row.get("submitted_at", Instant.class),
                    row.get("terminal_at", Instant.class),
                    row.get("expires_at", Instant.class));
        }

        boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }

        boolean terminal() {
            return "SUCCEEDED".equals(status)
                    || "FAILED".equals(status)
                    || "CANCELLED".equals(status);
        }

        /** 已结算的秒数，未结算时为 0。 */
        int chargedSeconds() {
            return billedSeconds == null ? 0 : billedSeconds;
        }
    }
}
