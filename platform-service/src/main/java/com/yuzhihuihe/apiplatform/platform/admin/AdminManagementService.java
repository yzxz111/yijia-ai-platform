package com.yuzhihuihe.apiplatform.platform.admin;

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
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminManagementService {

    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> USER_ROLES = Set.of("USER", "ADMIN");
    private static final Set<String> PAYMENT_ENVIRONMENTS =
            Set.of("LOCAL", "SANDBOX", "PRODUCTION");

    private final AdminManagementRepository repository;
    private final PaymentAvailabilityService paymentAvailabilityService;

    public AdminManagementService(
            AdminManagementRepository repository,
            PaymentAvailabilityService paymentAvailabilityService) {
        this.repository = repository;
        this.paymentAvailabilityService = paymentAvailabilityService;
    }

    public Overview overview() {
        return repository.overview();
    }

    public PageResult<UserView> listUsers(int page, int size, String query, String status) {
        return repository.listUsers(page, size, query, status);
    }

    public PageResult<UserView> listUsers(AdminListQuery request) {
        return repository.listUsers(request);
    }

    public PageResult<UserView> listUsers(AdminListQuery request, UUID actorUserId) {
        return repository.listUsers(request, actorUserId);
    }

    /** 带租户边界的管理端用户列表。控制器必须走这一个。 */
    public PageResult<UserView> listUsers(
            AdminListQuery request, AdminManagementRepository.AdminActor actor) {
        return repository.listUsers(request, actor);
    }

    /**
     * 把 JWT 里的身份解析成带租户边界的 actor。
     *
     * @param userId           token 的 subject
     * @param tenantIdClaim    token 的 {@code tenant_id} 声明，可能缺失或不可解析
     */
    public AdminManagementRepository.AdminActor resolveAdminActor(
            UUID userId, String tenantIdClaim) {
        UUID tenantId = null;
        if (tenantIdClaim != null && !tenantIdClaim.isBlank()) {
            try {
                tenantId = UUID.fromString(tenantIdClaim);
            } catch (IllegalArgumentException malformed) {
                // 声明不可解析时按"没有租户"处理：全局管理员不受影响，
                // 其余人 fail closed。绝不因为解析失败而放行。
                tenantId = null;
            }
        }
        return new AdminManagementRepository.AdminActor(
                userId, tenantId, repository.isGlobalAdministrator(userId));
    }

    /** 带租户边界的用户详情。控制器必须走这一个。 */
    public UserDetailView userDetail(
            UUID userId, AdminManagementRepository.AdminActor actor) {
        return repository.userDetail(userId, actor);
    }

    public UserDetailView userDetail(UUID userId, UUID actorUserId) {
        return repository.userDetail(userId, actorUserId);
    }

    public UserAvatarView userAvatar(UUID userId) {
        return repository.userAvatar(userId);
    }

    /** 带租户边界的头像读取。控制器必须走这一个。 */
    public UserAvatarView userAvatar(
            UUID userId, AdminManagementRepository.AdminActor actor) {
        return repository.userAvatar(userId, actor);
    }

    public List<UserIdentityView> resolveUserIdentities(List<UUID> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 200
                || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_user_ids");
        }
        return repository.resolveUserIdentities(ids.stream().distinct().toList());
    }

    @Transactional
    public UserView updateUser(UUID userId, UpdateUserRequest request, UUID actorUserId) {
        // 兼容旧的二元签名：拿不到租户 → 非全局管理员会被 fail-closed 挡住（见下）。
        return updateUser(userId, request, new AdminManagementRepository.AdminActor(
                actorUserId, null, false));
    }

    @Transactional
    public UserView updateUser(
            UUID userId, UpdateUserRequest request, AdminManagementRepository.AdminActor actor) {
        UUID actorUserId = actor == null ? null : actor.userId();
        if (userId == null || request == null || actorUserId == null) {
            throw invalid("invalid_user_update");
        }
        String status = enumValue(request.status(), USER_STATUSES, "invalid_user_status");
        String role = enumValue(request.role(), USER_ROLES, "invalid_user_role");
        AdminManagementRepository.UserIdentity identity = repository.lockUserIdentity(userId);
        String disabledReason = normalizeDisabledReason(request.disabledReason());
        if (!status.equals(identity.status())
                && "DISABLED".equals(status)
                && disabledReason == null) {
            throw invalid("disabled_reason_required");
        }
        // 全局管理员的判定必须**始终来自库里的事实**，不能只信 actor 里的标志：
        // 传进来的 actor 可能只带了 userId（旧签名、测试、内部复用），
        // 那样 actor.globalAdmin() 是 false，会让全局管理员连自己的操作都做不了
        // （authorizeUpdate 会抛 global_admin_required）。这与 listUsers/userDetail
        // 里 resolveGlobalAdmin 的口径一致：false 一律复核一次。
        boolean actorGlobalAdmin = actor != null && actor.globalAdmin();
        if (!actorGlobalAdmin) {
            actorGlobalAdmin = repository.isGlobalAdministrator(actorUserId);
        }
        UUID actorTenantId = actor == null ? null : actor.tenantId();
        // **跨租户写拦截（在事实层判定，不依赖凭据）。**
        // `AdminUserAuthorizationPolicy.authorizeUpdate` 只看角色差（USER/ADMIN 等级），
        // 完全不比租户；而这次补上的读边界意味着"读不到的人也不该改得到"，
        // 否则会出现"列表里看不见、但 PATCH 能改"的不一致。
        // 用 `identity.tenantId()`（来自 SELECT ... FOR UPDATE 的库内事实）而不是
        // actor 的声明：actorTenantId 为 null 时**不**用这条规则拦，
        // 因为那属于"凭据缺租户"，与"租户不同"是两件事，前者由读边界 fail-closed 负责。
        if (!actorGlobalAdmin
                && actorTenantId != null
                && identity.tenantId() != null
                && !identity.tenantId().equals(actorTenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found");
        }
        AdminUserAuthorizationPolicy.authorizeUpdate(
                actorUserId,
                actorGlobalAdmin,
                userId,
                identity.globalAdmin(),
                identity.status(),
                identity.role(),
                status,
                role);
        // 回读用**目标自己的租户**：上面已确认 actor 有权触及该租户，
        // 因此这次读一定看得见（否则非全局管理员会拿不到自己刚改出来的结果）。
        UUID readTenantId = actorGlobalAdmin ? actorTenantId : identity.tenantId();
        if (status.equals(identity.status()) && role.equals(identity.role())) {
            return repository.findUser(userId, actorUserId, actorGlobalAdmin, readTenantId);
        }
        repository.updateUser(identity, status, role, disabledReason, actorUserId);
        // 响应体同样是一次读取，必须与上面同一口径。
        return repository.findUser(userId, actorUserId, actorGlobalAdmin, readTenantId);
    }

    private String normalizeDisabledReason(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 500) throw invalid("disabled_reason_too_long");
        return normalized;
    }

    public PageResult<GrantView> listGrants(int page, int size, String status) {
        return repository.listGrants(page, size, status);
    }

    public PageResult<GrantView> listGrants(AdminListQuery request) {
        return repository.listGrants(request);
    }

    public List<PlanVersionView> listPlans() {
        return repository.listPlans();
    }

    public PageResult<PlanVersionView> listPlans(AdminListQuery request) {
        return repository.listPlans(request);
    }

    public PageResult<OrderView> listOrders(int page, int size, String status) {
        return repository.listOrders(page, size, status);
    }

    public PageResult<OrderView> listOrders(AdminListQuery request) {
        return repository.listOrders(request);
    }

    public List<PaymentConfigView> paymentConfigs() {
        return repository.paymentConfigs();
    }

    public PageResult<PaymentView> listPayments(int page, int size, String status) {
        return repository.listPayments(page, size, status);
    }

    public PageResult<PaymentView> listPayments(AdminListQuery request) {
        return repository.listPayments(request);
    }

    public PageResult<RefundView> listRefunds(int page, int size, String status) {
        return repository.listRefunds(page, size, status);
    }

    public PageResult<RefundView> listRefunds(AdminListQuery request) {
        return repository.listRefunds(request);
    }

    @Transactional
    public PaymentConfigView updatePaymentConfig(
            String provider,
            String environment,
            UpdatePaymentConfigRequest request,
            UUID actorUserId) {
        if (blank(provider) || request == null || actorUserId == null) {
            throw invalid("invalid_payment_config");
        }
        String normalizedEnvironment =
                enumValue(environment, PAYMENT_ENVIRONMENTS, "invalid_payment_environment");
        String normalizedProvider = provider.strip().toUpperCase(Locale.ROOT);
        if (request.enabled()) {
            PaymentProvider paymentProvider;
            try {
                paymentProvider = PaymentProvider.valueOf(normalizedProvider);
            } catch (IllegalArgumentException exception) {
                throw invalid("invalid_payment_provider");
            }
            paymentAvailabilityService.assertCanEnable(
                    paymentProvider, PaymentEnvironment.valueOf(normalizedEnvironment));
        }
        AdminManagementRepository.PaymentConfigState before =
                repository.lockPaymentConfig(normalizedProvider, normalizedEnvironment);
        repository.updatePaymentConfig(
                normalizedProvider, normalizedEnvironment, request.enabled());
        repository.auditPaymentConfigUpdate(
                normalizedProvider, normalizedEnvironment, request.enabled(), before, actorUserId);
        return repository.paymentConfig(normalizedProvider, normalizedEnvironment);
    }

    public PageResult<OutboxView> listOutbox(int page, int size, String status) {
        return repository.listOutbox(page, size, status);
    }

    public PageResult<OutboxView> listOutbox(AdminListQuery request) {
        return repository.listOutbox(request);
    }

    public OutboxHealth outboxHealth() {
        return repository.outboxHealth();
    }

    @Transactional
    public OutboxView retryOutbox(UUID eventId, RetryOutboxRequest request, UUID actorUserId) {
        if (eventId == null
                || request == null
                || actorUserId == null
                || blank(request.businessNo())
                || request.businessNo().strip().length() > 128
                || !request.businessNo().strip().startsWith("outbox-")) {
            throw invalid("invalid_outbox_retry");
        }
        String businessNo = request.businessNo().strip();
        if (repository.reserveOutboxRetry(businessNo, eventId, actorUserId) == 0) {
            return repository.replayOutboxRetry(businessNo, eventId);
        }
        AdminManagementRepository.RetryTarget target = repository.lockRetryTarget(eventId);
        repository.resetOutboxRetry(target, businessNo, actorUserId);
        OutboxView response = repository.findOutbox(eventId);
        repository.storeOutboxRetrySnapshot(eventId, businessNo);
        return response;
    }

    private String enumValue(String value, Set<String> allowed, String error) {
        if (blank(value)) {
            throw invalid(error);
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid(error);
        }
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
