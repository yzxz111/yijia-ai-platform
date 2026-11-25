package com.yuzhihuihe.apiplatform.platform.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 桌面端 OAuth 2.0 授权码流程中浏览器侧的部分。
 *
 * <p>系统浏览器始终只会收到一个短时效的事务标识符。授权码本身由 {@link #complete} 在用户于
 * Web 控制台完成身份认证之后创建，并通过桌面应用注册的自定义 scheme 或回环重定向
 * 交给桌面应用。
 */
@Service
class OAuthAuthorizationService {

    private static final Pattern DEVICE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final OAuthClientProperties client;
    private final OAuthLoginTransactionRepository transactions;
    private final OAuthAuthorizationCodeRepository codes;
    private final OpaqueTokenService tokens;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    OAuthAuthorizationService(
            OAuthClientProperties client,
            OAuthLoginTransactionRepository transactions,
            OAuthAuthorizationCodeRepository codes,
            OpaqueTokenService tokens) {
        this(client, transactions, codes, tokens, Clock.systemUTC());
    }

    OAuthAuthorizationService(
            OAuthClientProperties client,
            OAuthLoginTransactionRepository transactions,
            OAuthAuthorizationCodeRepository codes,
            OpaqueTokenService tokens,
            Clock clock) {
        this.client = client;
        this.transactions = transactions;
        this.codes = codes;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 校验一次授权请求并开启一个登录事务。返回的
     * {@code loginRedirectUrl} 指向同源的专用桌面端登录页。
     */
    @Transactional
    AuthorizationStart begin(AuthorizationParams params) {
        validate(params);
        List<String> scopes = client.resolveScopes(params.scope());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(client.getLoginTransactionTtl());
        transactions.insert(new OAuthLoginTransactionRepository.LoginTransaction(
                id,
                id.toString(),
                client.getClientId(),
                params.redirectUri(),
                params.deviceId(),
                String.join(" ", scopes),
                params.state(),
                params.codeChallenge(),
                "S256",
                null,
                null,
                null,
                OAuthLoginTransactionRepository.STATUS_PENDING,
                expiresAt,
                now,
                null));
        return new AuthorizationStart(id, "/desktop-login?oauth_transaction=" + id, expiresAt);
    }

    /**
     * 为已通过认证的控制台用户创建一次性授权码，并返回
     * 浏览器必须遵循的已注册重定向。
     */
    @Transactional
    AuthorizationCompletion complete(
            UUID transactionId, IdentityService.UserProfile user) {
        if (transactionId == null) {
            throw OAuthException.invalidRequest("transactionId is required.");
        }
        if (user == null || user.id() == null) {
            throw OAuthException.invalidToken();
        }
        OAuthLoginTransactionRepository.LoginTransaction transaction = transactions
                .lockById(transactionId)
                .orElseThrow(OAuthException::transactionNotFound);
        Instant now = clock.instant();
        if (!transaction.expiresAt().isAfter(now)
                || OAuthLoginTransactionRepository.STATUS_CANCELLED.equals(transaction.status())
                || OAuthLoginTransactionRepository.STATUS_COMPLETED.equals(transaction.status())) {
            throw OAuthException.transactionNotFound();
        }
        if (transaction.userId() != null && !transaction.userId().equals(user.id())) {
            throw OAuthException.accessDenied();
        }
        // A newly registered account only has a temporary password-setup session.
        // Keep the transaction pending so the browser can finish setup and retry with the
        // replacement session; a desktop token issued now could not access the profile API.
        if (!user.passwordSet()) {
            throw OAuthException.passwordSetupRequired();
        }
        if (OAuthLoginTransactionRepository.STATUS_PENDING.equals(transaction.status())) {
            transactions.markAuthenticated(transaction.id(), user.id(), user.tenantId());
        }

        String code = tokens.generate();
        Instant codeExpiresAt = now.plus(client.getAuthorizationCodeTtl());
        codes.insert(new OAuthAuthorizationCodeRepository.AuthorizationCode(
                UUID.randomUUID(),
                tokens.hash(code),
                transaction.clientId(),
                user.id(),
                user.tenantId(),
                transaction.redirectUri(),
                transaction.codeChallenge(),
                transaction.codeChallengeMethod(),
                transaction.deviceId(),
                transaction.scope(),
                codeExpiresAt,
                null,
                now));
        transactions.complete(transaction.id(), now);
        return new AuthorizationCompletion(
                callbackUrl(transaction.redirectUri(),
                        "code=" + encode(code) + "&state=" + encode(transaction.state())),
                List.of(transaction.scope().split(" ")),
                codeExpiresAt);
    }

    /** 取消该事务并返回桌面端错误重定向。 */
    @Transactional
    String cancel(UUID transactionId, String error) {
        OAuthLoginTransactionRepository.LoginTransaction transaction = transactions.lockById(transactionId)
                .orElseThrow(OAuthException::transactionNotFound);
        Instant now = clock.instant();
        transactions.cancel(transaction.id(), now);
        return callbackUrl(transaction.redirectUri(),
                "error=" + encode(error) + "&state=" + encode(transaction.state()));
    }

    private void validate(AuthorizationParams params) {
        if (!client.isEnabled() || !client.matchesClient(params.clientId())) {
            throw OAuthException.invalidClient();
        }
        if (!"code".equals(params.responseType())) {
            throw OAuthException.invalidRequest("response_type must be code.");
        }
        if (params.redirectUri() == null || !client.matchesRedirectUri(params.redirectUri())) {
            throw OAuthException.redirectUriMismatch();
        }
        if (params.state() == null || params.state().isBlank()) {
            throw OAuthException.invalidRequest("state is required.");
        }
        PkceVerifier.requireS256(params.codeChallengeMethod());
        PkceVerifier.requireValidChallenge(params.codeChallenge());
        if (params.deviceId() == null || !DEVICE_ID.matcher(params.deviceId()).matches()) {
            throw OAuthException.invalidDeviceId();
        }
    }

    private static String callbackUrl(String redirectUri, String query) {
        String separator = redirectUri.contains("?") ? "&" : "?";
        return redirectUri + separator + query;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 在 {@code GET /auth/authorize} 上收到的授权请求参数。 */
    record AuthorizationParams(
            String clientId,
            String responseType,
            String redirectUri,
            String scope,
            String state,
            String codeChallenge,
            String codeChallengeMethod,
            String deviceId) {

        static AuthorizationParams of(java.util.Map<String, String> params) {
            return new AuthorizationParams(
                    value(params, "client_id"),
                    value(params, "response_type"),
                    value(params, "redirect_uri"),
                    value(params, "scope"),
                    value(params, "state"),
                    value(params, "code_challenge"),
                    value(params, "code_challenge_method"),
                    value(params, "device_id"));
        }

        private static String value(java.util.Map<String, String> params, String key) {
            String value = params.get(key);
            return value == null ? null : value.strip();
        }
    }

    record AuthorizationStart(UUID transactionId, String loginRedirectUrl, Instant expiresAt) { }

    record AuthorizationCompletion(String redirectUrl, List<String> scopes, Instant expiresAt) { }
}
