package com.yuzhihuihe.apiplatform.platform.support;

import org.springframework.jdbc.core.JdbcTemplate;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;

public final class SupportServiceTestFactory {

    private SupportServiceTestFactory() {
    }

    public static SupportTicketService ticket(
            JdbcTemplate jdbc, NotificationEventService notifications) {
        return new SupportTicketService(new SupportTicketRepository(jdbc), notifications);
    }
}
