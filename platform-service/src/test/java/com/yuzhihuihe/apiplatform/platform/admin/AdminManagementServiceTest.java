package com.yuzhihuihe.apiplatform.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.RetryOutboxRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.UpdatePaymentConfigRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementRequests.UpdateUserRequest;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.GrantView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.OrderView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.OutboxView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PageResult;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PaymentConfigView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.PaymentView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.RefundView;
import com.yuzhihuihe.apiplatform.platform.admin.api.AdminManagementResponses.UserView;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AdminManagementServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID ORDINARY_ADMIN_ID =
            UUID.fromString("71000000-0000-4000-8000-000000000003");
    private static final UUID GRANT_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000102");

    /**
     * 普通用户与普通管理员共用的租户，**与全局管理员的租户不同**。
     *
     * <p>这样夹具同时成立三件事：① 全局管理员（自己独占一个租户）看得见全部三个；
     * ② 普通管理员看得见同租户的用户与自己（行级权限矩阵的断言依赖这一点）；
     * ③ 普通管理员看不见另一个租户的全局管理员——跨租户读被代码拦住。
     *
     * <p>这个常量是必需的而非可选：`platform.customer_order` 上有复合外键
     * `fk_order_user_scope (tenant_id, user_id) -> app_user`，说明"一个用户属于
     * 恰好一个租户"是**数据库层的不变量**。因此夹具里的订单、发放、令牌
     * 都必须用与 app_user 一致的租户，否则外键直接拒绝。
     */
    private static final UUID SHARED_TENANT_ID =
            UUID.fromString("71000000-0000-4000-8000-0000000000ff");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private AdminManagementService service;
    private PaymentAvailabilityService paymentAvailabilityService;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        paymentAvailabilityService = mock(PaymentAvailabilityService.class);
        service = new AdminManagementService(
                new AdminManagementRepository(jdbc), paymentAvailabilityService);
        jdbc.update("TRUNCATE platform.admin_audit_log, platform.outbox_event, "
                + "platform.grant_request, platform.refresh_token, platform.user_role, "
                + "platform.invitation, platform.app_user RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status, auth_version, global_admin)
                VALUES (?, ?, 'admin@example.com', 'hash', 'Admin', 'ACTIVE', 0, true),
                       (?, ?, 'user@example.com', 'hash', 'User', 'ACTIVE', 3, false),
                       (?, ?, 'ordinary-admin@example.com', 'hash', 'Ordinary Admin', 'ACTIVE', 0, false)
                """, ADMIN_ID, ADMIN_ID,
                USER_ID, SHARED_TENANT_ID,
                ORDINARY_ADMIN_ID, SHARED_TENANT_ID);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'ADMIN'
                """, ADMIN_ID, ADMIN_ID);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'ADMIN'
                """, SHARED_TENANT_ID, ORDINARY_ADMIN_ID);
        jdbc.update("""
                INSERT INTO platform.user_role(tenant_id, user_id, role_id)
                SELECT ?, ?, id FROM platform.app_role WHERE code = 'USER'
                """, SHARED_TENANT_ID, USER_ID);
        jdbc.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (1, 500, 100, 7, 1000, now(), true)
                """);
    }

    /**
     * 全局管理员 actor。
     *
     * <p>actor-less 的 {@code listUsers(request)} 重载现在按"无租户"评估并
     * fail-closed，所以任何"要看见全部租户"的断言都必须显式以全局管理员身份调用
     * ——这同时让用例的意图（谁在看）变得可见。
     */
    private AdminManagementRepository.AdminActor globalAdminActor() {
        return service.resolveAdminActor(ADMIN_ID, ADMIN_ID.toString());
    }

    @Test
    void scopesAdminUserReadsToTheActorsTenantUnlessGlobal() {
        // 夹具布局：全局管理员独占一个租户；普通管理员与普通用户共用一个租户。
        // 这条用例把"跨租户读"从"靠系统里恰好只有一个管理员"变成"代码拦得住"。
        AdminListQuery all = new AdminListQuery(0, 20, null, null, "email", "asc");

        // 全局管理员：三个租户的人都看得到
        var global = globalAdminActor();
        assertThat(global.globalAdmin()).isTrue();
        assertThat(service.listUsers(all, global).items())
                .hasSize(3);

        // 普通管理员：只看得见自己租户的两个人（用户 + 他自己），
        // **看不见**另一个租户的全局管理员
        var scoped = service.resolveAdminActor(
                ORDINARY_ADMIN_ID, SHARED_TENANT_ID.toString());
        assertThat(scoped.globalAdmin()).isFalse();
        assertThat(service.listUsers(all, scoped).items())
                .extracting(UserView::id)
                .containsExactlyInAnyOrder(USER_ID, ORDINARY_ADMIN_ID)
                .doesNotContain(ADMIN_ID);

        // 普通管理员按 id 直取另一个租户的用户 => 404，而不是把数据交出去
        assertThatThrownBy(() -> service.userDetail(ADMIN_ID, scoped))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("user_not_found");

        // 同租户的仍然取得到
        assertThat(service.userDetail(USER_ID, scoped).user().id()).isEqualTo(USER_ID);

        // 全局管理员按 id 直取跨租户用户仍然可用
        assertThat(service.userDetail(USER_ID, global).user().id()).isEqualTo(USER_ID);
    }

    @Test
    void failsClosedWhenTheActorHasNoResolvableTenant() {
        // 凭据里没有 tenant_id（老 token / 声明不可解析）时，
        // 非全局管理员必须看不到任何用户 —— "读不到租户"绝不等于"有权看全部"。
        var noTenant = service.resolveAdminActor(ORDINARY_ADMIN_ID, null);
        assertThat(noTenant.tenantId()).isNull();
        assertThat(service.listUsers(
                        new AdminListQuery(0, 20, null, null, "email", "asc"), noTenant)
                .items())
                .isEmpty();

        // 声明不可解析（不是合法 UUID）同样 fail closed，而不是抛 500 或放行
        var malformed = service.resolveAdminActor(ORDINARY_ADMIN_ID, "not-a-uuid");
        assertThat(malformed.tenantId()).isNull();
        assertThat(service.listUsers(
                        new AdminListQuery(0, 20, null, null, "email", "asc"), malformed)
                .items())
                .isEmpty();

        // 全局管理员即使没有租户声明也照常工作（他本来就不受租户约束）
        var globalNoTenant = service.resolveAdminActor(ADMIN_ID, null);
        assertThat(service.listUsers(
                        new AdminListQuery(0, 20, null, null, "email", "asc"), globalNoTenant)
                .items())
                .hasSize(3);

        // **userDetail 必须与列表同一口径。**
        // findUser 原先写的是 `actorTenantId == null ? "" : ...`，即"拿不到租户 ⇒
        // 不加谓词 ⇒ 放行"——与 tenantPredicate 的 AND FALSE 正好相反，会把任意租户的
        // 用户详情交出去。这两条断言就是钉住它（列表、头像早已有同类断言，详情此前缺失）。
        assertThatThrownBy(() -> service.userDetail(USER_ID, noTenant))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("user_not_found");
        assertThatThrownBy(() -> service.userDetail(USER_ID, malformed))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("user_not_found");
        // 全局管理员无租户声明时仍然可用
        assertThat(service.userDetail(USER_ID, globalNoTenant).user().id()).isEqualTo(USER_ID);
    }

    @Test
    void scopesAvatarReadsToTheActorsTenantUnlessGlobal() {
        // 头像必须与列表/详情同一口径：只修列表会留下"知道 id 就能拿到头像"的后门。
        jdbc.update("""
                UPDATE platform.app_user
                SET avatar_bytes = ?, avatar_content_type = 'image/png', avatar_updated_at = now()
                WHERE id = ?
                """, new byte[] {1, 2, 3, 4}, USER_ID);

        var global = globalAdminActor();
        var scoped = service.resolveAdminActor(
                ORDINARY_ADMIN_ID, SHARED_TENANT_ID.toString());

        // 同租户 + 全局管理员：都拿得到
        assertThat(service.userAvatar(USER_ID, scoped)).isNotNull();
        assertThat(service.userAvatar(USER_ID, global)).isNotNull();

        // 普通管理员取另一个租户的全局管理员头像 => 取不到
        assertThat(service.userAvatar(ADMIN_ID, scoped)).isNull();

        // 非全局管理员且租户不可解析 => fail closed
        var noTenant = service.resolveAdminActor(ORDINARY_ADMIN_ID, null);
        assertThat(service.userAvatar(USER_ID, noTenant)).isNull();
    }

    @Test
    void disablesUserPreservesSessionAndWritesVisibleReason() {
        jdbc.update("""
                INSERT INTO platform.refresh_token(
                    id, tenant_id, user_id, jti, token_hash, expires_at, csrf_token_hash)
                VALUES (?, ?, ?, 'jti-admin-user', 'hash-admin-user',
                        now() + interval '1 day', repeat('a', 64))
                """, UUID.randomUUID(), SHARED_TENANT_ID, USER_ID);

        UserView user = service.updateUser(
                USER_ID, new UpdateUserRequest("DISABLED", "USER", "账号存在异常登录"), ADMIN_ID);

        assertThat(user.status()).isEqualTo("DISABLED");
        assertThat(user.disabledReason()).isEqualTo("账号存在异常登录");
        assertThat(user.disabledAt()).isNotNull();
        assertThat(jdbc.queryForMap("SELECT auth_version FROM platform.app_user WHERE id = ?", USER_ID))
                .containsEntry("auth_version", 3L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.refresh_token WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class, USER_ID)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT disabled_reason, disabled_at IS NOT NULL AS disabled,
                       disabled_by
                FROM platform.app_user WHERE id = ?
                """, USER_ID))
                .containsEntry("disabled_reason", "账号存在异常登录")
                .containsEntry("disabled", true)
                .containsEntry("disabled_by", ADMIN_ID);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'USER_UPDATED' AND resource_id = ?
                  AND detail #>> '{before,status}' = 'ACTIVE'
                  AND detail #>> '{before,role}' = 'USER'
                  AND detail #>> '{after,status}' = 'DISABLED'
                  AND detail #>> '{after,role}' = 'USER'
                  AND detail #>> '{after,disabledReason}' = '账号存在异常登录'
                """, Integer.class, ADMIN_ID, USER_ID.toString())).isEqualTo(1);
    }

    @Test
    void disablingRequiresReasonAndRoleChangeStillRevokesSessions() {
        assertThatThrownBy(() -> service.updateUser(
                USER_ID, new UpdateUserRequest("DISABLED", "USER", "  "), ADMIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled_reason_required");

        jdbc.update("""
                INSERT INTO platform.refresh_token(
                    id, tenant_id, user_id, jti, token_hash, expires_at, csrf_token_hash)
                VALUES (?, ?, ?, 'jti-role-change', 'hash-role-change',
                        now() + interval '1 day', repeat('b', 64))
                """, UUID.randomUUID(), SHARED_TENANT_ID, USER_ID);
        service.updateUser(
                USER_ID, new UpdateUserRequest("ACTIVE", "ADMIN", null), ADMIN_ID);

        assertThat(jdbc.queryForObject(
                "SELECT auth_version FROM platform.app_user WHERE id = ?", Long.class, USER_ID))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.refresh_token WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class, USER_ID)).isOne();
    }

    @Test
    void globalAdministratorPromotesAndDemotesUsers() {
        UserView promoted = service.updateUser(
                USER_ID, new UpdateUserRequest("ACTIVE", "ADMIN", null), ADMIN_ID);
        UserView demoted = service.updateUser(
                USER_ID, new UpdateUserRequest("ACTIVE", "USER", null), ADMIN_ID);

        assertThat(promoted.role()).isEqualTo("ADMIN");
        assertThat(demoted.role()).isEqualTo("USER");
        assertThat(demoted.globalAdmin()).isFalse();
    }

    @Test
    void ordinaryAdministratorCannotPromoteOrDemoteUsers() {
        assertThatThrownBy(() -> service.updateUser(
                USER_ID, new UpdateUserRequest("ACTIVE", "ADMIN", null),
                ORDINARY_ADMIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("global_admin_required");

        service.updateUser(
                USER_ID, new UpdateUserRequest("ACTIVE", "ADMIN", null), ADMIN_ID);

        assertThatThrownBy(() -> service.updateUser(
                USER_ID, new UpdateUserRequest("ACTIVE", "USER", null),
                ORDINARY_ADMIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("global_admin_required");
    }

    @Test
    void noAdministratorCanDisableOrDemoteGlobalAdministrator() {
        assertThatThrownBy(() -> service.updateUser(
                ADMIN_ID, new UpdateUserRequest("DISABLED", "ADMIN", "测试封禁"),
                ORDINARY_ADMIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("global_admin_immutable");
        assertThatThrownBy(() -> service.updateUser(
                ADMIN_ID, new UpdateUserRequest("ACTIVE", "USER", null), ADMIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("global_admin_immutable");
    }

    @Test
    void databaseProtectsGlobalAdministratorIdentityStatusAndRole() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE platform.app_user SET global_admin = false WHERE id = ?", ADMIN_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE platform.app_user SET status = 'DISABLED' WHERE id = ?", ADMIN_ID))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM platform.user_role
                WHERE user_id = ?
                  AND role_id = (SELECT id FROM platform.app_role WHERE code = 'ADMIN')
                """, ADMIN_ID))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ordinaryAdministratorCanDisableOrdinaryUsers() {
        UserView updated = service.updateUser(
                USER_ID, new UpdateUserRequest("DISABLED", "USER", "管理员安全审核"),
                ORDINARY_ADMIN_ID);

        assertThat(updated.status()).isEqualTo("DISABLED");
        assertThat(updated.role()).isEqualTo("USER");
    }

    @Test
    void computesRowManagementPermissionsForEachAdministratorLevel() {
        AdminListQuery query =
                new AdminListQuery(0, 20, null, null, "email", "asc");

        // 两个视角都要**带租户**构造：只给 userId 的旧签名现在按"无租户"评估并
        // fail-closed，用它断言行级权限会把结果看成空集。
        PageResult<UserView> globalView =
                service.listUsers(query, globalAdminActor());
        PageResult<UserView> ordinaryView = service.listUsers(query,
                service.resolveAdminActor(ORDINARY_ADMIN_ID, SHARED_TENANT_ID.toString()));

        assertThat(globalView.items()).filteredOn(UserView::globalAdmin)
                .singleElement().satisfies(user -> {
                    assertThat(user.statusManageable()).isFalse();
                    assertThat(user.roleManageable()).isFalse();
                });
        assertThat(globalView.items()).filteredOn(user -> user.id().equals(USER_ID))
                .singleElement().satisfies(user -> {
                    assertThat(user.statusManageable()).isTrue();
                    assertThat(user.roleManageable()).isTrue();
                });
        assertThat(ordinaryView.items()).filteredOn(user -> user.id().equals(USER_ID))
                .singleElement().satisfies(user -> {
                    assertThat(user.statusManageable()).isTrue();
                    assertThat(user.roleManageable()).isFalse();
                });
        // 另一个租户的全局管理员**根本不在普通管理员的可见集里**：
        // 原先这里断言"看得见但两列都不可管理"，那是租户过滤缺失时的行为。
        // 现在跨租户读被拦掉了，正确断言是"看不见"。
        assertThat(ordinaryView.items())
                .extracting(UserView::id)
                .containsExactlyInAnyOrder(USER_ID, ORDINARY_ADMIN_ID)
                .doesNotContain(ADMIN_ID);
    }

    @Test
    void paginatesFiltersAndAllowlistSortsAdminUsers() {
        // 显式以全局管理员身份调用：actor-less 重载现在按"无租户"评估并
        // fail-closed（见 failsClosedWhenTheActorHasNoResolvableTenant），
        // 用它来断言"能看见全部三个租户"本身就是错的用法。
        PageResult<UserView> first = service.listUsers(
                new AdminListQuery(0, 1, "example.com", "ACTIVE", "email", "asc"),
                globalAdminActor());

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).singleElement().satisfies(user -> {
            assertThat(user.email()).isEqualTo("admin@example.com");
            assertThat(user.userNo()).matches("^U[0-9A-Z]{8}$");
        });

        assertThat(service.resolveUserIdentities(List.of(ADMIN_ID, USER_ID)))
                .hasSize(2)
                .allSatisfy(identity -> {
                    assertThat(identity.userId()).isIn(ADMIN_ID, USER_ID);
                    assertThat(identity.userNo()).matches("^U[0-9A-Z]{8}$");
                    assertThat(identity.displayName()).isNotBlank();
                });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.listUsers(
                        new AdminListQuery(0, 20, null, null, "password_hash", "asc")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_sort");
    }

    @Test
    void filtersUsersByActivePackageAndReturnsStructuredPackageIdentity() {
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, 'grant-user-package-filter', ?, ?, ?, 'ACTIVE', 'ADMIN', 'filter', ?)
                """, GRANT_ID, SHARED_TENANT_ID, USER_ID, VERSION_ID, ADMIN_ID);

        PageResult<UserView> result = service.listUsers(
                new AdminListQuery(0, 20, null, null, "email", "asc", "STARTER"),
                ADMIN_ID);

        assertThat(result.items()).singleElement().satisfies(user -> {
            assertThat(user.id()).isEqualTo(USER_ID);
            assertThat(user.currentPackageCode()).isEqualTo("STARTER");
            assertThat(user.currentPackageVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> service.listUsers(
                new AdminListQuery(0, 20, null, null, "email", "asc", "UNKNOWN"),
                ADMIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_package_code");
    }

    @Test
    void treatsSqlLikeMetacharactersLiterallyInAdminSearch() {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status, auth_version)
                VALUES (?, ?, 'literal-percent@example.com', 'hash', 'phase7%literal', 'ACTIVE', 0),
                       (?, ?, 'decoy-percent@example.com', 'hash', 'phase7-anything-literal', 'ACTIVE', 0),
                       (?, ?, 'literal-underscore@example.com', 'hash', 'phase7_literal', 'ACTIVE', 0),
                       (?, ?, 'decoy-underscore@example.com', 'hash', 'phase7Xliteral', 'ACTIVE', 0)
                """,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status, auth_version)
                VALUES (?, ?, 'literal-backslash@example.com', 'hash', 'phase7\\literal', 'ACTIVE', 0),
                       (?, ?, 'decoy-backslash@example.com', 'hash', 'phase7literal', 'ACTIVE', 0)
                """,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        PageResult<UserView> percent = service.listUsers(
                new AdminListQuery(
                        0, 20, "phase7%literal", null, "email", "asc"),
                globalAdminActor());
        PageResult<UserView> underscore = service.listUsers(
                new AdminListQuery(
                        0, 20, "phase7_literal", null, "email", "asc"),
                globalAdminActor());
        PageResult<UserView> backslash = service.listUsers(
                new AdminListQuery(
                        0, 20, "phase7\\literal", null, "email", "asc"),
                globalAdminActor());

        assertThat(percent.items()).singleElement()
                .extracting(UserView::email)
                .isEqualTo("literal-percent@example.com");
        assertThat(underscore.items()).singleElement()
                .extracting(UserView::email)
                .isEqualTo("literal-underscore@example.com");
        assertThat(backslash.items()).singleElement()
                .extracting(UserView::email)
                .isEqualTo("literal-backslash@example.com");
    }

    @Test
    void rejectsUnknownSortColumnsAcrossPlatformAdminLists() {
        AdminListQuery unsafe =
                new AdminListQuery(0, 20, null, null, "password_hash", "asc");

        assertInvalidSort(() -> service.listGrants(unsafe));
        assertInvalidSort(() -> service.listPlans(unsafe));
        assertInvalidSort(() -> service.listOrders(unsafe));
        assertInvalidSort(() -> service.listPayments(unsafe));
        assertInvalidSort(() -> service.listRefunds(unsafe));
        assertInvalidSort(() -> service.listOutbox(unsafe));
    }

    private void assertInvalidSort(org.assertj.core.api.ThrowableAssert.ThrowingCallable query) {
        org.assertj.core.api.Assertions.assertThatThrownBy(query)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_sort");
    }

    @Test
    void retriesFailedOutboxAndReturnsGrantToQueued() {
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by, failure_reason)
                VALUES (?, 'grant-retry-admin', ?, ?, ?, 'FAILED', 'ADMIN', 'retry', ?, 'down')
                """, GRANT_ID, SHARED_TENANT_ID, USER_ID, VERSION_ID, ADMIN_ID);
        jdbc.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload,
                    status, attempt_count, last_error)
                VALUES (?, 'GRANT', ?, 'PACKAGE_GRANTED_V1', 'grant-retry-admin', '{}',
                        'FAILED', 5, 'down')
                """, EVENT_ID, GRANT_ID);

        OutboxView retried = service.retryOutbox(
                EVENT_ID, new RetryOutboxRequest("outbox-retry-001"), ADMIN_ID);

        assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForMap("SELECT status, attempt_count FROM platform.outbox_event WHERE id = ?", EVENT_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0);
        assertThat(jdbc.queryForMap("SELECT status, failure_reason FROM platform.grant_request WHERE id = ?", GRANT_ID))
                .containsEntry("status", "QUEUED")
                .containsEntry("failure_reason", null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'OUTBOX_RETRIED'
                  AND detail->>'businessNo' = 'outbox-retry-001'
                """, Integer.class, ADMIN_ID)).isEqualTo(1);
    }

    @Test
    void duplicateRetryBusinessNumberReturnsOriginalResultWithoutDuplicateMutationOrAudit() {
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by, failure_reason)
                VALUES (?, 'grant-retry-replay', ?, ?, ?, 'FAILED', 'ADMIN', 'retry', ?, 'down')
                """, GRANT_ID, SHARED_TENANT_ID, USER_ID, VERSION_ID, ADMIN_ID);
        jdbc.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload,
                    status, attempt_count, last_error)
                VALUES (?, 'GRANT', ?, 'PACKAGE_GRANTED_V1', 'grant-retry-replay', '{}',
                        'FAILED', 5, 'down')
                """, EVENT_ID, GRANT_ID);
        RetryOutboxRequest request =
                new RetryOutboxRequest("outbox-retry-replay-001");

        OutboxView original = service.retryOutbox(EVENT_ID, request, ADMIN_ID);
        jdbc.update("""
                UPDATE platform.outbox_event
                SET status = 'DELIVERING', attempt_count = 1,
                    claim_token = ?, last_error = 'in flight'
                WHERE id = ?
                """, UUID.randomUUID(), EVENT_ID);

        OutboxView replay = service.retryOutbox(EVENT_ID, request, ADMIN_ID);

        assertThat(replay).isEqualTo(original);
        assertThat(jdbc.queryForMap(
                "SELECT status, attempt_count, last_error FROM platform.outbox_event WHERE id = ?", EVENT_ID))
                .containsEntry("status", "DELIVERING")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error", "in flight");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'OUTBOX_RETRIED'
                  AND detail->>'businessNo' = 'outbox-retry-replay-001'
                """, Integer.class, ADMIN_ID)).isEqualTo(1);
    }

    @Test
    void filtersGrantsByActivationLifecycleStatus() {
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, 'grant-queued-filter', ?, ?, ?, 'QUEUED', 'ADMIN', 'queued', ?)
                """, GRANT_ID, SHARED_TENANT_ID, USER_ID, VERSION_ID, ADMIN_ID);

        PageResult<GrantView> result =
                service.listGrants(0, 20, "QUEUED");

        assertThat(result.items()).singleElement().satisfies(grant -> {
            assertThat(grant.businessNo()).isEqualTo("grant-queued-filter");
            assertThat(grant.status()).isEqualTo("QUEUED");
            assertThat(grant.userNo()).matches("^U[0-9A-Z]{8}$");
            assertThat(grant.userDisplayName()).isNotBlank();
        });
    }

    @Test
    void keepsPackageSnapshotNamesForExistingGrants() {
        jdbc.update("""
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, 'grant-snapshot-name', ?, ?, ?, 'ACTIVE', 'ADMIN', 'snapshot', ?)
                """, GRANT_ID, SHARED_TENANT_ID, USER_ID, VERSION_ID, ADMIN_ID);
        jdbc.update("""
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload)
                VALUES (?, 'GRANT', ?, 'PACKAGE_GRANTED_V1', 'grant-snapshot-name',
                        '{"packageCode":"STARTER","packageName":"Starter Historical"}'::jsonb)
                """, EVENT_ID, GRANT_ID);

        PageResult<GrantView> grants = service.listGrants(
                new AdminListQuery(0, 20, "Starter Historical", "ACTIVE", "createdAt", "desc"));

        assertThat(grants.items()).singleElement()
                .extracting(GrantView::packageName)
                .isEqualTo("Starter Historical");
        var userDetail = service.userDetail(USER_ID, ADMIN_ID);
        assertThat(userDetail.grants()).singleElement()
                .extracting(grant -> grant.packageName())
                .isEqualTo("Starter Historical");
        assertThat(userDetail.user().currentPackage()).isEqualTo("Starter Historical v1");
        assertThat(service.listUsers(
                        new AdminListQuery(0, 20, "user@example.com", "ACTIVE", "email", "asc"),
                        ADMIN_ID)
                .items()).singleElement()
                .extracting(UserView::currentPackage)
                .isEqualTo("Starter Historical v1");
    }

    @Test
    void listsPaymentsAndRefundsAndUpdatesConfigWithoutReplacingCredentials() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.customer_order(
                    id, order_no, tenant_id, user_id, package_plan_version_id,
                    amount, status, settlement_method, payment_provider, paid_at,
                    package_snapshot)
                VALUES (?, 'order-admin-billing', ?, ?, ?, 9900, 'PAID',
                        'EXTERNAL', 'MOCK', now(),
                        '{"packageCode":"STARTER","packageName":"Starter Historical"}'::jsonb)
                """, orderId, SHARED_TENANT_ID, USER_ID, VERSION_ID);
        jdbc.update("""
                INSERT INTO platform.payment_transaction(
                    id, transaction_no, order_id, provider, external_trade_no,
                    amount, status, callback_hash, paid_at)
                VALUES (?, 'payment-admin-billing', ?, 'MOCK', 'external-payment',
                        9900, 'SUCCEEDED', 'sensitive-callback-hash', now())
                """, paymentId, orderId);
        jdbc.update("""
                INSERT INTO platform.refund(
                    id, refund_no, order_id, payment_transaction_id,
                    external_refund_no, amount, status, reason, completed_at,
                    refund_type, policy_version, calculation_snapshot,
                    requested_by, provider, funding_source,
                    gateway_hold_business_no, revocation_business_no)
                VALUES (?, 'refund-admin-billing', ?, ?, 'external-refund',
                        1000, 'COMPLETED', 'customer request', now(),
                        'PARTIAL', 1, '{"fixture":"phase2"}'::jsonb,
                        ?, 'MOCK', 'EXTERNAL', 'refund-hold:refund-admin-billing',
                        'refund-revoke:refund-admin-billing')
                """, UUID.randomUUID(), orderId, paymentId, USER_ID);
        jdbc.update("""
                UPDATE platform.payment_config
                SET enabled = false, encrypted_credentials = NULL,
                    credential_fingerprint = 'mock-fingerprint',
                    last_validation_status = 'VALID', last_validated_at = now()
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);

        PageResult<OrderView> orders =
                service.listOrders(0, 20, "PAID");
        PageResult<PaymentView> payments =
                service.listPayments(0, 20, "SUCCEEDED");
        PageResult<RefundView> refunds =
                service.listRefunds(0, 20, "COMPLETED");
        PaymentConfigView config = service.updatePaymentConfig(
                "MOCK", "LOCAL", new UpdatePaymentConfigRequest(true), ADMIN_ID);
        verify(paymentAvailabilityService).assertCanEnable(
                PaymentProvider.MOCK, PaymentEnvironment.LOCAL);

        assertThat(orders.items()).singleElement().satisfies(order -> {
            assertThat(order.orderNo()).isEqualTo("order-admin-billing");
            assertThat(order.settlementMethod()).isEqualTo("EXTERNAL");
            assertThat(order.userNo()).matches("^U[0-9A-Z]{8}$");
            assertThat(order.userDisplayName()).isNotBlank();
            assertThat(order.packageName()).isEqualTo("Starter Historical");
            assertThat(order.packageVersion()).isEqualTo(1);
        });
        assertThat(payments.items()).singleElement().satisfies(payment -> {
            assertThat(payment.transactionNo()).isEqualTo("payment-admin-billing");
            assertThat(payment.orderNo()).isEqualTo("order-admin-billing");
        });
        assertThat(refunds.items()).singleElement().satisfies(refund -> {
            assertThat(refund.refundNo()).isEqualTo("refund-admin-billing");
            assertThat(refund.fundingSource()).isEqualTo("EXTERNAL");
            assertThat(refund.userNo()).matches("^U[0-9A-Z]{8}$");
            assertThat(refund.userDisplayName()).isNotBlank();
        });
        assertThat(OrderView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("balanceDebitLedgerId", "accountId", "ledgerId");
        assertThat(RefundView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("balanceRefundLedgerId", "accountId", "ledgerId");
        assertThat(config.enabled()).isTrue();
        assertThat(config.configured()).isTrue();
        assertThat(config.credentialFingerprint()).isEqualTo("mock-fingerprint");
        assertThat(config.lastValidationStatus()).isEqualTo("VALID");
        assertThat(config.lastValidatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT encrypted_credentials IS NULL FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'PAYMENT_CONFIG_UPDATED'
                  AND detail #>> '{before,enabled}' = 'false'
                  AND detail #>> '{before,configured}' = 'true'
                  AND detail #>> '{after,enabled}' = 'true'
                  AND detail #>> '{after,configured}' = 'true'
                  AND detail::text NOT LIKE '%01020304%'
                """, Integer.class, ADMIN_ID)).isEqualTo(1);
    }

    @Test
    void rejectsUnhealthyEnablementButAlwaysAllowsDisablement() {
        jdbc.update("""
                UPDATE platform.payment_config SET enabled = false
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """);
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "payment_adapter_unhealthy"))
                .when(paymentAvailabilityService)
                .assertCanEnable(PaymentProvider.MOCK, PaymentEnvironment.LOCAL);

        assertThatThrownBy(() -> service.updatePaymentConfig(
                "MOCK", "LOCAL",
                new UpdatePaymentConfigRequest(true), ADMIN_ID))
                .hasMessageContaining("payment_adapter_unhealthy");
        assertThat(jdbc.queryForObject("""
                SELECT enabled FROM platform.payment_config
                WHERE provider = 'MOCK' AND environment = 'LOCAL'
                """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE action = 'PAYMENT_CONFIG_UPDATED'
                """, Integer.class)).isZero();

        paymentAvailabilityService = mock(PaymentAvailabilityService.class);
        service = new AdminManagementService(
                new AdminManagementRepository(jdbc), paymentAvailabilityService);
        service.updatePaymentConfig(
                "MOCK", "LOCAL",
                new UpdatePaymentConfigRequest(false), ADMIN_ID);
        verifyNoInteractions(paymentAvailabilityService);
    }
}
