package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class IdentityApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new IdentityApiExceptionHandler())
                .build();
    }

    @Test
    void invalidCredentialsExposeOnlyTheStableCode() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/identity/invalid-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("submitted@example.com", "submitted-password");
    }

    @Test
    void rateLimitIncludesRetryAfterAndExposesOnlyTheStableCode() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/identity/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value("login_rate_limited"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("submitted@example.com", "submitted-password");
    }

    @RestController
    private static final class TestController {

        @GetMapping("/test/identity/invalid-credentials")
        void invalidCredentials() {
            throw IdentityApiException.invalidCredentials();
        }

        @GetMapping("/test/identity/rate-limited")
        void rateLimited() {
            throw IdentityApiException.rateLimited(Duration.ofMinutes(15));
        }
    }
}
