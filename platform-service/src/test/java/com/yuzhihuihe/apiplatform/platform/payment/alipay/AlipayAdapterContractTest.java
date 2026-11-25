package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlipayAdapterContractTest {

    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(900);
    private static final String APP_ID = "2021000000000001";
    private static final String SELLER_ID = "2088000000000001";
    private static final URI GATEWAY_URL = URI.create("https://openapi.alipay.com/gateway.do");
    private static final URI NOTIFY_URL = URI.create("https://pay.example.com/alipay/callback");
    private static final URI RETURN_URL = URI.create("https://pay.example.com/orders/return");
    private static final String VALID_FORM = """
            <form method="post" action="https://openapi.alipay.com/gateway.do">
              <input type="hidden" name="app_id" value="2021000000000001">
              <input type="hidden" name="biz_content" value="{}">
              <input type="hidden" name="sign" value="safe-signature">
            </form>
            """;

    private FakeAlipayGateway gateway;
    private AlipayAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        gateway = new FakeAlipayGateway();
        gateway.formHtml = VALID_FORM;
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        adapter = new AlipayAdapter(
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                APP_ID,
                SELLER_ID,
                generator.generateKeyPair().getPublic(),
               GATEWAY_URL,
               NOTIFY_URL,
               RETURN_URL,
               "alipay-fingerprint",
               transactionNo -> new AlipayAdapter.ExpectedPayment(399, "CNY"));
    }

    @Test
    void pageAndWapPaymentsUseTransactionNumberAndExactDecimalCnyAmount() {
        PaymentAdapter.PaymentCreation page = adapter.createPayment(
                command("pay_page_1", 1, PaymentProduct.ALIPAY_PAGE));
        assertThat(gateway.pageRequest).satisfies(request -> {
            assertThat(request.appId()).isEqualTo(APP_ID);
            assertThat(request.sellerId()).isEqualTo(SELLER_ID);
            assertThat(request.merchantOrderNo()).isEqualTo("pay_page_1");
            assertThat(request.totalAmount()).isEqualTo("0.01");
            assertThat(request.currency()).isEqualTo("CNY");
            assertThat(request.notifyUrl()).isEqualTo(NOTIFY_URL);
            assertThat(request.returnUrl()).isEqualTo(RETURN_URL);
        });
        assertStructuredForm(page);

        PaymentAdapter.PaymentCreation wap = adapter.createPayment(
                command("pay_wap_1", 123_456_789, PaymentProduct.ALIPAY_WAP));
        assertThat(gateway.wapRequest.merchantOrderNo()).isEqualTo("pay_wap_1");
        assertThat(gateway.wapRequest.totalAmount()).isEqualTo("1234567.89");
        assertStructuredForm(wap);
    }

    @Test
    void formFieldsAreImmutableAndRawHtmlNeverEscapesTheAdapter() {
        PaymentAdapter.PaymentCreation result = adapter.createPayment(
                command("pay_form_1", 399, PaymentProduct.ALIPAY_PAGE));

        assertThat(result.toString()).doesNotContain("<form");
        assertThatThrownBy(() -> result.action().fields().put("app_id", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnsafeOrUnexpectedSdkFormMarkup() {
        List<String> invalidForms = List.of(
                "<form method=\"get\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"http://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://evil.example/gateway.do\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do?changed=1\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\""
                        + " target=\"_blank\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\""
                        + " onsubmit=\"steal()\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\""
                        + " data-extra=\"unexpected\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"y\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"text\" name=\"app_id\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"hidden\" name=\"unknown\" value=\"x\"></form>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<input type=\"hidden\" name=\"app_id\" value=\"x\"></form>"
                        + "<script>document.forms[0].submit()</script>",
                "<form method=\"post\" action=\"https://openapi.alipay.com/gateway.do\">"
                        + "<div><input type=\"hidden\" name=\"app_id\" value=\"x\"></div></form>",
                VALID_FORM + VALID_FORM);

        for (String invalidForm : invalidForms) {
            gateway.formHtml = invalidForm;
            assertThatThrownBy(() -> adapter.createPayment(
                    command("pay_invalid_form", 399, PaymentProduct.ALIPAY_PAGE)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("invalid_alipay_payment_response")
                    .hasMessageNotContaining("openapi.alipay.com")
                    .hasMessageNotContaining("script");
        }
    }

    @Test
    void sdkNormalizerConsumesOnlyTheExactSdkOwnedAutoSubmitScaffolding() {
        String sdkForm = """
                <form name="punchout_form" method="post"
                      action="https://openapi.alipay.com/gateway.do">
                  <input type="hidden" name="app_id" value="2021000000000001">
                  <input type="submit" value="\u7acb\u5373\u652f\u4ed8" style="display:none" >
                </form>
                <script>document.forms[0].submit();</script>
                """;

        assertThat(AlipayAdapter.normalizeSdkForm(sdkForm, GATEWAY_URL))
                .doesNotContain("type=\"submit\"", "<script>")
                .containsOnlyOnce("<form");

        List<String> nearMatches = List.of(
                sdkForm.replace("punchout_form", "other_form"),
                sdkForm.replace("gateway.do", "gateway.do?changed=1"),
                sdkForm.replace("method=\"post\"", "method=\"post\" target=\"_blank\""),
                sdkForm.replace("method=\"post\"", "method=\"post\" onsubmit=\"steal()\""),
                sdkForm.replace("name=\"app_id\"", "name=\"app_id\" data-extra=\"x\""),
                sdkForm.replace("<script>", "<script data-extra=\"x\">"),
                sdkForm.replace("document.forms[0].submit();", "window.location='https://evil.example';"),
                sdkForm.replace("display:none", "display:block"),
                sdkForm.replace(
                        "<input type=\"hidden\" name=\"app_id\" value=\"2021000000000001\">\n"
                                + "  <input type=\"submit\" value=\"\u7acb\u5373\u652f\u4ed8\""
                                + " style=\"display:none\" >",
                        "<input type=\"submit\" value=\"\u7acb\u5373\u652f\u4ed8\""
                                + " style=\"display:none\" >\n"
                                + "  <input type=\"hidden\" name=\"app_id\""
                                + " value=\"2021000000000001\">"),
                sdkForm.replace("</form>",
                        "<input type=\"hidden\" name=\"method\" value=\"late\"></form>"));
        for (String nearMatch : nearMatches) {
            assertThatThrownBy(() -> AlipayAdapter.normalizeSdkForm(nearMatch, GATEWAY_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("invalid_alipay_payment_response");
        }
    }

    @Test
    void mapsSevenCommonOperationsOnlyAfterIdentityAndAmountValidation() {
        adapter.createPayment(command("pay_query_1", 399, PaymentProduct.ALIPAY_PAGE));
        gateway.payment = payment(AlipayGateway.PaymentStatus.SUCCESS, "pay_query_1", "3.99");
        gateway.closed = payment(AlipayGateway.PaymentStatus.CLOSED, "pay_close_1", "3.99");
        gateway.refund = new AlipayGateway.RefundResult(
                AlipayGateway.RefundStatus.PROCESSING, APP_ID, SELLER_ID,
                "pay_query_1", "refund_1", "ali-trade-1", "3.99", "CNY", "10000");
        gateway.refundQuery = new AlipayGateway.RefundResult(
                AlipayGateway.RefundStatus.SUCCESS, APP_ID, SELLER_ID,
                "pay_query_1", "refund_1", "ali-trade-1", "3.99", "CNY", "10000");
        LocalDate billDate = LocalDate.of(2030, 1, 1);
        gateway.bill = new AlipayGateway.BillResult(
                billDate, APP_ID, SELLER_ID, "a".repeat(64), List.of(
                        new AlipayGateway.BillLine(
                                "ali-bill-1", BillEntryType.PAYMENT, "pay_query_1", null,
                                "3.99", "CNY", "0.02", "CNY",
                                AlipayGateway.PaymentStatus.SUCCESS, NOW)));

        assertThat(adapter.queryPayment(paymentQuery("pay_query_1")).outcome())
                .isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(adapter.closePayment(paymentQuery("pay_close_1")).outcome())
                .isEqualTo(ProviderOutcome.CLOSED);
        PaymentAdapter.ProviderRefund created = adapter.createRefund(
                new PaymentAdapter.RefundCommand(
                        "pay_query_1", "ali-trade-1", "refund_1", 999, 399, "CNY",
                        "requested-by-customer", NOTIFY_URL));
        assertThat(gateway.refundRequest).satisfies(request -> {
            assertThat(request.merchantOrderNo()).isEqualTo("pay_query_1");
            assertThat(request.merchantRefundNo()).isEqualTo("refund_1");
            assertThat(request.totalAmount()).isEqualTo("9.99");
            assertThat(request.refundAmount()).isEqualTo("3.99");
        });
        assertThat(created.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(adapter.queryRefund(refundQuery()).outcome())
                .isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(adapter.downloadBill(billDate)).satisfies(download -> {
            assertThat(download.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(download.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.amount()).isEqualTo(399);
                assertThat(entry.feeAmount()).isEqualTo(2);
                assertThat(entry.feeCurrency()).isEqualTo("CNY");
                assertThat(entry.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            });
        });
    }

    @Test
    void rejectsProviderResultsWithWrongIdentityOrCapturedAmount() {
        adapter.createPayment(command("pay_amount_check", 399, PaymentProduct.ALIPAY_PAGE));
        gateway.payment = payment(
                AlipayGateway.PaymentStatus.SUCCESS, "pay_amount_check", "4.00");

        assertThatThrownBy(() -> adapter.queryPayment(paymentQuery("pay_amount_check")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_alipay_payment_response");

        gateway.payment = new AlipayGateway.PaymentResult(
                AlipayGateway.PaymentStatus.SUCCESS, APP_ID, "wrong-seller",
                "pay_amount_check", "ali-trade-1", "3.99", "CNY", NOW, "10000");
        assertThatThrownBy(() -> adapter.queryPayment(paymentQuery("pay_amount_check")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_alipay_payment_response")
                .hasMessageNotContaining("wrong-seller");
    }

    @Test
    void paymentQueryUsesDurableFactsWithoutPriorCreateInThisAdapter() {
        PaymentAdapter.PaymentQuery query =
                new PaymentAdapter.PaymentQuery("pay_persisted_1", 399, "CNY");
        gateway.payment = payment(
                AlipayGateway.PaymentStatus.SUCCESS, "pay_persisted_1", "3.99");

        assertThat(adapter.queryPayment(query)).satisfies(payment -> {
            assertThat(payment.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(payment.amount()).isEqualTo(399);
            assertThat(payment.currency()).isEqualTo("CNY");
        });

        gateway.payment = payment(
                AlipayGateway.PaymentStatus.SUCCESS, "pay_persisted_1", "4.00");
        assertThatThrownBy(() -> adapter.queryPayment(query))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_alipay_payment_response");
    }

    @Test
    void refundQueryUsesDurableFactsWithoutPriorCreateInThisAdapter() {
        gateway.refundQuery = new AlipayGateway.RefundResult(
                AlipayGateway.RefundStatus.SUCCESS, APP_ID, SELLER_ID,
                "pay_query_1", "refund_1", "ali-trade-1", "3.99", "CNY", "10000");

        assertThat(adapter.queryRefund(refundQuery())).satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
            assertThat(result.amount()).isEqualTo(399);
        });
        assertThat(gateway.refundQueryRequest).satisfies(request -> {
            assertThat(request.merchantOrderNo()).isEqualTo("pay_query_1");
            assertThat(request.tradeNo()).isEqualTo("ali-trade-1");
            assertThat(request.merchantRefundNo()).isEqualTo("refund_1");
            assertThat(request.refundAmount()).isEqualTo("3.99");
            assertThat(request.currency()).isEqualTo("CNY");
        });
    }

    @Test
    void timeoutsAndUnexpectedGatewayFailuresAreUncertainWithoutLeakingDetails() {
        gateway.failure = new AlipayGateway.GatewayException(
                AlipayGateway.FailureKind.TIMEOUT, "SYSTEM_ERROR");
        assertThat(adapter.queryPayment(paymentQuery("pay_timeout"))).satisfies(payment -> {
            assertThat(payment.outcome()).isEqualTo(ProviderOutcome.UNCERTAIN);
            assertThat(payment.providerErrorCode()).isEqualTo("SYSTEM_ERROR");
        });

        gateway.failure = null;
        gateway.unexpected = new RuntimeException(
                "private-key=<secret> form=<html> https://openapi.alipay.com/gateway.do");
        assertThat(adapter.queryPayment(paymentQuery("pay_unexpected"))).satisfies(payment -> {
            assertThat(payment.outcome()).isEqualTo(ProviderOutcome.UNCERTAIN);
            assertThat(payment.toString())
                    .doesNotContain("secret", "<html>", "openapi.alipay.com", "private-key");
        });
    }

    @Test
    void registersOneProductionAdapterForBothAlipayProducts() {
        assertThat(adapter.provider()).isEqualTo(PaymentProvider.ALIPAY);
        assertThat(adapter.environment()).isEqualTo(PaymentEnvironment.PRODUCTION);
        assertThat(adapter.products()).containsExactlyInAnyOrder(
                PaymentProduct.ALIPAY_PAGE, PaymentProduct.ALIPAY_WAP);
        assertThat(adapter.configuration().healthy()).isTrue();
        assertThat(adapter.configuration().credentialFingerprint())
                .isEqualTo("alipay-fingerprint");
    }

    private void assertStructuredForm(PaymentAdapter.PaymentCreation result) {
        assertThat(result.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(result.action().type()).isEqualTo(PaymentActionType.HTML_FORM);
        assertThat(result.action().targetUrl()).isEqualTo(GATEWAY_URL);
        assertThat(result.action().fields()).containsOnlyKeys("app_id", "biz_content", "sign");
        assertThat(result.action().qrContent()).isNull();
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

    private AlipayGateway.PaymentResult payment(
            AlipayGateway.PaymentStatus status, String merchantOrderNo, String amount) {
        return new AlipayGateway.PaymentResult(
                status, APP_ID, SELLER_ID, merchantOrderNo, "ali-trade-1", amount, "CNY",
                status == AlipayGateway.PaymentStatus.SUCCESS ? NOW : null, "10000");
    }

    private PaymentAdapter.RefundQuery refundQuery() {
        return new PaymentAdapter.RefundQuery(
                "pay_query_1", "ali-trade-1", "refund_1", 399, "CNY");
    }

    private static final class FakeAlipayGateway implements AlipayGateway {
        private PaymentRequest pageRequest;
        private PaymentRequest wapRequest;
        private RefundRequest refundRequest;
        private RefundQueryRequest refundQueryRequest;
        private String formHtml;
        private PaymentResult payment;
        private PaymentResult closed;
        private RefundResult refund;
        private RefundResult refundQuery;
        private BillResult bill;
        private GatewayException failure;
        private RuntimeException unexpected;

        @Override
        public String createPagePayment(PaymentRequest request) {
            pageRequest = request;
            failIfConfigured();
            return formHtml;
        }

        @Override
        public String createWapPayment(PaymentRequest request) {
            wapRequest = request;
            failIfConfigured();
            return formHtml;
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
        public RefundResult queryRefund(RefundQueryRequest request) {
            refundQueryRequest = request;
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
