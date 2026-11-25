package com.yuzhihuihe.apiplatform.platform.payment.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WechatPayAdapterContractTest {

    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(900);
    private static final String APP_ID = "wx-test-app";
    private static final String MERCHANT_ID = "1900000109";
    private static final URI NOTIFY_URL = URI.create("https://pay.example.com/wechat/callback");
    private static final URI REFUND_NOTIFY_URL =
            URI.create("https://pay.example.com/refunds/wechat/callback");

    private FakeWechatPayGateway gateway;
    private WechatPayAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        gateway = new FakeWechatPayGateway();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        adapter = new WechatPayAdapter(
                gateway,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                APP_ID,
                MERCHANT_ID,
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                generator.generateKeyPair().getPublic(),
                "PUB_KEY_ID_01142321344320250709",
                NOTIFY_URL,
                "wechat-fingerprint");
    }

    @Test
    void nativePrepayUsesCnyCentsAndMerchantOrderNumberAndReturnsQrCode() {
        gateway.nativePrepay = new WechatPayGateway.NativePrepay(
                "PREPAY_SUCCESS", "weixin://wxpay/bizpayurl?pr=test-token");

        PaymentAdapter.PaymentCreation result = adapter.createPayment(command(
                "pay_native_1", 199, PaymentProduct.WECHAT_NATIVE));

        assertThat(gateway.prepayCommand).satisfies(request -> {
            assertThat(request.appId()).isEqualTo(APP_ID);
            assertThat(request.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(request.merchantOrderNo()).isEqualTo("pay_native_1");
            assertThat(request.amount()).isEqualTo(199);
            assertThat(request.currency()).isEqualTo("CNY");
            assertThat(request.notifyUrl()).isEqualTo(NOTIFY_URL);
        });
        assertThat(result.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(result.action().type()).isEqualTo(PaymentActionType.QR_CODE);
        assertThat(result.action().qrContent())
                .isEqualTo("weixin://wxpay/bizpayurl?pr=test-token");
        assertThat(result.action().targetUrl()).isNull();
    }

    @Test
    void h5PrepayReturnsHttpsRedirect() {
        gateway.h5Prepay = new WechatPayGateway.H5Prepay(
                "PREPAY_SUCCESS", URI.create("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb"));

        PaymentAdapter.PaymentCreation result = adapter.createPayment(command(
                "pay_h5_1", 2990, PaymentProduct.WECHAT_H5));

        assertThat(gateway.prepayCommand.merchantOrderNo()).isEqualTo("pay_h5_1");
        assertThat(result.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(result.action().type()).isEqualTo(PaymentActionType.REDIRECT_URL);
        assertThat(result.action().targetUrl())
                .isEqualTo(URI.create("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb"));
        assertThat(result.action().qrContent()).isNull();
    }

    @Test
    void mapsQueryCloseRefundRefundQueryAndBillToCommonOutcomes() {
        gateway.payment = payment(WechatPayGateway.PaymentStatus.SUCCESS, "pay_query_1");
        gateway.closed = payment(WechatPayGateway.PaymentStatus.CLOSED, "pay_close_1");
        gateway.refund = new WechatPayGateway.RefundResult(
                WechatPayGateway.RefundStatus.PROCESSING,
                "refund_1", "wx-refund-1", 399, "PROCESSING");
        gateway.refundQuery = new WechatPayGateway.RefundResult(
                WechatPayGateway.RefundStatus.SUCCESS,
                "refund_1", "wx-refund-1", 399, "SUCCESS");
        LocalDate billDate = LocalDate.of(2030, 1, 1);
        gateway.bill = new WechatPayGateway.BillResult(
                billDate,
                "a".repeat(64),
                List.of(new WechatPayGateway.BillLine(
                        "wx-bill-1", BillEntryType.PAYMENT, "pay_query_1", null,
                        399, "CNY", 2, "CNY",
                        WechatPayGateway.PaymentStatus.SUCCESS, NOW)));

        assertThat(adapter.queryPayment(paymentQuery("pay_query_1")).outcome())
                .isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(adapter.closePayment(paymentQuery("pay_close_1")).outcome())
                .isEqualTo(ProviderOutcome.CLOSED);
        PaymentAdapter.ProviderRefund created = adapter.createRefund(new PaymentAdapter.RefundCommand(
                "pay_query_1", "wx-transaction-1", "refund_1", 999, 399, "CNY",
                "requested-by-customer", REFUND_NOTIFY_URL));
        assertThat(gateway.refundRequest).satisfies(request -> {
            assertThat(request.merchantOrderNo()).isEqualTo("pay_query_1");
            assertThat(request.merchantRefundNo()).isEqualTo("refund_1");
            assertThat(request.totalAmount()).isEqualTo(999);
            assertThat(request.amount()).isEqualTo(399);
            assertThat(request.currency()).isEqualTo("CNY");
            assertThat(request.notifyUrl()).isEqualTo(REFUND_NOTIFY_URL);
        });
        assertThat(created.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(adapter.queryRefund(new PaymentAdapter.RefundQuery(
                "pay_query_1", "wx-transaction-1", "refund_1", 399, "CNY")).outcome())
                .isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(adapter.downloadBill(billDate)).satisfies(download -> {
            assertThat(download.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(download.sha256()).isEqualTo("a".repeat(64));
            assertThat(download.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.providerRecordId()).isEqualTo("wx-bill-1");
                assertThat(entry.feeAmount()).isEqualTo(2);
                assertThat(entry.feeCurrency()).isEqualTo("CNY");
                assertThat(entry.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            });
        });
    }

    @Test
    void refundQueryRejectsProviderAmountThatDiffersFromDurableFacts() {
        gateway.refundQuery = new WechatPayGateway.RefundResult(
                WechatPayGateway.RefundStatus.SUCCESS,
                "refund_1", "wx-refund-1", 400, "SUCCESS");

        assertThatThrownBy(() -> adapter.queryRefund(new PaymentAdapter.RefundQuery(
                "pay_query_1", "wx-transaction-1", "refund_1", 399, "CNY")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_wechat_payment_response");
    }

    @Test
    void timeoutIsUncertainAndProviderRejectionIsFailed() {
        gateway.failure = new WechatPayGateway.GatewayException(
                WechatPayGateway.FailureKind.TIMEOUT, "TIMEOUT");
        assertThat(adapter.queryPayment(paymentQuery("pay_timeout"))).satisfies(payment -> {
            assertThat(payment.outcome()).isEqualTo(ProviderOutcome.UNCERTAIN);
            assertThat(payment.providerErrorCode()).isEqualTo("TIMEOUT");
        });

        gateway.failure = new WechatPayGateway.GatewayException(
                WechatPayGateway.FailureKind.REJECTED, "ORDER_CLOSED");
        assertThat(adapter.queryPayment(paymentQuery("pay_rejected"))).satisfies(payment -> {
            assertThat(payment.outcome()).isEqualTo(ProviderOutcome.FAILED);
            assertThat(payment.providerErrorCode()).isEqualTo("ORDER_CLOSED");
        });
    }

    @Test
    void notPayRemainsPendingUntilThePaymentExpiresOrIsClosed() {
        gateway.payment = payment(WechatPayGateway.PaymentStatus.NOTPAY, "pay_not_paid");

        assertThat(adapter.queryPayment(paymentQuery("pay_not_paid")).outcome())
                .isEqualTo(ProviderOutcome.PENDING);
    }

    @Test
    void succeededResponsesMustMatchMerchantAppAmountCurrencyAndIdentifiers() {
        gateway.payment = new WechatPayGateway.PaymentResult(
                WechatPayGateway.PaymentStatus.SUCCESS,
                "wrong-app", MERCHANT_ID, "pay_invalid", "wx-transaction-1",
                399, "CNY", NOW, "SUCCESS");

        assertThatThrownBy(() -> adapter.queryPayment(paymentQuery("pay_invalid")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_wechat_payment_response")
                .hasMessageNotContaining("wrong-app");
    }

    @Test
    void succeededResponseMustMatchTheAmountCapturedAtPrepay() {
        gateway.nativePrepay = new WechatPayGateway.NativePrepay(
                "PREPAY_SUCCESS", "weixin://wxpay/bizpayurl?pr=amount-check");
        adapter.createPayment(command("pay_amount_check", 399, PaymentProduct.WECHAT_NATIVE));
        gateway.payment = new WechatPayGateway.PaymentResult(
                WechatPayGateway.PaymentStatus.SUCCESS,
                APP_ID, MERCHANT_ID, "pay_amount_check", "wx-transaction-amount-check",
                400, "CNY", NOW, "SUCCESS");

        assertThatThrownBy(() -> adapter.queryPayment(paymentQuery("pay_amount_check")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_wechat_payment_response");
    }

    @Test
    void unexpectedGatewayExceptionsDoNotExposeSecretsOrProviderUrls() {
        gateway.unexpected = new RuntimeException(
                "request={api_v3_key:secret} https://api.mch.weixin.qq.com cert=private-key");

        PaymentAdapter.ProviderPayment result = adapter.queryPayment(
                paymentQuery("pay_safe_error"));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.UNCERTAIN);
        assertThat(result.toString())
                .doesNotContain("secret", "api.mch.weixin.qq.com", "private-key", "request=");
    }

    @Test
    void adapterRegistersOneProductionInstanceForBothWechatProducts() {
        assertThat(adapter.provider()).isEqualTo(PaymentProvider.WECHAT_PAY);
        assertThat(adapter.environment()).isEqualTo(PaymentEnvironment.PRODUCTION);
        assertThat(adapter.products())
                .containsExactlyInAnyOrder(PaymentProduct.WECHAT_NATIVE, PaymentProduct.WECHAT_H5);
        assertThat(adapter.configuration().healthy()).isTrue();
        assertThat(adapter.configuration().credentialFingerprint())
                .isEqualTo("wechat-fingerprint");
    }

    private PaymentAdapter.PaymentCommand command(
            String transactionNo, long amount, PaymentProduct product) {
        return new PaymentAdapter.PaymentCommand(
                transactionNo, "ord_" + transactionNo, amount, "CNY", product,
                NOTIFY_URL, "203.0.113.10", EXPIRES_AT);
    }

    private PaymentAdapter.PaymentQuery paymentQuery(String transactionNo) {
        return new PaymentAdapter.PaymentQuery(transactionNo, 399, "CNY");
    }

    private WechatPayGateway.PaymentResult payment(
            WechatPayGateway.PaymentStatus status, String merchantOrderNo) {
        return new WechatPayGateway.PaymentResult(
                status, APP_ID, MERCHANT_ID, merchantOrderNo, "wx-transaction-1",
                399, "CNY", status == WechatPayGateway.PaymentStatus.SUCCESS ? NOW : null,
                status.name());
    }

    private static final class FakeWechatPayGateway implements WechatPayGateway {
        private PrepayCommand prepayCommand;
        private RefundRequest refundRequest;
        private NativePrepay nativePrepay;
        private H5Prepay h5Prepay;
        private PaymentResult payment;
        private PaymentResult closed;
        private RefundResult refund;
        private RefundResult refundQuery;
        private BillResult bill;
        private GatewayException failure;
        private RuntimeException unexpected;

        @Override
        public NativePrepay prepayNative(PrepayCommand request) {
            prepayCommand = request;
            failIfConfigured();
            return nativePrepay;
        }

        @Override
        public H5Prepay prepayH5(PrepayCommand request) {
            prepayCommand = request;
            failIfConfigured();
            return h5Prepay;
        }

        @Override
        public PaymentResult queryPayment(String merchantOrderNo) {
            failIfConfigured();
            return payment;
        }

        @Override
        public PaymentResult closePayment(String merchantOrderNo) {
            failIfConfigured();
            return closed;
        }

        @Override
        public RefundResult createRefund(RefundRequest request) {
            refundRequest = request;
            failIfConfigured();
            return refund;
        }

        @Override
        public RefundResult queryRefund(String merchantRefundNo) {
            failIfConfigured();
            return refundQuery;
        }

        @Override
        public BillResult downloadBill(LocalDate billDate) {
            failIfConfigured();
            return bill;
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
            if (unexpected != null) {
                throw unexpected;
            }
        }
    }
}
