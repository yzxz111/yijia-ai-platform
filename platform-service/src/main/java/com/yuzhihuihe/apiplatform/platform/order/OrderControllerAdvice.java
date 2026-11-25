package com.yuzhihuihe.apiplatform.platform.order;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderControllerAdvice {

    private static final Map<String, String> SAFE_MESSAGES = Map.ofEntries(
            Map.entry("package_quota_remaining", "当前套餐仍有剩余额度或正在排队，暂时无法重复购买。"),
            Map.entry("package_purchase_limit_reached", "该套餐已达到购买次数上限。"),
            Map.entry("package_sold_out", "该套餐已售罄，请选择其他套餐。"),
            Map.entry("published_package_version_not_found", "套餐版本已下架或不存在，请刷新页面后重试。"),
            Map.entry("package_version_not_found", "套餐版本已下架或不存在，请刷新页面后重试。"),
            Map.entry("paid_package_required", "请选择已发布的付费套餐。"),
            Map.entry("order_idempotency_payload_mismatch", "订单信息已发生变化，请关闭结算窗口后重试。"),
            Map.entry("invalid_order_request", "订单信息无效，请刷新页面后重试。"),
            Map.entry("invalid_idempotency_key", "订单请求已失效，请关闭结算窗口后重试。"));

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
