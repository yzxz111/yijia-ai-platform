package com.yuzhihuihe.apiplatform.platform.limitusage;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 为这个端点能给出的、并非数据的两种回答，把稳定的错误码放到响应上。
 *
 * <p>没有它时，503 会以 Spring 的默认错误文档到达控制台，而该文档不携带任何机器可读的错误码
 * —— 于是「网关无法读取」与其他任何服务器故障将无法区分，
 * 而调用方绝不该做的一件事就是把它读作「你的用量为零」。
 * 仅当原因是本控制器抛出的原因时才重新发出；其它情况一律原样再抛，因此该
 * advice 无法悄悄改写它不理解的失败。
 */
@RestControllerAdvice(assignableTypes = PackageLimitUsageController.class)
public class PackageLimitUsageControllerAdvice {

    private static final Map<String, String> SAFE_MESSAGES = Map.of(
            "package_instance_not_found", "套餐实例不存在或不属于当前账号。",
            "gateway_limit_usage_unavailable", "用量服务暂时不可用，请稍后重试。");

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> handle(ResponseStatusException exception) {
        String reason = exception.getReason();
        String message = reason == null ? null : SAFE_MESSAGES.get(reason);
        if (message == null) {
            throw exception;
        }
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorBody(reason, message));
    }

    public record ErrorBody(String code, String message) {
    }
}
