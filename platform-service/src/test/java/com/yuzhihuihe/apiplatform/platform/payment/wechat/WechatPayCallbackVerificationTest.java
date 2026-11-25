package com.yuzhihuihe.apiplatform.platform.payment.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
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
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class WechatPayCallbackVerificationTest {

    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final String APP_ID = "wx-test-app";
    private static final String MERCHANT_ID = "1900000109";
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_01142321344320250709";
    private static final byte[] API_V3_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private KeyPair merchantKeys;
    private KeyPair platformKeys;
    private WechatPayAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        merchantKeys = rsaKeyPair();
        platformKeys = rsaKeyPair();
        Files.write(tempDir.resolve("merchant-private-key.pem"), merchantKeys.getPrivate().getEncoded());
        Files.write(tempDir.resolve("platform-public-key.pem"), platformKeys.getPublic().getEncoded());
        adapter = new WechatPayAdapter(
                new NoOpGateway(), objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                APP_ID, MERCHANT_ID, API_V3_KEY, platformKeys.getPublic(), PUBLIC_KEY_ID,
                URI.create("https://pay.example.com/wechat/callback"), "wechat-fingerprint");
    }

    @Test
    void validSignatureAndAesGcmResourceProduceVerifiedPayment() throws Exception {
        SignedCallback callback = signedCallback(NOW, "payment-callback-nonce", "associated-data");

        PaymentAdapter.ProviderPayment result = adapter.verifyAndParseCallback(callback.envelope());

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(result.transactionNo()).isEqualTo("pay_callback_1");
        assertThat(result.externalTradeNo()).isEqualTo("wx-transaction-1");
        assertThat(result.amount()).isEqualTo(399);
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.paidAt()).isEqualTo(Instant.parse("2030-01-02T03:03:04Z"));
        assertThat(result.payloadHash()).isEqualTo(sha256(callback.body()));
    }

    @Test
    void validSignatureAndAesGcmResourceProduceVerifiedRefundNumber() throws Exception {
        SignedCallback callback = signedCallback(
                NOW, "refund-callback-nonce", "refund-associated-data", refundResource());

        PaymentAdapter.VerifiedRefundCallback result =
                adapter.verifyAndParseRefundCallback(callback.envelope());

        assertThat(result.refundNo()).isEqualTo("ref_callback_1");
    }

    @Test
    void refundCallbackForAnotherMerchantIsRejected() throws Exception {
        ObjectNode refund = refundResource();
        refund.put("mchid", "another-merchant");
        SignedCallback callback = signedCallback(
                NOW, "refund-callback-nonce", "refund-associated-data", refund);

        assertThatThrownBy(() -> adapter.verifyAndParseRefundCallback(callback.envelope()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_wechat_callback")
                .hasMessageNotContaining("another-merchant");
    }

    @Test
    void changedRawBodyFailsSignatureVerificationBeforeProducingPayment() throws Exception {
        SignedCallback signed = signedCallback(NOW, "payment-callback-nonce", "associated-data");
        byte[] changed = signed.body().clone();
        changed[changed.length - 2] ^= 1;

        assertRejected(new PaymentAdapter.CallbackEnvelope(changed, signed.headers()));
    }

    @Test
    void staleTimestampFailsBeforeResourceDecryption() throws Exception {
        SignedCallback stale = signedCallback(NOW.minusSeconds(301), "stale-nonce", "associated-data");

        assertRejected(stale.envelope());
    }

    @Test
    void wrongSerialAndOptionalPublicKeyIdFailClosed() throws Exception {
        SignedCallback signed = signedCallback(NOW, "payment-callback-nonce", "associated-data");
        Map<String, List<String>> wrongSerial = new java.util.LinkedHashMap<>(signed.headers());
        wrongSerial.put("Wechatpay-Serial", List.of("wrong-serial"));
        assertRejected(new PaymentAdapter.CallbackEnvelope(signed.body(), wrongSerial));

        Map<String, List<String>> wrongPublicKeyId =
                new java.util.LinkedHashMap<>(signed.headers());
        wrongPublicKeyId.put("Wechatpay-Public-Key-Id", List.of("wrong-public-key-id"));
        assertRejected(new PaymentAdapter.CallbackEnvelope(signed.body(), wrongPublicKeyId));
    }

    @Test
    void wrongAssociatedDataFailsAuthenticatedDecryption() throws Exception {
        SignedCallback signed = signedCallback(NOW, "payment-callback-nonce", "associated-data");
        ObjectNode body = (ObjectNode) objectMapper.readTree(signed.body());
        ((ObjectNode) body.path("resource")).put("associated_data", "changed-associated-data");
        byte[] changed = objectMapper.writeValueAsBytes(body);

        assertRejected(envelope(changed, NOW, "payment-callback-nonce"));
    }

    @Test
    void authenticatedPaymentWithWrongMerchantMetadataIsRejectedWithoutLeakingPayload()
            throws Exception {
        ObjectNode transaction = paymentResource();
        transaction.put("mchid", "wrong-merchant");
        SignedCallback signed = signedCallback(
                NOW, "payment-callback-nonce", "associated-data", transaction);

        assertThatThrownBy(() -> adapter.verifyAndParseCallback(signed.envelope()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_wechat_payment_response")
                .hasMessageNotContaining("wrong-merchant")
                .hasMessageNotContaining("ciphertext")
                .hasMessageNotContaining("api-v3");
    }

    private SignedCallback signedCallback(
            Instant timestamp, String signatureNonce, String associatedData) throws Exception {
        return signedCallback(timestamp, signatureNonce, associatedData, paymentResource());
    }

    private SignedCallback signedCallback(
            Instant timestamp,
            String signatureNonce,
            String associatedData,
            JsonNode transaction) throws Exception {
        byte[] resourceNonce = "resource-iv1".getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY, "AES"),
                new GCMParameterSpec(128, resourceNonce));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(objectMapper.writeValueAsBytes(transaction));

        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("algorithm", "AEAD_AES_256_GCM");
        resource.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        resource.put("associated_data", associatedData);
        resource.put("nonce", new String(resourceNonce, StandardCharsets.UTF_8));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", "callback-id-1");
        body.put("create_time", "2030-01-02T03:04:05+00:00");
        body.put("event_type", transaction.has("out_refund_no")
                ? "REFUND.SUCCESS" : "TRANSACTION.SUCCESS");
        body.put("resource_type", "encrypt-resource");
        body.set("resource", resource);
        body.put("summary", "payment succeeded");
        byte[] rawBody = objectMapper.writeValueAsBytes(body);
        return new SignedCallback(rawBody, headers(rawBody, timestamp, signatureNonce));
    }

    private PaymentAdapter.CallbackEnvelope envelope(
            byte[] rawBody, Instant timestamp, String nonce) throws Exception {
        return new PaymentAdapter.CallbackEnvelope(rawBody, headers(rawBody, timestamp, nonce));
    }

    private Map<String, List<String>> headers(
            byte[] rawBody, Instant timestamp, String nonce) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(platformKeys.getPrivate());
        signature.update(signatureMessage(rawBody, timestamp, nonce));
        return Map.of(
                "Wechatpay-Timestamp", List.of(Long.toString(timestamp.getEpochSecond())),
                "Wechatpay-Nonce", List.of(nonce),
                "Wechatpay-Signature", List.of(Base64.getEncoder().encodeToString(signature.sign())),
                "Wechatpay-Serial", List.of(PUBLIC_KEY_ID),
                "Wechatpay-Public-Key-Id", List.of(PUBLIC_KEY_ID));
    }

    private byte[] signatureMessage(byte[] rawBody, Instant timestamp, String nonce) {
        byte[] prefix = (timestamp.getEpochSecond() + "\n" + nonce + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] message = java.util.Arrays.copyOf(prefix, prefix.length + rawBody.length + 1);
        System.arraycopy(rawBody, 0, message, prefix.length, rawBody.length);
        message[message.length - 1] = '\n';
        return message;
    }

    private ObjectNode paymentResource() {
        ObjectNode amount = objectMapper.createObjectNode();
        amount.put("total", 399);
        amount.put("currency", "CNY");
        ObjectNode transaction = objectMapper.createObjectNode();
        transaction.put("appid", APP_ID);
        transaction.put("mchid", MERCHANT_ID);
        transaction.put("out_trade_no", "pay_callback_1");
        transaction.put("transaction_id", "wx-transaction-1");
        transaction.put("trade_state", "SUCCESS");
        transaction.put("success_time", "2030-01-02T03:03:04+00:00");
        transaction.set("amount", amount);
        return transaction;
    }

    private ObjectNode refundResource() {
        ObjectNode refund = objectMapper.createObjectNode();
        refund.put("mchid", MERCHANT_ID);
        refund.put("out_trade_no", "pay_callback_1");
        refund.put("transaction_id", "wx-transaction-1");
        refund.put("out_refund_no", "ref_callback_1");
        refund.put("refund_id", "wx-refund-1");
        refund.put("refund_status", "SUCCESS");
        return refund;
    }

    private void assertRejected(PaymentAdapter.CallbackEnvelope envelope) {
        assertThatThrownBy(() -> adapter.verifyAndParseCallback(envelope))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_wechat_callback")
                .hasMessageNotContaining("associated-data")
                .hasMessageNotContaining("ciphertext")
                .hasMessageNotContaining("api-v3");
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private record SignedCallback(byte[] body, Map<String, List<String>> headers) {
        private PaymentAdapter.CallbackEnvelope envelope() {
            return new PaymentAdapter.CallbackEnvelope(body, headers);
        }
    }

    private static final class NoOpGateway implements WechatPayGateway {
        @Override
        public NativePrepay prepayNative(PrepayCommand request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public H5Prepay prepayH5(PrepayCommand request) {
            throw new UnsupportedOperationException();
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
        public RefundResult queryRefund(String merchantRefundNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BillResult downloadBill(java.time.LocalDate billDate) {
            throw new UnsupportedOperationException();
        }
    }
}
