package com.yuzhihuihe.apiplatform.platform.payment;

import java.util.Map;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
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
@RequestMapping("/console/platform/payments")
public class PaymentCallbackController {

    private final PaymentAdapterRegistry registry;
    private final PaymentPostingService postingService;
    private final CommercialTransactionMetrics metrics;

    public PaymentCallbackController(
            PaymentAdapterRegistry registry,
            PaymentPostingService postingService,
            CommercialTransactionMetrics metrics) {
        this.registry = registry;
        this.postingService = postingService;
        this.metrics = metrics;
    }

    @PostMapping(value = "/wechat-pay/callback", consumes = MediaType.ALL_VALUE)
    ResponseEntity<?> wechatPayCallback(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader HttpHeaders headers) {
        return handle(
                PaymentProvider.WECHAT_PAY,
                PaymentProduct.WECHAT_NATIVE,
                rawBody,
                headers);
    }

    @PostMapping(value = "/alipay/callback", consumes = MediaType.ALL_VALUE)
    ResponseEntity<?> alipayCallback(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader HttpHeaders headers) {
        return handle(
                PaymentProvider.ALIPAY,
                PaymentProduct.ALIPAY_PAGE,
                rawBody,
                headers);
    }

    private ResponseEntity<?> handle(
            PaymentProvider provider,
            PaymentProduct product,
            byte[] rawBody,
            HttpHeaders headers) {
        byte[] callbackBody = rawBody == null ? new byte[0] : rawBody;
        PaymentAdapter adapter;
        try {
            adapter = registry.require(provider, product);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return failure(provider);
        }

        PaymentAdapter.ProviderPayment payment;
        try {
            payment = adapter.verifyAndParseCallback(
                    new PaymentAdapter.CallbackEnvelope(callbackBody, headers));
        } catch (RuntimeException exception) {
            postingService.recordInvalidCallback(provider, callbackBody);
            return failure(provider);
        }
        if (payment == null || payment.outcome() != ProviderOutcome.SUCCEEDED) {
            return failure(provider);
        }

        try {
            postingService.postSucceeded(provider, payment);
            return success(provider);
        } catch (RuntimeException exception) {
            return failure(provider);
        }
    }

    private ResponseEntity<?> success(PaymentProvider provider) {
        metrics.callback(provider, ProviderOutcome.SUCCEEDED);
        if (provider == PaymentProvider.WECHAT_PAY) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", "SUCCESS", "message", "成功"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("success");
    }

    private ResponseEntity<?> failure(PaymentProvider provider) {
        metrics.callback(provider, ProviderOutcome.FAILED);
        if (provider == PaymentProvider.WECHAT_PAY) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", "FAIL", "message", "失败"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("failure");
    }
}
