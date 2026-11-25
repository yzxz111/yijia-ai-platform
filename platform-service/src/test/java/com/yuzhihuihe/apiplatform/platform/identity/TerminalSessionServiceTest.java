package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.safety.AccountEnforcementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TerminalSessionServiceTest {

    private static final String PEPPER = "terminal-device-test-pepper-value-32";
    private static final String DEVICE_ID = "0123456789abcdef0123456789abcdef";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DeviceIdentityService devices = new DeviceIdentityService(PEPPER, false);
    private final TerminalPresenceService presence = mock(TerminalPresenceService.class);
    private TerminalSessionService service;

    @BeforeEach
    void setUp() {
        service = new TerminalSessionService(
                new TerminalSessionRepository(jdbc), null, null, devices, presence,
                mock(AccountEnforcementRepository.class));
    }

    @Test
    void acceptsOnlyAnActiveTerminalWithTheMatchingHmacDeviceHash() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String expectedHash = devices.hashRequired(DEVICE_ID);
        when(jdbc.queryForObject(
                anyString(), eq(Integer.class), eq(sessionId), eq(tenantId), eq(userId),
                eq(TerminalSessionService.CLIENT_TYPE), eq(expectedHash))).thenReturn(1);

        service.validateDevice(
                tenantId, userId, sessionId, TerminalSessionService.CLIENT_TYPE, DEVICE_ID);

        verify(jdbc).queryForObject(
                anyString(), eq(Integer.class), eq(sessionId), eq(tenantId), eq(userId),
                eq(TerminalSessionService.CLIENT_TYPE), eq(expectedHash));
        assertThat(expectedHash).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsADeviceThatDoesNotMatchTheActiveTerminal() {
        when(jdbc.queryForObject(
                anyString(), eq(Integer.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq(TerminalSessionService.CLIENT_TYPE),
                anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.validateDevice(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TerminalSessionService.CLIENT_TYPE, DEVICE_ID))
                .isInstanceOfSatisfying(IdentityApiException.class,
                        error -> assertThat(error.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void rejectsMalformedDeviceIdsWithoutQueryingTheTerminalTable() {
        assertThatThrownBy(() -> service.validateDevice(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TerminalSessionService.CLIENT_TYPE, "malformed-device"))
                .isInstanceOfSatisfying(IdentityApiException.class,
                        error -> assertThat(error.code()).isEqualTo("invalid_credentials"));

        verify(jdbc, never()).queryForObject(
                anyString(), eq(Integer.class),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void marksOnlyTheAuthenticatedDeviceForDelayedBrowserClose() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String expectedHash = devices.hashRequired(DEVICE_ID);
        when(jdbc.update(
                anyString(), org.mockito.ArgumentMatchers.any(Timestamp.class),
                eq(sessionId), eq(tenantId), eq(userId),
                eq(TerminalSessionService.CLIENT_TYPE), eq(expectedHash))).thenReturn(1);

        service.requestBrowserClose(
                tenantId, userId, sessionId, TerminalSessionService.CLIENT_TYPE, DEVICE_ID);

        verify(jdbc).update(
                anyString(), org.mockito.ArgumentMatchers.any(Timestamp.class),
                eq(sessionId), eq(tenantId), eq(userId),
                eq(TerminalSessionService.CLIENT_TYPE), eq(expectedHash));
    }

    @Test
    void heartbeatCancelsAPendingBrowserClose() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String expectedHash = devices.hashRequired(DEVICE_ID);
        when(jdbc.update(
                anyString(), eq(sessionId), eq(tenantId), eq(userId),
                eq(TerminalSessionService.CLIENT_TYPE), eq(expectedHash))).thenReturn(1);

        service.heartbeat(
                tenantId, userId, sessionId, TerminalSessionService.CLIENT_TYPE, DEVICE_ID);

        verify(jdbc).update(
                anyString(), eq(sessionId), eq(tenantId), eq(userId),
                eq(TerminalSessionService.CLIENT_TYPE), eq(expectedHash));
    }
}
