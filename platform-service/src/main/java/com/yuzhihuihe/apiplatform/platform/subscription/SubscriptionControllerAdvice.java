package com.yuzhihuihe.apiplatform.platform.subscription;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 订阅治理接口的错误响应体。
 *
 * <p>格式与既有的 {@code OrderControllerAdvice} / {@code PackageCatalogControllerAdvice} 一致：
 * 扁平的 {@code {code, message}}，前端用同一个 {@code readApiErrorMessage} 读取。
 *
 * <p>用 {@code code = reason}（而不是维护一张英文原因到中文文案的映射表）：失败原因列表有
 * 十几条，维护一张平行映射表意味着**每加一个原因就要记得加一条文案**，漏掉的那条会让用户
 * 看到英文码。这里改为直接透出稳定短码，中文文案由前端按码渲染——一处维护，不会漂移。
 *
 * <p>只作用于本包的三个控制器，因此不会影响任何既有接口的响应体。
 */
@RestControllerAdvice(assignableTypes = {AdminSubscriptionController.class, SubscriptionController.class})
class SubscriptionControllerAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> handle(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        String code = exception.getReason() == null || exception.getReason().isBlank()
                ? resolved.name().toLowerCase()
                : exception.getReason();
        return ResponseEntity.status(resolved).body(Map.of("code", code, "message", code));
    }
}
