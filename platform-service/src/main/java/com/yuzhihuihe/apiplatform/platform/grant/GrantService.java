package com.yuzhihuihe.apiplatform.platform.grant;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GrantService {

    private final GrantRepository repository;
    private final PackageSnapshotService snapshotService;
    private final PackageFulfillmentService fulfillmentService;

    GrantService(
            GrantRepository repository,
            PackageSnapshotService snapshotService,
            PackageFulfillmentService fulfillmentService) {
        this.repository = repository;
        this.snapshotService = snapshotService;
        this.fulfillmentService = fulfillmentService;
    }

    @Transactional
    public GrantResult createGrant(CreateGrantRequest request, UUID actorUserId) {
        validate(request, actorUserId);
        UUID tenantId = repository.findActiveUserTenantId(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "grant_user_not_found"));
        PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(request.packageVersionId());
        return fulfillmentService.fulfill(new PackageFulfillmentService.FulfillmentCommand(
                tenantId,
                request.userId(),
                request.packageVersionId(),
                request.businessNo(),
                "ADMIN",
                request.reason(),
                actorUserId,
                snapshot));
    }

    private void validate(CreateGrantRequest request, UUID actorUserId) {
        if (request == null || actorUserId == null || request.userId() == null
                || request.packageVersionId() == null || blank(request.businessNo())
                || request.businessNo().length() > 64 || blank(request.reason())
                || request.reason().strip().length() > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_grant_request");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CreateGrantRequest(
            UUID userId,
            UUID packageVersionId,
            String reason,
            String businessNo) {
    }

    public record GrantResult(UUID id, UUID eventId, String businessNo, String status) {
    }
}
