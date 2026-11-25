package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Clock;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = InternalSmsController.class)
final class InternalSmsControllerAdvice {

    private final ObjectMapper objectMapper;
    private final InternalRequestSigner signer;

    InternalSmsControllerAdvice(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this.objectMapper = objectMapper;
        this.signer = new InternalRequestSigner(hmacSecret, clock);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> handleStatus(
            ResponseStatusException error, HttpServletRequest request) {
        String code = error.getReason() == null || error.getReason().isBlank()
                ? "internal_sms_request_failed" : error.getReason();
        return response(request, error.getStatusCode().value(), code);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> handleUnreadableBody(
            HttpMessageNotReadableException error, HttpServletRequest request) {
        return response(request, 400, "invalid_request_body");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<?> handleUnexpected(
            RuntimeException error, HttpServletRequest request) {
        return response(request, 500, "internal_sms_error");
    }

    ResponseEntity<?> response(HttpServletRequest request, int status, String code) {
        Object requestId = request.getAttribute(
                InternalSmsHmacFilter.AUTHENTICATED_REQUEST_ID_ATTRIBUTE);
        ErrorPayload error = new ErrorPayload(code, code);
        if (!(requestId instanceof UUID authenticatedRequestId)) {
            return ResponseEntity.status(status).body(error);
        }
        try {
            InternalRequestSigner.SignedResponse signed = signer.signResponse(
                    authenticatedRequestId, status, objectMapper.writeValueAsBytes(error));
            return ResponseEntity.status(status).body(
                    new InternalSmsController.SignedInternalResponse(
                            signed.requestId(), signed.timestamp(), signed.contentSha256(),
                            signed.signature(), signed.payloadBase64()));
        } catch (Exception signingUnavailable) {
            return ResponseEntity.status(status).body(error);
        }
    }

    private record ErrorPayload(String code, String reason) { }
}
