package com.yuzhihuihe.apiplatform.platform.reporting;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OperatingReconciliationJob {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final OperatingReconciliationService service;
    private final Clock clock;

    @Autowired
    public OperatingReconciliationJob(OperatingReconciliationService service) {
        this(service, Clock.systemUTC());
    }

    OperatingReconciliationJob(OperatingReconciliationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${reporting.reconciliation-cron:0 15 2 * * *}", zone = "Asia/Shanghai")
    public void reconcilePreviousDay() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        Instant from = today.minusDays(1).atStartOfDay(ZONE).toInstant();
        Instant to = today.atStartOfDay(ZONE).toInstant();
        service.reconcile(from, to);
    }
}
