package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OperatingFactServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final UUID TENANT = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("92000000-0000-4000-8000-000000000002");

    @Test
    void mapsAuthoritativeRevenueCashInUsageAndRefundFacts() {
        MemoryStore store = new MemoryStore();
        OperatingFactService service = service(store);
        UUID order = UUID.fromString("92000000-0000-4000-8000-000000000003");
        UUID recharge = UUID.fromString("92000000-0000-4000-8000-000000000004");
        UUID reservation = UUID.fromString("92000000-0000-4000-8000-000000000005");
        UUID refund = UUID.fromString("92000000-0000-4000-8000-000000000006");

        service.recordPackagePurchase(new OperatingFactService.PackagePurchase(
                order, TENANT, USER, UUID.randomUUID(), UUID.randomUUID(), 1_900, "CNY",
                "EXTERNAL", NOW));
        service.recordWalletRecharge(new OperatingFactService.WalletRecharge(
                recharge, TENANT, USER, UUID.randomUUID(), 2_000, "CNY", NOW));
        service.recordWalletUsage(new OperatingFactService.WalletUsage(
                reservation, TENANT, USER, "req-1", "text-basic", "pv-1", 300,
                70, 30, "HYBRID", NOW));
        service.recordPackageRefund(new OperatingFactService.PackageRefund(
                refund, order, TENANT, USER, 1_900, "CNY", "EXTERNAL", NOW));

        assertThat(store.facts).extracting(OperatingFactService.Fact::eventType)
                .containsExactly("PACKAGE_PURCHASE_REVENUE", "WALLET_RECHARGE_CASH_IN",
                        "WALLET_USAGE_REVENUE", "PACKAGE_REFUND");
        assertThat(store.facts.get(0).confirmedRevenueAmount()).isEqualTo(1_900);
        assertThat(store.facts.get(0).cashInAmount()).isZero();
        assertThat(store.facts.get(1).confirmedRevenueAmount()).isZero();
        assertThat(store.facts.get(1).cashInAmount()).isEqualTo(2_000);
        assertThat(store.facts.get(2).confirmedRevenueAmount()).isEqualTo(300);
        assertThat(store.facts.get(2).totalTokens()).isEqualTo(100);
        assertThat(store.facts.get(2).billingMode()).isEqualTo("HYBRID");
        assertThat(store.facts.get(3).sign()).isEqualTo(-1);
        assertThat(store.facts.get(3).reversal()).isTrue();
        assertThat(store.facts.get(3).refundAmount()).isEqualTo(1_900);
    }

    @Test
    void usesStableSourceEventIdsAndRejectsNegativeAmounts() {
        MemoryStore store = new MemoryStore();
        OperatingFactService service = service(store);
        UUID order = UUID.randomUUID();

        service.recordPackagePurchase(new OperatingFactService.PackagePurchase(
                order, TENANT, USER, UUID.randomUUID(), null, 500, "CNY", "BALANCE", NOW));

        assertThat(store.facts.getFirst().sourceEventId())
                .isEqualTo("customer-order:" + order + ":paid:v1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.recordWalletRecharge(new OperatingFactService.WalletRecharge(
                        UUID.randomUUID(), TENANT, USER, UUID.randomUUID(), -1, "CNY", NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_operating_fact");
    }

    private OperatingFactService service(MemoryStore store) {
        return new OperatingFactService(Clock.fixed(NOW, ZoneOffset.UTC), store);
    }

    static final class MemoryStore implements OperatingFactService.Store {
        final List<OperatingFactService.Fact> facts = new ArrayList<>();
        final java.util.Map<String, String> receipts = new java.util.HashMap<>();

        @Override
        public OperatingFactService.Claim claim(String sourceEventId, String payloadHash) {
            String existing = receipts.putIfAbsent(sourceEventId, payloadHash);
            if (existing == null) return OperatingFactService.Claim.CLAIMED;
            return existing.equals(payloadHash)
                    ? OperatingFactService.Claim.REPLAYED : OperatingFactService.Claim.CONFLICT;
        }

        @Override
        public void insert(OperatingFactService.Fact fact) {
            facts.add(fact);
        }
    }
}
