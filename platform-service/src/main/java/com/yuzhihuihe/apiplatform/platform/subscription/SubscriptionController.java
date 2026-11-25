package com.yuzhihuihe.apiplatform.platform.subscription;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连续包月的前台接口。
 *
 * <p>鉴权与既有控制台接口一致：{@code tenant_id} 取自 JWT claim，用户身份取自
 * {@code sub}，因此租户隔离由令牌保证而不是由请求参数保证。
 *
 * <p>所有错误都是 {@code ResponseStatusException(reason)} 形式的稳定短码
 * （{@code subscription_not_found} / {@code invalid_idempotency_key} /
 * {@code subscription_period_ended} / {@code subscription_not_open_for_update}），
 * 与 {@code OrderController}、{@code WalletController} 同一约定，因此不需要额外的
 * advice 类来做映射。
 */
@RestController
@RequestMapping("/console/platform/subscriptions")
public class SubscriptionController {

    private final SubscriptionService service;

    SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    /** 我的订阅。 */
    @GetMapping
    List<SubscriptionService.SubscriptionView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(tenantId(jwt), userId(jwt));
    }

    /** 订阅详情，含各期扣款记录。 */
    @GetMapping("/{subscriptionId}")
    SubscriptionService.SubscriptionDetail detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID subscriptionId) {
        return service.detail(tenantId(jwt), userId(jwt), subscriptionId);
    }

    /**
     * 开通连续包月，**并在同一请求内完成首期支付**。
     *
     * <p>{@code Idempotency-Key} 是必填的：重复点击「开通」不能产生第二笔扣款。
     * 与 {@code POST /orders} 一样用请求头承载，前端已有生成该键的既有工具函数。
     */
    @PostMapping
    ResponseEntity<SubscriptionService.SubscriptionView> open(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OpenRequest request) {
        SubscriptionService.SubscriptionView view = service.open(
                tenantId(jwt), userId(jwt),
                request == null ? null : request.packageVersionId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * 开关自动续费。
     *
     * <p>关闭即「解约」：当期用满为止，不再扣下一期。
     */
    @PatchMapping("/{subscriptionId}/auto-renew")
    SubscriptionService.SubscriptionView setAutoRenew(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID subscriptionId,
            @RequestBody AutoRenewRequest request) {
        if (request == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_subscription_request");
        }
        return request.autoRenew()
                ? service.resume(tenantId(jwt), userId(jwt), subscriptionId)
                : service.cancel(tenantId(jwt), userId(jwt), subscriptionId);
    }

    /** 解约（语义比 PATCH 更明确，前端按按钮文案二选一即可）。 */
    @PostMapping("/{subscriptionId}/cancel")
    SubscriptionService.SubscriptionView cancel(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID subscriptionId) {
        return service.cancel(tenantId(jwt), userId(jwt), subscriptionId);
    }

    /** 撤销解约。仅当当期尚未结束时允许。 */
    @PostMapping("/{subscriptionId}/resume")
    SubscriptionService.SubscriptionView resume(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID subscriptionId) {
        return service.resume(tenantId(jwt), userId(jwt), subscriptionId);
    }

    /**
     * 确认接受调整后的续费价（设计 §7.3）。
     *
     * <p>这是「因涨价而暂缓」的订阅**唯一**的恢复入口：确认之后下一轮调度即按新价扣款。
     *
     * <p>不要求 {@code Idempotency-Key}：与 {@code cancel}/{@code resume} 同一约定，而本操作
     * 天然幂等——重复提交第二次会得到 {@code 409 subscription_no_pending_price_change}
     * （待确认状态已被清掉），不会重复扣款：真正的扣款由调度侧的水位推进驱动，扣款本身另有
     * 订单幂等键兜底。
     */
    @PostMapping("/{subscriptionId}/confirm-price")
    SubscriptionService.SubscriptionView confirmPrice(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID subscriptionId) {
        return service.confirmPrice(tenantId(jwt), userId(jwt), subscriptionId);
    }

    private static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record OpenRequest(UUID packageVersionId) {
    }

    record AutoRenewRequest(boolean autoRenew) {
    }
}
