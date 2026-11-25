package com.yuzhihuihe.apiplatform.platform.sms;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class SmsApiExceptionHandler {

    @ExceptionHandler(SmsApiException.class)
    ResponseEntity<ErrorBody> handle(SmsApiException error) {
        return ResponseEntity.status(error.status()).body(new ErrorBody(error.code(), error.code()));
    }

    record ErrorBody(String code, String reason) { }
}
