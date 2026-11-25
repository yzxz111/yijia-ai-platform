package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class WalletEligibilityInternalControllerTest {

    private static final String SECRET = "wallet-eligibility-secret";
    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private WalletQueryService walletQueryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        walletQueryService = org.mockito.Mockito.mock(WalletQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WalletEligibilityInternalController(
                walletQueryService, new InternalRequestSigner(SECRET, CLOCK))).build();
    }

    @Test
    void reportsPositiveBalanceAsUsable() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(walletQueryService.summary(tenantId, userId)).thenReturn(
                new WalletQueryService.WalletSummary(1L, "CNY", NOW));

        mvc.perform(signedGet(tenantId, userId, NOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.availableAmount").value(1))
                .andExpect(jsonPath("$.currency").value("CNY"));
    }

    @Test
    void reportsZeroBalanceAsUnusable() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(walletQueryService.summary(tenantId, userId)).thenReturn(
                new WalletQueryService.WalletSummary(0L, "CNY", NOW));

        mvc.perform(signedGet(tenantId, userId, NOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.availableAmount").value(0));
    }

    @Test
    void scopesTheLookupToBothPathIdentifiers() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(walletQueryService.summary(tenantId, userId)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "wallet_not_found"));

        mvc.perform(signedGet(tenantId, userId, NOW))
                .andExpect(status().isNotFound());

        verify(walletQueryService).summary(tenantId, userId);
    }

    @Test
    void rejectsSignatureForDifferentCanonicalUserPath() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID signedUserId = UUID.randomUUID();
        UUID requestedUserId = UUID.randomUUID();
        String signedPath = path(tenantId, signedUserId);
        InternalRequestSigner.SignedHeaders headers = signer().sign(
                NOW.getEpochSecond(), UUID.randomUUID(), "GET", signedPath, new byte[0]);

        mvc.perform(get(path(tenantId, requestedUserId))
                        .header("X-Internal-Timestamp", headers.timestamp())
                        .header("X-Internal-Request-Id", headers.requestId())
                        .header("X-Internal-Content-Sha256", headers.contentSha256())
                        .header("X-Internal-Signature", headers.signature()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredSignature() throws Exception {
        mvc.perform(signedGet(UUID.randomUUID(), UUID.randomUUID(),
                        NOW.minus(Duration.ofMinutes(6))))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedGet(
            UUID tenantId, UUID userId, Instant timestamp) {
        String path = path(tenantId, userId);
        InternalRequestSigner.SignedHeaders headers = signer().sign(
                timestamp.getEpochSecond(), UUID.randomUUID(), "GET", path, new byte[0]);
        return get(path)
                .header("X-Internal-Timestamp", headers.timestamp())
                .header("X-Internal-Request-Id", headers.requestId())
                .header("X-Internal-Content-Sha256", headers.contentSha256())
                .header("X-Internal-Signature", headers.signature());
    }

    private InternalRequestSigner signer() {
        return new InternalRequestSigner(SECRET, CLOCK);
    }

    private String path(UUID tenantId, UUID userId) {
        return "/internal/platform/wallet/eligibility/" + tenantId + "/" + userId;
    }
}
