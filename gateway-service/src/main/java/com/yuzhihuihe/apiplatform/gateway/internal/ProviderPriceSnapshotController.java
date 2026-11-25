package com.yuzhihuihe.apiplatform.gateway.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ProviderPriceSnapshotController {

    private final ProviderPriceQueryService service;

    public ProviderPriceSnapshotController(ProviderPriceQueryService service) {
        this.service = service;
    }

    @GetMapping("/internal/provider-prices/snapshot")
    @PreAuthorize("hasRole('INTERNAL')")
    Flux<ProviderPriceSnapshot> snapshot() {
        return service.snapshots();
    }
}
