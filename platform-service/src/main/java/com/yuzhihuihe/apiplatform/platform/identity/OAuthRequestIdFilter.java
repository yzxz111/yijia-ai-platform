package com.yuzhihuihe.apiplatform.platform.identity;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给所有 OAuth 端点响应盖上 {@code X-Request-Id}，成功与失败都带。
 *
 * <p>没有它时，客户端报 "400 Bad Request" 无法与服务端日志对应——这正是桌面端
 * 2026-09-12 16:57 那次失败无法定位的原因之一。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class OAuthRequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/console/platform/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String existing = request.getHeader(HEADER);
        String requestId = existing == null || existing.isBlank()
                ? "oauth_" + UUID.randomUUID().toString().replace("-", "")
                : existing.strip();
        response.setHeader(HEADER, requestId);
        chain.doFilter(request, response);
    }
}
