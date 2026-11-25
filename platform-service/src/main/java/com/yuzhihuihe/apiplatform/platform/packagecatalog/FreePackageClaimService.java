package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.grant.GrantService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageFulfillmentService;
import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FreePackageClaimService {

    private final FreePackageClaimRepository repository;
    private final PackageSnapshotService snapshotService;
    private final PackageFulfillmentService fulfillmentService;

    FreePackageClaimService(
            FreePackageClaimRepository repository,
            PackageSnapshotService snapshotService,
            PackageFulfillmentService fulfillmentService) {
        this.repository = repository;
        this.snapshotService = snapshotService;
        this.fulfillmentService = fulfillmentService;
    }

    @Transactional
    public GrantService.GrantResult claim(UUID userId) {
        FreePackageClaimRepository.UserTenantRow user = repository.findActiveUser(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "grant_user_not_found"));
        UUID versionId = repository.findEligibleFreeVersionIds()
                .stream().reduce((first, second) -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "free_package_configuration_ambiguous");
                })
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "free_package_unavailable"));
        PackageSnapshotService.PackageSnapshot snapshot = snapshotService.loadPublished(versionId);
        return fulfillmentService.fulfill(new PackageFulfillmentService.FulfillmentCommand(
                user.tenantId(), user.id(), versionId, businessNo(user.id()), "SYSTEM",
                "self-service free claim", user.id(), snapshot));
    }

    public boolean hasClaimed(UUID userId) {
        return repository.hasClaimed(userId, businessNo(userId));
    }

    private String businessNo(UUID userId) {
        return "free:" + userId;
    }
}
