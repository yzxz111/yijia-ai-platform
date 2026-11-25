package com.yuzhihuihe.apiplatform.platform.wallet;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {
        WalletUsageInternalController.class,
        WalletEligibilityInternalController.class
})
final class WalletInternalControllerAdvice {

    private static final Set<String> SAFE_CODES = Set.of(
            "billing_reconciliation_required",
            "invalid_internal_signature",
            "invalid_wallet_usage_reservation",
            "invalid_wallet_usage_settlement",
            "wallet_insufficient_balance",
            "wallet_not_found",
            "wallet_usage_idempotency_mismatch",
            "wallet_usage_not_releasable",
            "wallet_usage_not_settleable",
            "wallet_usage_reservation_not_found");

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> handle(ResponseStatusException error) {
        String reason = error.getReason();
        String code = reason != null && SAFE_CODES.contains(reason)
                ? reason : "wallet_operation_failed";
        HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
        String safeReason = status == null ? "Wallet operation failed" : status.getReasonPhrase();
        return ResponseEntity.status(error.getStatusCode())
                .body(new ErrorBody(code, safeReason));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ErrorBody> handleUnexpected(RuntimeException error) {
        return ResponseEntity.internalServerError()
                .body(new ErrorBody("wallet_operation_failed", "Internal Server Error"));
    }

    record ErrorBody(String code, String reason) { }
}
