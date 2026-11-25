package com.yuzhihuihe.apiplatform.platform.safety;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SafetyAppealController.class)
final class SafetyAppealControllerAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException error) {
        String code = error.getMessage() == null || error.getMessage().isBlank()
                ? "invalid_appeal_request" : error.getMessage();
        HttpStatus status = "content_safety_case_not_found".equals(code)
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorBody(code, code));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> handleUnreadableBody() {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("invalid_appeal_request", "invalid_appeal_request"));
    }

    record ErrorBody(String code, String reason) { }
}
