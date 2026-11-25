package com.yuzhihuihe.apiplatform.platform.order;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackageInventoryService {

    private final PackageInventoryRepository repository;

    PackageInventoryService(PackageInventoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public InventoryView reserve(UUID tenantId, UUID userId, UUID packageVersionId) {
        if (tenantId == null || userId == null || packageVersionId == null) {
            throw invalid("invalid_inventory_request");
        }
        PackageInventoryRepository.InventoryRow inventory = lockInventory(packageVersionId);
        if (!"PUBLISHED".equals(inventory.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "package_version_not_found");
        }
        if (!repository.hasActiveUser(tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order_user_not_found");
        }
        Integer maximum = inventory.maxPurchasesPerUser() != null
                ? inventory.maxPurchasesPerUser()
                : inventory.oncePerUser() ? 1 : null;
        if (maximum != null) {
            long purchases = repository.purchaseCount(userId, packageVersionId);
            if (purchases >= maximum) {
                throw conflict("package_purchase_limit_reached");
            }
        }
        Long remaining = remainingStock(inventory, inventory.stockReserved());
        if (remaining != null && remaining <= 0) {
            throw conflict("package_sold_out");
        }
        int updated = repository.incrementReserved(packageVersionId);
        if (updated != 1) {
            throw conflict("package_sold_out");
        }
        return view(inventory, inventory.stockReserved() + 1);
    }

    @Transactional
    public void consume(UUID orderId, UUID packageVersionId) {
        changeReservation(orderId, packageVersionId, "SOLD");
    }

    @Transactional
    public void release(UUID orderId, UUID packageVersionId) {
        changeReservation(orderId, packageVersionId, "RELEASED");
    }

    @Transactional
    public void release(UUID orderId) {
        UUID packageVersionId = repository.findPackageVersionId(orderId).orElse(null);
        if (packageVersionId != null) {
            release(orderId, packageVersionId);
        }
    }

    public InventoryView get(UUID packageVersionId) {
        return view(lockInventory(packageVersionId));
    }

    private void changeReservation(UUID orderId, UUID packageVersionId, String targetStatus) {
        if (orderId == null || packageVersionId == null) {
            throw invalid("invalid_inventory_request");
        }
        String current = repository.lockReservationStatus(orderId, packageVersionId).orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));
        if (targetStatus.equals(current) || !"RESERVED".equals(current)) {
            return;
        }
        lockInventory(packageVersionId);
        int updated = repository.changeInventory(targetStatus, packageVersionId);
        if (updated != 1) {
            throw new IllegalStateException("package_inventory_state_changed");
        }
        repository.changeOrderStatus(targetStatus, orderId);
    }

    private PackageInventoryRepository.InventoryRow lockInventory(UUID packageVersionId) {
        return repository.lockInventory(packageVersionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "package_version_not_found"));
    }

    private InventoryView view(PackageInventoryRepository.InventoryRow row) {
        return view(row, row.stockReserved());
    }

    private InventoryView view(
            PackageInventoryRepository.InventoryRow row, long stockReserved) {
        Long remaining = remainingStock(row, stockReserved);
        return new InventoryView(
                row.stockTotal(), row.stockSold(), stockReserved, remaining,
                remaining != null && remaining == 0,
                remaining != null && remaining > 0 && remaining <= row.lowStockThreshold());
    }

    private Long remainingStock(
            PackageInventoryRepository.InventoryRow row, long stockReserved) {
        return row.stockTotal() == null
                ? null
                : row.stockTotal() - row.stockSold() - stockReserved;
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record InventoryView(
            Long stockTotal,
            long stockSold,
            long stockReserved,
            Long remainingStock,
            boolean soldOut,
            boolean lowStock) {
    }
}
