package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ApiKeyController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ApiKeyExceptionHandler {

    private static final Map<String, String> MESSAGES = Map.of(
            "invalid_api_key_request", "密钥名称或可调用模型不符合要求，请检查后重试。",
            "api_key_limit_exceeded", "API 密钥数量已达上限，请先撤销不再使用的密钥。",
            "api_key_model_unavailable", "当前没有可用于创建 API 密钥的模型。",
            "api_key_model_not_allowed", "所选模型不在当前账号的可用范围内，请重新选择。",
            "api_key_owner_required", "登录信息无效，请重新登录后重试。",
            "invalid_api_key_status", "API 密钥状态无效。",
            "invalid_api_key_id", "API 密钥编号无效。",
            "api_key_not_found", "API 密钥不存在或已被撤销。",
            "invalid_sms_proof", "短信验证已失效，请重新验证。",
            "sms_proof_service_unavailable", "短信验证服务暂时不可用，请稍后重试。"
    );

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiKeyErrorResponse> handleResponseStatusException(
            ResponseStatusException error) {
        String code = error.getReason() == null
                ? "api_key_operation_failed"
                : error.getReason();
        String message = "api_key_management_disabled".equals(code)
                ? "API 密钥管理功能已停用。"
                : MESSAGES.getOrDefault(code, fallbackMessage(error));
        return ResponseEntity.status(error.getStatusCode())
                .body(new ApiKeyErrorResponse(code, message));
    }

    private String fallbackMessage(ResponseStatusException error) {
        if (error.getStatusCode().is4xxClientError()) {
            return "请求未通过校验，请检查后重试。";
        }
        if (error.getStatusCode().equals(HttpStatus.SERVICE_UNAVAILABLE)) {
            return "API 密钥服务暂时不可用，请稍后重试。";
        }
        return "API 密钥操作失败，请稍后重试。";
    }

    public record ApiKeyErrorResponse(String code, String message) { }
}
