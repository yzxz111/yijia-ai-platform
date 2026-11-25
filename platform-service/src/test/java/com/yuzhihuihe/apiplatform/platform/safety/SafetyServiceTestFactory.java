package com.yuzhihuihe.apiplatform.platform.safety;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SafetyServiceTestFactory {

    private SafetyServiceTestFactory() {
    }

    public static ContentSafetyAdminService admin(
            JdbcTemplate jdbc,
            ContentSafetyCaseService cases,
            NotificationEventService notifications) {
        return new ContentSafetyAdminService(
                new ContentSafetyAdminRepository(jdbc), cases, notifications);
    }
}
