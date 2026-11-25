package com.yuzhihuihe.apiplatform.platform.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 删除早于配置保留期的支付异常证据。
 *
 * <p>{@code platform.payment_anomaly} 的行由未认证的支付回调插入，因此
 * 若没有保留规则它们会无限累积。本任务以有界批次删除最旧的行，
 * 并在下一次调度运行时重复，直到积压被清空：有界批次使
 * 单次运行的锁与 WAL 工作量保持很小，这很重要，因为同一张表
 * 还服务于运维的异常复核列表。
 *
 * <p>保留期是安全信号上的权衡，因此它是配置而非常量：
 * {@code payment.anomaly.retention}（默认 {@code 30d}）以及
 * {@code payment.anomaly.cleanup-batch-size}（默认 {@code 500}，上限 5000）。
 */
@Component
public class PaymentAnomalyRetentionJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PaymentAnomalyRetentionJob.class);
    private static final int MAX_BATCH_SIZE = 5_000;

    private final PaymentPostingRepository repository;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public PaymentAnomalyRetentionJob(
            PaymentPostingRepository repository,
            Clock clock,
            @Value("${payment.anomaly.retention:30d}") Duration retention,
            @Value("${payment.anomaly.cleanup-batch-size:500}") int batchSize) {
        this.repository = repository;
        this.clock = clock;
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("payment_anomaly_retention_must_be_positive");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("payment_anomaly_cleanup_batch_size_out_of_range");
        }
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${payment.anomaly.cleanup-delay-ms:60000}",
            fixedDelayString = "${payment.anomaly.cleanup-delay-ms:60000}")
    public void scheduledRun() {
        try {
            int deleted = runOnce();
            if (deleted > 0) {
                LOGGER.info("deleted {} payment anomalies older than {} days",
                        deleted, retention.toDays());
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("payment anomaly retention run failed", failure);
        }
    }

    /** 至多删除一个有界批次，并返回删除了多少行。 */
    @Transactional
    public int runOnce() {
        Instant cutoff = clock.instant().minus(retention);
        return repository.deleteAnomaliesCreatedBefore(cutoff, batchSize);
    }
}
