package com.yuzhihuihe.apiplatform.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminUserAuthorizationPolicyTest {

    private static final UUID GLOBAL_ADMIN = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID ORDINARY_ADMIN = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID USER = UUID.fromString("71000000-0000-4000-8000-000000000003");

    @Test
    void globalAdministratorCanPromoteAndDemoteOtherUsers() {
        assertThatCode(() -> authorize(GLOBAL_ADMIN, true, USER, false, "ACTIVE", "USER", "ACTIVE", "ADMIN"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorize(GLOBAL_ADMIN, true, USER, false, "ACTIVE", "ADMIN", "ACTIVE", "USER"))
                .doesNotThrowAnyException();
    }

    @Test
    void ordinaryAdministratorCannotPromoteOrDemoteUsers() {
        assertForbidden(() -> authorize(
                ORDINARY_ADMIN, false, USER, false, "ACTIVE", "USER", "ACTIVE", "ADMIN"));
        assertForbidden(() -> authorize(
                ORDINARY_ADMIN, false, USER, false, "ACTIVE", "ADMIN", "ACTIVE", "USER"));
    }

    @Test
    void globalAdministratorCannotBeDisabledOrDemotedByAnyone() {
        assertImmutable(() -> authorize(
                ORDINARY_ADMIN, false, GLOBAL_ADMIN, true, "ACTIVE", "ADMIN", "DISABLED", "ADMIN"));
        assertImmutable(() -> authorize(
                GLOBAL_ADMIN, true, GLOBAL_ADMIN, true, "ACTIVE", "ADMIN", "ACTIVE", "USER"));
    }

    @Test
    void ordinaryAdministratorCanOnlyChangeOrdinaryUserStatus() {
        assertThatCode(() -> authorize(
                ORDINARY_ADMIN, false, USER, false, "ACTIVE", "USER", "DISABLED", "USER"))
                .doesNotThrowAnyException();
        assertForbidden(() -> authorize(
                ORDINARY_ADMIN, false, GLOBAL_ADMIN, false, "ACTIVE", "ADMIN", "DISABLED", "ADMIN"));
    }

    @Test
    void exposesOnlyActionsAllowedForTheActorAndTarget() {
        assertThat(AdminUserAuthorizationPolicy.permissions(GLOBAL_ADMIN, true, USER, false, "USER"))
                .isEqualTo(new AdminUserAuthorizationPolicy.Permissions(true, true));
        assertThat(AdminUserAuthorizationPolicy.permissions(ORDINARY_ADMIN, false, USER, false, "USER"))
                .isEqualTo(new AdminUserAuthorizationPolicy.Permissions(true, false));
        assertThat(AdminUserAuthorizationPolicy.permissions(ORDINARY_ADMIN, false, GLOBAL_ADMIN, true, "ADMIN"))
                .isEqualTo(new AdminUserAuthorizationPolicy.Permissions(false, false));
        assertThat(AdminUserAuthorizationPolicy.permissions(ORDINARY_ADMIN, false, ORDINARY_ADMIN, false, "ADMIN"))
                .isEqualTo(new AdminUserAuthorizationPolicy.Permissions(false, false));
    }

    private void authorize(
            UUID actorUserId,
            boolean actorGlobalAdmin,
            UUID targetUserId,
            boolean targetGlobalAdmin,
            String currentStatus,
            String currentRole,
            String requestedStatus,
            String requestedRole) {
        AdminUserAuthorizationPolicy.authorizeUpdate(
                actorUserId, actorGlobalAdmin, targetUserId, targetGlobalAdmin,
                currentStatus, currentRole, requestedStatus, requestedRole);
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("global_admin_required");
    }

    private void assertImmutable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("global_admin_immutable");
    }
}
