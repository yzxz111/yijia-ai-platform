package com.yuzhihuihe.apiplatform.platform.admin;

import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.AdminListQuery;
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
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserPackageGrantView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
class AdminManagementRepository {

    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> GRANT_STATUSES = Set.of("QUEUED", "ACTIVE", "FAILED");
    private static final Set<String> ORDER_STATUSES =
            Set.of("PENDING", "PAID", "CLOSED", "REFUNDED", "PARTIALLY_REFUNDED");
    private static final Set<String> PAYMENT_STATUSES =
            Set.of("CREATED", "PENDING", "SUCCEEDED", "FAILED", "CLOSED");
    private static final Set<String> REFUND_STATUSES =
            Set.of(
                    "PREPARING",
                    "PENDING_APPROVAL",
                    "REJECTED",
                    "CANCELED",
                    "SUBMITTING",
                    "PROCESSING",
                    "REVOKING",
                    "COMPLETED",
                    "FAILED");
    private static final Set<String> OUTBOX_STATUSES =
            Set.of("PENDING", "DELIVERING", "DELIVERED", "FAILED");
    private static final Map<String, String> USER_SORTS =
            Map.of(
                    "createdAt",
                    "u.created_at",
                    "email",
                    "u.email",
                    "userNo",
                    "u.user_no",
                    "status",
                    "u.status");
    private static final Map<String, String> GRANT_SORTS =
            Map.of(
                    "createdAt",
                    "g.created_at",
                    "status",
                    "g.status",
                    "businessNo",
                    "g.business_no");
    private static final Map<String, String> PLAN_SORTS =
            Map.of(
                    "code",
                    "p.code",
                    "version",
                    "v.version",
                    "createdAt",
                    "v.created_at",
                    "status",
                    "v.status");
    private static final Map<String, String> ORDER_SORTS =
            Map.of(
                    "createdAt",
                    "o.created_at",
                    "status",
                    "o.status",
                    "amount",
                    "o.amount",
                    "orderNo",
                    "o.order_no");
    private static final Map<String, String> PAYMENT_SORTS =
            Map.of(
                    "createdAt",
                    "payment.created_at",
                    "status",
                    "payment.status",
                    "amount",
                    "payment.amount",
                    "transactionNo",
                    "payment.transaction_no");
    private static final Map<String, String> REFUND_SORTS =
            Map.of(
                    "createdAt",
                    "refund.created_at",
                    "status",
                    "refund.status",
                    "amount",
                    "refund.amount",
                    "refundNo",
                    "refund.refund_no");
    private static final Map<String, String> OUTBOX_SORTS =
            Map.of(
                    "createdAt",
                    "o.created_at",
                    "status",
                    "o.status",
                    "attemptCount",
                    "o.attempt_count");

    private final JdbcTemplate jdbc;

    AdminManagementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Overview overview() {
        return jdbc.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM platform.app_user) AS users,
                    (SELECT count(*) FROM platform.app_user WHERE status = 'ACTIVE') AS active_users,
                    (SELECT count(*) FROM platform.grant_request) AS grants,
                    (SELECT count(*) FROM platform.outbox_event WHERE status IN ('PENDING', 'FAILED')) AS outbox_attention,
                    (SELECT count(*) FROM platform.customer_order) AS orders,
                    (SELECT count(*) FROM platform.customer_order WHERE status = 'PAID') AS paid_orders,
                    (SELECT COALESCE(sum(amount), 0) FROM platform.customer_order WHERE status = 'PAID') AS paid_amount
                """,
                (result, row) ->
                        new Overview(
                                result.getLong("users"),
                                result.getLong("active_users"),
                                result.getLong("grants"),
                                result.getLong("outbox_attention"),
                                result.getLong("orders"),
                                result.getLong("paid_orders"),
                                result.getLong("paid_amount")));
    }

    /**
     * 管理端读取用户时的租户边界。
     *
     * <p><b>这是一个真实缺口，不是假想。</b>本仓库的管理端用户查询原先只有
     * {@code WHERE 1=1} 加状态/套餐/搜索条件，**没有任何 tenant 谓词**：
     * {@code platform.app_user.tenant_id} 存在、{@code user_role} 也带 tenant，
     * 但用户列表与详情只按 {@code user_id} 取数。今天恰好只有 1 个全局管理员
     * （{@code V33} 的唯一索引把 {@code global_admin} 钉死为一行），所以还没有
     * 第二个租户的管理员可以去读别人的数据；但"只有一个管理员"是运维现状，
     * 不是代码约束——任何一天给第二个管理员授 {@code ADMIN} 角色，跨租户读就成立了。
     *
     * <p>因此把边界写进查询：**全局管理员看全部，其余管理员只看自己租户**。
     * 全局管理员本来就要跨租户运营（发套餐、处理工单），给他加过滤会直接弄坏
     * 现有后台；非全局管理员则从来没有任何需要跨租户的场景。
     *
     * @param userId      发起请求的管理员
     * @param tenantId    该管理员所属租户；{@code null} 表示凭据里没有租户，
     *                    此时**非全局管理员看不到任何用户**（fail closed）
     * @param globalAdmin 该管理员是否为全局管理员
     */
    public record AdminActor(UUID userId, UUID tenantId, boolean globalAdmin) {

        public static AdminActor of(UUID userId, UUID tenantId) {
            return new AdminActor(userId, tenantId, false);
        }
    }

    /**
     * 管理端用户可见范围的租户谓词。
     *
     * <p>全局管理员不加谓词；其余一律收紧到自己的租户，且**租户缺失时收紧到
     * 空集**（{@code AND FALSE}）——"读不到凭据里的租户"绝不能被当成"有权看全部"。
     */
    private static String tenantPredicate(boolean actorGlobalAdmin, UUID actorTenantId) {
        if (actorGlobalAdmin) {
            return "";
        }
        return actorTenantId == null ? " AND FALSE" : " AND u.tenant_id = ?";
    }

    /**
     * 解析"这个 actor 能不能跨租户"。
     *
     * <p>{@code true} 只在调用方查过库并确认后才会传进来，所以它可以直接采信。
     * {@code false} 则**一律复核**：它既可能是"调用方不知道"（旧签名、测试、
     * 内部复用只给了 userId），也可能是"控制器查过了、确实不是全局管理员"。
     * 两者复核一次的代价是一次主键查询，而漏判的代价是全局管理员的后台
     * 整个空掉——这个方向的错误比多查一次贵得多。
     */
    private boolean resolveGlobalAdmin(AdminActor actor) {
        if (actor == null || actor.userId() == null) {
            return false;
        }
        if (actor.globalAdmin()) {
            return true;
        }
        return isGlobalAdministrator(actor.userId());
    }

    public PageResult<UserView> listUsers(int page, int size, String query, String status) {
        return listUsers(new AdminListQuery(page, size, query, status, "createdAt", "desc"));
    }

    public PageResult<UserView> listUsers(AdminListQuery request) {
        // 显式转型：null 同时匹配 UUID 与 AdminActor 两个重载。
        return listUsers(request, (AdminActor) null);
    }

    public PageResult<UserView> listUsers(AdminListQuery request, UUID actorUserId) {
        // 兼容旧的二元签名。它拿不到租户，因此只能按"无租户"评估：
        // 非全局管理员会被 fail-closed 挡成空结果。能拿到租户的调用方
        // （控制器从 JWT 的 tenant_id 取）请改用
        // {@link #listUsers(AdminListQuery, AdminActor)}。
        return listUsers(request, new AdminActor(actorUserId, null, false));
    }

    public PageResult<UserView> listUsers(AdminListQuery request, AdminActor actor) {
        PageRequest pagination = page(request.page(), request.size());
        UUID actorUserId = actor == null ? null : actor.userId();
        UUID actorTenantId = actor == null ? null : actor.tenantId();
        // 调用方只给了 userId（没给租户）时，全局标志可以在仓储内部查出来，
        // 因此这里**不要求**调用方预先知道它——否则每个调用点都要自己查一次，
        // 而漏查一次的表现是"整个后台突然看不到用户"，比原始缺口更难查。
        // 必须是 final：下面的行映射 lambda 要捕获它。
        final boolean actorGlobalAdmin = resolveGlobalAdmin(actor);
        String normalizedStatus =
                enumFilter(request.status(), USER_STATUSES, "invalid_user_status");
        String packageCode = packageCodeFilter(request.packageCode());
        String search = search(request.query());
        String order = order(request, USER_SORTS, "createdAt", "u.id");
        String where =
                " WHERE 1=1"
                        + tenantPredicate(actorGlobalAdmin, actorTenantId)
                        + (normalizedStatus == null ? "" : " AND u.status = ?")
                        + (packageCode == null
                                ? ""
                                : " AND (SELECT package_plan.code"
                                        + " FROM platform.grant_request package_grant"
                                        + " JOIN platform.package_plan_version package_version"
                                        + " ON package_version.id = package_grant.package_plan_version_id"
                                        + " JOIN platform.package_plan package_plan"
                                        + " ON package_plan.id = package_version.package_plan_id"
                                        + " WHERE package_grant.user_id = u.id"
                                        + " AND package_grant.status = 'ACTIVE'"
                                        + " ORDER BY package_grant.updated_at DESC, package_grant.id DESC"
                                        + " LIMIT 1) = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(u.email) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.display_name) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.user_no) LIKE ? ESCAPE '!')");
        List<Object> arguments = new ArrayList<>();
        // 租户谓词的参数必须**第一个**绑定：它在 where 串里排在最前
        // （紧随 "WHERE 1=1"）。顺序错位会让分页与筛选静默串位。
        if (!actorGlobalAdmin && actorTenantId != null) {
            arguments.add(actorTenantId);
        }
        if (normalizedStatus != null) arguments.add(normalizedStatus);
        if (packageCode != null) arguments.add(packageCode);
        if (search != null) {
            arguments.add(search);
            arguments.add(search);
            arguments.add(search);
        }
        long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform.app_user u" + where,
                        Long.class,
                        arguments.toArray());
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<UserView> items =
                jdbc.query(
                        """
                SELECT u.id, u.tenant_id, u.user_no, u.email, u.display_name, u.status, u.auth_version,
                       u.global_admin, u.avatar_updated_at, u.disabled_reason, u.disabled_at,
                       u.last_login_at, u.created_at,
                       (SELECT concat(COALESCE(event.payload->>'packageName', p.name), ' v', v.version)
                           FROM platform.grant_request g
                           JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                           JOIN platform.package_plan p ON p.id = v.package_plan_id
                           LEFT JOIN platform.outbox_event event
                             ON event.aggregate_id = g.id
                            AND event.aggregate_type = 'GRANT'
                            AND event.event_type = 'PACKAGE_GRANTED_V1'
                           WHERE g.user_id = u.id AND g.status = 'ACTIVE'
                           ORDER BY g.updated_at DESC, g.id DESC LIMIT 1) AS current_package,
                       (SELECT p.code
                           FROM platform.grant_request g
                           JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                           JOIN platform.package_plan p ON p.id = v.package_plan_id
                           WHERE g.user_id = u.id AND g.status = 'ACTIVE'
                           ORDER BY g.updated_at DESC, g.id DESC LIMIT 1) AS current_package_code,
                       (SELECT v.version
                           FROM platform.grant_request g
                           JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                           WHERE g.user_id = u.id AND g.status = 'ACTIVE'
                           ORDER BY g.updated_at DESC, g.id DESC LIMIT 1) AS current_package_version,
                       COALESCE((SELECT r.code FROM platform.user_role ur
                           JOIN platform.app_role r ON r.id = ur.role_id
                           WHERE ur.user_id = u.id
                           ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END LIMIT 1), 'USER') AS role
                FROM platform.app_user u
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) -> user(result, actorUserId, actorGlobalAdmin),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public UserDetailView userDetail(UUID userId, UUID actorUserId) {
        // 兼容旧的二元签名：同上，拿不到租户时非全局管理员会被 fail-closed。
        return userDetail(userId, new AdminActor(actorUserId, null, false));
    }

    public UserDetailView userDetail(UUID userId, AdminActor actor) {
        UUID actorUserId = actor == null ? null : actor.userId();
        UUID actorTenantId = actor == null ? null : actor.tenantId();
        // 同 listUsers：只给了 userId 时把全局标志查出来，
        // 否则旧的二元调用方（测试、内部复用）会被误判成受限管理员。
        boolean actorGlobalAdmin = resolveGlobalAdmin(actor);
        UserView user = findUser(userId, actorUserId, actorGlobalAdmin, actorTenantId);
        List<UserPackageGrantView> grants =
                jdbc.query(
                        """
                SELECT g.business_no,
                       COALESCE(event.payload->>'packageCode', p.code) AS package_code,
                       COALESCE(event.payload->>'packageName', p.name) AS package_name,
                       v.version, g.status, g.reason, g.created_at, g.updated_at
                FROM platform.grant_request g
                JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                LEFT JOIN platform.outbox_event event
                  ON event.aggregate_id = g.id
                 AND event.aggregate_type = 'GRANT'
                 AND event.event_type = 'PACKAGE_GRANTED_V1'
                JOIN platform.app_user owner ON owner.id = g.user_id
                WHERE g.user_id = ?
                """
                                // 与 findUser 同一口径：非全局管理员且租户不可解析时 AND FALSE，
                                // 而不是"不加谓词就放行"。
                                + (actorGlobalAdmin
                                        ? ""
                                        : (actorTenantId == null
                                                ? " AND FALSE"
                                                : " AND owner.tenant_id = ?"))
                                + " ORDER BY g.created_at DESC, g.id DESC",
                        (result, row) ->
                                new UserPackageGrantView(
                                        result.getString("business_no"),
                                                result.getString("package_code"),
                                        result.getString("package_name"), result.getInt("version"),
                                        result.getString("status"), result.getString("reason"),
                                        instant(result, "created_at"),
                                                instant(result, "updated_at")),
                        actorGlobalAdmin
                                ? new Object[] {userId}
                                : (actorTenantId == null
                                        ? new Object[] {userId}
                                        : new Object[] {userId, actorTenantId}));
        return new UserDetailView(user, grants);
    }

    public UserAvatarView userAvatar(UUID userId) {
        // 兼容旧的单参签名：拿不到租户时按"无租户"评估，非全局管理员 fail-closed。
        return userAvatar(userId, (AdminActor) null);
    }

    /**
     * 头像字节流，同样受租户边界约束。
     *
     * <p><b>为什么这个也要管。</b>它是 {@code /console/platform/admin/users/{id}/avatar}，
     * 与用户列表/详情同属管理端；在补上租户谓词之前，它只按 {@code id} 取字节，
     * 于是"列表看不见、但知道 id 就能拿到头像"——把边界修在列表上而漏掉它，
     * 等于留了一扇后门。头像本身不敏感，但它是**跨租户可读**的证明。
     */
    public UserAvatarView userAvatar(UUID userId, AdminActor actor) {
        boolean globalAdmin = resolveGlobalAdmin(actor);
        UUID tenantId = actor == null ? null : actor.tenantId();
        if (!globalAdmin && tenantId == null) {
            // 非全局管理员且租户不可解析 —— fail closed，与列表/详情同一口径。
            return null;
        }
        return jdbc
                .query(
                        """
                SELECT avatar_bytes, avatar_content_type
                FROM platform.app_user
                WHERE id = ? AND avatar_bytes IS NOT NULL
                """
                                + (globalAdmin ? "" : " AND tenant_id = ?"),
                        (result, row) ->
                                new UserAvatarView(
                                        result.getBytes("avatar_bytes"),
                                        result.getString("avatar_content_type")),
                        globalAdmin
                                ? new Object[] {userId}
                                : new Object[] {userId, tenantId})
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<UserIdentityView> resolveUserIdentities(List<UUID> uniqueIds) {
        String placeholders =
                String.join(",", java.util.Collections.nCopies(uniqueIds.size(), "?"));
        return jdbc.query(
                "SELECT id, user_no, display_name FROM platform.app_user WHERE id IN ("
                        + placeholders
                        + ") ORDER BY user_no",
                (result, row) ->
                        new UserIdentityView(
                                result.getObject("id", UUID.class),
                                result.getString("user_no"),
                                result.getString("display_name")),
                uniqueIds.toArray());
    }

    UserIdentity lockUserIdentity(UUID userId) {
        return jdbc
                .query(
                        """
                SELECT u.id, u.tenant_id, u.status, u.global_admin,
                       COALESCE((SELECT r.code
                           FROM platform.user_role ur
                           JOIN platform.app_role r ON r.id = ur.role_id
                           WHERE ur.user_id = u.id
                           ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END
                           LIMIT 1), 'USER') AS role
                FROM platform.app_user u WHERE u.id = ? FOR UPDATE OF u
                """,
                        (result, row) ->
                                new UserIdentity(
                                        result.getObject("id", UUID.class),
                                        result.getObject("tenant_id", UUID.class),
                                        result.getString("status"),
                                        result.getString("role"),
                                        result.getBoolean("global_admin")),
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found"));
    }

    void updateUser(
            UserIdentity identity,
            String status,
            String role,
            String disabledReason,
            UUID actorUserId) {
        boolean statusChanged = !status.equals(identity.status());
        boolean roleChanged = !role.equals(identity.role());
        jdbc.update(
                """
                UPDATE platform.app_user
                SET status = ?,
                    disabled_reason = CASE
                        WHEN ? = 'ACTIVE' THEN NULL
                        WHEN ? THEN ?
                        ELSE disabled_reason
                    END,
                    disabled_at = CASE
                        WHEN ? = 'ACTIVE' THEN NULL
                        WHEN ? THEN now()
                        ELSE disabled_at
                    END,
                    disabled_by = CASE
                        WHEN ? = 'ACTIVE' THEN NULL
                        WHEN ? THEN ?
                        ELSE disabled_by
                    END,
                    auth_version = auth_version + ?,
                    updated_at = now()
                WHERE id = ?
                """,
                status,
                status,
                statusChanged,
                disabledReason,
                status,
                statusChanged,
                status,
                statusChanged,
                actorUserId,
                roleChanged ? 1 : 0,
                identity.id());
        if (roleChanged) {
            jdbc.update(
                    "UPDATE platform.refresh_token SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL",
                    identity.id());
            jdbc.update("DELETE FROM platform.user_role WHERE user_id = ?", identity.id());
            jdbc.update(
                    """
                    INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                    SELECT ?, ?, id FROM platform.app_role WHERE code = ?
                    """,
                    identity.tenantId(),
                    identity.id(),
                    role);
        }
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'USER_UPDATED', 'USER', ?, 'SUCCESS',
                        jsonb_build_object(
                            'before', jsonb_build_object('status', ?, 'role', ?),
                            'after', jsonb_build_object(
                                'status', ?, 'role', ?, 'disabledReason', ?::text)))
                """,
                identity.tenantId(),
                actorUserId,
                identity.id().toString(),
                identity.status(),
                identity.role(),
                status,
                role,
                disabledReason);
    }

    public PageResult<GrantView> listGrants(int page, int size, String status) {
        return listGrants(new AdminListQuery(page, size, null, status, "createdAt", "desc"));
    }

    public PageResult<GrantView> listGrants(AdminListQuery request) {
        PageRequest pagination = page(request.page(), request.size());
        String normalized = enumFilter(request.status(), GRANT_STATUSES, "invalid_grant_status");
        String search = search(request.query());
        String where =
                " WHERE 1=1"
                        + (normalized == null ? "" : " AND g.status = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(g.business_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.email) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.user_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.display_name) LIKE ? ESCAPE '!'"
                                        + " OR lower(COALESCE(o.payload->>'packageCode', p.code)) LIKE ? ESCAPE '!'"
                                        + " OR lower(COALESCE(o.payload->>'packageName', p.name)) LIKE ? ESCAPE '!')");
        List<Object> filters = new ArrayList<>();
        if (normalized != null) filters.add(normalized);
        if (search != null) filters.addAll(List.of(search, search, search, search, search, search));
        String order = order(request, GRANT_SORTS, "createdAt", "g.id");
        long total =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.grant_request g
                JOIN platform.app_user u ON u.id = g.user_id
                JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                LEFT JOIN platform.outbox_event o
                  ON o.aggregate_id = g.id
                 AND o.aggregate_type = 'GRANT'
                 AND o.event_type = 'PACKAGE_GRANTED_V1'
                """
                                + where,
                        Long.class,
                        filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<GrantView> items =
                jdbc.query(
                        """
                SELECT g.id, g.business_no, g.user_id, u.user_no, u.email, u.display_name,
                       COALESCE(o.payload->>'packageCode', p.code) AS package_code,
                       COALESCE(o.payload->>'packageName', p.name) AS package_name,
                       v.version, g.status, g.reason,
                       o.id AS outbox_id, o.status AS outbox_status, o.attempt_count,
                       g.failure_reason, g.created_at, g.updated_at
                FROM platform.grant_request g
                JOIN platform.app_user u ON u.id = g.user_id
                JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                LEFT JOIN platform.outbox_event o
                  ON o.aggregate_id = g.id
                 AND o.aggregate_type = 'GRANT'
                 AND o.event_type = 'PACKAGE_GRANTED_V1'
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) ->
                                new GrantView(
                                        result.getObject("id", UUID.class),
                                        result.getString("business_no"),
                                        result.getObject("user_id", UUID.class),
                                        result.getString("user_no"),
                                        result.getString("email"),
                                        result.getString("display_name"),
                                        result.getString("package_code"),
                                        result.getString("package_name"),
                                        result.getInt("version"),
                                        result.getString("status"),
                                        result.getString("reason"),
                                        result.getObject("outbox_id", UUID.class),
                                        result.getString("outbox_status"),
                                        result.getInt("attempt_count"),
                                        result.getString("failure_reason"),
                                        instant(result, "created_at"),
                                        instant(result, "updated_at")),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public List<PlanVersionView> listPlans() {
        return listPlans(new AdminListQuery(0, 100, null, null, "code", "asc")).items();
    }

    public PageResult<PlanVersionView> listPlans(AdminListQuery request) {
        PageRequest pagination = page(request.page(), request.size());
        String normalized =
                enumFilter(request.status(), Set.of("DRAFT", "PUBLISHED"), "invalid_plan_status");
        String search = search(request.query());
        String where =
                " WHERE 1=1"
                        + (normalized == null ? "" : " AND v.status = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(p.code) LIKE ? ESCAPE '!'"
                                        + " OR lower(p.name) LIKE ? ESCAPE '!')");
        List<Object> filters = new ArrayList<>();
        if (normalized != null) filters.add(normalized);
        if (search != null) filters.addAll(List.of(search, search));
        String order = order(request, PLAN_SORTS, "code", "v.id");
        long total =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                """
                                + where,
                        Long.class,
                        filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<PlanVersionView> items =
                jdbc.query(
                        """
                SELECT v.id, p.code, p.name, p.description, p.status AS plan_status,
                       v.version, v.status, v.price_amount, v.currency, v.total_token_quota,
                       v.audio_seconds_quota,
                       v.duration_days, v.max_api_keys, v.requests_per_minute,
                       v.tokens_per_minute, v.max_concurrency, v.estimated_cost_amount,
                       v.estimated_margin_ratio, v.margin_warning,
                       v.stock_total, v.stock_sold, v.stock_reserved,
                       v.low_stock_threshold, v.max_purchases_per_user,
                       (SELECT count(*) FROM platform.package_plan_version_model m
                        WHERE m.package_plan_version_id = v.id) AS model_count,
                       v.published_at, v.created_at
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) ->
                                new PlanVersionView(
                                        result.getObject("id", UUID.class),
                                        result.getString("code"),
                                        result.getString("name"),
                                        result.getString("description"),
                                        result.getString("plan_status"),
                                        result.getInt("version"),
                                        result.getString("status"),
                                        result.getLong("price_amount"),
                                        result.getString("currency"),
                                        result.getLong("total_token_quota"),
                                        nullableLong(result, "audio_seconds_quota"),
                                        result.getInt("duration_days"),
                                        result.getInt("max_api_keys"),
                                        result.getInt("requests_per_minute"),
                                        result.getLong("tokens_per_minute"),
                                        result.getInt("max_concurrency"),
                                        nullableLong(result, "estimated_cost_amount"),
                                        result.getBigDecimal("estimated_margin_ratio"),
                                        result.getBoolean("margin_warning"),
                                        nullableLong(result, "stock_total"),
                                        result.getLong("stock_sold"),
                                        result.getLong("stock_reserved"),
                                        result.getLong("low_stock_threshold"),
                                        nullableInteger(result, "max_purchases_per_user"),
                                        remainingStock(result),
                                        soldOut(result),
                                        lowStock(result),
                                        result.getInt("model_count"),
                                        instant(result, "published_at"),
                                        instant(result, "created_at")),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public PageResult<OrderView> listOrders(int page, int size, String status) {
        return listOrders(new AdminListQuery(page, size, null, status, "createdAt", "desc"));
    }

    public PageResult<OrderView> listOrders(AdminListQuery request) {
        PageRequest pagination = page(request.page(), request.size());
        String normalized = enumFilter(request.status(), ORDER_STATUSES, "invalid_order_status");
        String search = search(request.query());
        String where =
                " WHERE 1=1"
                        + (normalized == null ? "" : " AND o.status = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(o.order_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.email) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.user_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(u.display_name) LIKE ? ESCAPE '!'"
                                        + " OR lower(COALESCE(o.package_snapshot->>'packageCode', p.code)) LIKE ? ESCAPE '!'"
                                        + " OR lower(COALESCE(o.package_snapshot->>'packageName', p.name)) LIKE ? ESCAPE '!')");
        List<Object> filters = new ArrayList<>();
        if (normalized != null) filters.add(normalized);
        if (search != null) filters.addAll(List.of(search, search, search, search, search, search));
        String order = order(request, ORDER_SORTS, "createdAt", "o.id");
        long total =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.customer_order o
                JOIN platform.app_user u ON u.id = o.user_id
                JOIN platform.package_plan_version v ON v.id = o.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                """
                                + where,
                        Long.class,
                        filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<OrderView> items =
                jdbc.query(
                        """
                SELECT o.id, o.order_no, o.user_id, u.user_no, u.email, u.display_name,
                       u.avatar_updated_at,
                       COALESCE(o.package_snapshot->>'packageCode', p.code) AS package_code,
                       COALESCE(o.package_snapshot->>'packageName', p.name) AS package_name,
                       v.version AS package_version,
                       o.amount, o.currency, o.status,
                       o.settlement_method, o.payment_provider,
                       (SELECT pt.status FROM platform.payment_transaction pt WHERE pt.order_id = o.id
                        ORDER BY pt.updated_at DESC LIMIT 1) AS payment_status,
                       (SELECT r.status FROM platform.refund r WHERE r.order_id = o.id
                        ORDER BY r.updated_at DESC LIMIT 1) AS refund_status,
                       COALESCE((SELECT sum(f.sign * f.total_tokens)
                           FROM platform.operating_fact f WHERE f.user_id = o.user_id), 0) AS total_tokens_used,
                       COALESCE((SELECT sum(f.sign * f.allocated_usage_revenue_amount)
                           FROM platform.operating_fact f WHERE f.user_id = o.user_id), 0) AS cumulative_spend_amount,
                       o.paid_at, o.created_at, o.updated_at
                FROM platform.customer_order o
                JOIN platform.app_user u ON u.id = o.user_id
                JOIN platform.package_plan_version v ON v.id = o.package_plan_version_id
                JOIN platform.package_plan p ON p.id = v.package_plan_id
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) ->
                                new OrderView(
                                        result.getObject("id", UUID.class),
                                        result.getString("order_no"),
                                        result.getObject("user_id", UUID.class),
                                        result.getString("user_no"),
                                        result.getString("email"),
                                        result.getString("display_name"),
                                        result.getString("package_code"),
                                        result.getString("package_name"),
                                        result.getInt("package_version"),
                                        result.getLong("amount"),
                                        result.getString("currency"),
                                        result.getString("status"),
                                        result.getString("settlement_method"),
                                        result.getString("payment_provider"),
                                        result.getString("payment_status"),
                                        result.getString("refund_status"),
                                        instant(result, "paid_at"),
                                        instant(result, "created_at"),
                                        instant(result, "updated_at"),
                                        instant(result, "avatar_updated_at"),
                                        result.getLong("total_tokens_used"),
                                        result.getBigDecimal("cumulative_spend_amount")
                                                .longValue()),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public List<PaymentConfigView> paymentConfigs() {
        return jdbc.query(
                """
                SELECT provider, environment, enabled,
                       credential_fingerprint IS NOT NULL AS configured,
                       credential_fingerprint, last_validation_status,
                       last_validated_at, updated_at
                FROM platform.payment_config ORDER BY provider, environment
                """,
                (result, row) ->
                        new PaymentConfigView(
                                result.getString("provider"),
                                result.getString("environment"),
                                result.getBoolean("enabled"),
                                result.getBoolean("configured"),
                                result.getString("credential_fingerprint"),
                                result.getString("last_validation_status"),
                                instant(result, "last_validated_at"),
                                instant(result, "updated_at")));
    }

    public PageResult<PaymentView> listPayments(int page, int size, String status) {
        return listPayments(new AdminListQuery(page, size, null, status, "createdAt", "desc"));
    }

    public PageResult<PaymentView> listPayments(AdminListQuery request) {
        PageRequest pagination = page(request.page(), request.size());
        String normalized =
                enumFilter(request.status(), PAYMENT_STATUSES, "invalid_payment_status");
        String search = search(request.query());
        String where =
                " WHERE 1=1"
                        + (normalized == null ? "" : " AND payment.status = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(payment.transaction_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(orders.order_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(users.email) LIKE ? ESCAPE '!'"
                                        + " OR lower(payment.external_trade_no) LIKE ? ESCAPE '!')");
        List<Object> filters = new ArrayList<>();
        if (normalized != null) filters.add(normalized);
        if (search != null) filters.addAll(List.of(search, search, search, search));
        String order = order(request, PAYMENT_SORTS, "createdAt", "payment.id");
        long total =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.payment_transaction payment
                JOIN platform.customer_order orders ON orders.id = payment.order_id
                JOIN platform.app_user users ON users.id = orders.user_id
                """
                                + where,
                        Long.class,
                        filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<PaymentView> items =
                jdbc.query(
                        """
                SELECT payment.id, payment.transaction_no, payment.order_id, orders.order_no,
                       orders.user_id, users.email, payment.provider, payment.external_trade_no,
                       payment.amount, payment.status, payment.paid_at,
                       payment.created_at, payment.updated_at
                FROM platform.payment_transaction payment
                JOIN platform.customer_order orders ON orders.id = payment.order_id
                JOIN platform.app_user users ON users.id = orders.user_id
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) ->
                                new PaymentView(
                                        result.getObject("id", UUID.class),
                                        result.getString("transaction_no"),
                                        result.getObject("order_id", UUID.class),
                                        result.getString("order_no"),
                                        result.getObject("user_id", UUID.class),
                                        result.getString("email"),
                                        result.getString("provider"),
                                        result.getString("external_trade_no"),
                                        result.getLong("amount"),
                                        result.getString("status"),
                                        instant(result, "paid_at"),
                                        instant(result, "created_at"),
                                        instant(result, "updated_at")),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public PageResult<RefundView> listRefunds(int page, int size, String status) {
        return listRefunds(new AdminListQuery(page, size, null, status, "createdAt", "desc"));
    }

    public PageResult<RefundView> listRefunds(AdminListQuery request) {
        PageRequest pagination = page(request.page(), request.size());
        String normalized = enumFilter(request.status(), REFUND_STATUSES, "invalid_refund_status");
        String search = search(request.query());
        String where =
                " WHERE 1=1"
                        + (normalized == null ? "" : " AND refund.status = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(refund.refund_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(orders.order_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(users.email) LIKE ? ESCAPE '!'"
                                        + " OR lower(users.user_no) LIKE ? ESCAPE '!'"
                                        + " OR lower(users.display_name) LIKE ? ESCAPE '!'"
                                        + " OR lower(refund.external_refund_no) LIKE ? ESCAPE '!')");
        List<Object> filters = new ArrayList<>();
        if (normalized != null) filters.add(normalized);
        if (search != null) filters.addAll(List.of(search, search, search, search, search, search));
        String order = order(request, REFUND_SORTS, "createdAt", "refund.id");
        long total =
                jdbc.queryForObject(
                        """
                SELECT count(*) FROM platform.refund refund
                JOIN platform.customer_order orders ON orders.id = refund.order_id
                JOIN platform.app_user users ON users.id = orders.user_id
                """
                                + where,
                        Long.class,
                        filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<RefundView> items =
                jdbc.query(
                        """
                SELECT refund.id, refund.refund_no, refund.order_id, orders.order_no,
                        orders.user_id, users.user_no, users.email, users.display_name,
                        refund.funding_source,
                       refund.payment_transaction_id,
                       refund.external_refund_no, refund.amount, refund.status, refund.reason,
                       refund.completed_at, refund.created_at, refund.updated_at
                FROM platform.refund refund
                JOIN platform.customer_order orders ON orders.id = refund.order_id
                JOIN platform.app_user users ON users.id = orders.user_id
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) ->
                                new RefundView(
                                        result.getObject("id", UUID.class),
                                        result.getString("refund_no"),
                                        result.getObject("order_id", UUID.class),
                                        result.getString("order_no"),
                                        result.getObject("user_id", UUID.class),
                                        result.getString("user_no"),
                                        result.getString("email"),
                                        result.getString("display_name"),
                                        result.getString("funding_source"),
                                        result.getObject("payment_transaction_id", UUID.class),
                                        result.getString("external_refund_no"),
                                        result.getLong("amount"),
                                        result.getString("status"),
                                        result.getString("reason"),
                                        instant(result, "completed_at"),
                                        instant(result, "created_at"),
                                        instant(result, "updated_at")),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    PaymentConfigState lockPaymentConfig(String provider, String environment) {
        return jdbc
                .query(
                        """
                        SELECT enabled, credential_fingerprint IS NOT NULL AS configured
                        FROM platform.payment_config
                        WHERE provider = ? AND environment = ?
                        FOR UPDATE
                        """,
                        (result, row) ->
                                new PaymentConfigState(
                                        result.getBoolean("enabled"),
                                        result.getBoolean("configured")),
                        provider,
                        environment)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "payment_config_not_found"));
    }

    void updatePaymentConfig(String provider, String environment, boolean enabled) {
        int updated =
                jdbc.update(
                        """
                UPDATE platform.payment_config
                SET enabled = ?, updated_at = now()
                WHERE provider = ? AND environment = ?
                """,
                        enabled,
                        provider,
                        environment);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_config_not_found");
        }
    }

    void auditPaymentConfigUpdate(
            String provider,
            String environment,
            boolean enabled,
            PaymentConfigState before,
            UUID actorUserId) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                SELECT tenant_id, ?, 'PAYMENT_CONFIG_UPDATED', 'PAYMENT_CONFIG', ?, 'SUCCESS',
                        jsonb_build_object(
                            'provider', ?, 'environment', ?,
                            'before', jsonb_build_object('enabled', ?, 'configured', ?),
                            'after', jsonb_build_object('enabled', ?, 'configured', ?))
                FROM platform.app_user WHERE id = ?
                """,
                actorUserId,
                provider + ":" + environment,
                provider,
                environment,
                before.enabled(),
                before.configured(),
                enabled,
                before.configured(),
                actorUserId);
    }

    PaymentConfigView paymentConfig(String provider, String environment) {
        return jdbc.queryForObject(
                """
                SELECT provider, environment, enabled,
                       credential_fingerprint IS NOT NULL AS configured,
                       credential_fingerprint, last_validation_status,
                       last_validated_at, updated_at
                FROM platform.payment_config WHERE provider = ? AND environment = ?
                """,
                (result, row) ->
                        new PaymentConfigView(
                                result.getString("provider"),
                                result.getString("environment"),
                                result.getBoolean("enabled"),
                                result.getBoolean("configured"),
                                result.getString("credential_fingerprint"),
                                result.getString("last_validation_status"),
                                instant(result, "last_validated_at"),
                                instant(result, "updated_at")),
                provider,
                environment);
    }

    public PageResult<OutboxView> listOutbox(int page, int size, String status) {
        return listOutbox(new AdminListQuery(page, size, null, status, "createdAt", "desc"));
    }

    public PageResult<OutboxView> listOutbox(AdminListQuery request) {
        PageRequest pagination = page(request.page(), request.size());
        String normalized = enumFilter(request.status(), OUTBOX_STATUSES, "invalid_outbox_status");
        String search = search(request.query());
        String where =
                " WHERE 1=1"
                        + (normalized == null ? "" : " AND o.status = ?")
                        + (search == null
                                ? ""
                                : " AND (lower(o.business_key) LIKE ? ESCAPE '!'"
                                        + " OR lower(o.event_type) LIKE ? ESCAPE '!'"
                                        + " OR lower(o.aggregate_type) LIKE ? ESCAPE '!')");
        List<Object> filters = new ArrayList<>();
        if (normalized != null) filters.add(normalized);
        if (search != null) filters.addAll(List.of(search, search, search));
        String order = order(request, OUTBOX_SORTS, "createdAt", "o.id");
        long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event o" + where,
                        Long.class,
                        filters.toArray());
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(pagination.size());
        arguments.add(pagination.offset());
        List<OutboxView> items =
                jdbc.query(
                        """
                SELECT o.id, o.aggregate_type, o.aggregate_id, o.event_type, o.business_key,
                       o.status, o.attempt_count, o.next_attempt_at, o.delivered_at,
                       o.last_error, o.created_at
                FROM platform.outbox_event o
                """
                                + where
                                + order
                                + " LIMIT ? OFFSET ?",
                        (result, row) -> outbox(result),
                        arguments.toArray());
        return new PageResult<>(items, total, pagination.page(), pagination.size());
    }

    public OutboxHealth outboxHealth() {
        return jdbc.queryForObject(
                """
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'DELIVERING') AS delivering,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       COALESCE(max(EXTRACT(EPOCH FROM (now() - created_at)))
                           FILTER (WHERE status IN ('PENDING', 'FAILED')), 0) AS oldest_seconds
                FROM platform.outbox_event
                """,
                (result, row) ->
                        new OutboxHealth(
                                result.getLong("pending"),
                                result.getLong("delivering"),
                                result.getLong("failed"),
                                result.getLong("oldest_seconds")));
    }

    int reserveOutboxRetry(String businessNo, UUID eventId, UUID actorUserId) {
        return jdbc.update(
                """
                INSERT INTO platform.outbox_retry_request(business_no, event_id, actor_user_id)
                VALUES (?, ?, ?)
                ON CONFLICT (business_no) DO NOTHING
                """,
                businessNo,
                eventId,
                actorUserId);
    }

    RetryTarget lockRetryTarget(UUID eventId) {
        return jdbc
                .query(
                        """
                SELECT outbox.id, outbox.aggregate_id, outbox.event_type,
                       COALESCE(grant_request.tenant_id, customer_order.tenant_id) AS tenant_id
                FROM platform.outbox_event AS outbox
                LEFT JOIN platform.grant_request AS grant_request
                  ON outbox.event_type = 'PACKAGE_GRANTED_V1'
                 AND grant_request.id = outbox.aggregate_id
                LEFT JOIN platform.refund AS refund
                  ON outbox.event_type = 'PACKAGE_REFUNDED_V1'
                 AND refund.id = outbox.aggregate_id
                LEFT JOIN platform.customer_order AS customer_order
                  ON customer_order.id = refund.order_id
                WHERE outbox.id = ? AND outbox.status = 'FAILED'
                  AND outbox.event_type IN ('PACKAGE_GRANTED_V1', 'PACKAGE_REFUNDED_V1')
                  AND COALESCE(grant_request.tenant_id, customer_order.tenant_id) IS NOT NULL
                FOR UPDATE OF outbox
                """,
                        (result, row) ->
                                new RetryTarget(
                                        result.getObject("id", UUID.class),
                                                result.getObject("aggregate_id", UUID.class),
                                        result.getString("event_type"),
                                                result.getObject("tenant_id", UUID.class)),
                        eventId)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT, "outbox_event_not_retryable"));
    }

    void resetOutboxRetry(RetryTarget target, String businessNo, UUID actorUserId) {
        jdbc.update(
                """
                UPDATE platform.outbox_event
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = now(),
                    delivered_at = NULL, last_error = NULL
                WHERE id = ? AND status = 'FAILED'
                """,
                target.eventId());
        if ("PACKAGE_GRANTED_V1".equals(target.eventType())) {
            jdbc.update(
                    """
                    UPDATE platform.grant_request
                    SET status = 'QUEUED', completed_at = NULL,
                        failure_reason = NULL, updated_at = now()
                    WHERE id = ? AND status = 'FAILED'
                    """,
                    target.aggregateId());
        }
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'OUTBOX_RETRIED', 'OUTBOX', ?, 'SUCCESS',
                        jsonb_build_object('businessNo', ?))
                """,
                target.tenantId(),
                actorUserId,
                target.eventId().toString(),
                businessNo);
    }

    void storeOutboxRetrySnapshot(UUID eventId, String businessNo) {
        jdbc.update(
                """
                UPDATE platform.outbox_retry_request retry
                SET response = snapshot.value
                FROM (
                    SELECT jsonb_build_object(
                        'id', o.id,
                        'aggregateType', o.aggregate_type,
                        'aggregateId', o.aggregate_id,
                        'eventType', o.event_type,
                        'businessKey', o.business_key,
                        'status', o.status,
                        'attemptCount', o.attempt_count,
                        'nextAttemptAt', o.next_attempt_at,
                        'deliveredAt', o.delivered_at,
                        'lastError', left(o.last_error, 500),
                        'createdAt', o.created_at) AS value
                    FROM platform.outbox_event o
                    WHERE o.id = ?
                ) snapshot
                WHERE retry.business_no = ? AND retry.event_id = ? AND retry.response IS NULL
                """,
                eventId,
                businessNo,
                eventId);
    }

    OutboxView replayOutboxRetry(String businessNo, UUID eventId) {
        UUID originalEventId =
                jdbc.queryForObject(
                        """
                SELECT event_id FROM platform.outbox_retry_request WHERE business_no = ?
                """,
                        UUID.class,
                        businessNo);
        if (!eventId.equals(originalEventId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "outbox_retry_business_no_conflict");
        }
        return jdbc
                .query(
                        """
                SELECT (response->>'id')::uuid AS id,
                       response->>'aggregateType' AS aggregate_type,
                       (response->>'aggregateId')::uuid AS aggregate_id,
                       response->>'eventType' AS event_type,
                       response->>'businessKey' AS business_key,
                       response->>'status' AS status,
                       (response->>'attemptCount')::integer AS attempt_count,
                       (response->>'nextAttemptAt')::timestamptz AS next_attempt_at,
                       (response->>'deliveredAt')::timestamptz AS delivered_at,
                       response->>'lastError' AS last_error,
                       (response->>'createdAt')::timestamptz AS created_at
                FROM platform.outbox_retry_request
                WHERE business_no = ? AND response IS NOT NULL
                """,
                        (result, row) -> outbox(result),
                        businessNo)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT, "outbox_retry_result_unavailable"));
    }

    UserView findUser(UUID id, UUID actorUserId, boolean actorGlobalAdmin) {
        // 三元签名拿不到租户 —— 非全局管理员会被下面的 AND FALSE 挡住（fail closed）。
        return findUser(id, actorUserId, actorGlobalAdmin, null);
    }

    /** 带租户的事实回读：`null` 表示"不知道租户"，非全局管理员即 fail-closed。 */
    UserView findUser(
            UUID id, UUID actorUserId, boolean actorGlobalAdmin, UUID actorTenantId) {
        return jdbc
                .query(
                        """
                SELECT u.id, u.tenant_id, u.user_no, u.email, u.display_name, u.status, u.auth_version,
                       u.global_admin, u.avatar_updated_at, u.disabled_reason, u.disabled_at,
                       u.last_login_at, u.created_at,
                       (SELECT concat(COALESCE(event.payload->>'packageName', p.name), ' v', v.version)
                           FROM platform.grant_request g
                           JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                           JOIN platform.package_plan p ON p.id = v.package_plan_id
                           LEFT JOIN platform.outbox_event event
                             ON event.aggregate_id = g.id
                            AND event.aggregate_type = 'GRANT'
                            AND event.event_type = 'PACKAGE_GRANTED_V1'
                           WHERE g.user_id = u.id AND g.status = 'ACTIVE'
                           ORDER BY g.updated_at DESC, g.id DESC LIMIT 1) AS current_package,
                       (SELECT p.code
                           FROM platform.grant_request g
                           JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                           JOIN platform.package_plan p ON p.id = v.package_plan_id
                           WHERE g.user_id = u.id AND g.status = 'ACTIVE'
                           ORDER BY g.updated_at DESC, g.id DESC LIMIT 1) AS current_package_code,
                       (SELECT v.version
                           FROM platform.grant_request g
                           JOIN platform.package_plan_version v ON v.id = g.package_plan_version_id
                           WHERE g.user_id = u.id AND g.status = 'ACTIVE'
                           ORDER BY g.updated_at DESC, g.id DESC LIMIT 1) AS current_package_version,
                       COALESCE((SELECT r.code FROM platform.user_role ur
                           JOIN platform.app_role r ON r.id = ur.role_id
                           WHERE ur.user_id = u.id
                           ORDER BY CASE r.code WHEN 'ADMIN' THEN 0 ELSE 1 END LIMIT 1), 'USER') AS role
                FROM platform.app_user u WHERE u.id = ?
                """
                                // **fail closed，与列表查询同一口径。**
                                // 这里原先写的是 `actorTenantId == null ? "" : ...`，
                                // 也就是"拿不到租户 ⇒ 不加谓词 ⇒ 放行"——正好与
                                // tenantPredicate 的 AND FALSE 相反，也与本方法
                                // 二元重载上的注释（"非全局管理员会被 fail-closed"）
                                // 以及控制器的注释相反。非全局管理员且租户不可解析时，
                                // 它会把任意租户的用户详情交出去。
                                + (actorGlobalAdmin
                                        ? ""
                                        : (actorTenantId == null ? " AND FALSE" : " AND u.tenant_id = ?"))
                                + "",
                        (result, row) -> user(result, actorUserId, actorGlobalAdmin),
                        actorGlobalAdmin
                                ? new Object[] {id}
                                : (actorTenantId == null
                                        ? new Object[] {id}
                                        : new Object[] {id, actorTenantId}))
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found"));
    }

    OutboxView findOutbox(UUID id) {
        return jdbc
                .query(
                        """
                SELECT id, aggregate_type, aggregate_id, event_type, business_key, status,
                       attempt_count, next_attempt_at, delivered_at, left(last_error, 500) AS last_error, created_at
                FROM platform.outbox_event WHERE id = ?
                """,
                        (result, row) -> outbox(result),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "outbox_event_not_found"));
    }

    private UserView user(ResultSet result, UUID actorUserId, boolean actorGlobalAdmin)
            throws SQLException {
        UUID id = result.getObject("id", UUID.class);
        boolean globalAdmin = result.getBoolean("global_admin");
        String role = result.getString("role");
        AdminUserAuthorizationPolicy.Permissions permissions =
                AdminUserAuthorizationPolicy.permissions(
                        actorUserId, actorGlobalAdmin, id, globalAdmin, role);
        return new UserView(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getString("user_no"),
                result.getString("email"),
                result.getString("display_name"),
                result.getString("status"),
                role,
                globalAdmin,
                permissions.statusManageable(),
                permissions.roleManageable(),
                result.getLong("auth_version"),
                instant(result, "last_login_at"),
                instant(result, "created_at"),
                instant(result, "avatar_updated_at"),
                result.getString("disabled_reason"),
                instant(result, "disabled_at"),
                result.getString("current_package"),
                result.getString("current_package_code"),
                result.getObject("current_package_version", Integer.class));
    }

    boolean isGlobalAdministrator(UUID userId) {
        return jdbc
                .query(
                        "SELECT global_admin FROM platform.app_user WHERE id = ?",
                        (result, row) -> result.getBoolean("global_admin"),
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_not_found"));
    }

    private OutboxView outbox(ResultSet result) throws SQLException {
        return new OutboxView(
                result.getObject("id", UUID.class),
                result.getString("aggregate_type"),
                result.getObject("aggregate_id", UUID.class),
                result.getString("event_type"),
                result.getString("business_key"),
                result.getString("status"),
                result.getInt("attempt_count"),
                instant(result, "next_attempt_at"),
                instant(result, "delivered_at"),
                result.getString("last_error"),
                instant(result, "created_at"));
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("invalid_pagination");
        try {
            return new PageRequest(page, size, Math.multiplyExact(page, size));
        } catch (ArithmeticException error) {
            throw invalid("invalid_pagination");
        }
    }

    private String enumFilter(String value, Set<String> allowed, String error) {
        return blank(value) ? null : enumValue(value, allowed, error);
    }

    private String packageCodeFilter(String value) {
        if (blank(value)) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}")) {
            throw invalid("invalid_package_code");
        }
        // 格式合法 ≠ 这个套餐码存在。
        //
        // 归一化后的值会直接进 SQL 做等值比较，因此一个拼错的套餐码（
        // 例如把 STARTER 拼成 UNKNOWN）只会安静地匹配不到任何用户，
        // 返回一页空结果——调用方分不清「没有这样的用户」和「套餐码写错了」，
        // 而这两件事需要完全不同的动作。与 enumFilter 对状态/角色做的事一致：
        // 不在允许集合里的取值一律拒绝，而不是当成一个永不匹配的过滤器。
        Integer known = jdbc.queryForObject(
                "SELECT count(*) FROM platform.package_plan WHERE code = ?",
                Integer.class, normalized);
        if (known == null || known == 0) {
            throw invalid("invalid_package_code");
        }
        return normalized;
    }

    private String search(String value) {
        if (blank(value)) return null;
        String escaped =
                value.strip()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private String order(
            AdminListQuery request,
            Map<String, String> allowed,
            String defaultSort,
            String tieBreaker) {
        String sort = blank(request.sort()) ? defaultSort : request.sort().strip();
        String column = allowed.get(sort);
        if (column == null) throw invalid("invalid_sort");
        String direction =
                blank(request.direction())
                        ? "DESC"
                        : request.direction().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw invalid("invalid_sort_direction");
        return " ORDER BY " + column + " " + direction + ", " + tieBreaker + " " + direction;
    }

    private String enumValue(String value, Set<String> allowed, String error) {
        if (blank(value)) throw invalid(error);
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid(error);
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long remainingStock(ResultSet result) throws SQLException {
        Long total = nullableLong(result, "stock_total");
        return total == null
                ? null
                : total - result.getLong("stock_sold") - result.getLong("stock_reserved");
    }

    private static boolean soldOut(ResultSet result) throws SQLException {
        Long remaining = remainingStock(result);
        return remaining != null && remaining == 0;
    }

    private static boolean lowStock(ResultSet result) throws SQLException {
        Long remaining = remainingStock(result);
        return remaining != null
                && remaining > 0
                && remaining <= result.getLong("low_stock_threshold");
    }

    private record PageRequest(int page, int size, int offset) {}

    record UserIdentity(UUID id, UUID tenantId, String status, String role, boolean globalAdmin) {}

    record PaymentConfigState(boolean enabled, boolean configured) {}

    record RetryTarget(UUID eventId, UUID aggregateId, String eventType, UUID tenantId) {}
}
