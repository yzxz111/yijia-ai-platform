package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeFastpayRefundQueryModel;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AlipaySdkGatewayTest {

    @Test
    void onlyDeterministicProviderFailuresAreTerminal() {
        assertThat(AlipaySdkGateway.classifyProviderFailure("ACQ.TRADE_NOT_EXIST"))
                .isEqualTo(AlipayGateway.FailureKind.NOT_FOUND);
        assertThat(AlipaySdkGateway.classifyProviderFailure("ACQ.TRADE_HAS_CLOSE"))
                .isEqualTo(AlipayGateway.FailureKind.REJECTED);
        assertThat(AlipaySdkGateway.classifyProviderFailure("ACQ.BUYER_BALANCE_NOT_ENOUGH"))
                .isEqualTo(AlipayGateway.FailureKind.REJECTED);

        assertThat(AlipaySdkGateway.classifyProviderFailure("SYSTEM_ERROR"))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyProviderFailure("ISP.UNKNOWN_ERROR"))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyProviderFailure("ACQ.SYSTEM_ERROR"))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyProviderFailure("429"))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyProviderFailure("UNRECOGNIZED_PROVIDER_CODE"))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
    }

    @Test
    void transientBillHttpFailuresRemainUncertain() {
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(408))
                .isEqualTo(AlipayGateway.FailureKind.TIMEOUT);
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(504))
                .isEqualTo(AlipayGateway.FailureKind.TIMEOUT);
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(425))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(429))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(500))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(404))
                .isEqualTo(AlipayGateway.FailureKind.REJECTED);
        assertThat(AlipaySdkGateway.classifyBillHttpFailure(302))
                .isEqualTo(AlipayGateway.FailureKind.INTERNAL);
    }

    @Test
    void parsesCompressedTradeBillIntoTypedPaymentAndRefundLines() throws Exception {
        byte[] detail = getClass().getResourceAsStream(
                        "/payment/alipay/trade-detail.csv")
                .readAllBytes();
        byte[] archive = zip("2088000000000001_20260731_业务明细.csv", detail);

        var lines = AlipaySdkGateway.parseBill(archive, 1024 * 1024);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .extracting(
                        AlipayGateway.BillLine::providerRecordId,
                        AlipayGateway.BillLine::entryType,
                        AlipayGateway.BillLine::merchantOrderNo,
                        AlipayGateway.BillLine::amount,
                        AlipayGateway.BillLine::currency,
                        AlipayGateway.BillLine::feeAmount,
                        AlipayGateway.BillLine::feeCurrency,
                        AlipayGateway.BillLine::status)
                .containsExactly(
                        "2026073122001000000000000001", BillEntryType.PAYMENT,
                        "pay_alipay_bill", "3.99", "CNY", "0.02", "CNY",
                        AlipayGateway.PaymentStatus.SUCCESS);
        assertThat(lines.get(1))
                .extracting(
                        AlipayGateway.BillLine::providerRecordId,
                        AlipayGateway.BillLine::entryType,
                        AlipayGateway.BillLine::merchantRefundNo,
                        AlipayGateway.BillLine::amount,
                        AlipayGateway.BillLine::feeAmount,
                        AlipayGateway.BillLine::feeCurrency,
                        AlipayGateway.BillLine::status)
                .containsExactly(
                        "ref_alipay_bill", BillEntryType.REFUND,
                        "ref_alipay_bill", "1.00", "0.00", "CNY",
                        AlipayGateway.PaymentStatus.SUCCESS);
        assertThat(lines).allSatisfy(line -> assertThat(
                line.occurredAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 7, 31)));
    }

    @Test
    void rejectsCompressedBillThatExpandsPastTheConfiguredBoundary() throws Exception {
        byte[] archive = zip(
                "2088000000000001_20260731_业务明细.csv",
                "x".repeat(128).getBytes(Charset.forName("GB18030")));

        assertThatThrownBy(() -> AlipaySdkGateway.parseBill(archive, 64))
                .isInstanceOfSatisfying(AlipayGateway.GatewayException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(AlipayGateway.FailureKind.REJECTED);
                    assertThat(exception.providerCode()).isEqualTo("BILL_TOO_LARGE");
                });
    }

    @Test
    void closePaymentReturnsThePostCloseQueryState() throws Exception {
        Map<String, AlipayGateway.PaymentStatus> expected = new LinkedHashMap<>();
        expected.put("TRADE_SUCCESS", AlipayGateway.PaymentStatus.SUCCESS);
        expected.put("TRADE_CLOSED", AlipayGateway.PaymentStatus.CLOSED);
        expected.put("WAIT_BUYER_PAY", AlipayGateway.PaymentStatus.WAIT_BUYER_PAY);
        expected.put("UNRECOGNIZED", AlipayGateway.PaymentStatus.UNKNOWN);
        Map<String, AlipayGateway.PaymentStatus> actual = new LinkedHashMap<>();

        for (String providerStatus : expected.keySet()) {
            AlipayClient client = mock(AlipayClient.class);
            when(client.execute(any(AlipayTradeCloseRequest.class)))
                    .thenReturn(successfulClose());
            when(client.execute(any(AlipayTradeQueryRequest.class)))
                    .thenReturn(successfulQuery(providerStatus));
            try (AlipaySdkGateway gateway = gateway(client)) {
                actual.put(providerStatus, gateway.closePayment("pay_close_1").status());
            }
        }

        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @Test
    void closePaymentQueriesWhenProviderReportsTradeAlreadyFinished() throws Exception {
        AlipayClient client = mock(AlipayClient.class);
        AlipayTradeCloseResponse closeResponse = new AlipayTradeCloseResponse();
        closeResponse.setCode("40004");
        closeResponse.setSubCode("ACQ.TRADE_HAS_FINISHED");
        closeResponse.setOutTradeNo("pay_close_1");
        when(client.execute(any(AlipayTradeCloseRequest.class))).thenReturn(closeResponse);
        when(client.execute(any(AlipayTradeQueryRequest.class)))
                .thenReturn(successfulQuery("TRADE_SUCCESS"));

        try (AlipaySdkGateway gateway = gateway(client)) {
            assertThat(gateway.closePayment("pay_close_1").status())
                    .isEqualTo(AlipayGateway.PaymentStatus.SUCCESS);
        }

        verify(client).execute(any(AlipayTradeQueryRequest.class));
    }

    @Test
    void refundQueryUsesDurableFactsOnAFreshGateway() throws Exception {
        AlipayClient client = mock(AlipayClient.class);
        AlipayTradeFastpayRefundQueryResponse response =
                new AlipayTradeFastpayRefundQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo("pay_refund_1");
        response.setTradeNo("ali-trade-1");
        response.setOutRequestNo("refund_1");
        response.setRefundAmount("3.99");
        response.setRefundStatus("REFUND_SUCCESS");
        when(client.execute(any(AlipayTradeFastpayRefundQueryRequest.class)))
                .thenReturn(response);

        try (AlipaySdkGateway gateway = gateway(client)) {
            AlipayGateway.RefundResult result = gateway.queryRefund(
                    new AlipayGateway.RefundQueryRequest(
                            "2021000000000001", "2088000000000001",
                            "pay_refund_1", "ali-trade-1", "refund_1", "3.99", "CNY"));

            assertThat(result.status()).isEqualTo(AlipayGateway.RefundStatus.SUCCESS);
            assertThat(result.merchantOrderNo()).isEqualTo("pay_refund_1");
            assertThat(result.merchantRefundNo()).isEqualTo("refund_1");
            assertThat(result.tradeNo()).isEqualTo("ali-trade-1");
            assertThat(result.refundAmount()).isEqualTo("3.99");
            assertThat(result.currency()).isEqualTo("CNY");
        }

        ArgumentCaptor<AlipayTradeFastpayRefundQueryRequest> captor =
                ArgumentCaptor.forClass(AlipayTradeFastpayRefundQueryRequest.class);
        verify(client).execute(captor.capture());
        AlipayTradeFastpayRefundQueryModel model =
                (AlipayTradeFastpayRefundQueryModel) captor.getValue().getBizModel();
        assertThat(model.getOutTradeNo()).isEqualTo("pay_refund_1");
        assertThat(model.getTradeNo()).isEqualTo("ali-trade-1");
        assertThat(model.getOutRequestNo()).isEqualTo("refund_1");
    }

    private AlipaySdkGateway gateway(AlipayClient client) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var applicationKeys = generator.generateKeyPair();
        var alipayKeys = generator.generateKeyPair();
        AlipaySdkGateway gateway = new AlipaySdkGateway(
                URI.create("https://openapi.alipay.com/gateway.do"),
                "2021000000000001", "2088000000000001",
                applicationKeys.getPrivate(), alipayKeys.getPublic(), "UTF-8", "RSA2",
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        ReflectionTestUtils.setField(gateway, "client", client);
        return gateway;
    }

    private byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private AlipayTradeCloseResponse successfulClose() {
        AlipayTradeCloseResponse response = new AlipayTradeCloseResponse();
        response.setCode("10000");
        response.setOutTradeNo("pay_close_1");
        return response;
    }

    private AlipayTradeQueryResponse successfulQuery(String providerStatus) {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo("pay_close_1");
        response.setTradeNo("ali-trade-1");
        response.setTradeStatus(providerStatus);
        response.setTotalAmount("3.99");
        response.setPayCurrency("CNY");
        if ("TRADE_SUCCESS".equals(providerStatus)) {
            response.setSendPayDate(new Date(1_893_456_000_000L));
        }
        return response;
    }
}
