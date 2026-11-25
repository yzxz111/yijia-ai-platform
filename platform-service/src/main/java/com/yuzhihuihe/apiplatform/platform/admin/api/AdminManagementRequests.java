package com.yuzhihuihe.apiplatform.platform.admin.api;

public final class AdminManagementRequests {

    private AdminManagementRequests() {}

    public record AdminListQuery(
            int page,
            int size,
            String query,
            String status,
            String sort,
            String direction,
            String packageCode) {

        public AdminListQuery(
                int page, int size, String query, String status, String sort, String direction) {
            this(page, size, query, status, sort, direction, null);
        }
    }

    public record UpdateUserRequest(String status, String role, String disabledReason) {}

    public record UpdatePaymentConfigRequest(boolean enabled) {}

    public record RetryOutboxRequest(String businessNo) {}
}
