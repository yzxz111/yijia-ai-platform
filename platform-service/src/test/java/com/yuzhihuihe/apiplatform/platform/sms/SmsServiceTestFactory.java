package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;

public final class SmsServiceTestFactory {

    private SmsServiceTestFactory() {
    }

    public static SmsNotificationService notification(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SecretEncryptionService encryption,
            Clock clock) {
        return new SmsNotificationService(
                new SmsNotificationRepository(jdbc), objectMapper, encryption, clock);
    }
}
