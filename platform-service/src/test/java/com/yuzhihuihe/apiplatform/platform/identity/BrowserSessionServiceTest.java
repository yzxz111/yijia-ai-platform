package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrowserSessionServiceTest {
    private final BrowserSessionRepository pages = mock(BrowserSessionRepository.class);
    private final TerminalSessionRepository sessions = mock(TerminalSessionRepository.class);
    private final DeviceIdentityService devices = mock(DeviceIdentityService.class);
    private final OpaqueTokenService tokens = new OpaqueTokenService();
    private final BrowserSessionService service = new BrowserSessionService(
            pages, sessions, devices, tokens, mock(TerminalPresenceService.class));
    private final UUID pageId = UUID.randomUUID();

    private void credential(OffsetDateTime revokedAt, String reason, Instant expiry) {
        var user = new IdentityService.UserProfile(UUID.randomUUID(), UUID.randomUUID(), "test@example.com", "Test", "USER");
        when(sessions.findRefreshByTokenHash(tokens.hash("refresh"))).thenReturn(Optional.of(
                new TerminalSessionRepository.RefreshTokenRow(UUID.randomUUID(), expiry, revokedAt, reason,
                        tokens.hash("csrf"), UUID.randomUUID(), "DESKTOP_WEB", "device-hash", user)));
        when(devices.hashRequired("device")).thenReturn("device-hash");
        when(pages.recordEvent(any(), any(), any(), eq("device-hash"), eq(pageId), eq(2L), eq(true))).thenReturn(true);
    }

    @Test
    void requiresSessionBoundCsrfEvenWhenTheRefreshCookieIsValid() {
        credential(null, null, Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> service.close("refresh", null, "device", pageId, 2))
                .isInstanceOfSatisfying(IdentityApiException.class,
                        error -> assertThat(error.code()).isEqualTo("invalid_csrf_token"));
        verifyNoInteractions(pages);
    }

    @Test
    void rejectsRevokedAndExpiredRefreshCredentials() {
        credential(OffsetDateTime.now(), "LOGOUT", Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> service.close("refresh", "csrf", "device", pageId, 2)).isInstanceOf(IdentityApiException.class);
        credential(null, null, Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.close("refresh", "csrf", "device", pageId, 2)).isInstanceOf(IdentityApiException.class);
        verifyNoInteractions(pages);
    }

    @Test
    void acceptsARecentRotationOnlyForTheSameActiveBoundSession() {
        credential(OffsetDateTime.now().minusSeconds(5), "ROTATED", Instant.now().plusSeconds(3600));
        service.close("refresh", "csrf", "device", pageId, 2);
        verify(pages).recordEvent(any(), any(), any(), eq("device-hash"), eq(pageId), eq(2L), eq(true));
    }

    @Test
    void rejectsAnOldRotatedCredential() {
        credential(OffsetDateTime.now().minusSeconds(65), "ROTATED", Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> service.close("refresh", "csrf", "device", pageId, 2)).isInstanceOf(IdentityApiException.class);
        verifyNoInteractions(pages);
    }
}
