package com.yuzhihuihe.apiplatform.platform.admin;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.RetryOutboxRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.UpdatePaymentConfigRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.UpdateUserRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.GrantView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.OrderView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.OutboxHealth;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.OutboxView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.Overview;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PageResult;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PaymentConfigView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PaymentView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PlanVersionView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.RefundView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserAvatarView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserDetailView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserIdentityView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserView;
import com.yuzhihuihe.apiplatform.platform.refund.RefundApplicationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/admin")
public class AdminManagementController {

    private final AdminManagementService service;
    private final RefundApplicationService refundService;
    private final AdminAnalyticsService analyticsService;

    public AdminManagementController(
            AdminManagementService service,
            RefundApplicationService refundService,
            AdminAnalyticsService analyticsService) {
        this.service = service;
        this.refundService = refundService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    Overview overview() {
        return service.overview();
    }

    @GetMapping("/analytics")
    AdminAnalyticsService.OperatingAnalytics analytics(
            @RequestParam(defaultValue = "6") int months) {
        return analyticsService.analytics(months);
    }

    @GetMapping("/users")
    PageResult<UserView> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String packageCode,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @AuthenticationPrincipal Jwt jwt) {
        return service.listUsers(
                new AdminListQuery(page, size, query, status, sort, direction, packageCode),
                tenantActor(jwt));
    }

    @GetMapping("/users/identities")
    List<UserIdentityView> userIdentities(
            @RequestParam List<UUID> ids) {
        return service.resolveUserIdentities(ids);
    }

    @GetMapping("/users/{id}")
    UserDetailView userDetail(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return service.userDetail(id, tenantActor(jwt));
    }

    @GetMapping("/users/{id}/avatar")
    ResponseEntity<byte[]> userAvatar(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        // 头像同样受租户边界约束：否则"列表看不见、知道 id 就能拿到"会留下后门。
        UserAvatarView avatar = service.userAvatar(id, tenantActor(jwt));
        if (avatar == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .body(avatar.bytes());
    }

    @PatchMapping("/users/{id}")
    UserView updateUser(
            @PathVariable UUID id,
            @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        // 走带租户的 actor：响应体是一次事实回读，不传租户会让它绕过刚加上的边界
        // （而且非全局管理员会拿不到自己刚改出来的结果）。
        return service.updateUser(id, request, tenantActor(jwt));
    }

    @GetMapping("/grants")
    PageResult<GrantView> grants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listGrants(listQuery(page, size, query, status, sort, direction));
    }

    @GetMapping("/plans")
    PageResult<PlanVersionView> plans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "code") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        return service.listPlans(listQuery(page, size, query, status, sort, direction));
    }

    @GetMapping("/orders")
    PageResult<OrderView> orders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listOrders(listQuery(page, size, query, status, sort, direction));
    }

    @GetMapping("/payment-configs")
    List<PaymentConfigView> paymentConfigs() {
        return service.paymentConfigs();
    }

    @GetMapping("/payments")
    PageResult<PaymentView> payments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listPayments(listQuery(page, size, query, status, sort, direction));
    }

    @GetMapping("/refunds")
    PageResult<RefundView> refunds(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listRefunds(listQuery(page, size, query, status, sort, direction));
    }

    @GetMapping("/refunds/{refundNo}")
    RefundApplicationService.RefundView refundDetail(@PathVariable String refundNo) {
        return refundService.adminDetail(refundNo);
    }

    @PatchMapping("/payment-configs/{provider}/{environment}")
    PaymentConfigView updatePaymentConfig(
            @PathVariable String provider,
            @PathVariable String environment,
            @RequestBody UpdatePaymentConfigRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.updatePaymentConfig(provider, environment, request, actor(jwt));
    }

    @GetMapping("/outbox")
    PageResult<OutboxView> outbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listOutbox(listQuery(page, size, query, status, sort, direction));
    }

    @GetMapping("/outbox/health")
    OutboxHealth outboxHealth() {
        return service.outboxHealth();
    }

    @PostMapping("/outbox/{id}/retry")
    OutboxView retryOutbox(
            @PathVariable UUID id,
            @RequestBody RetryOutboxRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.retryOutbox(id, request, actor(jwt));
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * 读取用户时的租户边界。
     *
     * <p>{@code tenant_id} 是 {@code JwtTokenService} 在签发时写进 token 的声明，
     * 因此这里无需再查库。它可能缺失（老 token / 特殊签发路径），此时按
     * "没有租户"传给服务层——非全局管理员会 **fail closed**（看不到任何用户），
     * 而不是放开。
     */
    private AdminManagementRepository.AdminActor tenantActor(Jwt jwt) {
        return service.resolveAdminActor(actor(jwt), jwt.getClaimAsString("tenant_id"));
    }

    private AdminListQuery listQuery(
            int page, int size, String query, String status, String sort, String direction) {
        return new AdminListQuery(page, size, query, status, sort, direction);
    }
}
