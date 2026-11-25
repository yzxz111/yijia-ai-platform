package com.yuzhihuihe.apiplatform.platform.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把非 ASCII 的 {@code User-Agent} 规范化成 ASCII，再交给 Spring Security。
 *
 * <p>真实客户端会在 UA 里放中文产品名（Yuzai Electron 发 {@code 宇智AI/2026.6.30}）。
 * Spring Security 的 {@code StrictHttpFirewall} 默认 {@code ALLOWED_HEADER_VALUES} 只接受可打印
 * ASCII，这类请求会在**进入控制器之前**被 {@code RequestRejectedException} 拒掉，对外表现为
 * 一个没有 RFC 6749 错误体的 400/503——这正是 2026-09-12 桌面端登录失败的原因。
 *
 * <p>与其去改 firewall 的装配（{@code WebSecurityCustomizer} 在当前配置下没有被应用），
 * 不如在过滤链最前面就把 UA 转成等价的百分号编码 ASCII 形式：
 * {@code 宇智AI/2026.6.30 → %E5%AE%87%E6%99%BAAI/2026.6.30}。语义不丢（可解码还原），
 * 长度可控，且不触碰 Spring Security 的任何默认安全规则。
 *
 * <p>顺序必须早于 Spring Security 过滤链（默认 order = -100）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class AsciiUserAgentFilter extends OncePerRequestFilter {

    static final String HEADER = "User-Agent";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userAgent = request.getHeader(HEADER);
        if (userAgent == null || isAscii(userAgent)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new AsciiUserAgentRequest(request, encode(userAgent)), response);
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7E || value.charAt(index) < 0x20) {
                return false;
            }
        }
        return true;
    }

    /** 按 UTF-8 字节做百分号编码，结果保证是纯 ASCII 且可逆。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 只覆盖 User-Agent 的读取，其余请求行为完全不变。 */
    private static final class AsciiUserAgentRequest extends HttpServletRequestWrapper {

        private final String userAgent;

        private AsciiUserAgentRequest(HttpServletRequest request, String userAgent) {
            super(request);
            this.userAgent = userAgent;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? userAgent : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(java.util.List.of(userAgent))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original != null && original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
