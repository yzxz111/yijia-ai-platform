package com.yuzhihuihe.apiplatform.gateway.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

/**
 * 预留的清扫任务：把已经不可能是「正在进行中」的额度还给客户。
 *
 * <p>它的不变式只有一条：**没有任何预留会被无限期持有。** 前半句是各领域自己的
 * 恢复路径（{@code VideoTaskRecoveryJob} / {@code AudioTaskRecoveryJob} /
 * {@code ImageTaskRecoveryJob}）的职责——它们能看到任务行，因此能先向上游问一次；
 * 后半句是本任务的职责——它只看预留本身，因此覆盖那些**领域路径看不见的行**：
 * 任务行已经终态、任务行丢失，或释放失败。
 *
 * <p>两半合起来才成立：领域路径负责「尽量少误释放」，本任务负责「不会永远不释放」。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "quota.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationRecoveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationRecoveryJob.class);

    private final QuotaReservationRepository repository;
    private final QuotaService quotaService;
    private final DatabaseClient databaseClient;
    private final int batchSize;
    private final String unknownOutcomeGrace;

    public ReservationRecoveryJob(
            QuotaReservationRepository repository,
            QuotaService quotaService,
            DatabaseClient databaseClient,
            @Value("${quota.recovery.batch-size:100}") int batchSize,
            @Value("${quota.recovery.unknown-outcome-grace:2h}") String unknownOutcomeGrace) {
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("invalid_reservation_recovery_batch_size");
        }
        if (unknownOutcomeGrace == null || unknownOutcomeGrace.isBlank()) {
            throw new IllegalArgumentException("invalid_reservation_recovery_grace");
        }
        this.repository = repository;
        this.quotaService = quotaService;
        this.databaseClient = databaseClient;
        this.batchSize = batchSize;
        this.unknownOutcomeGrace = unknownOutcomeGrace.strip();
    }

    @Scheduled(
            initialDelayString = "${quota.recovery.initial-delay:60s}",
            fixedDelayString = "${quota.recovery.fixed-delay:30s}")
    Mono<Void> scheduledRecovery() {
        return Mono.defer(() -> recoverOnce().then());
    }

    public Mono<Integer> recoverOnce() {
        return repository.expiredReservationIds(batchSize)
                .concatMap(reservationId -> recover(reservationId)
                        .onErrorResume(error -> {
                            LOG.warn("reservation recovery failed for {}: {}", reservationId, error.toString());
                            return Mono.just(false);
                        }))
                .filter(Boolean::booleanValue)
                .count()
                .map(Math::toIntExact);
    }

    private Mono<Boolean> recover(java.util.UUID reservationId) {
        // 视频预留没有 token 形态的 usage_log 证据：它们的任务表
        // 以及以秒计量的经济性证据由 VideoGenerationService 处理。
        // 更重要的是，当供应商接受了提交、而网关丢失了随后的任务 INSERT 时，
        // 预留可以在没有任务行的情况下存在。把这种未知状态
        // 当作已过期的 token 预留会释放秒数，并让
        // 一次幂等重试买下第二次生成。因此视频预留优先交给它自己的终态路径。
        //
        // 但「优先交给别人」不等于「永远保持」：那条路径只能看见**任务行**，
        // 而任务行丢失（或释放失败）时它就是看不见这一行的。所以这里同样接上
        // 有上界的兜底——宽限期内保持，之后收尾。
        return isVideoReservation(reservationId)
                .flatMap(video -> {
                    if (video) {
                        LOG.warn("reservation_recovery_skipped_video reservationId={} "
                                        + "reason=terminal_evidence_required grace={}",
                                reservationId, unknownOutcomeGrace);
                        return expireOrphanedReservation(reservationId);
                    }
                    return recoverTokenReservation(reservationId);
                });
    }

    private Mono<Boolean> recoverTokenReservation(java.util.UUID reservationId) {
        return recoveryEvidence(reservationId)
                .flatMap(evidence -> {
                    boolean succeeded = "SUCCEEDED".equals(evidence.outcome());
                    boolean failedOrCancelled = "FAILED".equals(evidence.outcome())
                            || "CANCELLED".equals(evidence.outcome());
                    if (succeeded) {
                        return settleTerminalEvidence(reservationId, evidence);
                    }
                    if (failedOrCancelled) {
                        return evidence.firstTokenMs() == null
                                ? quotaService.expire(reservationId).thenReturn(true)
                                : settleTerminalEvidence(reservationId, evidence);
                    }
                    return Mono.just(false);
                })
                // 没有终态证据 -> 在**宽限期内**保持占用，本轮跳过（返回 false，不计入 recovered）。
                //
                // 这里曾经是 quotaService.expire(...)：一个过期但没有任何 usage_log
                // 证据的 token 预留会被直接释放。那是危险方向——上游可能已经接受了
                // 这次调用（甚至已经产出并收费），只是证据还没落库；释放额度等于
                // 把这次调用白送出去，而且一次幂等重试会因此买下第二次生成。
                // 这与本文件视频分支的规则一致（见 recover 的注释：保持每一条视频
                // 预留被占用，直到终态路径结算或显式释放）：**没有证据不是证据**。
                //
                // 但「永远保持」同样错，而且错得更显眼：线上一条音频预留因此卡了
                // 一天多——它的任务行**已经是 FAILED**，所以各领域的恢复扫描
                // （只扫非终态任务）永远看不见它，而这里又拒不放行。所以现在是
                // **有上界的持有**：过了宽限期就按「已经不可能有证据」收尾。
                // 宽限期默认 2 小时，而各领域的恢复扫描每 60 秒就会重试一次，
                // 因此这个上界不会抢在任何正当的结算之前。
                .switchIfEmpty(expireOrphanedReservation(reservationId));
    }

    /**
     * 一条已过期很久、且**没有任何终态证据**的预留：收尾并释放。
     *
     * <p>它覆盖的是各领域恢复扫描看不到的那一类行——任务行已经是终态、
     * 或者根本没有任务行（提交被厂商接受而网关丢失了 INSERT，或进程恰好死在
     * 预留与任务行之间）。这类行不会有新的信息到达，继续持有只是把客户的额度
     * 锁死，而且没有任何东西会来纠正它。
     *
     * <p>判据完全落在预留自己的 {@code expires_at} 上，因此它天然覆盖所有维度，
     * 不需要为图片 / 视频 / 音频各写一份。
     */
    private Mono<Boolean> expireOrphanedReservation(java.util.UUID reservationId) {
        return databaseClient.sql("""
                        SELECT expires_at < now() - CAST(:grace AS interval) AS past_grace
                        FROM gateway.quota_reservation
                        WHERE id = :reservation_id
                        """)
                .bind("grace", unknownOutcomeGrace)
                .bind("reservation_id", reservationId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("past_grace", Boolean.class)))
                .one()
                .defaultIfEmpty(false)
                .flatMap(pastGrace -> pastGrace
                        ? quotaService.expire(reservationId)
                                .doOnSuccess(ignored -> LOG.warn(
                                        "reservation_recovery_expired_without_evidence "
                                                + "reservationId={} grace={}",
                                        reservationId, unknownOutcomeGrace))
                                .thenReturn(true)
                        : Mono.just(false));
    }

    /**
     * 这条预留是否属于"按秒计费的生成"那一类，因而必须交给它自己的终态路径处理。
     *
     * <p>判据读的是**实例的主维度**，而不是预留自己的单位（V97 之后
     * `quota_reservation.usage_dimension` 已经存在）。两者在一个混合套餐
     * （视频秒 + 音频秒，主维度 VIDEO_SECOND）上不同：一条**音频**预留会因为
     * 实例是 VIDEO_SECOND 而被这里归入"生成类"，于是通用清扫不会释放它。
     * 这个结果是有意留下的：
     *
     * <ul>
     *   <li>对视频预留，这正是本方法存在的理由——供应商可能已经接受了提交，
     *       而网关丢失了任务行，释放秒数会让一次幂等重试买下第二次生成；
     *   <li>对混合套餐里的音频预留，保护方向相同（语音同样是一条已付费的
     *       生成路径），且音频有自己的恢复通道
     *       （{@code AudioTaskRecoveryJob} 与 {@code AudioGenerationService.recoverExpired}，
     *       后者的顺序是"先轮询一次、再释放"）。
     * </ul>
     *
     * <p>已知的边界（记录在设计文档里，未在此改动）：一个**纯音频**套餐
     * （usage_dimension = AUDIO_SECOND）的预留会落到下面的 token 恢复策略上，
     * 与图片预留今天的情形相同。要统一这两者，需要先确定"同步音频任务在
     * 进程死掉之后由谁释放"，那是音频恢复链的另一个决定，不该在维度改动里顺带做。
     */
    private Mono<Boolean> isVideoReservation(java.util.UUID reservationId) {
        return databaseClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM gateway.quota_reservation reservation
                            JOIN gateway.package_instance package_instance
                              ON package_instance.id = reservation.package_instance_id
                            WHERE reservation.id = :reservation_id
                              AND package_instance.usage_dimension = 'VIDEO_SECOND'
                        ) AS video_reservation
                        """)
                .bind("reservation_id", reservationId)
                .map((row, metadata) -> Boolean.TRUE.equals(
                        row.get("video_reservation", Boolean.class)))
                .one()
                .defaultIfEmpty(false);
    }

    private Mono<Boolean> settleTerminalEvidence(
            java.util.UUID reservationId,
            RecoveryEvidence evidence) {
        if (evidence.totalTokens() <= 0) {
            return settle(reservationId, estimatedUsage(evidence), QuotaService.UsageQuality.ESTIMATED);
        }
        if (!hasConsistentTokenBreakdown(evidence)) {
            return settle(reservationId, boundedReservationEstimate(evidence), QuotaService.UsageQuality.ESTIMATED);
        }
        return settle(reservationId, evidence,
                evidence.estimated()
                        ? QuotaService.UsageQuality.ESTIMATED
                        : QuotaService.UsageQuality.ACTUAL);
    }

    private Mono<RecoveryEvidence> recoveryEvidence(java.util.UUID reservationId) {
        return databaseClient.sql("""
                        SELECT u.input_tokens, u.output_tokens, u.total_tokens,
                               u.usage_estimated, u.outcome, u.first_token_ms,
                               r.reserved_tokens
                        FROM gateway.usage_log u
                        JOIN gateway.quota_reservation r ON r.id = u.reservation_id
                        WHERE u.reservation_id = :reservation_id
                        """)
                .bind("reservation_id", reservationId)
                .map((row, metadata) -> new RecoveryEvidence(
                        requiredLong(row.get("input_tokens", Long.class)),
                        requiredLong(row.get("output_tokens", Long.class)),
                        requiredLong(row.get("total_tokens", Long.class)),
                        Boolean.TRUE.equals(row.get("usage_estimated", Boolean.class)),
                        row.get("outcome", String.class),
                        row.get("first_token_ms", Long.class),
                        requiredLong(row.get("reserved_tokens", Long.class))))
                .one();
    }

    private Mono<Boolean> settle(
            java.util.UUID reservationId,
            RecoveryEvidence evidence,
            QuotaService.UsageQuality quality) {
        return settle(
                reservationId,
                new QuotaService.TokenUsage(
                        evidence.inputTokens(), evidence.outputTokens(), evidence.totalTokens()),
                quality);
    }

    private Mono<Boolean> settle(
            java.util.UUID reservationId,
            QuotaService.TokenUsage usage,
            QuotaService.UsageQuality quality) {
        return quotaService.settle(reservationId, usage, quality).thenReturn(true);
    }

    private QuotaService.TokenUsage estimatedUsage(RecoveryEvidence evidence) {
        long componentTotal;
        try {
            componentTotal = Math.addExact(evidence.inputTokens(), evidence.outputTokens());
        } catch (ArithmeticException overflow) {
            return boundedReservationEstimate(evidence);
        }
        return new QuotaService.TokenUsage(
                evidence.inputTokens(),
                evidence.outputTokens(),
                Math.max(componentTotal, Math.max(evidence.totalTokens(), evidence.reservedTokens())));
    }

    private boolean hasConsistentTokenBreakdown(RecoveryEvidence evidence) {
        try {
            return evidence.totalTokens() >= Math.addExact(evidence.inputTokens(), evidence.outputTokens());
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private QuotaService.TokenUsage boundedReservationEstimate(RecoveryEvidence evidence) {
        return new QuotaService.TokenUsage(0, 0, evidence.reservedTokens());
    }

    private static long requiredLong(Long value) {
        return value == null ? 0 : value;
    }

    private record RecoveryEvidence(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            boolean estimated,
            String outcome,
            Long firstTokenMs,
            long reservedTokens) {
    }
}
