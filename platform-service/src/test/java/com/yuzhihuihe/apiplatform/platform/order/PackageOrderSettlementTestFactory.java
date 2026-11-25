package com.yuzhihuihe.apiplatform.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.grant.PackageFulfillmentService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PackageOrderSettlementTestFactory {

    private PackageOrderSettlementTestFactory() {
    }

    public static PackageOrderSettlementService create(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PackageSnapshotService snapshotService,
            PackageFulfillmentService fulfillmentService,
            CommercialNotificationService notifications) {
        return new PackageOrderSettlementService(
                new PackageOrderSettlementRepository(jdbc),
                objectMapper,
                snapshotService,
                fulfillmentService,
                null,
                notifications,
                null,
                null);
    }
}
