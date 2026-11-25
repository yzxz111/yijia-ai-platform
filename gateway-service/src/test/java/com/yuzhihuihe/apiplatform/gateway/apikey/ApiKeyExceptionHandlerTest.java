package com.yuzhihuihe.apiplatform.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ApiKeyExceptionHandlerTest {

    @Test
    void translatesApiKeyLimitFailureIntoAReadableErrorResponse() {
        ResponseEntity<ApiKeyExceptionHandler.ApiKeyErrorResponse> response =
                new ApiKeyExceptionHandler().handleResponseStatusException(
                        new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "api_key_limit_exceeded"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isEqualTo(new ApiKeyExceptionHandler.ApiKeyErrorResponse(
                "api_key_limit_exceeded",
                "API 密钥数量已达上限，请先撤销不再使用的密钥。"));
    }

    @Test
    void safelyHandlesAnErrorWithoutAnInternalReasonCode() {
        ResponseEntity<ApiKeyExceptionHandler.ApiKeyErrorResponse> response =
                new ApiKeyExceptionHandler().handleResponseStatusException(
                        new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiKeyExceptionHandler.ApiKeyErrorResponse(
                "api_key_operation_failed",
                "API 密钥操作失败，请稍后重试。"));
    }
}
