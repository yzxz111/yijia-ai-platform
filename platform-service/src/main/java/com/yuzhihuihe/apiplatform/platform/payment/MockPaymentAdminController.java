package com.yuzhihuihe.apiplatform.platform.payment;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "payment.mock", name = "enabled", havingValue = "true")
@RequestMapping("/console/platform/admin/orders")
public class MockPaymentAdminController {

    private final MockPaymentService service;

    public MockPaymentAdminController(MockPaymentService service) {
        this.service = service;
    }

    @PostMapping("/{orderNo}/simulate-payment")
    MockPaymentService.SimulationResult simulatePayment(
            @PathVariable String orderNo,
            @AuthenticationPrincipal Jwt jwt) {
        return service.simulate(orderNo, UUID.fromString(jwt.getSubject()));
    }
}
