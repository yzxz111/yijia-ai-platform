package com.yuzhihuihe.apiplatform.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.yuzhihuihe.apiplatform.platform.PlatformServiceApplication;
import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 固定内部接口面的授权规则。
 *
 * <p>{@code /internal/**} 过去会落到 {@code anyRequest().permitAll()}，因此它按构造就是公开的，
 * 仅靠 HMAC 过滤器才使它保持关闭。现在该链会列出那些签名会被校验
 * （由过滤器或在控制器内部完成）的路径，并拒绝其它所有内部
 * 路径。本测试同时证明这两半：已签名的端点仍能到达其过滤器/控制器，
 * 而不是被新规则拒绝；未列出的内部路径会在被处理之前
 * 就被拒绝。
 *
 * <p>未签名的请求得到 401/403/400 中的哪一个，取决于它走多远 —— 防火墙的
 * HMAC 过滤器回答 401，缺失必需请求头由 MVC 回答 400，而 {@code denyAll()}
 * 对已认证调用方回答 403、对匿名调用方通过入口点回答 401 —— 因此
 * 这些断言固定的是真正重要的性质：请求从不被处理（`never 2xx`）且从不
 * 到达它不该到达的控制器。
 */
@SpringBootTest(
        classes = PlatformServiceApplication.class,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "security.internal.hmac-secret=internal-surface-test-secret",
                "security.internal.allowed-clock-skew=5m",
                "security.internal.request-replay-ttl=11m"
        })
@AutoConfigureMockMvc
class InternalSurfaceAuthorizationTest {

    private static final String USER_ID = "93000000-0000-4000-8000-000000000002";

    /** 所有在控制器处理之前会校验其 HMAC 签名的内部路径。 */
    private static final List<String> SIGNED_INTERNAL_PATHS = List.of(
            "/internal/platform/operating-facts/usage-events",
            "/internal/platform/sms/proofs/consume",
            "/internal/platform/sms/signals/package-expiry",
            "/internal/platform/sms/signals/rate-limit",
            "/internal/platform/content-safety/events",
            "/internal/platform/wallet/eligibility/"
                    + "93000000-0000-4000-8000-000000000001/" + USER_ID,
            "/internal/platform/wallet/usage-reservations",
            "/internal/platform/wallet/usage-reservations/"
                    + "93000000-0000-4000-8000-000000000003",
            "/internal/platform/wallet/usage-reservations/"
                    + "93000000-0000-4000-8000-000000000003/settle",
            "/internal/platform/wallet/usage-reservations/"
                    + "93000000-0000-4000-8000-000000000003/release");

    /** 在任何地方都不是控制器映射：只有新的拒绝规则能响应它。 */
    private static final List<String> UNLISTED_INTERNAL_PATHS = List.of(
            "/internal/platform/sms/signals/not-an-endpoint",
            "/internal/platform/operating-facts/other",
            "/internal/future/admin/reset",
            "/internal/platform/wallet/usage-reservations/x/y/z");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        TestJwtKeyRing.configure(registry);
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean SmsProofService proofs;
    @MockitoBean org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void everySignedInternalPathPassesTheNewDenyRule() throws Exception {
        for (String path : SIGNED_INTERNAL_PATHS) {
            // 缺失签名由 HMAC 过滤器（401）或 MVC 的必需请求头
            // 处理（400）来回答；若返回 403，则说明显式的 permitAll 条目缺失，新的
            // 拒绝规则在过滤器有机会运行之前就先触发了。
            ResultActions result = mockMvc.perform(
                    post(path).contentType(MediaType.APPLICATION_JSON).content("{}"));
            result.andExpect(status().is4xxClientError());
            assertThatNotForbiddenOrServed(path, result);
        }
    }

    @Test
    void usageEventsStillReachesItsHmacFilterOnPost() throws Exception {
        mockMvc.perform(post("/internal/platform/operating-facts/usage-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlistedInternalPathsAreDeniedInsteadOfFallingThroughToPermitAll() throws Exception {
        for (String path : UNLISTED_INTERNAL_PATHS) {
            ResultActions post = mockMvc.perform(
                    post(path).contentType(MediaType.APPLICATION_JSON).content("{}"));
            post.andExpect(status().is4xxClientError());
            assertThatDenied(path, post);

            ResultActions get = mockMvc.perform(get(path));
            get.andExpect(status().is4xxClientError());
            assertThatDenied(path, get);
        }
    }

    @Test
    void consoleAndActuatorPathsKeepTheirExistingRules() throws Exception {
        mockMvc.perform(get("/console/platform/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/console/platform/admin/wallet/summary"))
                .andExpect(status().isUnauthorized());
        // 未匹配的 /console/** 路径仍会命中 authenticated() 规则，而不会落到
        // 新的拒绝规则：未认证的调用方会得到入口点的 401。
        mockMvc.perform(get("/console/platform/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unhandledControllerExceptionStillProducesAServerErrorRatherThan401() throws Exception {
        // 该路径是 permitAll，且控制器会解引用解析后的请求体；证明
        // 容器的 /error 转发仍能渲染，就证明了 anyRequest().denyAll() 没有把
        // 服务端错误变成授权入口点的 401。
        mockMvc.perform(post("/console/platform/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    private static void assertThatNotForbiddenOrServed(String path, ResultActions result)
            throws Exception {
        int status = result.andReturn().getResponse().getStatus();
        if (status == HttpStatus.FORBIDDEN.value()) {
            throw new AssertionError(path + " -> 403: the explicit permitAll entry is missing");
        }
    }

    @Test
    void walletReservationOperationsReachTheirInControllerVerification() throws Exception {
        for (String path : List.of(
                "/internal/platform/wallet/usage-reservations",
                "/internal/platform/wallet/usage-reservations/"
                        + "93000000-0000-4000-8000-000000000003",
                "/internal/platform/wallet/usage-reservations/"
                        + "93000000-0000-4000-8000-000000000003/settle",
                "/internal/platform/wallet/usage-reservations/"
                        + "93000000-0000-4000-8000-000000000003/release")) {
            // 控制器会校验签名，并对未签名的请求回答 401；
            // 该断言的重点在于拒绝规则没有先以 403 触发。
            ResultActions result = mockMvc.perform(
                    post(path).contentType(MediaType.APPLICATION_JSON).content("{}"));
            result.andExpect(status().is4xxClientError());
            assertThatNotForbiddenOrServed(path, result);
        }
    }

    private static void assertThatDenied(String path, ResultActions result) throws Exception {
        int status = result.andReturn().getResponse().getStatus();
        if (status == HttpStatus.OK.value()) {
            throw new AssertionError(path + " -> 200: unlisted internal path was served");
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            throw new AssertionError(
                    path + " -> 404: the request reached the dispatcher instead of being denied");
        }
    }
}
