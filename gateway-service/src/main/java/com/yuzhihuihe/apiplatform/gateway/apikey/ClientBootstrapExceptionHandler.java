package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ClientBootstrapController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ClientBootstrapExceptionHandler {

    private static final Map<String, String> MESSAGES = Map.of(
            "invalid_credentials", "登录状态无效，请重新登录。",
            "active_package_required", "当前没有可用套餐，请先购买套餐。",
            "package_refund_in_progress", "套餐退款处理中，暂时无法获取调用凭证。",
            "client_bootstrap_rate_limited", "请求过于频繁，请稍后重试。",
            "credential_encryption_unavailable", "调用凭证服务暂时不可用。",
            "client_bootstrap_unavailable", "客户端初始化服务暂时不可用。"
    );

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ClientBootstrapError> handle(ResponseStatusException error) {
        String code = error.getReason() == null
                ? "client_bootstrap_unavailable"
                : error.getReason();
        String message = MESSAGES.getOrDefault(
                code, "客户端初始化服务暂时不可用。");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.getStatusCode());
        String retryAfter = error.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return response.body(new ClientBootstrapError(code, message));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ClientBootstrapError> handleUnexpected(Exception error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ClientBootstrapError(
                        "client_bootstrap_unavailable",
                        MESSAGES.get("client_bootstrap_unavailable")));
    }

    public record ClientBootstrapError(String code, String message) {
    }
}
