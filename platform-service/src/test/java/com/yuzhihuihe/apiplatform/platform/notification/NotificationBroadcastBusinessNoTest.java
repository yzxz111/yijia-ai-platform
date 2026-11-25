package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class NotificationBroadcastBusinessNoTest {

    @Test
    void generatesUuidBackedNumberWhenRequestValueIsBlank() {
        UUID id = UUID.fromString("10000000-0000-4000-8000-000000000001");

        assertThat(NotificationBroadcastService.businessNo(null, id))
                .isEqualTo("broadcast-10000000-0000-4000-8000-000000000001");
        assertThat(NotificationBroadcastService.businessNo("  ", id))
                .isEqualTo("broadcast-10000000-0000-4000-8000-000000000001");
        assertThat(NotificationBroadcastService.businessNo("legacy-1", id))
                .isEqualTo("legacy-1");
    }

    @Test
    void mapsDuplicateExplicitNumberToConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "uq_notification_broadcast_provider_business_no"));
        NotificationBroadcastService service = new NotificationBroadcastService(
                new NotificationBroadcastRepository(jdbc), new ObjectMapper(), Clock.systemUTC());
        var command = new NotificationBroadcastService.CreateCommand(
                "Maintenance", "Maintenance body", "2026-08-16 18:00",
                "legacy-duplicate", null,
                NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of());

        assertThatThrownBy(() -> service.create(command, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason())
                            .isEqualTo("notification_broadcast_business_no_conflict");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsDuplicateNumberOnLegacyEditToConflict() throws Exception {
        UUID broadcastId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("id", UUID.class)).thenReturn(broadcastId);
        when(result.getString("status")).thenReturn("DRAFT");
        when(result.getObject("tenant_id", UUID.class)).thenReturn(tenantId);
        when(result.getString("audience_type")).thenReturn("ALL_ACTIVE_USERS");
        when(result.getString("audience_filter")).thenReturn("{\"userIds\":[]}");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "uq_notification_broadcast_provider_business_no"));
        NotificationBroadcastService service = new NotificationBroadcastService(
                new NotificationBroadcastRepository(jdbc), new ObjectMapper(), Clock.systemUTC());
        var command = new NotificationBroadcastService.EditCommand(
                "Maintenance", "Maintenance body", "2026-08-16 18:00",
                "legacy-duplicate", tenantId,
                NotificationBroadcastService.AudienceKind.ALL_ACTIVE_USERS, List.of());

        assertThatThrownBy(() -> service.edit(broadcastId, command, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason())
                            .isEqualTo("notification_broadcast_business_no_conflict");
                });
    }
}
