package com.yuzhihuihe.apiplatform.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * 面向真实桌面客户端的 servlet firewall 装配。
 *
 * <p>真实客户端经常把 UTF-8 产品名放进 {@code User-Agent}——Yuzai Electron 桌面
 * 客户端发送 {@code 宇智AI/2026.6.30}。由于 OAuth 端点会读取该请求头，
 * 在解析控制器参数时被拒绝的请求会表现为一个不透明的 {@code 400}/{@code 503}，
 * 且没有 RFC 6749 错误响应体；这就是 2026-09-12 的桌面登录故障。两道防护
 * 避免其再次发生：{@link AsciiUserAgentFilter} 在安全过滤链之前对该请求头做百分号
 * 编码，而 Spring Security 自身的 allowed-header-values 判定本来就接受已分配的
 * 非 ASCII 码点。其它一切（URL 编码、路径、方法、CR/LF/NUL）保持其严格默认值。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class HttpFirewallConfiguration {

    /**
     * 基于 Spring Security 的严格默认值构建应用 firewall。
     *
     * <p>此处有意<em>不</em>替换 header-value 规则。Spring Security 6.5 的默认
     * {@code ALLOWED_HEADER_VALUES} 判定接受制表符、可打印 ASCII 以及每个<em>已分配</em>的
     * 非 ASCII 码点（其模式为
     * {@code [\p{IsAssigned}&&[[^\p{IsControl}]||\t]]*}），这正是一个 UTF-8
     * {@code User-Agent} 所需的，因此桌面客户端无需放宽限制即可继续工作。本方法
     * 过去安装的手写替换因此不是对默认值的收窄，而是对它的
     * 削弱：它在<em>每一个</em>请求头中都接受任何非 ISO 控制
     * 字符，还额外放行了默认值会拒绝的<em>未分配</em>码点
     * （例如 {@code U+0378}）。
     *
     * <p>{@code setAllowedHeaderValues} 只能整体替换该值的判定——它无法只作用于某一个请求头名——
     * 因此要为其它所有请求头保留默认值的唯一做法就是完全不调用它。残余风险与默认值
     * 相同：该判定仍接受非控制类的
     * Unicode 行分隔符 {@code U+2028}/{@code U+2029}。
     *
     * <p>只暴露这一个 bean。Spring Security 6.5 的 {@code StrictHttpFirewall} 只实现
     * {@code HttpFirewall}，并不是 {@code jakarta.servlet.Filter}，因此既不会被 Spring Boot
     * 自动注册成 servlet filter，也不能（也不该）被包进 {@code FilterRegistrationBean}——
     * 之前那句 {@code (Filter) platformHttpFirewall} 强转会让整个服务在启动时
     * {@code ClassCastException} 起不来。安全过滤链会自行注入这个 bean。
     */
    @Bean
    HttpFirewall platformHttpFirewall() {
        return new StrictHttpFirewall();
    }

    /**
     * 把上面的 firewall 真正接进 Spring Security 的过滤链。
     *
     * <p>只声明 {@code HttpFirewall} bean 是不够的：{@code WebSecurityConfiguration} 不会自动注入它，
     * 安全链会继续用默认 firewall。{@code WebSecurityCustomizer} 是官方接入点。
     */
    @Bean
    WebSecurityCustomizer platformHttpFirewallCustomizer(HttpFirewall platformHttpFirewall) {
        return web -> web.httpFirewall(platformHttpFirewall);
    }
}
