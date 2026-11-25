package com.yuzhihuihe.apiplatform.platform.subscription;

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

/**
 * 订阅治理的后台接口。
 *
 * <p>授权由既有的 {@code /console/platform/admin/**} 规则统一提供（{@code ROLE_ADMIN}），
 * 本类不需要再声明。写操作都把操作者身份（JWT 的 {@code sub}）传进服务层写审计。
 *
 * <p><b>不提供人工改价扣款</b>——见 {@link AdminSubscriptionService} 的类注释。
 */
@RestController
@RequestMapping("/console/platform/admin/subscriptions")
public class AdminSubscriptionController {

    private final AdminSubscriptionService service;

    AdminSubscriptionController(AdminSubscriptionService service) {
        this.service = service;
    }

    @GetMapping
    AdminSubscriptionRepository.PageResult<AdminSubscriptionRepository.AdminSubscriptionView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planCode,
            @RequestParam(required = false) UUID userId) {
        return service.list(status, planCode, userId, page, size);
    }

    @GetMapping("/{subscriptionId}")
    AdminSubscriptionService.Detail detail(@PathVariable UUID subscriptionId) {
        return service.detail(subscriptionId);
    }

    /** 人工终止；原因必填，写审计。 */
    @PostMapping("/{subscriptionId}/terminate")
    AdminSubscriptionRepository.AdminSubscriptionView terminate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID subscriptionId,
            @RequestBody(required = false) TerminateRequest request) {
        return service.terminate(
                subscriptionId, actorId(jwt), request == null ? null : request.reason());
    }

    /** 人工触发一次失败账期的重试；幂等。 */
    @PostMapping("/cycles/{cycleId}/retry")
    AdminSubscriptionService.RetryResult retry(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID cycleId,
            @RequestBody(required = false) RetryRequest request) {
        return service.retry(cycleId, actorId(jwt), request == null ? null : request.reason());
    }

    private static UUID actorId(Jwt jwt) {
        return jwt == null ? null : UUID.fromString(jwt.getSubject());
    }

    record TerminateRequest(String reason) {
    }

    record RetryRequest(String reason) {
    }
}
