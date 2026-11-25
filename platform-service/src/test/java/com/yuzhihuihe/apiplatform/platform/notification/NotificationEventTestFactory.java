package com.yuzhihuihe.apiplatform.platform.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

public final class NotificationEventTestFactory {

    private NotificationEventTestFactory() {
    }

    public static NotificationEventService create(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new NotificationEventService(new NotificationEventRepository(jdbc), objectMapper);
    }
}
