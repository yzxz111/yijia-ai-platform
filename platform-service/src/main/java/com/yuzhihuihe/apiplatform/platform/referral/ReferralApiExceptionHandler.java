package com.yuzhihuihe.apiplatform.platform.referral;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ReferralApiExceptionHandler {

    @ExceptionHandler(ReferralApiException.class)
    ResponseEntity<ErrorBody> handle(ReferralApiException error) {
        return ResponseEntity.status(error.status()).body(new ErrorBody(error.code()));
    }

    record ErrorBody(String code) { }
}
