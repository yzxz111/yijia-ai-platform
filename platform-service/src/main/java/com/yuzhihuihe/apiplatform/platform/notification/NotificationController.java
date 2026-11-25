package com.yuzhihuihe.apiplatform.platform.notification;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform")
public class NotificationController {

    private final NotificationQueryService queries;
    private final NotificationPreferenceService preferences;

    public NotificationController(
            NotificationQueryService queries, NotificationPreferenceService preferences) {
        this.queries = queries;
        this.preferences = preferences;
    }

    @GetMapping("/notifications")
    NotificationQueryService.NotificationPage notifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String read,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        int pageNumber = number(page, "invalid_notification_page");
        int pageSize = number(size, "invalid_notification_page_size");
        if (pageNumber < 0) throw invalid("invalid_notification_page");
        if (pageSize < 1 || pageSize > 100) throw invalid("invalid_notification_page_size");
        if (pageNumber > Integer.MAX_VALUE / pageSize) throw invalid("invalid_notification_page");
        return queries.list(tenant(jwt), user(jwt), category, bool(read), pageNumber, pageSize);
    }

    @GetMapping("/notifications/unread-count")
    UnreadCount unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCount(queries.unreadCount(tenant(jwt), user(jwt)));
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        queries.markRead(tenant(jwt), user(jwt), notificationId(id));
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        queries.markAllRead(tenant(jwt), user(jwt));
    }

    @PostMapping("/notifications/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        queries.archive(tenant(jwt), user(jwt), notificationId(id));
    }

    @GetMapping("/notification-preferences")
    List<NotificationPreferenceService.PreferenceView> notificationPreferences(
            @AuthenticationPrincipal Jwt jwt) {
        return preferences.list(tenant(jwt), user(jwt));
    }

    @PutMapping("/notification-preferences")
    List<NotificationPreferenceService.PreferenceView> updateNotificationPreferences(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) PreferenceRequest request) {
        if (request == null || request.preferences() == null) {
            throw invalid("invalid_notification_preferences");
        }
        return preferences.update(tenant(jwt), user(jwt), request.preferences());
    }

    @PostMapping("/notification-preferences/unsubscribe-sms")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribeSms(@AuthenticationPrincipal Jwt jwt) {
        preferences.unsubscribeSms(tenant(jwt), user(jwt));
    }

    private static Boolean bool(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("invalid_notification_read_filter");
        };
    }

    private static int number(String value, String reason) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException exception) {
            throw invalid(reason);
        }
    }

    private static UUID notificationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw invalid("invalid_notification_id");
        }
    }

    private static UUID tenant(Jwt jwt) {
        return identity(jwt == null ? null : jwt.getClaimAsString("tenant_id"));
    }

    private static UUID user(Jwt jwt) {
        return identity(jwt == null ? null : jwt.getSubject());
    }

    private static UUID identity(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record UnreadCount(long count) { }

    record PreferenceRequest(List<NotificationPreferenceService.PreferenceUpdate> preferences) { }
}
