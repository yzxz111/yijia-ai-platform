package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BrowserSessionService {
    private final BrowserSessionRepository pages;
    private final TerminalSessionRepository sessions;
    private final DeviceIdentityService devices;
    private final OpaqueTokenService tokens;
    private final TerminalPresenceService presence;

    BrowserSessionService(BrowserSessionRepository pages, TerminalSessionRepository sessions,
            DeviceIdentityService devices, OpaqueTokenService tokens, TerminalPresenceService presence) {
        this.pages = pages;
        this.sessions = sessions;
        this.devices = devices;
        this.tokens = tokens;
        this.presence = presence;
    }

    @Transactional
    void heartbeat(LoginHandoffService.AuthenticatedTerminal terminal, String deviceId,
            UUID pageId, long sequence) {
        if (!TerminalSessionService.CLIENT_TYPE.equals(terminal.clientType())) {
            throw IdentityApiException.invalidHandoffDevice();
        }
        record(terminal.sessionId(), terminal.user().tenantId(), terminal.user().id(),
                devices.hashRequired(deviceId), pageId, sequence, false);
        presence.markPresent(terminal.user().id(), terminal.sessionId());
    }

    @Transactional
    void close(String refreshToken, String csrfToken, String deviceId, UUID pageId, long sequence) {
        var row = sessions.findRefreshByTokenHash(tokens.hash(refreshToken))
                .orElseThrow(IdentityApiException::invalidCredentials);
        if (csrfToken == null || csrfToken.isBlank() || !tokens.hash(csrfToken).equals(row.csrfTokenHash())) {
            throw IdentityApiException.invalidCsrfToken();
        }
        // A request captured just before refresh rotation may still close only its own active session.
        boolean recentlyRotated = "ROTATED".equals(row.revocationReason()) && row.revokedAt() != null
                && row.revokedAt().toInstant().isAfter(Instant.now().minusSeconds(60));
        if ((row.revokedAt() != null && !recentlyRotated) || !row.expiresAt().isAfter(Instant.now())
                || row.terminalSessionId() == null) throw IdentityApiException.invalidCredentials();
        String deviceHash = devices.hashRequired(deviceId);
        if (!TerminalSessionService.CLIENT_TYPE.equals(row.clientType()) || !deviceHash.equals(row.deviceHash())) {
            throw IdentityApiException.invalidHandoffDevice();
        }
        record(row.terminalSessionId(), row.user().tenantId(), row.user().id(), deviceHash, pageId, sequence, true);
    }

    private void record(UUID sessionId, UUID tenantId, UUID userId, String deviceHash,
            UUID pageId, long sequence, boolean closed) {
        if (pageId == null || sequence < 0 || !pages.recordEvent(
                sessionId, tenantId, userId, deviceHash, pageId, sequence, closed)) {
            throw IdentityApiException.invalidHandoffDevice();
        }
    }

    @Scheduled(fixedDelayString = "${identity.terminal-session.cleanup-delay:3600000}")
    void cleanup() { pages.deleteInactivePages(); }
}
