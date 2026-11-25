package com.yuzhihuihe.apiplatform.platform.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "platform.wallet.usage-expiry.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WalletUsageReservationExpiryJob {

    private static final Logger LOG =
            LoggerFactory.getLogger(WalletUsageReservationExpiryJob.class);

    private final WalletUsageReservationService reservations;
    private final int batchSize;

    public WalletUsageReservationExpiryJob(
            WalletUsageReservationService reservations,
            @Value("${platform.wallet.usage-expiry.batch-size:100}") int batchSize) {
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("invalid_wallet_expiry_batch_size");
        }
        this.reservations = reservations;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${platform.wallet.usage-expiry.initial-delay:60s}",
            fixedDelayString = "${platform.wallet.usage-expiry.fixed-delay:30s}")
    public void expireOnce() {
        for (java.util.UUID reservationId : reservations.expiredReservationIds(batchSize)) {
            try {
                reservations.expire(reservationId);
            } catch (RuntimeException failure) {
                LOG.warn("wallet reservation expiry failed for {}", reservationId, failure);
            }
        }
    }
}
