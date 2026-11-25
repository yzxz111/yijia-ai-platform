package com.yuzhihuihe.apiplatform.gateway.file;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class UploadEntitlementService {

    private static final long MIB = 1024L * 1024;
    private static final long WALLET_LIMIT = 20L * MIB;

    private final FileObjectRepository repository;
    private final WalletUsageClient walletClient;
    private final Duration eligibilityTimeout;

    public UploadEntitlementService(
            FileObjectRepository repository,
            WalletUsageClient walletClient,
            @Value("${platform.wallet.eligibility-timeout:2s}") Duration eligibilityTimeout) {
        this.repository = repository;
        this.walletClient = walletClient;
        this.eligibilityTimeout = eligibilityTimeout;
    }

    public Mono<UploadEntitlement> resolve(ApiKeyService.ApiKeyContext context) {
        Mono<UploadEntitlement> packageEntitlement = context.packageInstanceId() == null
                ? Mono.empty()
                : repository.packageEntitlement(
                                context.tenantId(), context.userId(), context.packageInstanceId())
                        .map(value -> new UploadEntitlement(
                                "PACKAGE", value.maximumBytes(), value.packageCode()));
        return packageEntitlement.switchIfEmpty(Mono.defer(() -> walletClient
                .eligibility(context.tenantId(), context.userId())
                .timeout(eligibilityTimeout)
                .onErrorMap(error -> unavailable())
                .switchIfEmpty(Mono.error(unavailable()))
                .flatMap(eligibility -> eligibility.usable() && eligibility.availableAmount() > 0
                        ? Mono.just(new UploadEntitlement("WALLET", WALLET_LIMIT, null))
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.PAYMENT_REQUIRED, "billing_source_required")))));
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "billing_eligibility_unavailable");
    }

    public record UploadEntitlement(String source, long maximumBytes, String packageCode) { }
}
