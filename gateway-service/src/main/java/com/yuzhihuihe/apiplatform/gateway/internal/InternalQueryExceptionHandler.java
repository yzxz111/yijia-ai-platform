package com.yuzhihuihe.apiplatform.gateway.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 内部上报端点的错误形态。
 *
 * <p>没有它的话，一次拒绝会由框架的默认响应体作答，而这会丢掉
 * reason —— 也就是平台团队据以编码的错误码 —— 于是一个 {@code 404
 * package_instance_not_found} 会以没有标签的 "Not Found" 形式返回。这与其他
 * 控制器的 advice 一致：reason 即错误码。
 */
@RestControllerAdvice(assignableTypes = {
        LimitUsageController.class,
        SettlementDeficitController.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class InternalQueryExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<InternalQueryError> handle(ResponseStatusException error) {
        String code = error.getReason() == null ? "internal_query_failed" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(new InternalQueryError(code));
    }

    record InternalQueryError(String code) { }
}
