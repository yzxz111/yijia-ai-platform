package com.yuzhihuihe.apiplatform.gateway.quota;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class QuotaReservationRepository {

    private final DatabaseClient databaseClient;

    public QuotaReservationRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<ReservationRow> lockExisting(UUID reservationId, String requestId) {
        return databaseClient.sql("""
                        SELECT id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                               model_code, reserved_tokens, actual_input_tokens, actual_output_tokens,
                               actual_total_tokens, uncovered_tokens, usage_quality, status, expires_at,
                               package_reserved_tokens, package_settled_tokens,
                               wallet_usage_reservation_id, wallet_reserved_amount, wallet_settled_amount,
                               billing_mode, billing_price_version,
                               -- 方案 C 起这一列是**存的**而不是现算的：一个实例可能同时
                               -- 持有视频秒与音频秒，因此"这笔预留占的是哪个桶"无法再从
                               -- 实例的主维度反推（V97）。它同时消掉了旧写法的一个隐患
                               -- ——映射器在列缺失时返回 TOKEN，而 TOKEN 与
                               -- VIDEO_SECOND 走同一个主桶，所以那个默认值曾经无害；
                               -- 音频桶出现后，同一个默认值会让一次 ASR 结算去扣视频秒。
                               usage_dimension
                        FROM gateway.quota_reservation
                        WHERE id = :id OR request_id = :request_id
                        FOR UPDATE
                        """)
                .bind("id", reservationId)
                .bind("request_id", requestId)
                .map((row, metadata) -> reservation(row))
                .one();
    }

    Mono<Void> advisoryLock(UUID userId) {
        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:user_id, 0))")
                .bind("user_id", userId.toString())
                .fetch().rowsUpdated()
                .then();
    }

    Mono<Void> idempotencyLock(UUID reservationId, String requestId) {
        return databaseClient.sql("""
                        SELECT pg_advisory_xact_lock(lock_key)
                        FROM (
                            SELECT DISTINCT lock_key
                            FROM (VALUES
                                (hashtextextended('quota-reservation:' || :reservation_id, 0)),
                                (hashtextextended('quota-request:' || :request_id, 0))
                            ) AS idempotency_keys(lock_key)
                            ORDER BY lock_key
                        ) AS ordered_keys
                        """)
                .bind("reservation_id", reservationId.toString())
                .bind("request_id", requestId)
                .fetch().rowsUpdated()
                .then();
    }

    Mono<LockedPackage> lockActivePackage(QuotaService.ReserveCommand command) {
        return databaseClient.sql("""
                        SELECT p.id, p.user_id, p.total_token_quota, p.reserved_tokens,
                               p.used_tokens, p.expires_at, p.usage_dimension,
                               p.audio_seconds_quota, p.audio_seconds_reserved,
                               p.audio_seconds_used
                        FROM gateway.package_instance p
                        JOIN gateway.api_key k ON k.package_instance_id = p.id
                        WHERE p.tenant_id = :tenant_id AND p.user_id = :user_id
                          AND p.status = 'ACTIVE' AND p.expires_at > now()
                          AND k.id = :api_key_id AND k.tenant_id = p.tenant_id
                          AND k.user_id = p.user_id AND k.status = 'ACTIVE'
                          AND (k.expires_at IS NULL OR k.expires_at > now())
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.package_refund_hold hold
                              WHERE hold.package_instance_id = p.id
                                AND hold.status IN ('WAITING', 'READY')
                          )
                        FOR UPDATE OF p
                        """)
                .bind("tenant_id", command.tenantId())
                .bind("user_id", command.userId())
                .bind("api_key_id", command.apiKeyId())
                .map((row, metadata) -> lockedPackage(row))
                .one();
    }

    Mono<LockedPackage> lockPackage(UUID packageId) {
        return databaseClient.sql("""
                        SELECT id, user_id, total_token_quota, reserved_tokens,
                               used_tokens, expires_at, usage_dimension,
                               audio_seconds_quota, audio_seconds_reserved,
                               audio_seconds_used
                        FROM gateway.package_instance
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .bind("id", packageId)
                .map((row, metadata) -> lockedPackage(row))
                .one();
    }

    private static LockedPackage lockedPackage(io.r2dbc.spi.Row row) {
        return new LockedPackage(
                row.get("id", UUID.class), row.get("user_id", UUID.class),
                requiredLong(row.get("total_token_quota", Long.class)),
                requiredLong(row.get("reserved_tokens", Long.class)),
                requiredLong(row.get("used_tokens", Long.class)),
                row.get("audio_seconds_quota", Long.class),
                requiredLong(row.get("audio_seconds_reserved", Long.class)),
                requiredLong(row.get("audio_seconds_used", Long.class)),
                row.get("expires_at", Instant.class),
                row.get("usage_dimension", String.class));
    }

    Mono<ModelQuota> lockModelQuota(UUID packageId, String modelCode) {
        return databaseClient.sql("""
                        SELECT id, usage_dimension,
                               -- One counter column per dimension, so read whichever one
                               -- this row actually carries. Reading token_quota alone made
                               -- a video alias report zero available seconds and fall
                               -- through to wallet billing it has no price for.
                               COALESCE(token_quota, seconds_quota) AS effective_quota,
                               reserved_tokens, used_tokens
                        FROM gateway.package_instance_model_quota
                        WHERE package_instance_id = :package_id AND model_code = :model_code
                        FOR UPDATE
                        """)
                .bind("package_id", packageId)
                .bind("model_code", modelCode)
                .map((row, metadata) -> new ModelQuota(
                        row.get("id", UUID.class),
                        requiredLong(row.get("effective_quota", Long.class)),
                        requiredLong(row.get("reserved_tokens", Long.class)),
                        requiredLong(row.get("used_tokens", Long.class)),
                        row.get("usage_dimension", String.class)))
                .one();
    }

    /**
     * 预留一段额度，并把它记在 {@code dimension} 对应的桶上（方案 C）。
     *
     * <p>{@code audio} 这个布尔量就是"本次预留扣哪一个桶"的唯一判据，它由
     * {@link LockedPackage#usesSecondBucket} 决定——那里的规则是"请求的维度是
     * AUDIO_SECOND 而实例的主维度不是"，因此一个**纯音频**实例（主维度就是
     * AUDIO_SECOND）仍然走主桶，不会去找一个它没有的第二桶。
     *
     * <p>两列写成一对 CASE WHEN 而不是拼两条 SQL：一个拼错列名的字符串会在
     * 运行期才失败，而一对并排的 CASE 让"音频走哪一列"在同一行里可读。
     * 那条 `reserved_tokens >= :reserved` 的守卫在下面移进了按桶选择的 CASE，
     * 因此它检查的仍然是**被扣的那个桶**。
     */
    Mono<Void> addReservation(
            QuotaService.ReserveCommand command, LockedPackage lockedPackage, ModelQuota modelQuota,
            long packageTokens, long walletAmount, String priceVersion, String dimension) {
        boolean audio = LockedPackage.usesSecondBucket(lockedPackage.usageDimension(), dimension);
        Mono<Void> packageUpdate = databaseClient.sql("""
                        UPDATE gateway.package_instance
                        SET reserved_tokens = reserved_tokens
                                + CASE WHEN :audio THEN 0 ELSE :tokens END,
                            audio_seconds_reserved = audio_seconds_reserved
                                + CASE WHEN :audio THEN :tokens ELSE 0 END,
                            updated_at = now(), lock_version = lock_version + 1
                        WHERE id = :id
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.package_refund_hold hold
                              WHERE hold.package_instance_id = package_instance.id
                                AND hold.status IN ('WAITING', 'READY')
                          )
                        """)
                .bind("audio", audio)
                .bind("tokens", packageTokens)
                .bind("id", lockedPackage.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(hasOpenRefundHold(lockedPackage.id()).flatMap(open -> Mono.error(
                        open ? new RefundHoldInProgressException()
                                : new IllegalStateException("package_reservation_update_failed"))))
                .then();
        Mono<Void> modelUpdate = modelQuota == null ? Mono.empty() : databaseClient.sql("""
                        UPDATE gateway.package_instance_model_quota
                        SET reserved_tokens = reserved_tokens + :tokens, updated_at = now()
                        WHERE id = :id
                        """)
                .bind("tokens", packageTokens)
                .bind("id", modelQuota.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("model_reservation_update_failed")))
                .then();
        DatabaseClient.GenericExecuteSpec reservationSpec = databaseClient.sql("""
                        INSERT INTO gateway.quota_reservation(
                            id, request_id, tenant_id, user_id, api_key_id,
                            package_instance_id, model_code, reserved_tokens, package_reserved_tokens,
                            wallet_usage_reservation_id, wallet_reserved_amount, billing_mode,
                            billing_price_version, expires_at, usage_dimension)
                        VALUES (
                            :id, :request_id, :tenant_id, :user_id, :api_key_id,
                            :package_id, :model_code, :tokens, :package_tokens,
                            :wallet_id, :wallet_amount, :billing_mode, :price_version, :expires_at,
                            :usage_dimension)
                        """)
                .bind("id", command.reservationId())
                .bind("request_id", command.requestId())
                .bind("tenant_id", command.tenantId())
                .bind("user_id", command.userId())
                .bind("api_key_id", command.apiKeyId())
                .bind("package_id", lockedPackage.id())
                .bind("model_code", command.modelCode())
                .bind("tokens", command.tokens())
                .bind("package_tokens", packageTokens)
                .bind("usage_dimension", dimension);
        reservationSpec = walletAmount > 0
                ? reservationSpec.bind("wallet_id", command.reservationId())
                : reservationSpec.bindNull("wallet_id", UUID.class);
        DatabaseClient.GenericExecuteSpec reservationInsert = reservationSpec
                .bind("wallet_amount", walletAmount)
                .bind("billing_mode", packageTokens > 0 && walletAmount > 0 ? "HYBRID"
                        : packageTokens > 0 ? "PACKAGE_ONLY" : "WALLET_ONLY");
        reservationInsert = priceVersion == null
                ? reservationInsert.bindNull("price_version", String.class)
                : reservationInsert.bind("price_version", priceVersion);
        Mono<Void> reservationInsertMono = reservationInsert
                .bind("expires_at", command.expiresAt())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("reservation_insert_failed")))
                .then();
        long balance = lockedPackage.availableFor(dimension) - packageTokens;
        Long modelBalance = modelQuota == null ? null : modelQuota.available() - packageTokens;
        return packageUpdate.then(modelUpdate).then(reservationInsertMono)
                .then(insertLedger(
                        command.reservationId(), lockedPackage.id(), command.modelCode(), "RESERVE",
                        -packageTokens, modelQuota == null ? null : -packageTokens,
                        balance, modelBalance,
                        -walletAmount));
    }

    Mono<Void> addWalletReservation(QuotaService.ReserveCommand command, long walletAmount,
            String priceVersion) {
        return databaseClient.sql("""
                INSERT INTO gateway.quota_reservation(
                    id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                    model_code, reserved_tokens, package_reserved_tokens,
                    wallet_usage_reservation_id, wallet_reserved_amount, billing_mode,
                    billing_price_version, expires_at, usage_dimension)
                VALUES (:id, :request_id, :tenant_id, :user_id, :api_key_id, NULL,
                    :model_code, :tokens, 0, :wallet_id, :wallet_amount, 'WALLET_ONLY',
                    :price_version, :expires_at, 'TOKEN')
                """)
                .bind("id", command.reservationId()).bind("request_id", command.requestId())
                .bind("tenant_id", command.tenantId()).bind("user_id", command.userId())
                .bind("api_key_id", command.apiKeyId()).bind("model_code", command.modelCode())
                .bind("tokens", command.tokens()).bind("wallet_id", command.reservationId())
                .bind("wallet_amount", walletAmount).bind("price_version", priceVersion)
                .bind("expires_at", command.expiresAt()).fetch().rowsUpdated().then();
    }

    Mono<ReservationRow> lockReservation(UUID reservationId) {
        return databaseClient.sql("""
                        SELECT id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                               model_code, reserved_tokens, actual_input_tokens, actual_output_tokens,
                               actual_total_tokens, uncovered_tokens, usage_quality, status, expires_at,
                               package_reserved_tokens, package_settled_tokens,
                               wallet_usage_reservation_id, wallet_reserved_amount, wallet_settled_amount,
                               billing_mode, billing_price_version, usage_dimension
                        FROM gateway.quota_reservation
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .bind("id", reservationId)
                .map((row, metadata) -> reservation(row))
                .one();
    }

    Mono<Boolean> hasOpenRefundHold(QuotaService.ReserveCommand command) {
        return databaseClient.sql("""
                        SELECT EXISTS(
                            SELECT 1
                            FROM gateway.api_key AS key
                            JOIN gateway.package_refund_hold AS hold
                              ON hold.package_instance_id = key.package_instance_id
                            WHERE key.id = :api_key_id
                              AND key.tenant_id = :tenant_id
                              AND key.user_id = :user_id
                              AND hold.status IN ('WAITING', 'READY')
                        ) AS open_hold
                        """)
                .bind("api_key_id", command.apiKeyId())
                .bind("tenant_id", command.tenantId())
                .bind("user_id", command.userId())
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("open_hold", Boolean.class)))
                .one();
    }

    Mono<ReservationRow> findReservation(UUID reservationId) {
        return databaseClient.sql("""
                        SELECT id, request_id, tenant_id, user_id, api_key_id, package_instance_id,
                               model_code, reserved_tokens, actual_input_tokens, actual_output_tokens,
                               actual_total_tokens, uncovered_tokens, usage_quality, status, expires_at,
                               package_reserved_tokens, package_settled_tokens,
                               wallet_usage_reservation_id, wallet_reserved_amount, wallet_settled_amount,
                               billing_mode, billing_price_version, usage_dimension
                        FROM gateway.quota_reservation
                        WHERE id = :id
                        """)
                .bind("id", reservationId)
                .map((row, metadata) -> reservation(row))
                .one();
    }

    private Mono<Boolean> hasOpenRefundHold(UUID packageId) {
        return databaseClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM gateway.package_refund_hold
                            WHERE package_instance_id = :package_id
                              AND status IN ('WAITING', 'READY')
                        ) AS open_hold
                        """)
                .bind("package_id", packageId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("open_hold", Boolean.class)))
                .one();
    }

    Mono<Long> settledModelCharge(ReservationRow reservation) {
        if (reservation.packageInstanceId() == null) {
            return Mono.just(0L);
        }
        return databaseClient.sql("""
                        SELECT COALESCE(model_token_delta, total_token_delta) AS model_token_delta
                        FROM gateway.quota_ledger
                        WHERE reservation_id = :reservation_id AND entry_type = 'SETTLE'
                        """)
                .bind("reservation_id", reservation.id())
                .map((row, metadata) -> requiredLong(row.get("model_token_delta", Long.class)))
                .one()
                .map(delta -> reservation.packageReservedTokens() - delta)
                .switchIfEmpty(Mono.error(new IllegalStateException("settlement_ledger_missing")));
    }

    /**
     * 提交一次结算：套餐按其实际收取的数量扣费，预留记录下已结算的部分和无法结算的部分，
     * 账本则得到它的 SETTLE 条目。
     *
     * <p>{@code walletDeficitAmount} 与 {@code walletDeficitTokens} 是被显式写入而不是取默认值，
     * 因此调用方在结算时无法不说明客户的预付费余额是否真的覆盖了本次请求。
     */
    Mono<Void> settle(
            ReservationRow reservation,
            QuotaService.TokenUsage usage,
            QuotaService.UsageQuality quality,
            LockedPackage lockedPackage,
            ModelQuota modelQuota,
            long packageCharge,
            long modelCharge,
            long uncoveredTokens,
            long walletSettleAmount,
            long walletDeficitAmount,
            long walletDeficitTokens) {
        // 扣哪一个桶由**这笔预留自己记住的单位**决定，而不是由实例的主维度决定
        // （方案 C 的行为核心）：一次 ASR 请求必须扣音频秒、绝不扣视频秒，
        // 反之亦然。reservation.usageDimension() 是预留时写下的那一列，
        // 因此它不依赖"这个实例现在卖什么"这种可能已经变化的信息。
        String dimension = reservation.usageDimension();
        boolean audio = LockedPackage.usesSecondBucket(lockedPackage.usageDimension(), dimension);
        Mono<Void> packageUpdate = databaseClient.sql("""
                        UPDATE gateway.package_instance
                        SET reserved_tokens = reserved_tokens
                                - CASE WHEN :audio THEN 0 ELSE :reserved END,
                            audio_seconds_reserved = audio_seconds_reserved
                                - CASE WHEN :audio THEN :reserved ELSE 0 END,
                            used_tokens = used_tokens
                                + CASE WHEN :audio THEN 0 ELSE :charged END,
                            audio_seconds_used = audio_seconds_used
                                + CASE WHEN :audio THEN :charged ELSE 0 END,
                            updated_at = now(), lock_version = lock_version + 1
                        WHERE id = :id
                          AND CASE WHEN :audio THEN audio_seconds_reserved ELSE reserved_tokens END
                              >= :reserved
                        """)
                .bind("audio", audio)
                .bind("reserved", reservation.packageReservedTokens())
                .bind("charged", packageCharge)
                .bind("id", lockedPackage.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("package_settlement_update_failed")))
                .then();
        Mono<Void> modelUpdate = modelQuota == null ? Mono.empty() : databaseClient.sql("""
                        UPDATE gateway.package_instance_model_quota
                        SET reserved_tokens = reserved_tokens - :reserved,
                            used_tokens = used_tokens + :charged,
                            updated_at = now()
                        WHERE id = :id AND reserved_tokens >= :reserved
                        """)
                .bind("reserved", reservation.packageReservedTokens())
                .bind("charged", modelCharge)
                .bind("id", modelQuota.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("model_settlement_update_failed")))
                .then();
        Mono<Void> reservationUpdate = databaseClient.sql("""
                        UPDATE gateway.quota_reservation
                        SET actual_input_tokens = :input_tokens,
                            actual_output_tokens = :output_tokens,
                            actual_total_tokens = :total_tokens,
                            package_settled_tokens = :package_settled_tokens,
                            wallet_settled_amount = :wallet_settled_amount,
                            wallet_deficit_amount = :wallet_deficit_amount,
                            wallet_deficit_tokens = :wallet_deficit_tokens,
                            uncovered_tokens = :uncovered_tokens,
                            usage_estimated = :estimated,
                            usage_quality = :usage_quality,
                            status = 'SETTLED', settled_at = now()
                        WHERE id = :id AND status = 'RESERVED'
                        """)
                .bind("input_tokens", usage.inputTokens())
                .bind("output_tokens", usage.outputTokens())
                .bind("total_tokens", usage.totalTokens())
                .bind("package_settled_tokens", packageCharge)
                .bind("wallet_settled_amount", walletSettleAmount)
                .bind("wallet_deficit_amount", walletDeficitAmount)
                .bind("wallet_deficit_tokens", walletDeficitTokens)
                .bind("uncovered_tokens", uncoveredTokens)
                .bind("estimated", quality == QuotaService.UsageQuality.ESTIMATED)
                .bind("usage_quality", quality.name())
                .bind("id", reservation.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("reservation_settlement_update_failed")))
                .then();
        long balance = lockedPackage.totalFor(dimension) - lockedPackage.usedFor(dimension)
                - packageCharge
                - (lockedPackage.reservedFor(dimension) - reservation.packageReservedTokens());
        Long modelBalance = modelQuota == null ? null
                : modelQuota.totalTokens() - modelQuota.usedTokens() - modelCharge
                        - (modelQuota.reservedTokens() - reservation.packageReservedTokens());
        return packageUpdate.then(modelUpdate).then(reservationUpdate)
                .then(insertLedger(
                        reservation.id(), lockedPackage.id(), reservation.modelCode(), "SETTLE",
                        reservation.packageReservedTokens() - packageCharge,
                        modelQuota == null ? null : reservation.packageReservedTokens() - modelCharge,
                        balance, modelBalance,
                        -walletSettleAmount));
    }

    Mono<Void> release(
            ReservationRow reservation,
            LockedPackage lockedPackage,
            ModelQuota modelQuota,
            QuotaService.ReleaseReason reason,
            boolean expired) {
        // 与结算同一判据：释放的是这笔预留**当时扣的那个桶**。扣在一个桶上、
        // 放回另一个桶会让被扣的那个桶永久少一段余额，而另一个桶凭空多出来，
        // 两者都不会报错——它只会表现为客户额度越来越少或越来越多。
        String dimension = reservation.usageDimension();
        boolean audio = LockedPackage.usesSecondBucket(lockedPackage.usageDimension(), dimension);
        Mono<Void> packageUpdate = databaseClient.sql("""
                        UPDATE gateway.package_instance
                        SET reserved_tokens = reserved_tokens
                                - CASE WHEN :audio THEN 0 ELSE :reserved END,
                            audio_seconds_reserved = audio_seconds_reserved
                                - CASE WHEN :audio THEN :reserved ELSE 0 END,
                            updated_at = now(), lock_version = lock_version + 1
                        WHERE id = :id
                          AND CASE WHEN :audio THEN audio_seconds_reserved ELSE reserved_tokens END
                              >= :reserved
                        """)
                .bind("audio", audio)
                .bind("reserved", reservation.packageReservedTokens())
                .bind("id", lockedPackage.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("package_release_update_failed")))
                .then();
        Mono<Void> modelUpdate = modelQuota == null ? Mono.empty() : databaseClient.sql("""
                        UPDATE gateway.package_instance_model_quota
                        SET reserved_tokens = reserved_tokens - :reserved, updated_at = now()
                        WHERE id = :id AND reserved_tokens >= :reserved
                        """)
                .bind("reserved", reservation.packageReservedTokens())
                .bind("id", modelQuota.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("model_release_update_failed")))
                .then();
        String status = expired ? "EXPIRED" : "RELEASED";
        Mono<Void> reservationUpdate = databaseClient.sql("""
                        UPDATE gateway.quota_reservation
                        SET status = :status, release_reason = :reason, released_at = now()
                        WHERE id = :id AND status = 'RESERVED'
                        """)
                .bind("status", status)
                .bind("reason", reason.name())
                .bind("id", reservation.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("reservation_release_update_failed")))
                .then();
        long balance = lockedPackage.availableFor(dimension) + reservation.packageReservedTokens();
        Long modelBalance = modelQuota == null ? null : modelQuota.available() + reservation.packageReservedTokens();
        return packageUpdate.then(modelUpdate).then(reservationUpdate)
                .then(insertLedger(
                        reservation.id(), lockedPackage.id(), reservation.modelCode(),
                        expired ? "EXPIRE" : "RELEASE",
                        reservation.packageReservedTokens(),
                        modelQuota == null ? null : reservation.packageReservedTokens(),
                        balance, modelBalance,
                        reservation.walletReservedAmount()));
    }


    Mono<Void> settleWallet(
            ReservationRow reservation,
            QuotaService.TokenUsage usage,
            long walletSettleAmount,
            QuotaService.UsageQuality quality,
            long walletDeficitAmount,
            long walletDeficitTokens) {
        return databaseClient.sql("""
                        UPDATE gateway.quota_reservation
                        SET actual_input_tokens = :input_tokens,
                            actual_output_tokens = :output_tokens,
                            actual_total_tokens = :total_tokens,
                            package_settled_tokens = 0,
                            wallet_settled_amount = :wallet_settled_amount,
                            wallet_deficit_amount = :wallet_deficit_amount,
                            wallet_deficit_tokens = :wallet_deficit_tokens,
                            uncovered_tokens = :uncovered_tokens,
                            usage_estimated = :estimated,
                            usage_quality = :usage_quality,
                            status = 'SETTLED', settled_at = now()
                        WHERE id = :id AND status = 'RESERVED'
                        """)
                .bind("input_tokens", usage.inputTokens())
                .bind("output_tokens", usage.outputTokens())
                .bind("total_tokens", usage.totalTokens())
                .bind("wallet_settled_amount", walletSettleAmount)
                .bind("wallet_deficit_amount", walletDeficitAmount)
                .bind("wallet_deficit_tokens", walletDeficitTokens)
                .bind("uncovered_tokens", usage.totalTokens())
                .bind("estimated", quality == QuotaService.UsageQuality.ESTIMATED)
                .bind("usage_quality", quality.name())
                .bind("id", reservation.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("reservation_settlement_update_failed")))
                .then();
    }

    Mono<Void> releaseWallet(
            ReservationRow reservation,
            QuotaService.ReleaseReason reason,
            boolean expired) {
        String status = expired ? "EXPIRED" : "RELEASED";
        return databaseClient.sql("""
                        UPDATE gateway.quota_reservation
                        SET status = :status, release_reason = :reason, released_at = now()
                        WHERE id = :id AND status = 'RESERVED'
                        """)
                .bind("status", status)
                .bind("reason", reason.name())
                .bind("id", reservation.id())
                .fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("reservation_release_update_failed")))
                .then();
    }

    /**
     * 记录一次结算未能收取的部分。
     *
     * <p>这就是产品负责人要求的那个显式标志：一个已结算的请求，若其实际成本超过了客户的
     * 预付费余额，就会在预留行本身上留下非零的 {@code wallet_deficit_amount}（处于风险中的
     * 资金）和/或 {@code wallet_deficit_tokens}（套餐容量不足以覆盖的数量），而用量经济证据
     * 正是基于这一行构建的。把它清零则表示超出的部分已改从客户的可用余额中收取。
     */
    Mono<Void> recordDeficit(UUID reservationId, long deficitAmount, long deficitTokens) {
        return databaseClient.sql("""
                        UPDATE gateway.quota_reservation
                        SET wallet_deficit_amount = :deficit_amount,
                            wallet_deficit_tokens = :deficit_tokens
                        WHERE id = :id
                        """)
                .bind("deficit_amount", Math.max(0, deficitAmount))
                .bind("deficit_tokens", Math.max(0, deficitTokens))
                .bind("id", reservationId)
                .fetch().rowsUpdated()
                .then();
    }

    /**
     * 终结一个已经卖不出任何东西的实例。
     *
     * <p>「用尽」要求**两个桶都用尽**（方案 C）：一个既卖视频秒又卖音频秒的实例，
     * 视频秒花完时音频秒仍然有效，反之亦然。只看主桶会在客户还没用过一次
     * 转写的时候终结实例，把他为语音付过的钱一并带走。
     * {@code PackageActivationRepository.closeTerminalActive} 是同一条规则的另一半
     * （激活新实例前先关掉旧的），两处必须一致，否则一个实例会在其中一条路径上
     * 提前终结。
     */
    Mono<TerminalPackage> closeTerminalPackage(UUID packageId) {
        return databaseClient.sql("""
                        UPDATE gateway.package_instance
                        SET status = CASE WHEN expires_at <= now() THEN 'EXPIRED' ELSE 'EXHAUSTED' END,
                            ended_at = now(), updated_at = now(), lock_version = lock_version + 1
                        WHERE id = :id AND status = 'ACTIVE'
                          AND (expires_at <= now()
                               OR (used_tokens >= total_token_quota
                                   AND (audio_seconds_quota IS NULL
                                        OR audio_seconds_used >= audio_seconds_quota)))
                        RETURNING user_id, status, usage_dimension
                        """)
                .bind("id", packageId)
                .map((row, metadata) -> new TerminalPackage(
                        row.get("user_id", UUID.class),
                        row.get("status", String.class),
                        row.get("usage_dimension", String.class)))
                .one();
    }

    Flux<UUID> expiredReservationIds(int limit) {
        return databaseClient.sql("""
                        SELECT reservation.id
                        FROM gateway.quota_reservation AS reservation
                        WHERE reservation.status = 'RESERVED' AND reservation.expires_at <= now()
                        ORDER BY
                            -- 有终态证据的先处理。只有这些预留能被真正结算或判定为
                            -- 失败过期；没有证据的本轮会被**跳过**（见
                            -- ReservationRecoveryJob.recoverTokenReservation），
                            -- 因此它们不该占用批额度。
                            -- 不这样排序时，一批里最老的"无证据"预留会把批次占满，
                            -- 把有证据的一直推到下一轮——batchSize 越小时越明显，
                            -- 这正是 recoveryBatchPrioritizesTerminalEvidence... 钉住的事。
                            (EXISTS (
                                SELECT 1
                                FROM gateway.usage_log AS evidence
                                WHERE evidence.reservation_id = reservation.id)) DESC,
                            reservation.expires_at, reservation.id
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, metadata) -> row.get("id", UUID.class))
                .all();
    }

    Mono<UsageEvidence> usageEvidence(UUID reservationId) {
        return databaseClient.sql("""
                        SELECT input_tokens, output_tokens, total_tokens, usage_estimated, outcome
                        FROM gateway.usage_log
                        WHERE reservation_id = :reservation_id
                        """)
                .bind("reservation_id", reservationId)
                .map((row, metadata) -> new UsageEvidence(
                        requiredLong(row.get("input_tokens", Long.class)),
                        requiredLong(row.get("output_tokens", Long.class)),
                        requiredLong(row.get("total_tokens", Long.class)),
                        Boolean.TRUE.equals(row.get("usage_estimated", Boolean.class)),
                        row.get("outcome", String.class)))
                .one();
    }

    private Mono<Void> insertLedger(
            UUID reservationId,
            UUID packageId,
            String modelCode,
            String entryType,
            long totalDelta,
            Long modelDelta,
            long balanceAfter,
            Long modelBalanceAfter,
            long walletAmountDelta) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO gateway.quota_ledger(
                            id, business_no, reservation_id, package_instance_id, model_code,
                            entry_type, total_token_delta, model_token_delta, wallet_amount_delta,
                            balance_after, model_balance_after)
                        VALUES (
                            :id, :business_no, :reservation_id, :package_id, :model_code,
                            :entry_type, :total_delta, :model_delta, :wallet_amount_delta,
                            :balance_after, :model_balance_after)
                        """)
                .bind("id", UUID.randomUUID())
                .bind("business_no", reservationId + ":" + entryType)
                .bind("reservation_id", reservationId)
                .bind("package_id", packageId)
                .bind("model_code", modelCode)
                .bind("entry_type", entryType)
                .bind("total_delta", totalDelta)
                .bind("wallet_amount_delta", walletAmountDelta)
                .bind("balance_after", balanceAfter);
        spec = modelDelta == null ? spec.bindNull("model_delta", Long.class) : spec.bind("model_delta", modelDelta);
        spec = modelBalanceAfter == null
                ? spec.bindNull("model_balance_after", Long.class)
                : spec.bind("model_balance_after", modelBalanceAfter);
        return spec.fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("quota_ledger_insert_failed")))
                .then();
    }

    private ReservationRow reservation(io.r2dbc.spi.Row row) {
        return new ReservationRow(
                row.get("id", UUID.class), row.get("request_id", String.class),
                row.get("tenant_id", UUID.class), row.get("user_id", UUID.class),
                row.get("api_key_id", UUID.class), row.get("package_instance_id", UUID.class),
                row.get("model_code", String.class), requiredLong(row.get("reserved_tokens", Long.class)),
                requiredLong(row.get("package_reserved_tokens", Long.class)),
                requiredLong(row.get("package_settled_tokens", Long.class)),
                row.get("wallet_usage_reservation_id", UUID.class),
                requiredLong(row.get("wallet_reserved_amount", Long.class)),
                requiredLong(row.get("wallet_settled_amount", Long.class)),
                row.get("billing_mode", String.class),
                row.get("billing_price_version", String.class),
                nullableLong(row.get("actual_input_tokens", Long.class)),
                nullableLong(row.get("actual_output_tokens", Long.class)),
                nullableLong(row.get("actual_total_tokens", Long.class)),
                requiredLong(row.get("uncovered_tokens", Long.class)),
                row.get("usage_quality", String.class), row.get("status", String.class),
                row.get("expires_at", Instant.class),
                reservationUsageDimension(row));
    }

    /**
     * 读一笔预留**自己记住的**计费单位。
     *
     * <p>方案 C 起这一列是存的（V97），因此三条预留查询都必须投影它。这里
     * fail-closed 而不是像旧版那样退回 {@code TOKEN}：旧版那个默认值当时无害
     * （TOKEN 与 VIDEO_SECOND 走同一个主桶），但音频桶出现之后，同一个默认值
     * 会让一次 ASR 结算去扣**视频秒**——按视频 69 分/秒对 ASR 的 0.022 分/秒
     * 计费，而且不会报任何错。少写一列的后果必须是启动即失败，不是算错钱。
     */
    private static String reservationUsageDimension(io.r2dbc.spi.Row row) {
        String value;
        try {
            value = row.get("usage_dimension", String.class);
        } catch (RuntimeException notProjected) {
            // 驱动对"列不存在"抛的是 NoSuchElementException，它继承
            // RuntimeException；这里捕获而不重试。
            throw new IllegalStateException(
                    "reservation_usage_dimension_not_projected", notProjected);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("reservation_usage_dimension_missing_value");
        }
        return value;
    }

    private static long requiredLong(Long value) {
        return value == null ? 0 : value;
    }

    private static Long nullableLong(Long value) {
        return value;
    }

    /**
     * 一个已加锁的套餐实例，连同它可能持有的**两个**额度桶。
     *
     * <p>方案 C 之前这里只有一个桶：{@code totalTokens / reservedTokens / usedTokens}
     * 的单位由 {@code usageDimension} 决定（token、秒或张）。混合套餐
     * （视频秒 + 音频秒）需要第二个桶，否则一次 ASR 请求只能去花视频秒——
     * 两者相差三个数量级，而这正是方案 C 存在的理由。
     *
     * <p>桶的选择由 {@link #usesSecondBucket} 一处决定，而不是散在各个
     * 读写点：本类与 {@link QuotaService} 共有六处需要它（预留、结算、释放、
     * 以及三处余额计算），任何一处用了另一个判断都会算错钱，而不会报错。
     *
     * @param audioSecondsQuota 第二份额度；为 null 表示这个实例不卖语音。
     * @param audioSecondsReserved / audioSecondsUsed 第二份桶的在途与已用秒数。
     */
    record LockedPackage(
            UUID id, UUID userId, long totalTokens, long reservedTokens,
            long usedTokens, Long audioSecondsQuota, long audioSecondsReserved,
            long audioSecondsUsed, Instant expiresAt, String usageDimension) {

        LockedPackage {
            // 在 usage_dimension 存在之前写入的行是 token 套餐。
            usageDimension = usageDimension == null || usageDimension.isBlank()
                    ? "TOKEN" : usageDimension;
        }

        /**
         * 单桶实例的形式：方案 C 之前的每一个实例都是这个形状，而今天绝大多数
         * 仍然是（只有"视频秒 + 音频秒"的混合版本才有第二份桶）。
         * 第二份桶缺席在这里是**有意义**的，因此这个便捷形式不会掩盖任何东西；
         * 需要它的地方必须显式写出来。
         */
        LockedPackage(
                UUID id, UUID userId, long totalTokens, long reservedTokens,
                long usedTokens, Instant expiresAt, String usageDimension) {
            this(id, userId, totalTokens, reservedTokens, usedTokens,
                    null, 0, 0, expiresAt, usageDimension);
        }

        /**
         * 本次请求的单位是否住在**第二份**桶里。
         *
         * <p>只有当请求是 {@code AUDIO_SECOND} 而实例的主维度**不是**
         * {@code AUDIO_SECOND} 时才是。第二种情形是纯音频套餐
         * （usage_dimension = AUDIO_SECOND，它的音频秒就在主列
         * {@code total_token_quota} 里），那种实例没有第二份桶，
         * 而把它判成"用第二桶"会让每一次音频请求都找不到桶。
         *
         * <p>反过来，实例有第二份桶而请求是视频秒/token/图片时，答案也是主桶：
         * 第二份桶**只**属于 AUDIO_SECOND，这是本方法刻意只有一个条件的原因。
         */
        static boolean usesSecondBucket(String instanceDimension, String dimension) {
            return "AUDIO_SECOND".equals(dimension) && !"AUDIO_SECOND".equals(instanceDimension);
        }

        /** 这个实例能不能按 {@code dimension} 计费。不能就必须在扣任何东西之前拒绝。 */
        boolean supports(String dimension) {
            if (dimension == null || dimension.isBlank()) {
                return false;
            }
            return usesSecondBucket(usageDimension, dimension)
                    ? audioSecondsQuota != null
                    : dimension.equals(usageDimension);
        }

        private boolean secondBucket(String dimension) {
            return usesSecondBucket(usageDimension, dimension);
        }

        long totalFor(String dimension) {
            return secondBucket(dimension) ? audioSecondsQuota : totalTokens;
        }

        long reservedFor(String dimension) {
            return secondBucket(dimension) ? audioSecondsReserved : reservedTokens;
        }

        long usedFor(String dimension) {
            return secondBucket(dimension) ? audioSecondsUsed : usedTokens;
        }

        long availableFor(String dimension) {
            return Math.max(0, totalFor(dimension) - usedFor(dimension) - reservedFor(dimension));
        }
    }

    record ModelQuota(
            UUID id, long totalTokens, long reservedTokens, long usedTokens,
            String usageDimension) {

        ModelQuota {
            usageDimension = usageDimension == null || usageDimension.isBlank()
                    ? "TOKEN" : usageDimension;
        }

        long available() {
            return Math.max(0, totalTokens - usedTokens - reservedTokens);
        }
    }

    record ReservationRow(
            UUID id,
            String requestId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            UUID packageInstanceId,
            String modelCode,
            long reservedTokens,
            long packageReservedTokens,
            long packageSettledTokens,
            UUID walletUsageReservationId,
            long walletReservedAmount,
            long walletSettledAmount,
            String billingMode,
            String billingPriceVersion,
            Long actualInputTokens,
            Long actualOutputTokens,
            Long actualTotalTokens,
            long uncoveredTokens,
            String usageQuality,
            String status,
            Instant expiresAt,
            String usageDimension) {
    }

    record TerminalPackage(UUID userId, String status, String usageDimension) {
    }

    record UsageEvidence(
            long inputTokens, long outputTokens, long totalTokens,
            boolean estimated, String outcome) {
    }

    static final class RefundHoldInProgressException extends RuntimeException {
        private RefundHoldInProgressException() {
            super("package_refund_in_progress");
        }
    }
}
