package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class InternalSmsMvcExceptionAdvice {

    private final InternalSmsControllerAdvice responses;

    InternalSmsMvcExceptionAdvice(InternalSmsControllerAdvice responses) {
        this.responses = responses;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException error,
            HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        if (!authenticated(request)) {
            throw error;
        }
        return responses.response(request, 405, "method_not_allowed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException error,
            HttpServletRequest request) throws HttpMediaTypeNotSupportedException {
        if (!authenticated(request)) {
            throw error;
        }
        return responses.response(request, 415, "unsupported_media_type");
    }

    private boolean authenticated(HttpServletRequest request) {
        return request.getAttribute(InternalSmsHmacFilter.AUTHENTICATED_REQUEST_ID_ATTRIBUTE)
                instanceof UUID;
    }
}
