package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/admin/sms")
public class AdminSmsController {

    private final AdminSmsService service;

    public AdminSmsController(AdminSmsService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    List<AdminSmsService.TemplateView> templates() {
        return service.listTemplates();
    }

    @PostMapping("/templates/activate")
    SmsTemplateActivationService.ActivationResult activateTemplate(
            @RequestBody SmsTemplateActivationService.ActivationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.activateTemplate(request, UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/deliveries")
    AdminSmsService.PageResult<AdminSmsService.DeliveryView> deliveries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String templateKey,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listDeliveries(new AdminSmsService.DeliveryQuery(
                page, size, query, status, templateKey, sort, direction));
    }

    @PostMapping("/deliveries/{id}/retry")
    AdminSmsService.DeliveryView retry(
            @PathVariable UUID id,
            @RequestBody AdminSmsService.RetryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.retry(id, request, UUID.fromString(jwt.getSubject()));
    }
}
