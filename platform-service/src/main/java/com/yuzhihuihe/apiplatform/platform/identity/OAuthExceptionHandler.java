package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.identity.api.OAuthErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * OAuth 端点专用错误处理：所有失败都必须返回 RFC 6749 错误体，不能落到 Spring 的
 * {@code BasicErrorController}（那会返回 timestamp/status/error/path，客户端无法判断原因）。
 *
 * <p>只作用于 {@link OAuthController}，不影响平台其它接口的既有错误契约。
 */
@RestControllerAdvice(assignableTypes = OAuthController.class)
final class OAuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthExceptionHandler.class);

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<OAuthErrorResponse> handle(OAuthException error) {
        return response(error.status(), error.error(), error.description(), null, error);
    }

    /** 请求体、Content-Type、方法、必填参数等框架级错误统一转成 invalid_request。 */
    @ExceptionHandler({
            HttpMediaTypeNotSupportedException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            HttpRequestMethodNotSupportedException.class,
    })
    ResponseEntity<OAuthErrorResponse> handleMalformedRequest(Exception error) {
        log.warn("oauth request rejected before controller: {}", error.getMessage());
        return response(HttpStatus.BAD_REQUEST, "invalid_request",
                "The request body, content type or method is not accepted by the token endpoint.",
                null, error);
    }

    /**
     * 兜底：任何未预期异常也必须给出 RFC 6749 结构，并记录真实异常便于定位
     * （此前这类异常会直接落到 BasicErrorController，服务端与客户端都拿不到原因）。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<OAuthErrorResponse> handleUnexpected(Exception error) {
        String requestId = "oauth_" + UUID.randomUUID().toString().replace("-", "");
        log.error("unexpected oauth endpoint failure, requestId={}", requestId, error);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "authorization_unavailable",
                "The authorization service is temporarily unavailable.", requestId, error);
    }

    private ResponseEntity<OAuthErrorResponse> response(
            HttpStatus status, String error, String description, String requestId, Exception cause) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        return builder.body(new OAuthErrorResponse(error, description));
    }
}
