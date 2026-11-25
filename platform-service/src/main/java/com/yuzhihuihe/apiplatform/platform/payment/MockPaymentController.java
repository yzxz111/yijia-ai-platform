package com.yuzhihuihe.apiplatform.platform.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "payment.mock", name = "enabled", havingValue = "true")
@RequestMapping("/console/platform/payments/mock")
public class MockPaymentController {

    private final MockPaymentService service;

    public MockPaymentController(MockPaymentService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/callback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    MockPaymentService.CallbackResult callback(
            @RequestHeader(name = "X-Payment-Signature", required = false) String signature,
            @RequestBody MockPaymentService.CallbackRequest request) {
        return service.acceptVerified(request, signature);
    }
}
