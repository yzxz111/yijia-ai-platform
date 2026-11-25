package com.yuzhihuihe.apiplatform.gateway.internal;

import com.yuzhihuihe.apiplatform.gateway.quota.PackageActivationService;
import com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/events")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PackageEventController {

    private final PackageActivationService packageActivationService;
    private final PackageRefundService packageRefundService;

    public PackageEventController(
            PackageActivationService packageActivationService,
            PackageRefundService packageRefundService) {
        this.packageActivationService = packageActivationService;
        this.packageRefundService = packageRefundService;
    }

    @PostMapping("/package-granted")
    Mono<PackageActivationService.ActivationResult> packageGranted(
            @RequestBody PackageActivationService.PackageGrantedEvent event) {
        return packageActivationService.consume(event);
    }

    @PostMapping("/package-refunded")
    Mono<PackageRefundService.RevocationResult> packageRefunded(
            @Valid @RequestBody PackageRefundService.PackageRefundedEvent event) {
        return packageRefundService.revoke(event);
    }
}
