package com.yuzhihuihe.apiplatform.platform.refund;

import java.util.Map;
import java.util.Set;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapterRegistry;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/refunds")
public class RefundCallbackController {

    private static final Set<String> ACKNOWLEDGED_STATUSES = Set.of(
            "REJECTED", "CANCELED", "REVOKING", "COMPLETED", "FAILED");

    private final PaymentAdapterRegistry registry;
    private final RefundExecutionService refundExecutionService;

    public RefundCallbackController(
            PaymentAdapterRegistry registry,
            RefundExecutionService refundExecutionService) {
        this.registry = registry;
        this.refundExecutionService = refundExecutionService;
    }

    @PostMapping(value = "/wechat-pay/callback", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Map<String, String>> wechatPayCallback(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader HttpHeaders headers) {
        byte[] callbackBody = rawBody == null ? new byte[0] : rawBody;
        PaymentAdapter.VerifiedRefundCallback callback;
        try {
            PaymentAdapter adapter = registry.require(
                    PaymentProvider.WECHAT_PAY, PaymentProduct.WECHAT_NATIVE);
            callback = adapter.verifyAndParseRefundCallback(
                    new PaymentAdapter.CallbackEnvelope(callbackBody, headers));
        } catch (RuntimeException exception) {
            return failure(HttpStatus.UNAUTHORIZED);
        }

        try {
            RefundExecutionService.ExecutionResult result =
                    refundExecutionService.query(callback.refundNo());
            return ACKNOWLEDGED_STATUSES.contains(result.refundStatus())
                    ? success()
                    : failure(HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            return failure(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<Map<String, String>> success() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "SUCCESS", "message", "成功"));
    }

    private ResponseEntity<Map<String, String>> failure(HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "FAIL", "message", "失败"));
    }
}
