package com.yuzhihuihe.apiplatform.platform.payment.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import org.junit.jupiter.api.Test;

class WechatPaySdkGatewayTest {

    @Test
    void serviceStatusClassificationKeepsTransientProviderFailuresUncertain() {
        assertThat(WechatPaySdkGateway.classifyServiceStatus(400))
                .isEqualTo(WechatPayGateway.FailureKind.REJECTED);
        assertThat(WechatPaySdkGateway.classifyServiceStatus(404))
                .isEqualTo(WechatPayGateway.FailureKind.NOT_FOUND);
        assertThat(WechatPaySdkGateway.classifyServiceStatus(408))
                .isEqualTo(WechatPayGateway.FailureKind.INTERNAL);
        assertThat(WechatPaySdkGateway.classifyServiceStatus(425))
                .isEqualTo(WechatPayGateway.FailureKind.INTERNAL);
        assertThat(WechatPaySdkGateway.classifyServiceStatus(429))
                .isEqualTo(WechatPayGateway.FailureKind.INTERNAL);
        assertThat(WechatPaySdkGateway.classifyServiceStatus(500))
                .isEqualTo(WechatPayGateway.FailureKind.INTERNAL);
    }

    @Test
    void billDownloadRejectsBytesBeyondTheConfiguredBoundary() {
        InputStream oversized = new InputStream() {
            private int remaining = 9;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }
        };

        assertThatThrownBy(() -> WechatPaySdkGateway.readBounded(oversized, 8))
                .isInstanceOfSatisfying(WechatPayGateway.GatewayException.class, exception -> {
                    assertThat(exception.kind())
                            .isEqualTo(WechatPayGateway.FailureKind.REJECTED);
                    assertThat(exception.providerCode()).isEqualTo("BILL_TOO_LARGE");
                });
    }

    @Test
    void parsesVerifiedTradeBillIntoTypedPaymentAndRefundLines() throws Exception {
        byte[] fixture = getClass().getResourceAsStream(
                        "/payment/wechat/trade-bill.csv")
                .readAllBytes();

        var lines = WechatPaySdkGateway.parseBill(fixture);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .extracting(
                        WechatPayGateway.BillLine::providerRecordId,
                        WechatPayGateway.BillLine::entryType,
                        WechatPayGateway.BillLine::merchantOrderNo,
                        WechatPayGateway.BillLine::amount,
                        WechatPayGateway.BillLine::currency,
                        WechatPayGateway.BillLine::feeAmount,
                        WechatPayGateway.BillLine::feeCurrency,
                        WechatPayGateway.BillLine::status)
                .containsExactly(
                        "4200000001202607310000000001", BillEntryType.PAYMENT,
                        "pay_wechat_bill", 399L, "CNY", 2L, "CNY",
                        WechatPayGateway.PaymentStatus.SUCCESS);
        assertThat(lines.get(1))
                .extracting(
                        WechatPayGateway.BillLine::providerRecordId,
                        WechatPayGateway.BillLine::entryType,
                        WechatPayGateway.BillLine::merchantRefundNo,
                        WechatPayGateway.BillLine::amount,
                        WechatPayGateway.BillLine::feeAmount,
                        WechatPayGateway.BillLine::feeCurrency,
                        WechatPayGateway.BillLine::status)
                .containsExactly(
                        "5030000000202607310000000001", BillEntryType.REFUND,
                        "ref_wechat_bill", 100L, 2L, "CNY",
                        WechatPayGateway.PaymentStatus.SUCCESS);
        assertThat(lines).allSatisfy(line -> assertThat(
                line.occurredAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 7, 31)));
        assertThat(lines).allMatch(WechatPayGateway.BillLine::feeKnown);
    }

    @Test
    void distinguishesExplicitZeroFeeFromMissingFeeColumn() throws Exception {
        String fixture = new String(getClass().getResourceAsStream(
                "/payment/wechat/trade-bill.csv").readAllBytes(), StandardCharsets.UTF_8);
        String explicitZero = fixture.replace("`0.02,`0.60%", "`0.00,`0.60%");
        var zeroLines = WechatPaySdkGateway.parseBill(explicitZero.getBytes(StandardCharsets.UTF_8));
        assertThat(zeroLines).allSatisfy(line -> {
            assertThat(line.feeAmount()).isZero();
            assertThat(line.feeKnown()).isTrue();
        });

        String missingFee = removeColumn(fixture, "手续费");
        var unknownLines = WechatPaySdkGateway.parseBill(missingFee.getBytes(StandardCharsets.UTF_8));
        assertThat(unknownLines).allSatisfy(line -> {
            assertThat(line.feeAmount()).isZero();
            assertThat(line.feeKnown()).isFalse();
        });
    }

    @Test
    void rejectsTradeBillWithoutRequiredProviderColumns() {
        byte[] malformed = "交易时间,商户订单号\n2026-07-31 12:00:00,pay_1\n"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> WechatPaySdkGateway.parseBill(malformed))
                .isInstanceOfSatisfying(WechatPayGateway.GatewayException.class, exception -> {
                    assertThat(exception.kind())
                            .isEqualTo(WechatPayGateway.FailureKind.REJECTED);
                    assertThat(exception.providerCode()).isEqualTo("BILL_INVALID_FORMAT");
                });
    }

    private static String removeColumn(String csv, String columnName) {
        String[] lines = csv.split("\\R", -1);
        String[] headers = lines[0].split(",", -1);
        int removed = java.util.Arrays.asList(headers).indexOf(columnName);
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String[] values = line.split(",", -1);
            for (int index = 0; index < values.length; index++) {
                if (index == removed) continue;
                if (index > 0 && !(index == removed + 1)) result.append(',');
                result.append(values[index]);
            }
            result.append('\n');
        }
        return result.toString();
    }
}
