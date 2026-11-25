package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.Test;

class PaymentFeeServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T08:00:00Z");
    private static final UUID TENANT = UUID.fromString("97000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("97000000-0000-4000-8000-000000000002");
    private static final UUID PAYMENT = UUID.fromString("97000000-0000-4000-8000-000000000003");
    private static final UUID REFUND = UUID.fromString("97000000-0000-4000-8000-000000000004");
    private static final UUID RATE = UUID.fromString("97000000-0000-4000-8000-000000000005");

    @Test
    void estimatesExternalPaymentAndRefundFeesUsingEffectiveVersion() {
        MemoryStore store = new MemoryStore();
        store.rate = new PaymentFeeService.FeeRate(RATE,
                new BigDecimal("0.006000"), new BigDecimal("0.001000"));
        PaymentFeeService service = service(store);

        assertThat(service.recordEstimated(new PaymentFeeService.EstimateCommand(
                "pay-1", BillEntryType.PAYMENT, PaymentProvider.ALIPAY,
                PaymentProduct.ALIPAY_PAGE, TENANT, USER, null, PAYMENT, null,
                10_001L, "CNY", "EXTERNAL", OCCURRED_AT))).isTrue();
        assertThat(service.recordEstimated(new PaymentFeeService.EstimateCommand(
                "refund-1", BillEntryType.REFUND, PaymentProvider.ALIPAY,
                PaymentProduct.ALIPAY_PAGE, TENANT, USER, null, PAYMENT, REFUND,
                10_001L, "CNY", "EXTERNAL", OCCURRED_AT))).isTrue();

        assertThat(store.facts).extracting(PaymentFeeService.Fact::eventType)
                .containsExactly("PAYMENT_PROVIDER_FEE_ESTIMATED", "REFUND_PROVIDER_FEE_ESTIMATED");
        assertThat(store.facts.get(0).paymentProviderFeeAmount()).isEqualTo(61L);
        assertThat(store.facts.get(0).refundProviderFeeAmount()).isZero();
        assertThat(store.facts.get(1).paymentProviderFeeAmount()).isZero();
        assertThat(store.facts.get(1).refundProviderFeeAmount()).isEqualTo(11L);
        assertThat(store.facts).allMatch(PaymentFeeService.Fact::estimated);
    }

    @Test
    void balanceSettlementDoesNotCreateProviderFee() {
        MemoryStore store = new MemoryStore();
        store.rate = new PaymentFeeService.FeeRate(RATE, BigDecimal.ONE, BigDecimal.ONE);

        assertThat(service(store).recordEstimated(new PaymentFeeService.EstimateCommand(
                "balance-1", BillEntryType.PAYMENT, PaymentProvider.ALIPAY,
                PaymentProduct.ALIPAY_PAGE, TENANT, USER, null, null, null,
                10_001L, "CNY", "BALANCE", OCCURRED_AT))).isFalse();
        assertThat(store.facts).isEmpty();
    }

    @Test
    void actualBillReversesEstimateWithoutTreatingRefundPrincipalAsFeeAndReplaysByHash() {
        MemoryStore store = new MemoryStore();
        store.rate = new PaymentFeeService.FeeRate(RATE,
                new BigDecimal("0.006000"), new BigDecimal("0.001000"));
        PaymentFeeService service = service(store);
        service.recordEstimated(new PaymentFeeService.EstimateCommand(
                "refund-1", BillEntryType.REFUND, PaymentProvider.WECHAT_PAY,
                PaymentProduct.WECHAT_NATIVE, TENANT, USER, null, PAYMENT, REFUND,
                10_001L, "CNY", "EXTERNAL", OCCURRED_AT));

        PaymentAdapter.BillEntry bill = new PaymentAdapter.BillEntry(
                "wx-bill-refund-1", BillEntryType.REFUND, null, "refund-1",
                10_001L, "CNY", 8L, "CNY", ProviderOutcome.SUCCEEDED, OCCURRED_AT);
        assertThat(service.recordActualBill(
                PaymentProvider.WECHAT_PAY, "a".repeat(64), List.of(bill))).isEqualTo(1);
        assertThat(service.recordActualBill(
                PaymentProvider.WECHAT_PAY, "a".repeat(64), List.of(bill))).isZero();

        assertThat(store.facts).hasSize(3);
        PaymentFeeService.Fact reversal = store.facts.get(1);
        PaymentFeeService.Fact actual = store.facts.get(2);
        assertThat(reversal.sign()).isEqualTo(-1);
        assertThat(reversal.refundProviderFeeAmount()).isEqualTo(11L);
        assertThat(actual.sign()).isEqualTo(1);
        assertThat(actual.refundProviderFeeAmount()).isEqualTo(8L);
        assertThat(actual.paymentProviderFeeAmount()).isZero();
        assertThat(actual.refundProviderFeeAmount()).isNotEqualTo(bill.amount());
        assertThat(actual.estimated()).isFalse();
    }

    @Test
    void unknownBillFeeKeepsExistingEstimate() {
        MemoryStore store = new MemoryStore();
        store.rate = new PaymentFeeService.FeeRate(RATE,
                new BigDecimal("0.006000"), BigDecimal.ZERO);
        PaymentFeeService service = service(store);
        service.recordEstimated(new PaymentFeeService.EstimateCommand(
                "pay-unknown", BillEntryType.PAYMENT, PaymentProvider.WECHAT_PAY,
                PaymentProduct.WECHAT_NATIVE, TENANT, USER, null, PAYMENT, null,
                10_001L, "CNY", "EXTERNAL", OCCURRED_AT));

        PaymentAdapter.BillEntry bill = new PaymentAdapter.BillEntry(
                "wx-bill-unknown", BillEntryType.PAYMENT, "pay-unknown", null,
                10_001L, "CNY", 0L, "CNY", false,
                ProviderOutcome.SUCCEEDED, OCCURRED_AT);

        assertThat(service.recordActualBill(
                PaymentProvider.WECHAT_PAY, "b".repeat(64), List.of(bill))).isZero();
        assertThat(store.facts).singleElement().matches(PaymentFeeService.Fact::estimated);
    }

    @Test
    void actualBillReadsEstimatesInFixedSizeBatches() {
        MemoryStore store = new MemoryStore();
        List<PaymentAdapter.BillEntry> bills = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            bills.add(new PaymentAdapter.BillEntry(
                    "wx-bill-" + index, BillEntryType.PAYMENT, "pay-" + index, null,
                    100L, "CNY", 1L, "CNY", true,
                    ProviderOutcome.SUCCEEDED, OCCURRED_AT));
        }

        assertThat(service(store).recordActualBill(
                PaymentProvider.WECHAT_PAY, "c".repeat(64), bills)).isEqualTo(501);
        assertThat(store.estimateBatchSizes).containsExactly(500, 1);
    }

    private PaymentFeeService service(MemoryStore store) {
        return new PaymentFeeService(Clock.fixed(OCCURRED_AT.plusSeconds(60), ZoneOffset.UTC), store);
    }

    static final class MemoryStore implements PaymentFeeService.Store {
        PaymentFeeService.FeeRate rate;
        final Map<String, String> receipts = new HashMap<>();
        final List<PaymentFeeService.Fact> facts = new ArrayList<>();
        final List<Integer> estimateBatchSizes = new ArrayList<>();

        @Override
        public PaymentFeeService.EstimateCommand paymentEstimate(
                UUID paymentTransactionId, String settlementMethod, Instant occurredAt) {
            return null;
        }

        @Override
        public PaymentFeeService.EstimateCommand refundEstimate(
                UUID refundId, String settlementMethod, Instant occurredAt) {
            return null;
        }

        @Override
        public PaymentFeeService.FeeRate findRate(
                PaymentProvider provider, PaymentProduct product, Instant occurredAt) {
            return rate;
        }

        @Override
        public PaymentFeeService.Claim claim(String eventId, String payloadHash) {
            String existing = receipts.putIfAbsent(eventId, payloadHash);
            if (existing == null) return PaymentFeeService.Claim.CLAIMED;
            return existing.equals(payloadHash)
                    ? PaymentFeeService.Claim.REPLAYED : PaymentFeeService.Claim.CONFLICT;
        }

        @Override
        public Map<String, PaymentFeeService.Fact> findEstimates(
                List<PaymentAdapter.BillEntry> entries) {
            estimateBatchSizes.add(entries.size());
            Map<String, PaymentFeeService.Fact> result = new HashMap<>();
            facts.stream()
                    .filter(PaymentFeeService.Fact::estimated)
                    .forEach(fact -> result.put(
                            fact.entryType().name() + ":" + fact.businessNo(), fact));
            return result;
        }

        @Override
        public PaymentFeeService.BusinessSnapshot findBusiness(
                BillEntryType type, String businessNo, PaymentProvider provider) {
            return null;
        }

        @Override
        public void insert(List<PaymentFeeService.Fact> values) {
            facts.addAll(values);
        }
    }
}
