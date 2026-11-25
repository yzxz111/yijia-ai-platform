package com.yuzhihuihe.apiplatform.platform.admin;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AdminUserAuthorizationPolicy {

    private AdminUserAuthorizationPolicy() {
    }

    static Permissions permissions(
            UUID actorUserId,
            boolean actorGlobalAdmin,
            UUID targetUserId,
            boolean targetGlobalAdmin,
            String targetRole) {
        boolean self = targetUserId.equals(actorUserId);
        boolean statusManageable = actorUserId != null && !self && !targetGlobalAdmin
                && (actorGlobalAdmin || "USER".equals(targetRole));
        boolean roleManageable = actorUserId != null && !self && !targetGlobalAdmin && actorGlobalAdmin;
        return new Permissions(statusManageable, roleManageable);
    }

    static void authorizeUpdate(
            UUID actorUserId,
            boolean actorGlobalAdmin,
            UUID targetUserId,
            boolean targetGlobalAdmin,
            String currentStatus,
            String currentRole,
            String requestedStatus,
            String requestedRole) {
        boolean statusChanged = !requestedStatus.equals(currentStatus);
        boolean roleChanged = !requestedRole.equals(currentRole);
        if (targetGlobalAdmin) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "global_admin_immutable");
        }
        if (targetUserId.equals(actorUserId) && "DISABLED".equals(requestedStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin_cannot_disable_self");
        }
        if (targetUserId.equals(actorUserId) && roleChanged) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin_cannot_change_own_role");
        }
        if (roleChanged && !actorGlobalAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "global_admin_required");
        }
        if (statusChanged && !actorGlobalAdmin && "ADMIN".equals(currentRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "global_admin_required");
        }
    }

    record Permissions(boolean statusManageable, boolean roleManageable) {
    }
}
