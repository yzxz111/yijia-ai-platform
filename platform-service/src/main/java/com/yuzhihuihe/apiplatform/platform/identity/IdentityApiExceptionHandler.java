package com.yuzhihuihe.apiplatform.platform.identity;

import com.yuzhihuihe.apiplatform.platform.identity.api.IdentityErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class IdentityApiExceptionHandler {

    @ExceptionHandler(IdentityApiException.class)
    ResponseEntity<IdentityErrorResponse> handle(IdentityApiException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        if (error.retryAfter() != null) {
            response.header(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(error.retryAfter().toSeconds()));
        }
        return response.body(new IdentityErrorResponse(error.code()));
    }
}
