package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class OrderControllerAdviceTest {

    private final OrderControllerAdvice advice = new OrderControllerAdvice();

    @Test
    void mapsKnownOrderFailuresToSafeReadableErrors() {
        Map<String, String> expectedMessages = Map.ofEntries(
                Map.entry("package_quota_remaining", "当前套餐仍有剩余额度或正在排队，暂时无法重复购买。"),
                Map.entry("package_purchase_limit_reached", "该套餐已达到购买次数上限。"),
                Map.entry("package_sold_out", "该套餐已售罄，请选择其他套餐。"),
                Map.entry("published_package_version_not_found", "套餐版本已下架或不存在，请刷新页面后重试。"),
                Map.entry("package_version_not_found", "套餐版本已下架或不存在，请刷新页面后重试。"),
                Map.entry("paid_package_required", "请选择已发布的付费套餐。"),
                Map.entry("order_idempotency_payload_mismatch", "订单信息已发生变化，请关闭结算窗口后重试。"),
                Map.entry("invalid_order_request", "订单信息无效，请刷新页面后重试。"),
                Map.entry("invalid_idempotency_key", "订单请求已失效，请关闭结算窗口后重试。"));

        expectedMessages.forEach((code, message) -> {
            ResponseEntity<OrderControllerAdvice.ErrorBody> response = advice.handle(
                    new ResponseStatusException(HttpStatus.CONFLICT, code));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isEqualTo(new OrderControllerAdvice.ErrorBody(code, message));
            assertThat(response.getBody().message()).doesNotContain(code);
        });
    }

    @Test
    void preservesStatusForMissingPackageVersion() {
        ResponseEntity<OrderControllerAdvice.ErrorBody> response = advice.handle(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "published_package_version_not_found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rethrowsUnknownResponseStatusReason() {
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "unrelated_order_failure");

        assertThatThrownBy(() -> advice.handle(exception))
                .isSameAs(exception);
    }
}
