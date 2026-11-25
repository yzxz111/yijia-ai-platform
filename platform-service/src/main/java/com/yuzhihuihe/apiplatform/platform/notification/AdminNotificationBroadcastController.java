package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/admin/notification-broadcasts")
public class AdminNotificationBroadcastController {
    private final NotificationBroadcastService service;

    public AdminNotificationBroadcastController(NotificationBroadcastService service) {
        this.service = service;
    }

    @PostMapping
    NotificationBroadcastService.BroadcastView create(
            @RequestBody CreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(request.toCommand(), actor(jwt));
    }

    @PatchMapping("/{id}")
    NotificationBroadcastService.BroadcastView edit(
            @PathVariable UUID id, @RequestBody EditRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.edit(id, request.toCommand(), actor(jwt));
    }

    @PostMapping("/{id}/schedule")
    NotificationBroadcastService.BroadcastView schedule(
            @PathVariable UUID id, @RequestBody ScheduleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.schedule(id, request.scheduledAt(), actor(jwt));
    }

    @PostMapping("/{id}/publish")
    NotificationBroadcastService.BroadcastView publish(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.publish(id, actor(jwt));
    }

    @PostMapping("/{id}/pause")
    NotificationBroadcastService.BroadcastView pause(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.pause(id, actor(jwt));
    }

    @PostMapping("/{id}/resume")
    NotificationBroadcastService.BroadcastView resume(
            @PathVariable UUID id, @RequestBody ScheduleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.resume(id, request.scheduledAt(), actor(jwt));
    }

    @PostMapping("/{id}/cancel")
    NotificationBroadcastService.BroadcastView cancel(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(id, actor(jwt));
    }

    @GetMapping
    NotificationBroadcastService.PageResult list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return service.list(page, size, status);
    }

    @GetMapping("/{id}")
    NotificationBroadcastService.BroadcastView detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record CreateRequest(String title, String body, String maintainTime,
            String providerBusinessNo, UUID tenantId,
            NotificationBroadcastService.AudienceKind audienceKind, List<UUID> userIds) {
        NotificationBroadcastService.CreateCommand toCommand() {
            return new NotificationBroadcastService.CreateCommand(title, body, maintainTime,
                    providerBusinessNo, tenantId, audienceKind, userIds);
        }
    }

    public record EditRequest(String title, String body, String maintainTime,
            String providerBusinessNo, UUID tenantId,
            NotificationBroadcastService.AudienceKind audienceKind, List<UUID> userIds) {
        NotificationBroadcastService.EditCommand toCommand() {
            return new NotificationBroadcastService.EditCommand(title, body, maintainTime,
                    providerBusinessNo, tenantId, audienceKind, userIds);
        }
    }

    public record ScheduleRequest(Instant scheduledAt) { }
}
