package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

public final class CommercialNotificationTestFactory {

    private CommercialNotificationTestFactory() {
    }

    public static CommercialNotificationService create(
            JdbcTemplate jdbc, NotificationEventService events, Clock clock) {
        return new CommercialNotificationService(
                new CommercialNotificationRepository(jdbc), events, clock);
    }
}
