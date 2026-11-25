package com.yuzhihuihe.apiplatform.platform.reporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public final class ReportingServiceTestFactory {

    private ReportingServiceTestFactory() {
    }

    public static OperatingAggregationService aggregation(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new OperatingAggregationService(
                new OperatingAggregationRepository(jdbc), transactionManager);
    }

    public static OperatingReportQueryService reportQuery(JdbcTemplate jdbc) {
        return new OperatingReportQueryService(new OperatingReportQueryRepository(jdbc));
    }
}
