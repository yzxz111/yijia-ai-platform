package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class AlipayCallbackVerificationTest {

    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final String APP_ID = "2021000000000001";
    private static final String SELLER_ID = "2088000000000001";
    private static final URI GATEWAY_URL = URI.create("https://openapi.alipay.com/gateway.do");
    private static final URI NOTIFY_URL = URI.create("https://pay.example.com/alipay/callback");
    private static final URI RETURN_URL = URI.create("https://pay.example.com/orders/return");
    private static final String FORM_HTML = """
            <form method="post" action="https://openapi.alipay.com/gateway.do">
              <input type="hidden" name="app_id" value="2021000000000001">
            </form>
            """;

    @TempDir
    Path tempDir;

    private KeyPair alipayKeys;
    private AlipayAdapter adapter;
    private AtomicInteger expectationLookups;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair applicationKeys = rsaKeyPair();
        alipayKeys = rsaKeyPair();
        Files.writeString(tempDir.resolve("application-private-key.pem"),
                pem("PRIVATE KEY", applicationKeys.getPrivate().getEncoded()),
                StandardCharsets.US_ASCII);
        Files.writeString(tempDir.resolve("alipay-public-key.pem"),
                pem("PUBLIC KEY", alipayKeys.getPublic().getEncoded()),
                StandardCharsets.US_ASCII);
        expectationLookups = new AtomicInteger();
        adapter = new AlipayAdapter(
                new FormOnlyGateway(), Clock.fixed(NOW, ZoneOffset.UTC), APP_ID, SELLER_ID,
                alipayKeys.getPublic(), GATEWAY_URL, NOTIFY_URL, RETURN_URL,
                "alipay-fingerprint", transactionNo -> {
                    expectationLookups.incrementAndGet();
                    if (!"pay_callback_1".equals(transactionNo)) {
                        throw new IllegalStateException("payment_expectation_not_found");
                    }
                    return new AlipayAdapter.ExpectedPayment(399, "CNY");
                });
    }

    @Test
    void verifiesRsa2OverEveryOriginalParameterExceptSignAndSignType() throws Exception {
        Map<String, String> parameters = validParameters();
        parameters.put("opaque_provider_parameter", "included in the signature");

        PaymentAdapter.ProviderPayment result = adapter.verifyAndParseCallback(
                envelope(signedBody(parameters)));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(result.transactionNo()).isEqualTo("pay_callback_1");
        assertThat(result.externalTradeNo()).isEqualTo("2026080122000000000001");
        assertThat(result.amount()).isEqualTo(399);
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.paidAt()).isEqualTo(Instant.parse("2030-01-02T03:03:04Z"));
        assertThat(result.payloadHash()).hasSize(64);
    }

    @Test
    void domesticNotificationWithoutCurrencyUsesCnyAfterSignatureVerification() throws Exception {
        Map<String, String> parameters = validParameters();
        parameters.remove("currency");

        byte[] body = signedBody(parameters);
        PaymentAdapter.ProviderPayment result = adapter.verifyAndParseCallback(envelope(body));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(result.amount()).isEqualTo(399);
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(expectationLookups).hasValue(1);

        // Even adding the default currency to an already signed notification changes its signature.
        assertRejected(envelope((new String(body, StandardCharsets.UTF_8) + "&currency=CNY")
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(expectationLookups).hasValue(1);
    }

    @Test
    void distinctMobileAppUsesSameCallbackAndRejectsCrossApplicationTransactions() throws Exception {
        String mobileAppId = "2021000000000002";
        AlipayAdapter mobile = new AlipayAdapter(new FormOnlyGateway(), Clock.fixed(NOW, ZoneOffset.UTC),
                mobileAppId, SELLER_ID, alipayKeys.getPublic(), GATEWAY_URL, NOTIFY_URL, RETURN_URL,
                "mobile-fingerprint", number -> new AlipayAdapter.ExpectedPayment(399, "CNY"));
        var bindings = org.mockito.Mockito.mock(AlipayApplicationAdapter.ApplicationBindings.class);
        org.mockito.Mockito.when(bindings.find("pay_callback_1")).thenReturn(mobileAppId);
        var combined = new AlipayApplicationAdapter(APP_ID, adapter, mobileAppId, mobile,
                bindings, false, Clock.fixed(NOW, ZoneOffset.UTC), () -> {});
        Map<String, String> parameters = validParameters();
        parameters.put("app_id", mobileAppId);
        var callback = envelope(signedBody(parameters));
        assertThat(combined.verifyAndParseCallback(callback).outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        org.mockito.Mockito.when(bindings.find("pay_callback_1")).thenReturn(APP_ID);
        assertThat(catchThrowable(() -> combined.verifyAndParseCallback(callback)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void missingCurrencyDoesNotBypassAmountValidationAndExplicitInvalidCurrencyIsRejected()
            throws Exception {
        Map<String, String> parameters = validParameters();
        parameters.remove("currency");
        parameters.put("total_amount", "0.01");
        assertRejected(envelope(signedBody(parameters)), "amount mismatch without currency");

        for (String currency : List.of("", "USD", "cny", " CNY ")) {
            Map<String, String> changed = validParameters();
            changed.put("currency", currency);
            assertRejected(envelope(signedBody(changed)), "explicit invalid currency");
        }
    }

    @Test
    void changedUnknownParameterFailsBecauseOriginalParameterSetIsSigned() throws Exception {
        byte[] signed = signedBody(validParameters());
        String changed = new String(signed, StandardCharsets.UTF_8)
                .replace("notify_id=notify-1", "notify_id=notify-2");

        assertRejected(envelope(changed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void invalidCharsetAmountSellerAppOrderTradeStatusOrCurrencyFailsClosed() throws Exception {
        Map<String, String> mutations = Map.of(
                "charset", "ISO-8859-1",
                "total_amount", "4.00",
                "seller_id", "2088999999999999",
                "app_id", "2021999999999999",
                "out_trade_no", "",
                "trade_no", "\r\nforged-log-field",
                "trade_status", "TRADE_CLOSED",
                "currency", "USD");

        for (Map.Entry<String, String> mutation : mutations.entrySet()) {
            Map<String, String> changed = validParameters();
            changed.put(mutation.getKey(), mutation.getValue());
            assertRejected(envelope(signedBody(changed)), mutation.getKey());
        }
    }

    @Test
    void tradeFinishedIsAlsoAValidSucceededNotification() throws Exception {
        Map<String, String> parameters = validParameters();
        parameters.put("trade_status", "TRADE_FINISHED");

        assertThat(adapter.verifyAndParseCallback(envelope(signedBody(parameters))).outcome())
                .isEqualTo(ProviderOutcome.SUCCEEDED);
    }

    @Test
    void changedSignatureAndDuplicateParametersAreRejectedWithoutLeakingCallback() throws Exception {
        String signed = new String(signedBody(validParameters()), StandardCharsets.UTF_8);
        String changedSignature = signed.replaceFirst("sign=[^&]+", "sign=invalid");
        assertRejected(envelope(changedSignature.getBytes(StandardCharsets.UTF_8)));
        assertRejected(envelope((signed + "&seller_id=" + SELLER_ID)
                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void freshAdapterLoadsDurableFactsOnlyAfterSignatureVerification() throws Exception {
        PaymentAdapter.ProviderPayment payment = adapter.verifyAndParseCallback(
                envelope(signedBody(validParameters())));

        assertThat(payment.amount()).isEqualTo(399);
        assertThat(expectationLookups).hasValue(1);

        String signed = new String(signedBody(validParameters()), StandardCharsets.UTF_8);
        assertRejected(envelope(signed.replaceFirst("sign=[^&]+", "sign=invalid")
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(expectationLookups).hasValue(1);
    }

    private Map<String, String> validParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("app_id", APP_ID);
        parameters.put("seller_id", SELLER_ID);
        parameters.put("out_trade_no", "pay_callback_1");
        parameters.put("trade_no", "2026080122000000000001");
        parameters.put("trade_status", "TRADE_SUCCESS");
        parameters.put("total_amount", "3.99");
        parameters.put("currency", "CNY");
        parameters.put("gmt_payment", "2030-01-02 11:03:04");
        parameters.put("charset", "UTF-8");
        parameters.put("notify_id", "notify-1");
        return parameters;
    }

    @Test
    void verifiesActualGbkNotificationIncludingChineseFields() throws Exception {
        var parameters = validParameters();
        parameters.put("charset", "GBK");
        parameters.put("subject", "余额充值一元");
        parameters.remove("currency");
        var charset = java.nio.charset.Charset.forName("GBK");
        byte[] body = signedBody(parameters, charset);
        var result = adapter.verifyAndParseCallback(envelope(body));
        assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(result.amount()).isEqualTo(399);
        String tampered = new String(body, StandardCharsets.US_ASCII).replace("charset=GBK", "charset=UTF-8");
        assertRejected(envelope(tampered.getBytes(StandardCharsets.US_ASCII)));
    }

    private byte[] signedBody(Map<String, String> unsigned) throws Exception {
        return signedBody(unsigned, StandardCharsets.UTF_8);
    }

    private byte[] signedBody(Map<String, String> unsigned, java.nio.charset.Charset charset) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>(unsigned);
        parameters.put("sign_type", "RSA2");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(alipayKeys.getPrivate());
        signer.update(canonical(parameters).getBytes(charset));
        parameters.put("sign", Base64.getEncoder().encodeToString(signer.sign()));
        return parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), charset) + "=" + URLEncoder.encode(entry.getValue(), charset))
                .collect(java.util.stream.Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
    }

    private String canonical(Map<String, String> parameters) {
        Map<String, String> sorted = new TreeMap<>(parameters);
        sorted.remove("sign");
        sorted.remove("sign_type");
        return sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private PaymentAdapter.CallbackEnvelope envelope(byte[] body) {
        return new PaymentAdapter.CallbackEnvelope(
                body, Map.of("Content-Type", List.of("application/x-www-form-urlencoded")));
    }

    private void assertRejected(PaymentAdapter.CallbackEnvelope envelope) {
        assertRejected(envelope, "callback");
    }

    private void assertRejected(PaymentAdapter.CallbackEnvelope envelope, String scenario) {
        Throwable thrown = catchThrowable(() -> adapter.verifyAndParseCallback(envelope));
        assertThat(thrown).as(scenario)
                .isNotNull()
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_alipay_callback")
                .hasMessageNotContaining("notify-1")
                .hasMessageNotContaining("2026080122000000000001")
                .hasMessageNotContaining("openapi.alipay.com");
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    private static final class FormOnlyGateway implements AlipayGateway {
        @Override
        public String createPagePayment(PaymentRequest request) {
            return FORM_HTML;
        }

        @Override
        public String createWapPayment(PaymentRequest request) {
            return FORM_HTML;
        }

        @Override
        public PaymentResult queryPayment(String merchantOrderNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentResult closePayment(String merchantOrderNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult createRefund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult queryRefund(RefundQueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BillResult downloadBill(java.time.LocalDate billDate) {
            throw new UnsupportedOperationException();
        }
    }
}
