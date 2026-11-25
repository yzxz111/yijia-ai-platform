package com.yuzhihuihe.apiplatform.platform.safety;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class AccountEnforcementBaselineJob {

    private static final Logger LOG =
            LoggerFactory.getLogger(AccountEnforcementBaselineJob.class);

    private final AccountEnforcementRepository repository;
    private final AtomicBoolean completed = new AtomicBoolean();

    public AccountEnforcementBaselineJob(AccountEnforcementRepository repository) {
        this.repository = repository;
    }

    @Scheduled(
            initialDelayString = "${content-safety.enforcement-baseline-initial-delay:5s}",
            fixedDelayString = "${content-safety.enforcement-baseline-fixed-delay:1m}")
    public void synchronize() {
        if (completed.get()) {
            return;
        }
        try {
            int initialized = repository.initializeMissingGatewayCacheBaselines();
            completed.set(true);
            if (initialized > 0) {
                LOG.info("initialized {} account enforcement cache baselines", initialized);
            }
        } catch (RuntimeException failure) {
            LOG.warn("account enforcement cache baseline synchronization failed", failure);
        }
    }
}
