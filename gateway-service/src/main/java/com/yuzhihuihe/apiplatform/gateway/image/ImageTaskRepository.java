package com.yuzhihuihe.apiplatform.gateway.image;

import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code gateway.image_generation_task} 的持久化。
 *
 * <p>该表是生图计费的幂等锚点。图片生成是同步的——一次请求一次响应，
 * 预留与结算在同一次调用内完成——因此这里没有可轮询的任务。
 * 这张表存在的第一个理由是：客户端在超时后重试时，必须拿回已经付过费的那次生成，
 * 而不是再买一次。{@code request_id} 上的唯一约束加上带守卫的终态写入
 * 使这一点成立。
 *
 * <p>第二个理由是**收尾**：正因为同步，一次中断（读超时、连接重置、进程死掉）
 * 会让网关永远不知道自己那笔钱花没花出去。这条行是唯一还记得那件事的东西，
 * 所以恢复扫描读的也是它——见 {@link #openTasks(int)}。
 *
 * <p>终态写入同时记录厂商返回的 {@code data} 数组，重放解析的正是它。
 * 它与状态在同一条带守卫的 UPDATE 中写入，因此一行绝不可能在
 * 结果尚未存入时就已经是 SUCCEEDED。
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ImageTaskRepository {

    private final DatabaseClient database;

    ImageTaskRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Long> insert(NewTask task) {
        return database.sql("""
                        INSERT INTO gateway.image_generation_task(
                            id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                            package_instance_id, model_code, provider_id, channel_id,
                            upstream_model, resolution_tier, usage_dimension,
                            requested_images, reserved_images, requested_size, response_format,
                            status, layer_decomposition, submitted_at, expires_at,
                            fanout_parent_request_id, fanout_index)
                        VALUES (
                            :id, :request_id, :reservation_id, :tenant_id, :user_id, :api_key_id,
                            :package_instance_id, :model_code, :provider_id, :channel_id,
                            :upstream_model, :resolution_tier, 'IMAGE',
                            :requested_images, :reserved_images, :requested_size, :response_format,
                            :status, :layer_decomposition, now(), :expires_at,
                            :fanout_parent_request_id, :fanout_index)
                        """)
                .bind("id", task.id())
                .bind("request_id", task.requestId())
                .bind("reservation_id", task.reservationId())
                .bind("tenant_id", task.tenantId())
                .bind("user_id", task.userId())
                .bind("api_key_id", task.apiKeyId())
                .bind("package_instance_id", task.packageInstanceId())
                .bind("model_code", task.modelCode())
                .bind("provider_id", task.providerId())
                .bind("channel_id", task.channelId())
                .bind("upstream_model", task.upstreamModel())
                .bind("resolution_tier", task.resolutionTier())
                .bind("requested_images", task.requestedImages())
                .bind("reserved_images", task.reservedImages())
                .bind("requested_size", task.requestedSize())
                .bind("response_format", task.responseFormat())
                .bind("status", task.status())
                // 结算按这条记录做，因此图层标记与张数同时写入（网关迁移 V82）。
                .bind("layer_decomposition", task.layerDecomposition())
                .bind("expires_at", task.expiresAt())
                // 扇出链接（网关迁移 V114）：单图请求两个都是 null，因此 n=1 路径
                // 插入的行与 V114 之前逐字节相同。两者要么都给、要么都不给——
                // ck_image_task_fanout_pair 会拒绝半条链接。
                //
                // 必须绑定任务上的**真实值**，而不是无条件的 null：后者会让每一个
                // 扇出子任务都落成"没有父请求"的孤儿行，而家族查询正是靠这两列把一次
                // 请求的 N 张图找回来的。孤儿行不会报错，只会让重放返回不完整的结果。
                //
                // 用空的 Parameter 而不是 bindNull：绑定值本身可能为 null（单图请求
                // 就是这样），而 bindNull 只会写入 null，那样扇出的每一行都会丢掉
                // 自己的父请求。Parameter.empty 让「没有值」与「值为 null」在这里
                // 表达同一件事——这一列本来就可空。
                .bind("fanout_parent_request_id",
                        Parameter.fromOrEmpty(task.fanoutParentRequestId(), String.class))
                .bind("fanout_index", Parameter.fromOrEmpty(task.fanoutIndex(), Integer.class))
                .fetch()
                .rowsUpdated();
    }

    /**
     * 一次扇出请求（网关迁移 V114）的全部子任务，按张序返回。
     *
     * <p>重放与恢复扫描都读它：重放要拿回同一批图片而不是再买一次，恢复扫描要能
     * 看到一家人。{@code n = 1} 的请求没有子任务，因此这个方法对它们返回空。
     */
    Flux<TaskRow> findByFanoutParent(String parentRequestId) {
        return database.sql(SELECT_COLUMNS
                        + " WHERE fanout_parent_request_id = :parent ORDER BY fanout_index")
                .bind("parent", parentRequestId)
                .map((row, metadata) -> TaskRow.of(row))
                .all();
    }

    Mono<TaskRow> findByRequestId(String requestId) {
        return database.sql(SELECT_COLUMNS + "WHERE request_id = :request_id")
                .bind("request_id", requestId)
                .map((row, metadata) -> TaskRow.of(row))
                .one();
    }

    /**
     * 把生成转为终态，但只能从非终态转入。
     *
     * @param resultData 厂商返回的 OpenAI 形态 {@code data} 数组的 JSON 文本，
     *        非成功或载荷过大无法镜像时为 null。{@code ck_image_task_result}
     *        会拒绝非成功行上的图片，因此调用方无法记录一次并不存在的生成。
     * @param resultImages 实际返回的张数，非成功时为 null
     * @return 首次为 1，此后每次重放都为 0，这正是结算恰好发生一次的原因
     */
    Mono<Long> completeTerminal(
            String requestId,
            String status,
            int billedImages,
            String errorCategory,
            String resultData,
            Integer resultImages) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.image_generation_task
                        SET status = :status, billed_images = :billed_images,
                            error_category = :error_category,
                            result_data = CAST(:result_data AS jsonb),
                            result_images = :result_images,
                            terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status = 'SUBMITTED'
                        """)
                .bind("status", status)
                .bind("billed_images", billedImages)
                .bind("request_id", requestId);
        // 成功的生成没有错误分类，而 R2DBC 会拒绝 null 的普通绑定。
        update = errorCategory == null
                ? update.bindNull("error_category", String.class)
                : update.bind("error_category", errorCategory);
        update = resultData == null
                ? update.bindNull("result_data", String.class)
                : update.bind("result_data", resultData);
        update = resultImages == null
                ? update.bindNull("result_images", Integer.class)
                : update.bind("result_images", resultImages);
        return update.fetch().rowsUpdated();
    }

    /**
     * 关闭一个结算被拒的任务，使一次未计费的生成无法被交付。
     *
     * <p>该任务已被赢得终态转换的调用方标记为 SUCCEEDED，而
     * {@link #completeTerminal} 不会再运行第二次。这里专门收窄到那个状态：
     * 它必须是 SUCCEEDED 且当前没有任何扣费，因此它绝不会重写一个已成功
     * 计费的任务。清除结果正是让一次重放回答
     * {@code image_generation_previously_failed}、而不是交付一批平台
     * 从未收款过的图片的原因。
     *
     * @return 该任务确实是那条未计费记录时为 1，已经计费过时为 0
     */
    Mono<Long> markSettlementRefused(String requestId) {
        return database.sql("""
                        UPDATE gateway.image_generation_task
                        SET status = 'FAILED', billed_images = 0,
                            error_category = 'gateway_settlement_refused',
                            result_data = NULL, result_images = NULL,
                            terminal_at = now(), updated_at = now()
                        WHERE request_id = :request_id
                          AND status = 'SUCCEEDED'
                          AND billed_images = 0
                        """)
                .bind("request_id", requestId)
                .fetch()
                .rowsUpdated();
    }

    /**
     * 在向调用方交付终态结果之前，读取该次生成的配额状态。
     *
     * <p>当配额转换在任务更新提交之后失败时，终态行可能短暂地领先于
     * 它的配额转换——尤其是同一个幂等键的并发重放，可能在赢家完成扣费之前
     * 就读到 SUCCEEDED。调用方必须同步重试该转换，而不是交付一批
     * 未付费的图片。预留缺失属于不变式被破坏，会作为错误上报，
     * 而不是被当作已释放。
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
                        "image_reservation_missing:" + reservationId)));
    }

    /**
     * 读取仍然开放（非终态）的图片任务，最老的优先。
     *
     * <p><b>只取 {@code SUBMITTED}。</b>终态行不需要恢复：它们的预留要么已结算、
     * 要么已释放，而一次终态重放拿回的正是当初那个答案。
     *
     * <p><b>并且排除扇出父行锚点（{@code requested_images > 1}）。</b>
     * 那一行是一次扇出请求的幂等锚点，它自己不买任何东西——每一张图都在子任务行上，
     * 各有各的预留。它**永远停在 SUBMITTED**（{@code ck_image_task_result} 要求
     * SUCCEEDED 的行必须有 {@code result_images > 0}，而父行不携带图片），因此这里
     * 必须显式把它排除掉：
     *
     * <p>否则恢复扫描会把这条锚点当成"一张还没跑完的图"，进而去访问它那一列
     * {@code reservation_id}（父行指向第一个子任务的预留）。那条预留属于**一张真实的
     * 图**，可能还在飞；扫描会按"结果未知"去释放它——把一次客户已经付过费、厂商
     * 也确实产出了的生成白送出去，正是恢复路径必须避免的那类事故。
     *
     * <p>判据与 {@code ImageGenerationService.isFanoutAnchor} 同源：父行是唯一
     * {@code requested_images > 1} 的行（子任务行恒为 1，单图行也是 1）。
     */
    Flux<TaskRow> openTasks(int limit) {
        return database.sql(SELECT_COLUMNS
                        + " WHERE status = 'SUBMITTED' AND requested_images = 1"
                        + " ORDER BY submitted_at LIMIT :limit")
                .bind("limit", limit)
                .map((row, metadata) -> TaskRow.of(row))
                .all();
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, request_id, reservation_id, tenant_id, user_id, api_key_id,
                   package_instance_id, model_code, provider_id, channel_id, upstream_model,
                   resolution_tier, requested_images, reserved_images, billed_images,
                   requested_size, response_format, result_data::text AS result_data,
                   result_images, status, error_category, layer_decomposition,
                   submitted_at, terminal_at, expires_at,
                   fanout_parent_request_id, fanout_index
            FROM gateway.image_generation_task
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
            String resolutionTier,
            int requestedImages,
            int reservedImages,
            String requestedSize,
            String responseFormat,
            String status,
            boolean layerDecomposition,
            Instant expiresAt,
            /**
             * 扇出父请求 id（网关迁移 V114）；单图请求为 null。
             *
             * <p>它与 {@link #fanoutIndex} 必须同时为 null 或同时非 null：
             * {@code ck_image_task_fanout_pair} 拒绝半条链接，因为半条链接的行
             * 永远无法被重放找到——那正是扇出最不能产生的东西。
             */
            String fanoutParentRequestId,
            /** 这一张在父请求里的 1-based 序号；单图请求为 null。 */
            Integer fanoutIndex) { }

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
            int requestedImages,
            int reservedImages,
            Integer billedImages,
            String requestedSize,
            String responseFormat,
            String resultData,
            Integer resultImages,
            String status,
            String errorCategory,
            boolean layerDecomposition,
            Instant submittedAt,
            Instant terminalAt,
            Instant expiresAt,
            /** 扇出父请求 id（V114）；单图请求与 V114 之前的行都是 null。 */
            String fanoutParentRequestId,
            /** 这一张在父请求里的 1-based 序号；与 {@link #fanoutParentRequestId} 同生同灭。 */
            Integer fanoutIndex) {

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
                    row.get("requested_images", Integer.class),
                    row.get("reserved_images", Integer.class),
                    row.get("billed_images", Integer.class),
                    row.get("requested_size", String.class),
                    row.get("response_format", String.class),
                    row.get("result_data", String.class),
                    row.get("result_images", Integer.class),
                    row.get("status", String.class),
                    row.get("error_category", String.class),
                    // 列是 NOT NULL DEFAULT false，因此这里不会拿到 null；
                    // 解箱成 boolean 让「标记缺失」变成一次响亮的失败，而不是
                    // 被安静地读成「不是图层调用」——那正是重复收费重新溜进来的方式。
                    Boolean.TRUE.equals(row.get("layer_decomposition", Boolean.class)),
                    row.get("submitted_at", Instant.class),
                    row.get("terminal_at", Instant.class),
                    row.get("expires_at", Instant.class),
                    // 这两列可空：单图请求与 V114 之前的行没有扇出链接。
                    row.get("fanout_parent_request_id", String.class),
                    row.get("fanout_index", Integer.class));
        }

        /** 这一行是不是某次扇出请求的一张。 */
        boolean fanoutChild() {
            return fanoutParentRequestId != null;
        }

        boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }

        boolean terminal() {
            return succeeded() || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }
}
