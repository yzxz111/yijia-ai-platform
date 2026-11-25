package com.yuzhihuihe.apiplatform.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    JwtKeyRing jwtKeyRing(
            @Value("${security.jwt.jwk-set-location}") Resource jwkSetResource,
            @Value("${security.jwt.active-key-id}") String activeKeyId) {
        return JwtKeyRing.load(jwkSetResource, activeKeyId);
    }

    @Bean
    RSAKey jwtRsaKey(JwtKeyRing jwtKeyRing) {
        return jwtKeyRing.activeSigningKey();
    }

    @Bean
    JWKSet jwtPublicJwkSet(JwtKeyRing jwtKeyRing) {
        return jwtKeyRing.publicKeys();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtRsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwtRsaKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(
            JWKSet jwtPublicJwkSet,
            @Value("${security.jwt.issuer:yuzhihuihe-platform}") String issuer) {
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(jwtPublicJwkSet);
        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new JwtTimestampValidator(), new JwtIssuerValidator(issuer)));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            AccessTokenVersionFilter accessTokenVersionFilter) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/console/platform/auth/terminal-presence",
                                "/console/platform/auth/terminal-close").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/console/platform/auth/login-handoffs/pending").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/console/platform/auth/login-handoffs/*/approve",
                                "/console/platform/auth/login-handoffs/*/deny").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/console/platform/auth/login-handoffs/*").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/console/platform/auth/login-handoffs/*/complete",
                                "/console/platform/auth/login-handoffs/*/cancel").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/console/platform/auth/authorize").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/console/platform/auth/token",
                                "/console/platform/auth/revoke").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/console/platform/auth/authorize/complete",
                                "/console/platform/auth/authorize/cancel").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/console/platform/auth/userinfo").authenticated()
                        .requestMatchers(
                        "/console/platform/auth/register",
                        "/console/platform/auth/identity",
                        "/console/platform/auth/identity/complete-registration",
                        "/console/platform/auth/email/challenges",
                        "/console/platform/auth/email-identity",
                        "/console/platform/auth/email-identity/complete-registration",
                        // Published service terms and privacy policy: read by the login page
                        // before any session exists.
                        "/console/legal/**",
                                "/console/platform/auth/password-reset",
                                "/console/platform/auth/password-reset/sms/challenges",
                                "/console/platform/auth/sms/challenges",
                                "/console/platform/auth/sms-login/challenges",
                                "/console/platform/auth/slider-captcha/challenges",
                                "/console/platform/auth/slider-captcha/challenges/*/verify",
                                "/console/platform/auth/sms-login",
                                "/console/platform/auth/login",
                                "/console/platform/auth/refresh",
                                "/console/platform/auth/logout",
                                // 这两条调用发生在「还没有 access token」的时刻：browser-close 由
                                // 即将关闭的页面在会话边缘发出，设备恢复的 complete 用一次性
                                // HttpOnly cookie（不是 JWT）作为凭据。合并时被对方的版本覆盖掉，
                                // 会导致它们落进 authenticated() 而被拦成 401。
                                "/console/platform/auth/browser-close",
                                "/console/platform/auth/device-recovery/*/complete",
                                 "/console/platform/payments/mock/callback",
                                 "/console/platform/payments/wechat-pay/callback",
                                 "/console/platform/payments/alipay/callback",
                                 "/console/platform/refunds/wechat-pay/callback",
                                 // Spring Boot's BasicErrorController. The container's ERROR
                                 // dispatch re-enters this chain, so without this entry a 401 or a
                                 // controller exception is turned into a 404 by the deny rule
                                 // below. It renders only status/error/path.
                                 "/error",
                                 "/.well-known/jwks.json",
                                "/actuator/health/**")
                        .permitAll()
                        // The internal surface is authenticated by HMAC request signatures, not by
                        // Spring Security. Every path that a filter or a controller verifies is
                        // listed here explicitly and must stay listed before the deny rule below,
                        // which is the point of the exercise: a new controller mapped outside
                        // /console/** now fails closed (403) instead of becoming public by default.
                        // InternalOperatingFactHmacFilter verifies usage-events and claims the
                        // request id before this chain's authorization filter runs, so the permitAll
                        // entry has to precede the deny rule or the filter would never see it.
                        .requestMatchers(
                                "/internal/platform/operating-facts/usage-events",
                                // InternalSmsHmacFilter verifies these three; unlisted siblings
                                // under /internal/platform/sms/** fall through to denyAll.
                                "/internal/platform/sms/proofs/consume",
                                "/internal/platform/sms/signals/package-expiry",
                                "/internal/platform/sms/signals/rate-limit",
                                // InternalRequestSigner.verify runs inside each controller method.
                                "/internal/platform/content-safety/events",
                                // InternalContentSafetyPolicyHmacFilter verifies this one and claims
                                // the request id before the chain's authorization filter runs, for the
                                // same reason as usage-events above. Its consumer is gateway-service's
                                // PolicySnapshotClient, which fetches the signed policy snapshot every
                                // 15s; leaving it out does not fail loudly -- the gateway simply keeps
                                // a stale snapshot until it expires and then answers every model call
                                // with 503 content_safety_unavailable. Verified by probe-policy-endpoint.mjs.
                                "/internal/gateway/content-safety/policy",
                                "/internal/platform/wallet/eligibility/*/*",
                                "/internal/platform/wallet/usage-reservations",
                                "/internal/platform/wallet/usage-reservations/*",
                                "/internal/platform/wallet/usage-reservations/*/settle",
                                "/internal/platform/wallet/usage-reservations/*/release")
                        .permitAll()
                        .requestMatchers("/internal/**").denyAll()
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/console/platform/admin/**").hasRole("ADMIN")
                        .requestMatchers("/console/platform/referrals/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(
                                "/console/platform/orders",
                                "/console/platform/orders/**",
                                // Callers' own package instances (limit usage); the admin package
                                // catalogue lives under /console/platform/admin/**.
                                "/console/platform/packages",
                                "/console/platform/packages/**",
                                "/console/platform/wallet",
                                "/console/platform/wallet/**",
                                // 连续包月：开通、解约、恢复都只作用于调用方自己的订阅
                                // （服务层按 tenant + user + id 三条件过滤，查不到即 404）。
                                "/console/platform/subscriptions",
                                "/console/platform/subscriptions/**",
                                "/console/platform/refunds",
                                "/console/platform/refunds/**",
                                "/console/platform/safety/appeals",
                                "/console/platform/safety/appeals/**",
                                "/console/platform/support-tickets",
                                "/console/platform/support-tickets/**")
                        .hasRole("USER")
                        // 免费版领取是**用户自助**动作——服务层写下的原因字符串就是
                        // "self-service free claim"，而 web-console 里唯一的调用点是
                        // 用户版套餐页的「领取」按钮。它必须排在下一条
                        // `/console/platform/plans/**` 之前：那一条为了运营端能读目录而
                        // 放开了 ADMIN，若让领取请求落到它，管理员会一路进到
                        // FreePackageClaimService 并以 404 grant_user_not_found 结束，
                        // 而不是一个明确的 403 —— 一个「看起来像不存在」的拒绝，
                        // 恰好是最难被运维诊断出来的那种。
                        // PlatformActuatorSecurityTest.administratorCannotClaimAUserFreePlan
                        // 钉住了这条区分。
                        .requestMatchers(
                                "/console/platform/plans/free/claim",
                                "/console/platform/plans/free/claim-status")
                        .hasRole("USER")
                        .requestMatchers(
                                // 已发布套餐目录：用户端与运营端都要读。运营端的「套餐管理」用管理接口
                                // 维护目录，但「发放量排行」和「每个版本包含哪些模型」取的是用户可见
                                // 视图（管理接口只给 modelCount，没有模型清单），因此管理员也必须能读，
                                // 否则每次打开该页都会产生一个静默 403。
                                "/console/platform/plans",
                                "/console/platform/plans/**",
                                "/console/platform/profile",
                                "/console/platform/profile/**",
                                "/console/platform/notifications",
                                "/console/platform/notifications/**",
                                "/console/platform/notification-preferences",
                                "/console/platform/notification-preferences/**")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/console/platform/**").authenticated()
                        // Everything reachable today is matched above. Denying the remainder means
                        // a future controller mapped outside /console/** and /internal/** fails
                        // closed instead of becoming public by construction. An A/B run with this
                        // line reverted to permitAll() produced byte-identical results for
                        // MockPaymentIdempotencyTest, GrantOutboxTest and PackageCatalogTest, so the
                        // rule is extra hardening and not a behaviour change for any existing path.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter)))
                .addFilterAfter(accessTokenVersionFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
