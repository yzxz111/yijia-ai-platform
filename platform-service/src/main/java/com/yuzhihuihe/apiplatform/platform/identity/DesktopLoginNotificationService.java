package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.springframework.stereotype.Service;

@Service
final class DesktopLoginNotificationService {

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationEventService events;

    DesktopLoginNotificationService(NotificationEventService events) {
        this.events = events;
    }

    void record(
            IdentityService.UserProfile user,
            UUID terminalSessionId,
            String mode,
            LoginClientSummaryService.ClientSummary client,
            OffsetDateTime occurredAt) {
        events.recordAuthoritativeBusinessEvent(
                "desktop-login-switched:v1:" + terminalSessionId,
                "DESKTOP_LOGIN_SWITCHED",
                user.tenantId(), user.id(),
                "TERMINAL_SESSION", terminalSessionId.toString(),
                Map.of(
                        "switchTime", occurredAt.withOffsetSameInstant(ZoneOffset.ofHours(8)).format(DISPLAY_TIME),
                        "region", client.region(),
                        "clientSummary", client.userAgentSummary(),
                        "handoffMode", mode),
                occurredAt);
    }
}
