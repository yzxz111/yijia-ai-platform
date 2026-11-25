package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletAccountService {

    private final WalletAccountRepository repository;

    WalletAccountService(WalletAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WalletAccount provision(UUID tenantId, UUID userId) {
        repository.createAccount(UUID.randomUUID(), tenantId, userId);
        repository.seedDefaultRechargeOptions(tenantId);
        return require(tenantId, userId);
    }

    private WalletAccount require(UUID tenantId, UUID userId) {
        WalletAccountRepository.AccountRow row = repository.findAccount(tenantId, userId);
        if (row == null) {
            throw new IllegalStateException("wallet account was not provisioned");
        }
        return new WalletAccount(
                row.id(), row.tenantId(), row.userId(), row.currency(),
                row.availableAmount(), row.reservedAmount(), row.version(), row.updatedAt());
    }

    public record WalletAccount(
            UUID id,
            UUID tenantId,
            UUID userId,
            String currency,
            long availableAmount,
            long reservedAmount,
            long version,
            Instant updatedAt) {
    }
}
