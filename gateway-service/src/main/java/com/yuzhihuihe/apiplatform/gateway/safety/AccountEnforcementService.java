package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public final class AccountEnforcementService {
    private final AccountEnforcementRepository repository;

    public AccountEnforcementService(AccountEnforcementRepository repository) {
        this.repository = repository;
    }

    public Mono<Enforcement> current(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) return Mono.empty();
        return repository
                .current(tenantId, userId)
                .map(row -> new Enforcement(row.state(), row.reasonCode(), row.caseNo()));
    }

    public record Enforcement(String state, String reasonCode, String caseNo) {}
}
