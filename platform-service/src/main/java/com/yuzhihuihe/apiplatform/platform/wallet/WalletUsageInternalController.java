package com.yuzhihuihe.apiplatform.platform.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/platform/wallet/usage-reservations")
public class WalletUsageInternalController {

    private final WalletUsageReservationService service;
    private final InternalRequestSigner signer;
    private final ObjectMapper objectMapper;

    public WalletUsageInternalController(WalletUsageReservationService service,
            InternalRequestSigner signer, ObjectMapper objectMapper) {
        this.service = service;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    WalletUsageReservationService.WalletReservation reserve(
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Request-Id") String requestId,
            @RequestHeader("X-Internal-Content-Sha256") String contentSha256,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestBody byte[] body) {
        verify("POST", "/internal/platform/wallet/usage-reservations", timestamp,
                requestId, contentSha256, signature, body);
        try {
            return service.reserve(objectMapper.readValue(body,
                    WalletUsageReservationService.ReserveCommand.class));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_wallet_usage_reservation", exception);
        }
    }

    @PostMapping("/{reservationId}/settle")
    WalletUsageReservationService.WalletReservation settle(
            @PathVariable UUID reservationId,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Request-Id") String requestId,
            @RequestHeader("X-Internal-Content-Sha256") String contentSha256,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestBody byte[] body) {
        verify("POST", "/internal/platform/wallet/usage-reservations/" + reservationId + "/settle",
                timestamp, requestId, contentSha256, signature, body);
        try {
            return service.settle(reservationId, objectMapper.readValue(body,
                    WalletUsageReservationService.SettleCommand.class));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_wallet_usage_settlement", exception);
        }
    }

    @PostMapping("/{reservationId}/release")
    WalletUsageReservationService.WalletReservation release(
            @PathVariable UUID reservationId,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Request-Id") String requestId,
            @RequestHeader("X-Internal-Content-Sha256") String contentSha256,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestBody(required = false) byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        verify("POST", "/internal/platform/wallet/usage-reservations/" + reservationId + "/release",
                timestamp, requestId, contentSha256, signature, payload);
        return service.release(reservationId);
    }

    @GetMapping("/{reservationId}")
    WalletUsageReservationService.WalletReservation get(
            @PathVariable UUID reservationId,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Request-Id") String requestId,
            @RequestHeader("X-Internal-Content-Sha256") String contentSha256,
            @RequestHeader("X-Internal-Signature") String signature) {
        verify("GET", "/internal/platform/wallet/usage-reservations/" + reservationId, timestamp,
                requestId, contentSha256, signature, new byte[0]);
        return service.get(reservationId);
    }

    private void verify(String method, String path, String timestamp, String requestId,
            String contentSha256, String signature, byte[] body) {
        try {
            UUID parsed = UUID.fromString(requestId);
            long seconds = Long.parseLong(timestamp);
            if (!signer.verify(seconds, parsed, method, path, body, contentSha256, signature)) {
                throw new IllegalArgumentException("invalid_signature");
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_internal_signature");
        }
    }
}
