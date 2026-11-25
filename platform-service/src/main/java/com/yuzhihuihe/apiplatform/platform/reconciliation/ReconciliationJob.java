package com.yuzhihuihe.apiplatform.platform.reconciliation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "reconciliation.job-enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private final ReconciliationService service;
    private final ZoneId zone;
    private final Clock clock;

    public ReconciliationJob(
            ReconciliationService service,
            @Value("${reconciliation.zone:Asia/Shanghai}") String zone,
            Clock clock) {
        this.service = service;
        this.zone = ZoneId.of(zone);
        this.clock = clock;
    }

    @Scheduled(
            cron = "${reconciliation.daily-cron:0 30 2 * * *}",
            zone = "${reconciliation.zone:Asia/Shanghai}")
    public void runDaily() {
        LocalDate billDate = LocalDate.now(clock.withZone(zone)).minusDays(1);
        for (var provider : service.enabledProviders()) {
            try {
                service.start(provider, billDate, null);
            } catch (RuntimeException ignored) {
                // 运行记录行与有界指标会保留该失败信息，而不会暴露提供方数据。
            }
        }
    }
}
