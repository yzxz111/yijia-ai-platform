package com.yuzhihuihe.apiplatform.platform.reporting;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OperatingAggregationJob {

    private final OperatingAggregationService service;

    public OperatingAggregationJob(OperatingAggregationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${reporting.aggregation-delay-ms:5000}")
    public void recomputeDirtyDates() {
        service.recomputeDirtyDates(31);
    }
}
