package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class RechargeOrderExpiryJob {

    private final RechargeOrderRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;

    RechargeOrderExpiryJob(
            RechargeOrderRepository repository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${payment.jobs.recharge-expiry-batch-size:${payment.jobs.batch-size:50}}")
            int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("recharge expiry batch size is out of range");
        }
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${payment.jobs.recharge-expiry-delay-ms:5000}",
            initialDelayString = "${payment.jobs.recharge-expiry-delay-ms:5000}")
    public void scheduledRun() {
        runOnce();
    }

    int runOnce() {
        Integer closed = transactions.execute(ignored -> {
            Instant now = clock.instant();
            return repository.closeExpiredBatch(now, batchSize);
        });
        return closed == null ? 0 : closed;
    }
}
