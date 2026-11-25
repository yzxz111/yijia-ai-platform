package com.yuzhihuihe.apiplatform.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static com.yuzhihuihe.apiplatform.platform.security.AccessTokenVersionTestSupport.stubCurrentLegacyToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
        classes = PlatformServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@AutoConfigureMockMvc
class PlatformActuatorSecurityTest {

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Environment environment;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Test
    void actuatorHealthProbesArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorPrometheusRequiresAdminJwt() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("jvm_info"));
    }

    @Test
    void onlyExactPaymentCallbackPathsArePublic() throws Exception {
        mockMvc.perform(get("/console/platform/payments/mock/callback"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/console/platform/payments/wechat-pay/callback"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/console/platform/payments/alipay/callback"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/console/platform/refunds/wechat-pay/callback"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(post("/console/platform/payments/unlisted/callback"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/console/platform/payments/wechat-pay/callback/extra"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/console/platform/payments/wechat-pay/callback/"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/console/platform/payments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformPaymentAndAdminRoutesRemainProtected() throws Exception {
        mockMvc.perform(post("/console/platform/payments/query"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/console/platform/admin/payments/reconcile"))
                .andExpect(status().isUnauthorized());
        stubCurrentLegacyToken(jdbcTemplate);
        mockMvc.perform(post("/console/platform/admin/payments/reconcile")
                        .with(jwt().jwt(token -> token
                                        .subject("95000000-0000-4000-8000-000000000001")
                                        .claim("tenant_id", "95000000-0000-4000-8000-000000000002")
                                        .claim("auth_version", 0L))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCannotClaimAUserFreePlan() throws Exception {
        stubCurrentLegacyToken(jdbcTemplate);
        var administrator = jwt().jwt(token -> token
                        .subject("95000000-0000-4000-8000-000000000001")
                        .claim("tenant_id", "95000000-0000-4000-8000-000000000002")
                        .claim("auth_version", 0L))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mockMvc.perform(post("/console/platform/plans/free/claim").with(administrator))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/plans/free/claim-status").with(administrator))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console/platform/plans").with(administrator))
                .andExpect(status().isOk());
    }

    @Test
    void consoleLoggingUsesLogstashJsonFormat() {
        assertThat(environment.getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
    }
}
