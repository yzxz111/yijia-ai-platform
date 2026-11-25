package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
@RequestMapping("/console/platform/admin/sms/templates")
public class AdminSmsTemplateTestController {

    private final SmsTemplateTestDeliveryService service;

    public AdminSmsTemplateTestController(SmsTemplateTestDeliveryService service) {
        this.service = service;
    }

    @PostMapping("/{templateKey}/test-deliveries")
    SmsTemplateTestDeliveryService.TestDeliveryView enqueue(
            @PathVariable String templateKey,
            @RequestBody SmsTemplateTestDeliveryService.TestDeliveryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            return service.enqueue(templateKey, request, UUID.fromString(jwt.getSubject()));
        } catch (IllegalArgumentException validationError) {
            throw unprocessable(validationError);
        } catch (IllegalStateException stateError) {
            if ("sms_template_not_active".equals(stateError.getMessage())) {
                throw unprocessable(stateError);
            }
            throw stateError;
        }
    }

    private ResponseStatusException unprocessable(RuntimeException validationError) {
        String reason = validationError.getMessage() == null
                ? "invalid_sms_template_test_request" : validationError.getMessage();
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, reason, validationError);
    }
}
