package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class UploadEntitlementServiceTest {

    private static final long FIVE_MIB = 5L * 1024 * 1024;
    private static final long TWENTY_MIB = 20L * 1024 * 1024;
    private FileObjectRepository repository;
    private WalletUsageClient walletClient;
    private UploadEntitlementService service;

    @BeforeEach
    void setUp() {
        repository = mock(FileObjectRepository.class);
        walletClient = mock(WalletUsageClient.class);
        service = new UploadEntitlementService(repository, walletClient, Duration.ofMillis(100));
    }

    @ParameterizedTest
    @MethodSource("packages")
    void validPackageDeterminesLimitWithoutCallingWallet(
            String packageCode, long expectedMaximumBytes) {
        var context = context(UUID.randomUUID(), false);
        when(repository.packageEntitlement(
                context.tenantId(), context.userId(), context.packageInstanceId()))
                .thenReturn(Mono.just(new FileObjectRepository.PackageEntitlement(
                        packageCode, expectedMaximumBytes)));

        var result = service.resolve(context).block();

        assertThat(result).isEqualTo(new UploadEntitlementService.UploadEntitlement(
                "PACKAGE", expectedMaximumBytes, packageCode));
        verifyNoInteractions(walletClient);
    }

    @Test
    void missingPackageFallsThroughToPositiveWalletWithoutTrustingContextFlag() {
        var context = context(UUID.randomUUID(), false);
        when(repository.packageEntitlement(
                context.tenantId(), context.userId(), context.packageInstanceId()))
                .thenReturn(Mono.empty());
        when(walletClient.eligibility(context.tenantId(), context.userId()))
                .thenReturn(Mono.just(new WalletUsageClient.WalletEligibility(true, 1, "CNY")));

        var result = service.resolve(context).block();

        assertThat(result).isEqualTo(new UploadEntitlementService.UploadEntitlement(
                "WALLET", TWENTY_MIB, null));
    }

    @Test
    void customPackageUsesItsPersistedLimitWithoutWalletFallback() {
        var context = context(UUID.randomUUID(), true);
        when(repository.packageEntitlement(
                context.tenantId(), context.userId(), context.packageInstanceId()))
                .thenReturn(Mono.just(new FileObjectRepository.PackageEntitlement(
                        "PKG_CUSTOM", FIVE_MIB)));

        assertThat(service.resolve(context).block()).isEqualTo(
                new UploadEntitlementService.UploadEntitlement(
                        "PACKAGE", FIVE_MIB, "PKG_CUSTOM"));
        verifyNoInteractions(walletClient);
    }

    @Test
    void absentPackageAndZeroWalletRequiresBillingSource() {
        var context = context(null, true);
        when(walletClient.eligibility(context.tenantId(), context.userId()))
                .thenReturn(Mono.just(new WalletUsageClient.WalletEligibility(false, 0, "CNY")));

        assertReason(service.resolve(context), "billing_source_required");
        verify(repository, never()).packageEntitlement(context.tenantId(), context.userId(), null);
    }

    @Test
    void walletEligibilityTimeoutUsesStableUnavailableError() {
        var context = context(null, false);
        when(walletClient.eligibility(context.tenantId(), context.userId()))
                .thenReturn(Mono.never());

        assertReason(service.resolve(context), "billing_eligibility_unavailable");
    }

    @Test
    void walletEligibilityErrorUsesStableUnavailableError() {
        var context = context(null, false);
        when(walletClient.eligibility(context.tenantId(), context.userId()))
                .thenReturn(Mono.error(new IllegalStateException("platform secret")));

        assertReason(service.resolve(context), "billing_eligibility_unavailable");
    }

    @Test
    void emptyWalletEligibilityUsesStableUnavailableError() {
        var context = context(null, false);
        when(walletClient.eligibility(context.tenantId(), context.userId()))
                .thenReturn(Mono.empty());

        assertReason(service.resolve(context), "billing_eligibility_unavailable");
    }

    private static Stream<Arguments> packages() {
        return Stream.of(
                Arguments.of("FREE", FIVE_MIB),
                Arguments.of("STARTER", TWENTY_MIB),
                Arguments.of("ADVANCED", TWENTY_MIB),
                Arguments.of("ENTERPRISE", TWENTY_MIB),
                Arguments.of("PKG_CUSTOM", 12L * 1024 * 1024));
    }

    private static ApiKeyService.ApiKeyContext context(UUID packageId, boolean walletEligible) {
        return new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), packageId,
                Set.of("chat-standard"), walletEligible);
    }

    private static void assertReason(Mono<?> result, String reason) {
        assertThatThrownBy(result::block)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getReason())
                .isEqualTo(reason);
    }
}
