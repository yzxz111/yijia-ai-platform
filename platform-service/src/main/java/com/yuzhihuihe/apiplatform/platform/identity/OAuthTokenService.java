package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 桌面端 OAuth 令牌端点：授权码换取令牌、轮换刷新令牌以及撤销。每个会创建
 * 凭据的步骤都在单个数据库事务中运行，因此失败绝不会在未签发令牌的情况下
 * 消耗掉一个授权码。
 */
@Service
class OAuthTokenService {

    private static final Pattern DEVICE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final OAuthClientProperties client;
    private final OAuthAuthorizationCodeRepository codes;
    private final OAuthRefreshTokenRepository refreshTokens;
    private final OpaqueTokenService tokens;
    private final DeviceIdentityService devices;
    private final LoginHandoffService handoffs;
    private final TerminalSessionService terminalSessions;
    private final TerminalSessionRepository terminalSessionsStore;
    private final JwtDecoder jwtDecoder;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    OAuthTokenService(
            OAuthClientProperties client,
            OAuthAuthorizationCodeRepository codes,
            OAuthRefreshTokenRepository refreshTokens,
            OpaqueTokenService tokens,
            DeviceIdentityService devices,
            LoginHandoffService handoffs,
            TerminalSessionService terminalSessions,
            TerminalSessionRepository terminalSessionsStore,
            JwtDecoder jwtDecoder) {
        this(client, codes, refreshTokens, tokens, devices, handoffs, terminalSessions,
                terminalSessionsStore, jwtDecoder, Clock.systemUTC());
    }

    OAuthTokenService(
            OAuthClientProperties client,
            OAuthAuthorizationCodeRepository codes,
            OAuthRefreshTokenRepository refreshTokens,
            OpaqueTokenService tokens,
            DeviceIdentityService devices,
            LoginHandoffService handoffs,
            TerminalSessionService terminalSessions,
            TerminalSessionRepository terminalSessionsStore,
            JwtDecoder jwtDecoder,
            Clock clock) {
        this.client = client;
        this.codes = codes;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.devices = devices;
        this.handoffs = handoffs;
        this.terminalSessions = terminalSessions;
        this.terminalSessionsStore = terminalSessionsStore;
        this.jwtDecoder = jwtDecoder;
        this.clock = clock;
    }

    @Transactional
    TokenIssue exchangeAuthorizationCode(
            String clientId,
            String code,
            String redirectUri,
            String codeVerifier,
            String deviceId,
            LoginActivityService.LoginContext context) {
        requireClient(clientId);
        if (code == null || code.isBlank()) {
            throw OAuthException.invalidRequest("code is required.");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw OAuthException.invalidRequest("redirect_uri is required.");
        }
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw OAuthException.invalidRequest("code_verifier is required.");
        }
        String device = requireDeviceId(deviceId);
        Instant now = clock.instant();

        OAuthAuthorizationCodeRepository.AuthorizationCode row = codes
                .lockByHash(tokens.hash(code))
                .orElseThrow(OAuthException::invalidGrant);
        if (row.consumedAt() != null
                || !row.expiresAt().isAfter(now)
                || !client.getClientId().equals(row.clientId())) {
            throw OAuthException.invalidGrant();
        }
        if (!row.redirectUri().equals(redirectUri)) {
            throw OAuthException.redirectUriMismatch();
        }
        if (!row.deviceId().equals(device)) {
            throw OAuthException.deviceMismatch();
        }
        PkceVerifier.verify(row.codeChallenge(), codeVerifier);
        if (codes.consume(row.id(), now) != 1) {
            throw OAuthException.invalidGrant();
        }

