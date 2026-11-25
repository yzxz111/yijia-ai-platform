package com.yuzhihuihe.apiplatform.platform.identity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 已注册的桌面 OAuth 客户端。桌面应用是公共客户端，因此本配置中
 * 任何位置都不存在客户端密钥。
 */
@Component
@ConfigurationProperties(prefix = "yuzhh.oauth.desktop")
public class OAuthClientProperties {

    private boolean enabled = true;
    private String clientId = "yuzhh-desktop";
    private String clientType = "PUBLIC";
    private String redirectUri = "yuzhhai://oauth/callback";
    private List<String> allowedScopes = List.of("openid", "profile");
    private List<String> codeChallengeMethods = List.of("S256");
    private Duration authorizationCodeTtl = Duration.ofMinutes(5);
    private Duration refreshTokenTtl = Duration.ofDays(60);
    private Duration loginTransactionTtl = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = text(clientId, "clientId");
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = text(clientType, "clientType").toUpperCase(Locale.ROOT);
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = text(redirectUri, "redirectUri");
    }

    public List<String> getAllowedScopes() {
        return allowedScopes;
    }

    public void setAllowedScopes(List<String> allowedScopes) {
        this.allowedScopes = normalized(allowedScopes, "allowedScopes");
    }

    public List<String> getCodeChallengeMethods() {
        return codeChallengeMethods;
    }

    public void setCodeChallengeMethods(List<String> codeChallengeMethods) {
        this.codeChallengeMethods = normalized(codeChallengeMethods, "codeChallengeMethods");
    }

    public Duration getAuthorizationCodeTtl() {
        return authorizationCodeTtl;
    }

    public void setAuthorizationCodeTtl(Duration authorizationCodeTtl) {
        this.authorizationCodeTtl = positive(authorizationCodeTtl, "authorizationCodeTtl");
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = positive(refreshTokenTtl, "refreshTokenTtl");
    }

    public Duration getLoginTransactionTtl() {
        return loginTransactionTtl;
    }

    public void setLoginTransactionTtl(Duration loginTransactionTtl) {
        this.loginTransactionTtl = positive(loginTransactionTtl, "loginTransactionTtl");
    }

    boolean isPublicClient() {
        return "PUBLIC".equals(clientType);
    }

    boolean matchesClient(String candidate) {
        return enabled && isPublicClient() && clientId.equals(candidate);
    }

    boolean matchesRedirectUri(String candidate) {
        if (redirectUri.equals(candidate)) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(candidate);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() > 0
                    && "/auth/callback".equals(uri.getRawPath())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    boolean supportsChallengeMethod(String method) {
        return codeChallengeMethods.contains(method);
    }

    /** 按注册顺序返回请求的 scope，当某个 scope 不被允许时抛出异常。 */
    List<String> resolveScopes(String requested) {
        if (requested == null || requested.isBlank()) {
            return List.of();
        }
        List<String> granted = java.util.Arrays.stream(requested.trim().split("\\s+"))
                .filter(value -> !value.isBlank())
                .toList();
        for (String scope : granted) {
            if (!allowedScopes.contains(scope)) {
                throw OAuthException.invalidScope();
            }
        }
        return granted;
    }

    private static List<String> normalized(List<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        List<String> normalized = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip())
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return normalized;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
