package com.yuzhihuihe.apiplatform.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * 针对 2026-09-12 桌面端登录失败的回归测试：平台 servlet 防火墙拒绝了
 * Electron 客户端，因为它的 {@code User-Agent} 携带了 UTF-8 产品名 {@code 宇智AI}。
 * OAuth 端点会读取 {@code User-Agent}，因此该拒绝表现为
 * {@code /console/platform/auth/token} 上不透明的 {@code 503 authorization_unavailable}。
 *
 * <p>{@code reject*} 各用例固定的是契约的另一半。该应用曾把
 * Spring Security 的允许请求头取值谓词整体替换为一个手写的谓词，后者接受
 * 所有非 ISO 控制字符的字符，因而在<em>每一个</em>请求头中也接受了
 * 诸如 {@code U+0378} 这样的未分配码点。原装谓词本就接受制表符、
 * 可打印 ASCII 以及已分配的非 ASCII 字符，因此那次替换既不必要
 * 也更弱，如今已不复存在。原装谓词仍然接受 Unicode 的行/段落
 * 分隔符 {@code U+2028}/{@code U+2029}（它们是格式字符，不是 ISO 控制字符），而
 * Java 自身的 HTTP 栈把它们当作换行处理；本服务读取的任何请求头都不会
 * 未经校验地使用，且 servlet 容器在防火墙看到它们之前就会拒绝裸 CR/LF。
 */
class HttpFirewallConfigurationTest {

    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "宇智AI/2026.6.30 Chrome/148.0.7778.280 Electron/42.10.0 Safari/537.36";

    private final HttpFirewall firewall = new HttpFirewallConfiguration().platformHttpFirewall();

    @Test
    void acceptsTheRealDesktopUserAgent() {
        assertThatCode(() -> accept(firewall, DESKTOP_USER_AGENT)).doesNotThrowAnyException();
    }

    @Test
    void acceptsUnicodeVariantsRealClientsSend() {
        assertThatCode(() -> accept(firewall, "微信/8.0.50(0x2800323A) NetType/WIFI"))
                .doesNotThrowAnyException();
        assertThatCode(() -> accept(firewall, "café-client/1.0")).doesNotThrowAnyException();
        assertThatCode(() -> accept(firewall, "Mozilla/5.0 宇智AI/2026.6.30"))
                .doesNotThrowAnyException();
    }

    @Test
    void stillAcceptsPlainAsciiUserAgents() {
        assertThatCode(() -> accept(firewall, "Mozilla/5.0 (Windows NT 10.0) Electron/42.10.0"))
                .doesNotThrowAnyException();
    }

    @Test
    void stillRejectsTheUrlEncodingAndPathVectorsTheFirewallGuards() {
        MockHttpServletRequest encodedSlash =
                new MockHttpServletRequest("POST", "/console/platform/auth/token");
        encodedSlash.setRequestURI("/console/platform/auth/token%2f..%2fadmin");

        assertThatThrownBy(() -> firewall.getFirewalledRequest(encodedSlash))
                .isInstanceOf(RequestRejectedException.class);
    }

    @Test
    void rejectsUnassignedAndNonAsciiControlCodePointsInEveryHeader() {
        assertThat(readable(firewall, "X-Callback-Context", "a\nb")).isFalse();
        assertThat(readable(firewall, "X-Callback-Context", "a\rb")).isFalse();
        assertThat(readable(firewall, "X-Callback-Context", "a\u0000b")).isFalse();
        assertThat(readable(firewall, "X-Callback-Context", "a\u007fb")).isFalse();
        // U+0085 NEL 是 ISO 控制字符，因此原装谓词和被移除的
        // 替换谓词都不接受它。
        assertThat(readable(firewall, "X-Internal-Signature", "a\u0085b")).isFalse();
        assertThat(readable(firewall, "X-Device-Id", "a\u0378b")).isFalse();
    }

    @Test
    void keepsTabPrintableAsciiAndAssignedNonAsciiReadable() {
        assertThat(readable(firewall, "User-Agent", DESKTOP_USER_AGENT)).isTrue();
        assertThat(readable(firewall, "X-Callback-Context", "a\tb")).isTrue();
        assertThat(readable(firewall, "X-Forwarded-For", "203.0.113.7, 198.51.100.4")).isTrue();
        assertThat(StrictHttpFirewall.ALLOWED_HEADER_VALUES.test("a\tb")).isTrue();
        assertThat(StrictHttpFirewall.ALLOWED_HEADER_VALUES.test("宇智AI/2026.6.30")).isTrue();
    }

    private static void accept(HttpFirewall firewall, String userAgent) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/console/platform/auth/token");
        request.addHeader("X-Device-Id", "0123456789abcdef0123456789abcdef");
        request.addHeader("User-Agent", userAgent);
        firewall.getFirewalledRequest(request);
    }

    /**
     * Spring Security 的防火墙在被防火墙包装的请求提供某个请求头取值时才校验它，
     * 因此该取值必须通过 {@link jakarta.servlet.http.HttpServletRequest} 读回。
     */
    private static boolean readable(HttpFirewall firewall, String name, String value) {
        try {
            Enumeration<String> headers = firewall
                    .getFirewalledRequest(new ProbeRequest(name, value))
                    .getHeaders(name);
            while (headers.hasMoreElements()) {
                headers.nextElement();
            }
            return true;
        } catch (RequestRejectedException rejected) {
            return false;
        }
    }

    /** {@code MockHttpServletRequest} 只通过它自己的请求头映射来暴露请求头。 */
    private static final class ProbeRequest extends MockHttpServletRequest {

        private final String name;
        private final String value;

        ProbeRequest(String name, String value) {
            super("POST", "/console/platform/auth/login");
            this.name = name;
            this.value = value;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(List.of(name));
        }

        @Override
        public Enumeration<String> getHeaders(String requested) {
            return name.equalsIgnoreCase(requested)
                    ? Collections.enumeration(List.of(value))
                    : Collections.emptyEnumeration();
        }
    }
}
