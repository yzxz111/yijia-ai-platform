package com.yuzhihuihe.apiplatform.platform.wallet;

import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/platform/wallet/eligibility")
public class WalletEligibilityInternalController {

    private final WalletQueryService walletQueryService;
    private final InternalRequestSigner signer;

    public WalletEligibilityInternalController(
            WalletQueryService walletQueryService, InternalRequestSigner signer) {
        this.walletQueryService = walletQueryService;
        this.signer = signer;
    }

    @GetMapping("/{tenantId}/{userId}")
    WalletEligibilityResponse eligibility(
            @PathVariable UUID tenantId,
            @PathVariable UUID userId,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Request-Id") String requestId,
            @RequestHeader("X-Internal-Content-Sha256") String contentSha256,
            @RequestHeader("X-Internal-Signature") String signature) {
        String path = "/internal/platform/wallet/eligibility/" + tenantId + "/" + userId;
        verify(path, timestamp, requestId, contentSha256, signature);
        WalletQueryService.WalletSummary summary = walletQueryService.summary(tenantId, userId);
        return new WalletEligibilityResponse(
                summary.availableAmount() > 0, summary.availableAmount(), summary.currency());
    }

    private void verify(String path, String timestamp, String requestId,
            String contentSha256, String signature) {
        try {
            UUID parsedRequestId = UUID.fromString(requestId);
            long parsedTimestamp = Long.parseLong(timestamp);
            if (!signer.verify(parsedTimestamp, parsedRequestId, "GET", path, new byte[0],
                    contentSha256, signature)) {
                throw new IllegalArgumentException("invalid_signature");
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid_internal_signature");
        }
    }

    record WalletEligibilityResponse(boolean usable, long availableAmount, String currency) {
    }
}
