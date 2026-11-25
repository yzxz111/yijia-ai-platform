package com.yuzhihuihe.apiplatform.platform.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OAuthResponses {

    private OAuthResponses() {}

    /** RFC 6749 第 5.1 节的令牌响应。字段名刻意使用 snake_case。 */
    public record TokenResponse(
            String access_token,
            String refresh_token,
            String token_type,
            long expires_in,
            String scope) {}

    /** 从已认证的访问令牌派生的、兼容 OIDC 的用户信息。 */
    public record UserInfoResponse(
            UUID id, UUID tenantId, String email, String displayName, String role) {}

    /** 由浏览器端的完成步骤返回，以便 Web 控制台可以通过其注册的重定向 把授权码交回桌面应用。 */
    public record AuthorizationCompletion(
            String redirectUrl, List<String> scopes, Instant expiresAt) {}

    /** 浏览器用户取消桌面端授权请求时返回。 */
    public record AuthorizationCancellation(String redirectUrl) {}

    /** 登录页面的元数据，使其能够渲染桌面端登录交接。 */
    public record AuthorizationRequestView(
            UUID transactionId,
            String clientId,
            String state,
            String redirectUri,
            List<String> scopes,
            Instant expiresAt) {}
}
