package com.yuzhihuihe.apiplatform.platform.order;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackagePurchasePolicyService {

    private static final int MAX_PURCHASES_PER_PACKAGE = 3;

    private final PackagePurchasePolicyRepository repository;

    PackagePurchasePolicyService(PackagePurchasePolicyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void assertCanCreate(
            UUID tenantId,
            UUID userId,
            PackageSnapshotService.PackageSnapshot snapshot) {
        validate(tenantId, userId, snapshot);
        repository.lock(tenantId, userId, snapshot.packageCode());
        if (repository.hasUnconsumedPackage(tenantId, userId, snapshot.packageCode())) {
            throw conflict("package_quota_remaining");
        }
        if (repository.purchaseCount(tenantId, userId, snapshot.packageCode())
                >= MAX_PURCHASES_PER_PACKAGE) {
            throw conflict("package_purchase_limit_reached");
        }
    }

    private void validate(
            UUID tenantId,
            UUID userId,
            PackageSnapshotService.PackageSnapshot snapshot) {
        if (tenantId == null || userId == null || snapshot == null
                || snapshot.packageCode() == null || snapshot.packageCode().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_purchase_policy_request");
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
