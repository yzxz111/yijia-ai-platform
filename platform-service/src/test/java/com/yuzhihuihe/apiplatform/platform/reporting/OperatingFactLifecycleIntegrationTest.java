package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OperatingFactLifecycleIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final UUID TENANT = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("93000000-0000-4000-8000-000000000002");

    @Test
    void coversExternalAndBalancePackagePurchaseAndRefundWithoutDuplicateRevenue() {
        var store = new OperatingFactServiceTest.MemoryStore();
        var service = new OperatingFactService(Clock.fixed(NOW, ZoneOffset.UTC), store);
        UUID externalOrder = UUID.randomUUID();
        UUID balanceOrder = UUID.randomUUID();
        var external = new OperatingFactService.PackagePurchase(externalOrder, TENANT, USER,
                UUID.randomUUID(), UUID.randomUUID(), 1_900, "CNY", "EXTERNAL", NOW);
        var balance = new OperatingFactService.PackagePurchase(balanceOrder, TENANT, USER,
                UUID.randomUUID(), null, 1_900, "CNY", "BALANCE", NOW);

        assertThat(service.recordPackagePurchase(external)).isTrue();
        assertThat(service.recordPackagePurchase(external)).isFalse();
        assertThat(service.recordPackagePurchase(balance)).isTrue();
        assertThat(service.recordPackageRefund(new OperatingFactService.PackageRefund(
                UUID.randomUUID(), externalOrder, TENANT, USER, 1_900, "CNY", "EXTERNAL", NOW))).isTrue();
        assertThat(service.recordPackageRefund(new OperatingFactService.PackageRefund(
                UUID.randomUUID(), balanceOrder, TENANT, USER, 1_900, "CNY", "BALANCE", NOW))).isTrue();

        assertThat(store.facts).filteredOn(f -> f.eventType().equals("PACKAGE_PURCHASE_REVENUE"))
                .hasSize(2).allSatisfy(f -> assertThat(f.confirmedRevenueAmount()).isEqualTo(1_900));
        assertThat(store.facts).filteredOn(f -> f.eventType().equals("PACKAGE_REFUND"))
                .hasSize(2).allSatisfy(f -> assertThat(f.sign()).isEqualTo(-1));
    }

    @Test
    void coversPackageOnlyHybridAndWalletOnlyUsageWithoutInventingPackageRevenue() {
        var store = new OperatingFactServiceTest.MemoryStore();
        var service = new OperatingFactService(Clock.fixed(NOW, ZoneOffset.UTC), store);

        // 纯套餐调用没有钱包结算，因此没有钱包收入事实。
        service.recordWalletUsage(new OperatingFactService.WalletUsage(
                UUID.randomUUID(), TENANT, USER, "req-hybrid", "text-basic", "pv-1",
                120, 20, 10, "HYBRID", NOW));
        service.recordWalletUsage(new OperatingFactService.WalletUsage(
                UUID.randomUUID(), TENANT, USER, "req-wallet", "text-basic", "pv-1",
                300, 70, 30, "WALLET_ONLY", NOW));

        assertThat(store.facts).hasSize(2);
        assertThat(store.facts).extracting(OperatingFactService.Fact::billingMode)
                .containsExactly("HYBRID", "WALLET_ONLY");
        assertThat(store.facts).extracting(OperatingFactService.Fact::confirmedRevenueAmount)
                .containsExactly(120L, 300L);
        assertThat(store.facts).noneMatch(f -> f.eventType().equals("PACKAGE_PURCHASE_REVENUE"));
    }
}
