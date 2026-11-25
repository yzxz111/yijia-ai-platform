package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminNotificationBroadcastControllerTest {

    @Test
    void exposesCompleteAdminWorkflowAndPropagatesAuthenticatedActor() {
        NotificationBroadcastService service = mock(NotificationBroadcastService.class);
        AdminNotificationBroadcastController controller =
                new AdminNotificationBroadcastController(service);
        UUID actor = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(actor.toString()).claim("role", "ADMIN")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        AdminNotificationBroadcastController.CreateRequest create =
                new AdminNotificationBroadcastController.CreateRequest(
                        "title", "body", "tomorrow", "business-1", null,
                        NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of());
        AdminNotificationBroadcastController.EditRequest edit =
                new AdminNotificationBroadcastController.EditRequest(
                        "title-2", "body-2", "later", "business-2", null,
                        NotificationBroadcastService.AudienceKind.USER_IDS, List.of(UUID.randomUUID()));
        Instant scheduledAt = Instant.parse("2026-08-09T05:00:00Z");

        controller.create(create, jwt);
        controller.edit(id, edit, jwt);
        controller.schedule(id,
                new AdminNotificationBroadcastController.ScheduleRequest(scheduledAt), jwt);
        controller.publish(id, jwt);
        controller.pause(id, jwt);
        controller.resume(id,
                new AdminNotificationBroadcastController.ScheduleRequest(scheduledAt), jwt);
        controller.cancel(id, jwt);
        controller.list(0, 20, "SCHEDULED");
        controller.detail(id);

        verify(service).create(create.toCommand(), actor);
        verify(service).edit(id, edit.toCommand(), actor);
        verify(service).schedule(id, scheduledAt, actor);
        verify(service).publish(id, actor);
        verify(service).pause(id, actor);
        verify(service).resume(id, scheduledAt, actor);
        verify(service).cancel(id, actor);
        verify(service).list(0, 20, "SCHEDULED");
        verify(service).detail(id);
    }

    @Test
    void controllerIsMountedUnderAdminBroadcastPathWithExpectedHttpMethods() {
        var mapping = AdminNotificationBroadcastController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/console/platform/admin/notification-broadcasts");

        Map<String, String> methods = Map.of(
                "create", "PostMapping", "edit", "PatchMapping", "schedule", "PostMapping",
                "publish", "PostMapping", "pause", "PostMapping", "resume", "PostMapping", "cancel", "PostMapping",
                "list", "GetMapping", "detail", "GetMapping");
        methods.forEach((name, annotation) -> {
            var method = java.util.Arrays.stream(
                    AdminNotificationBroadcastController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            assertThat(java.util.Arrays.stream(method.getAnnotations())
                    .map(value -> value.annotationType().getSimpleName()))
                    .contains(annotation);
        });
    }
}
