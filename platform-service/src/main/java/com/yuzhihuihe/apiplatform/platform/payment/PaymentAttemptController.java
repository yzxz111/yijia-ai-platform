package com.yuzhihuihe.apiplatform.platform.payment;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/orders")
public class PaymentAttemptController {

    private final PaymentAttemptService service;

    public PaymentAttemptController(PaymentAttemptService service) {
        this.service = service;
    }

    @PostMapping("/{orderNo}/payment-attempts")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentAttemptService.PaymentAttemptResult create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo,
            @RequestBody PaymentAttemptService.CreatePaymentAttemptRequest request,
            HttpServletRequest servletRequest) {
        return service.create(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getSubject()), orderNo, request,
                servletRequest.getRemoteAddr());
    }
}
