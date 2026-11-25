package com.yuzhihuihe.apiplatform.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.safety.AccountEnforcementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TerminalSessionService {

    static final String CLIENT_TYPE = "DESKTOP_WEB";
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final TerminalSessionRepository sessions;
    private final JwtTokenService jwtTokens;
    private final OpaqueTokenService opaqueTokens;
    private final DeviceIdentityService devices;
    private final TerminalPresenceService presence;
    private final AccountEnforcementRepository accountEnforcements;

    TerminalSessionService(
            TerminalSessionRepository sessions,
            JwtTokenService jwtTokens,
            OpaqueTokenService opaqueTokens,
            DeviceIdentityService devices,
            TerminalPresenceService presence,
            AccountEnforcementRepository accountEnforcements) {
        this.sessions = sessions;
        this.jwtTokens = jwtTokens;
        this.opaqueTokens = opaqueTokens;
        this.devices = devices;
        this.presence = presence;
        this.accountEnforcements = accountEnforcements;
    }

    @Transactional
    TerminalGrant create(IdentityService.UserProfile user, String deviceHash) {
        lockUser(user);
        UUID sessionId = insertTerminal(user, deviceHash);
        return issueForExisting(user, sessionId, deviceHash, false);
    }

    @Transactional
    TerminalGrant replace(
            IdentityService.UserProfile user,
            UUID expectedOldSessionId,
            String newDeviceHash,
            String reason) {
        UUID sessionId = prepareReplacement(user, expectedOldSessionId, newDeviceHash, reason);
        return issueForExisting(user, sessionId, newDeviceHash, false);
    }

    @Transactional
    UUID prepareReplacement(
            IdentityService.UserProfile user,
            UUID expectedOldSessionId,
            String newDeviceHash,
            String reason) {
        lockUser(user);
        int revoked = sessions.revokeTerminalForReplacement(
                expectedOldSessionId, user.tenantId(), user.id(), reason);
        if (revoked != 1) throw IdentityApiException.sessionReplaced();
        sessions.revokeRefreshesForTerminal(expectedOldSessionId, reason);
        return insertTerminal(user, newDeviceHash);
    }

    @Transactional
    TerminalGrant issueForExisting(
            IdentityService.UserProfile user,
            UUID terminalSessionId,
            String deviceHash,
            boolean completionRetry) {
        if (!sessions.activeTerminalMatches(
                terminalSessionId, user.tenantId(), user.id(), CLIENT_TYPE, deviceHash)) {
            throw IdentityApiException.sessionReplaced();
        }
        if (completionRetry) {
            sessions.revokeRefreshesForTerminal(terminalSessionId, "COMPLETION_RETRY");
        }
        IdentityService.AccessSession session = issue(user, terminalSessionId, deviceHash);
        presence.markPresent(user.id(), terminalSessionId);
        return new TerminalGrant(terminalSessionId, session);
    }

    @Transactional
    TerminalGrant refresh(String rawRefreshToken, String rawCsrfToken, String rawDeviceId) {
        String tokenHash = opaqueTokens.hash(rawRefreshToken);
        TerminalSessionRepository.RefreshTokenRow row = sessions.lockRefreshByTokenHash(tokenHash)
                .orElseThrow(IdentityApiException::invalidCredentials);
        requireCsrf(row.csrfTokenHash(), rawCsrfToken);
        if (row.revokedAt() != null) {
            if (isReplacement(row.revocationReason())) throw IdentityApiException.sessionReplaced();
            throw IdentityApiException.invalidCredentials();
        }
        if (!row.expiresAt().isAfter(Instant.now())) {
            revokeRefresh(row.id(), "TOKEN_EXPIRED");
            throw IdentityApiException.invalidCredentials();
        }

        String deviceHash = devices.hashRequired(rawDeviceId);
        if (row.terminalSessionId() == null) {
            return adoptLegacy(row, deviceHash);
        }
        if (!CLIENT_TYPE.equals(row.clientType()) || !deviceHash.equals(row.deviceHash())) {
            throw IdentityApiException.invalidHandoffDevice();
        }
        requireActiveTerminal(row.user(), row.terminalSessionId(), deviceHash);
        revokeRefresh(row.id(), "ROTATED");
        Instant newExpiry = Instant.now().plus(REFRESH_TTL);
        sessions.updateTerminalExpiry(row.terminalSessionId(), newExpiry);
        IdentityService.AccessSession session = issue(row.user(), row.terminalSessionId(), deviceHash, newExpiry);
        presence.markPresent(row.user().id(), row.terminalSessionId());
        return new TerminalGrant(row.terminalSessionId(), session);
    }

    /**
     * 为已经处于活动状态的终端会话签发 DESKTOP_WEB 访问令牌，而不
     * 创建浏览器刷新令牌。桌面端 OAuth 令牌端点维护它自己的
     * 轮换刷新令牌，因此这里只需要签名后的访问令牌。
     */
    @Transactional
    String issueDesktopAccessToken(
            IdentityService.UserProfile user, UUID terminalSessionId, String deviceHash) {
        if (!sessions.activeTerminalMatches(
                terminalSessionId, user.tenantId(), user.id(), CLIENT_TYPE, deviceHash)) {
            throw IdentityApiException.sessionReplaced();
        }
        requireSafetyAllowed(user);
        presence.markPresent(user.id(), terminalSessionId);
        return jwtTokens.createAccessToken(user, terminalSessionId, CLIENT_TYPE);
    }

    @Transactional(readOnly = true)
    void validateDevice(
            UUID tenantId,
            UUID userId,
            UUID terminalSessionId,
            String clientType,
            String rawDeviceId) {
        if (!CLIENT_TYPE.equals(clientType)) throw IdentityApiException.invalidCredentials();
        String deviceHash;
        try {
            deviceHash = devices.hashRequired(rawDeviceId);
        } catch (IdentityApiException invalidDevice) {
            throw IdentityApiException.invalidCredentials();
        }
        if (!sessions.activeTerminalMatches(
                terminalSessionId, tenantId, userId, clientType, deviceHash)) {
            throw IdentityApiException.invalidCredentials();
        }
    }

    @Transactional(noRollbackFor = IdentityApiException.class)
    void heartbeat(
            UUID tenantId,
            UUID userId,
            UUID terminalSessionId,
            String clientType,
            String rawDeviceId) {
        if (!CLIENT_TYPE.equals(clientType)) throw IdentityApiException.invalidHandoffDevice();
        String deviceHash = devices.hashRequired(rawDeviceId);
        if (!sessions.continueActiveTerminal(
                terminalSessionId, tenantId, userId, clientType, deviceHash)) {
            throw IdentityApiException.invalidHandoffDevice();
        }
        presence.markPresent(userId, terminalSessionId);
    }

    @Transactional
    void requestBrowserClose(
            UUID tenantId,
            UUID userId,
            UUID terminalSessionId,
            String clientType,
            String rawDeviceId) {
        if (!CLIENT_TYPE.equals(clientType)) throw IdentityApiException.invalidHandoffDevice();
        String deviceHash = devices.hashRequired(rawDeviceId);
        if (!sessions.requestBrowserClose(
                terminalSessionId, tenantId, userId, clientType, deviceHash, Instant.now())) {
            throw IdentityApiException.invalidHandoffDevice();
        }
    }

    @Transactional
    void logout(String rawRefreshToken, String rawCsrfToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String tokenHash = opaqueTokens.hash(rawRefreshToken);
        TerminalSessionRepository.LogoutTokenRow row = sessions.lockLogoutByTokenHash(tokenHash)
                .orElse(null);
        if (row == null) return;
        requireCsrf(row.csrfTokenHash(), rawCsrfToken);
        if (row.revokedAt() != null || !row.expiresAt().isAfter(Instant.now())) return;
        if (row.terminalSessionId() == null) {
            sessions.revokeLegacyRefreshByTokenHash(tokenHash);
            return;
        }
        UUID sessionId = row.terminalSessionId();
        sessions.revokeTerminal(sessionId, "LOGOUT");
        sessions.revokeRefreshesForTerminal(sessionId, "LOGOUT");
    }

    @Transactional
    void revokeAll(UUID tenantId, UUID userId, String reason) {
        sessions.revokeAll(tenantId, userId, reason);
    }

    private TerminalGrant adoptLegacy(
            TerminalSessionRepository.RefreshTokenRow row, String deviceHash) {
        lockUser(row.user());
        if (sessions.countActiveTerminals(row.user().id()) > 0) {
            revokeRefresh(row.id(), "LEGACY_SESSION_REPLACED");
            throw IdentityApiException.sessionReplaced();
        }
        UUID sessionId = insertTerminal(row.user(), deviceHash);
        sessions.revokeLegacyRefreshes(row.user().id());
        IdentityService.AccessSession session = issue(row.user(), sessionId, deviceHash);
        presence.markPresent(row.user().id(), sessionId);
        return new TerminalGrant(sessionId, session);
    }

    private void requireActiveTerminal(
            IdentityService.UserProfile user, UUID terminalSessionId, String deviceHash) {
        if (!sessions.activeTerminalMatches(
                terminalSessionId, user.tenantId(), user.id(), CLIENT_TYPE, deviceHash)) {
            throw IdentityApiException.sessionReplaced();
        }
    }

    private void lockUser(IdentityService.UserProfile user) {
        if (!sessions.lockUser(user)) throw IdentityApiException.invalidCredentials();
    }

    private UUID insertTerminal(IdentityService.UserProfile user, String deviceHash) {
        return sessions.insertTerminal(user, deviceHash, Instant.now().plus(REFRESH_TTL));
    }

    private IdentityService.AccessSession issue(
            IdentityService.UserProfile user, UUID sessionId, String deviceHash) {
        return issue(user, sessionId, deviceHash, Instant.now().plus(REFRESH_TTL));
    }

    private IdentityService.AccessSession issue(
            IdentityService.UserProfile user,
            UUID sessionId,
            String deviceHash,
            Instant refreshExpiry) {
        requireSafetyAllowed(user);
        String rawRefresh = opaqueTokens.generate();
        String rawCsrf = opaqueTokens.generate();
        sessions.insertRefresh(
                user, UUID.randomUUID(), UUID.randomUUID().toString(),
                opaqueTokens.hash(rawRefresh), refreshExpiry, sessionId, deviceHash,
                opaqueTokens.hash(rawCsrf));
        return new IdentityService.AccessSession(
                jwtTokens.createAccessToken(user, sessionId, CLIENT_TYPE),
                JwtTokenService.ACCESS_TOKEN_TTL.toSeconds(), rawRefresh, refreshExpiry,
                rawCsrf, user);
    }

    private void requireCsrf(String expectedHash, String rawCsrfToken) {
        String actualHash;
        try {
            actualHash = opaqueTokens.hash(rawCsrfToken);
        } catch (IdentityApiException invalidToken) {
            throw IdentityApiException.invalidCsrfToken();
        }
        if (expectedHash == null || !MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII))) {
            throw IdentityApiException.invalidCsrfToken();
        }
    }

    private void requireSafetyAllowed(IdentityService.UserProfile user) {
        if (accountEnforcements.isRestricted(user.tenantId(), user.id())) {
            throw IdentityApiException.accountSafetyRestricted();
        }
    }

    private void revokeRefresh(UUID refreshId, String reason) {
        sessions.revokeRefresh(refreshId, reason);
    }

    private boolean isReplacement(String reason) {
        return "SESSION_REPLACED".equals(reason)
                || "LEGACY_SESSION_REPLACED".equals(reason)
                || "EXPLICIT_RELOGIN".equals(reason);
    }

    record TerminalGrant(UUID terminalSessionId, IdentityService.AccessSession session) { }

}
