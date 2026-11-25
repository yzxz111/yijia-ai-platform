package com.yuzhihuihe.apiplatform.platform.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccessTokenVersionFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbcTemplate;

    public AccessTokenVersionFilter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/console/platform/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        Object claim = jwtAuthentication.getToken().getClaim("auth_version");
        String accountStatus = jwtAuthentication.getToken().getClaimAsString("account_status");
        if (accountStatus == null || accountStatus.isBlank()) {
            accountStatus = "ACTIVE";
        }
        TokenState tokenState;
        try {
            tokenState = claim instanceof Number tokenVersion
                    ? loadTokenState(jwtAuthentication.getToken().getSubject(),
                            jwtAuthentication.getToken().getClaimAsString("tenant_id"),
                            tokenVersion.longValue(),
                            accountStatus,
                            jwtAuthentication.getToken().getClaimAsString("session_id"),
                            jwtAuthentication.getToken().getClaimAsString("client_type"))
                    : null;
        } catch (DataAccessException unavailable) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"identity_session_unavailable\"}");
            return;
        }
        if (tokenState == null) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "access token is no longer valid");
            return;
        }
        if (tokenState.safetyRestricted() && !allowsSafetyRestrictedAccount(request)) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"account_safety_restricted\"}");
            return;
        }
        if (!"ACTIVE".equals(tokenState.accountStatus()) && !allowsRestrictedAccount(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"account_restricted\"}");
            return;
        }
        if (Boolean.FALSE.equals(jwtAuthentication.getToken().getClaim("password_set"))
                && !allowsPasswordSetup(request)) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpStatus.FORBIDDEN.value(), "password setup is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowsPasswordSetup(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/console/platform/auth/")
                || ("POST".equals(request.getMethod())
                        && "/console/platform/profile/password".equals(path));
    }

    private boolean allowsRestrictedAccount(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/console/platform/auth/")
                || isPathOrDescendant(path, "/console/platform/support-tickets")
                || isPathOrDescendant(path, "/console/platform/safety/appeals");
    }

    private boolean allowsSafetyRestrictedAccount(HttpServletRequest request) {
        String path = request.getRequestURI();
        return isPathOrDescendant(path, "/console/platform/support-tickets")
                || isPathOrDescendant(path, "/console/platform/safety/appeals");
    }

    private boolean isPathOrDescendant(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private TokenState loadTokenState(
            String subject,
            String tenantId,
            long tokenVersion,
            String tokenStatus,
            String sessionId,
            String clientType) {
        if (subject == null || subject.isBlank() || tenantId == null || tenantId.isBlank()
                || tokenStatus == null || tokenStatus.isBlank()) {
            return null;
        }
        if ((sessionId == null) != (clientType == null)) return null;
        if (clientType != null && !"DESKTOP_WEB".equals(clientType)) return null;
        try {
            UUID parsedSessionId = sessionId == null ? null : UUID.fromString(sessionId);
            List<TokenState> states = jdbcTemplate.query(
                    """
                    SELECT u.auth_version, u.status,
                           EXISTS (
                             SELECT 1 FROM platform.account_enforcement enforcement
                             WHERE enforcement.tenant_id = u.tenant_id
                               AND enforcement.user_id = u.id
                               AND enforcement.state IN ('FROZEN', 'BANNED')
                               AND enforcement.starts_at <= now()
                               AND (enforcement.ends_at IS NULL OR enforcement.ends_at > now())
                           ) AS safety_restricted,
                           CASE
                             WHEN ?::uuid IS NULL THEN NOT EXISTS (
                               SELECT 1 FROM platform.terminal_session active
                               WHERE active.user_id = u.id
                                 AND active.client_type = 'DESKTOP_WEB'
                                 AND active.status = 'ACTIVE'
                                 AND active.expires_at > now())
                             ELSE EXISTS (
                               SELECT 1 FROM platform.terminal_session active
                               WHERE active.id = ?::uuid
                                 AND active.user_id = u.id
                                 AND active.tenant_id = u.tenant_id
                                 AND active.client_type = ?
                                 AND active.status = 'ACTIVE'
                                 AND active.expires_at > now())
                           END AS terminal_current
                    FROM platform.app_user u
                    WHERE u.id = ? AND u.tenant_id = ?
                    """,
                    (result, rowNumber) -> new TokenState(
                            result.getLong("auth_version"), result.getString("status"),
                            result.getBoolean("safety_restricted"),
                            result.getBoolean("terminal_current")),
                    parsedSessionId, parsedSessionId, clientType,
                    UUID.fromString(subject), UUID.fromString(tenantId));
            if (states.size() != 1) return null;
            TokenState state = states.getFirst();
            return state.authVersion() == tokenVersion && state.terminalCurrent() ? state : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record TokenState(
            long authVersion,
            String accountStatus,
            boolean safetyRestricted,
            boolean terminalCurrent) { }
}
