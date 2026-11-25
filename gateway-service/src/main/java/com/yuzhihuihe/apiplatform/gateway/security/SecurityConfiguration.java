package com.yuzhihuihe.apiplatform.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;

@Configuration
@EnableReactiveMethodSecurity
public class SecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
    ReactiveJwtDecoder platformJwtDecoder(
            @Value("${security.jwt.jwk-set-uri:http://platform-service:8080/.well-known/jwks.json}")
            String jwkSetUri,
            @Value("${security.jwt.issuer:yuzhihuihe-platform}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new JwtTimestampValidator(), new JwtIssuerValidator(issuer)));
        return decoder;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    SecurityWebFilterChain gatewaySecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder platformJwtDecoder,
            ConsoleTokenValidator consoleTokenValidator) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .pathMatchers("/console/gateway/admin/**").hasRole("ADMIN")
                        .pathMatchers(
                                "/console/gateway/api-keys",
                                "/console/gateway/api-keys/**")
                        .hasRole("USER")
                        .pathMatchers(
                                "/console/gateway/client",
                                "/console/gateway/client/**",
                                "/console/gateway/quota",
                                "/console/gateway/quota/**",
                                "/console/gateway/usage",
                                "/console/gateway/usage/**")
                        // 桌面客户端登录后要拉 /client/bootstrap 与 /quota/summary 回显套餐与模型；
                        // 管理员同样会使用桌面端，而本链路没有角色层级（ROLE_ADMIN 并不蕴含
                        // ROLE_USER），只放 USER 会让管理员拿到静默 403，套餐和模型都回显不出来。
                        // platform-service 对「用户端与运营端共用」的路径已用 hasAnyRole，这里对齐。
                        .hasAnyRole("USER", "ADMIN")
                        // Skill 目录只在管理端可见：产品要求普通用户端不展示技能，
                        // 桌面客户端读取技能走 /v1/skills（API Key），与本控制台通道互不影响。
                        .pathMatchers(
                                "/console/gateway/skills",
                                "/console/gateway/skills/**")
                        .hasRole("ADMIN")
                        .pathMatchers("/console/gateway/**").authenticated()
                        .pathMatchers("/internal/**").hasRole("INTERNAL")
                        .pathMatchers("/v1/**").permitAll()
                        .anyExchange().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                        .jwtDecoder(platformJwtDecoder)
                        .jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(authenticationConverter))))
                .addFilterAfter(new ConsoleTokenVersionWebFilter(consoleTokenValidator),
                        SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }
}
