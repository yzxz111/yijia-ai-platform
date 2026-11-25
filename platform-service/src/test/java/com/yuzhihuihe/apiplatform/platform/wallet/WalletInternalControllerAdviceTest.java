package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class WalletInternalControllerAdviceTest {

    private final WalletInternalControllerAdvice advice = new WalletInternalControllerAdvice();

    @Test
    void returnsStableWalletCodeWithoutRawExceptionDetails() {
        ResponseEntity<WalletInternalControllerAdvice.ErrorBody> response = advice.handle(
                new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "wallet_insufficient_balance",
                        new IllegalStateException("sensitive-database-detail")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("wallet_insufficient_balance");
        assertThat(response.getBody().reason()).isEqualTo("Unprocessable Entity");
        assertThat(response.getBody().toString()).doesNotContain("sensitive-database-detail");
    }

    @Test
    void replacesUnknownBusinessReasonWithGenericCode() {
        ResponseEntity<WalletInternalControllerAdvice.ErrorBody> response = advice.handle(
                new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "unexpected_sensitive_failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("wallet_operation_failed");
        assertThat(response.getBody().toString()).doesNotContain("unexpected_sensitive_failure");
    }
}