        IdentityService.UserProfile user = handoffs.loadUser(row.userId(), row.tenantId());
        LoginHandoffService.Completed completed = bindTerminalSession(user, context, device);
        String refreshToken = tokens.generate();
        Instant refreshExpiresAt = now.plus(client.getRefreshTokenTtl());
        refreshTokens.insert(new OAuthRefreshTokenRepository.RefreshToken(
                UUID.randomUUID(),
                tokens.hash(refreshToken),
                UUID.randomUUID(),
                client.getClientId(),
                user.id(),
                user.tenantId(),
                device,
                completed.terminalSessionId(),
                row.scope(),
                refreshExpiresAt,
                null,
                null,
                null,
                now,
                null));
        return new TokenIssue(completed.session().accessToken(), refreshToken,
                completed.session().expiresIn(), row.scope());
    }

    /**
     * 轮换刷新令牌。即使抛出 OAuth 错误也会提交，因为重放一个已轮换的令牌
     * 必须永久撤销整个令牌族。
     */
    @Transactional(noRollbackFor = OAuthException.class)
    TokenIssue refresh(String clientId, String refreshToken, String deviceId,
            LoginActivityService.LoginContext context) {
        requireClient(clientId);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw OAuthException.invalidRequest("refresh_token is required.");
        }
        String device = requireDeviceId(deviceId);
        Instant now = clock.instant();

        OAuthRefreshTokenRepository.RefreshToken row = refreshTokens
                .lockByHash(tokens.hash(refreshToken))
                .orElseThrow(OAuthException::invalidRefreshToken);
        if (!client.getClientId().equals(row.clientId())) {
            throw OAuthException.invalidClient();
        }
        if (!row.deviceId().equals(device)) {
            throw OAuthException.invalidRefreshToken();
        }
        if (row.revokedAt() != null) {
            // 一个已轮换或已撤销的令牌被重放：丢弃整个令牌族。
            refreshTokens.revokeFamily(row.tokenFamilyId(), "REUSE_DETECTED", now);
            throw OAuthException.refreshTokenRevoked();
        }
        if (!row.expiresAt().isAfter(now) || row.terminalSessionId() == null) {
            throw OAuthException.invalidRefreshToken();
        }

        IdentityService.UserProfile user = handoffs.loadUser(row.userId(), row.tenantId());
        String deviceHash = devices.hashRequired(device);
        String accessToken = terminalSessions.issueDesktopAccessToken(
                user, row.terminalSessionId(), deviceHash);

        UUID rotatedId = UUID.randomUUID();
        if (refreshTokens.markRotated(row.id(), rotatedId, now) != 1) {
            throw OAuthException.refreshTokenRevoked();
        }
        String newRefreshToken = tokens.generate();
        refreshTokens.insert(new OAuthRefreshTokenRepository.RefreshToken(
                rotatedId,
                tokens.hash(newRefreshToken),
                row.tokenFamilyId(),
                row.clientId(),
                row.userId(),
                row.tenantId(),
                row.deviceId(),
                row.terminalSessionId(),
                row.scope(),
                now.plus(client.getRefreshTokenTtl()),
                null,
                null,
                null,
                now,
                null));
        return new TokenIssue(accessToken, newRefreshToken,
                JwtTokenService.ACCESS_TOKEN_TTL.toSeconds(), row.scope());
    }

    /**
     * RFC 7009 撤销。未知令牌仍然返回成功，这样调用方无法探测令牌是否有效。
     * 撤销刷新令牌会丢弃其整个令牌族；撤销访问令牌还会关闭
     * 绑定的桌面端终端会话。
     */
    @Transactional
    void revoke(String clientId, String token, String tokenTypeHint) {
        requireClient(clientId);
        if (token == null || token.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        String tokenHash;
        try {
            tokenHash = tokens.hash(token);
        } catch (IdentityApiException invalid) {
            return;
        }
        OAuthRefreshTokenRepository.RefreshToken row = refreshTokens.lockByHash(tokenHash)
                .orElse(null);
        if (row != null) {
            if (!client.getClientId().equals(row.clientId())) {
                throw OAuthException.invalidClient();
            }
            refreshTokens.revokeFamily(row.tokenFamilyId(), "REVOKED_BY_CLIENT", now);
            if (row.terminalSessionId() != null) {
                terminalSessionsStore.revokeTerminal(row.terminalSessionId(), "LOGOUT");
                terminalSessionsStore.revokeRefreshesForTerminal(row.terminalSessionId(), "LOGOUT");
            }
            return;
        }
        if ("access_token".equals(tokenTypeHint) || token.chars().filter(c -> c == '.').count() == 2) {
            revokeTerminalFromAccessToken(token, now);
        }
    }

    private void revokeTerminalFromAccessToken(String token, Instant now) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (RuntimeException invalid) {
            return;
        }
        if (!"DESKTOP_WEB".equals(jwt.getClaimAsString("client_type"))) {
            return;
        }
        String sessionId = jwt.getClaimAsString("session_id");
        if (sessionId == null) {
            return;
        }
        try {
            UUID terminalSessionId = UUID.fromString(sessionId);
            terminalSessionsStore.revokeTerminal(terminalSessionId, "LOGOUT");
            terminalSessionsStore.revokeRefreshesForTerminal(terminalSessionId, "LOGOUT");
        } catch (IllegalArgumentException invalid) {
            // 不是我们的会话标识符之一：没有可撤销的内容。
        }
    }

    private LoginHandoffService.Completed bindTerminalSession(
            IdentityService.UserProfile user, LoginActivityService.LoginContext context, String device) {
        try {
            LoginHandoffService.LoginResult result = handoffs.begin(
                    user, new LoginActivityService.LoginContext(
                            context == null ? null : context.ipAddress(),
                            device,
                            context == null ? null : context.userAgent()));
            if (result instanceof LoginHandoffService.Completed completed) {
                return completed;
            }
            throw OAuthException.loginHandoffPending();
        } catch (IdentityApiException error) {
            // 终端会话/设备上限等既有业务异常必须转成 OAuth 错误码，
            // 否则会返回控制台的 {"code": ...} 结构，与接口文档不一致。
            throw mapTerminalSessionFailure(error);
        }
    }

    private static OAuthException mapTerminalSessionFailure(IdentityApiException error) {
        return switch (error.code()) {
            case "login_device_limit_reached", "login_handoff_pending" ->
                    OAuthException.loginHandoffPending();
            case "login_handoff_denied" -> OAuthException.loginHandoffDenied();
            case "login_handoff_expired" -> OAuthException.loginHandoffExpired();
            case "account_safety_restricted" -> OAuthException.accessDenied();
            default -> OAuthException.invalidGrant();
        };
    }

    private void requireClient(String clientId) {
        if (!client.matchesClient(clientId)) {
            throw OAuthException.invalidClient();
        }
    }

    private String requireDeviceId(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId.strip()).matches()) {
            throw OAuthException.invalidDeviceId();
        }
        return deviceId.strip();
    }

    record TokenIssue(String accessToken, String refreshToken, long expiresIn, String scope) { }
}
