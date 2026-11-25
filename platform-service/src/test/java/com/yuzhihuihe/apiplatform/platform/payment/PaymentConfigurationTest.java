package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipayAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipayGateway;
import com.yuzhihuihe.apiplatform.platform.payment.wechat.WechatPayAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.wechat.WechatPayGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentConfigurationScan.class);

    @Test
    void reconciliationBillByteLimitIsInjectedIntoBothSdkGateways() {
        runner.withPropertyValues(properties(validWechatPayProperties()))
                .withPropertyValues("reconciliation.max-bill-bytes=4096")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(WechatPayGateway.class), "maxBillBytes"))
                            .isEqualTo(4096);
                });
        runner.withPropertyValues(properties(validAlipayProperties()))
                .withPropertyValues("reconciliation.max-bill-bytes=8192")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(AlipayGateway.class), "maxBillBytes"))
                            .isEqualTo(8192);
                });
    }

    @Test
    void independentMobileConfigurationKeepsDesktopAvailableUntilMobileIsEnabled() {
        Map<String, String> properties = validAlipayProperties();
        properties.put("payment.alipay.wap.app-id", "2021000000000002");
        properties.put("payment.alipay.wap.application-private-key-resource",
                writePrivateKey(rsaKeyPair(), "mobile-private.pem"));
        properties.put("payment.alipay.wap.alipay-public-key-resource",
                writePublicKey(rsaKeyPair(), "mobile-public.pem"));
        runner.withPropertyValues(properties(properties)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentAdapter.class);
            PaymentAdapter adapter = context.getBean(PaymentAdapter.class);
            assertThat(adapter.supportsCheckout(PaymentProduct.ALIPAY_PAGE)).isTrue();
            assertThat(adapter.supportsCheckout(PaymentProduct.ALIPAY_WAP)).isFalse();
            assertThat(adapter.products()).contains(PaymentProduct.ALIPAY_WAP);
        });
        properties.put("payment.alipay.wap.enabled", "true");
        runner.withPropertyValues(properties(properties)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentAdapter.class);
            assertThat(context.getBean(PaymentAdapter.class).supportsCheckout(PaymentProduct.ALIPAY_WAP)).isTrue();
        });
    }

    @Test
    void enablingMobileWithoutItsAppIdFailsClosed() {
        runner.withPropertyValues(properties(validAlipayProperties()))
                .withPropertyValues("payment.alipay.wap.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void alipayConfigurationActivationIsFailClosed() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AlipayGateway.class);
            assertThat(context).doesNotHaveBean(PaymentAdapter.class);
        });

        Map<String, String> valid = validAlipayProperties();
        for (String property : valid.keySet().stream()
                .filter(candidate -> !candidate.endsWith(".enabled"))
                .toList()) {
            Map<String, String> incomplete = new LinkedHashMap<>(valid);
            incomplete.remove(property);
            runner.withPropertyValues(properties(incomplete)).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(property + " must be configured");
            });
        }

        Map<String, String> invalidPrivateKey = new LinkedHashMap<>(valid);
        invalidPrivateKey.put("payment.alipay.application-private-key-resource",
                tempDir.resolve("missing-alipay-private-key.pem").toUri().toString());
        runner.withPropertyValues(properties(invalidPrivateKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.alipay.application-private-key-resource must be a readable RSA private key resource");
        });

        Map<String, String> invalidPublicKey = new LinkedHashMap<>(valid);
        invalidPublicKey.put("payment.alipay.alipay-public-key-resource",
                tempDir.resolve("missing-alipay-public-key.pem").toUri().toString());
        runner.withPropertyValues(properties(invalidPublicKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.alipay.alipay-public-key-resource must be a readable RSA public key resource");
        });

        Map<String, String> remoteKey = new LinkedHashMap<>(valid);
        remoteKey.put("payment.alipay.application-private-key-resource",
                "https://keys.example.com/alipay-private-key.pem");
        runner.withPropertyValues(properties(remoteKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.alipay.application-private-key-resource must be a local file resource");
        });

        Map<String, String> weakKey = new LinkedHashMap<>(valid);
        weakKey.put("payment.alipay.application-private-key-resource",
                writePrivateKey(rsaKeyPair(1024), "weak-alipay-private.pem"));
        runner.withPropertyValues(properties(weakKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.alipay.application-private-key-resource must use RSA with at least 2048 bits");
        });

        assertConfigurationFailure(valid, "payment.alipay.gateway-url",
                "https://sandbox.example.com/gateway.do",
                "payment.alipay.gateway-url must use the official HTTPS production gateway");
        assertConfigurationFailure(valid, "payment.alipay.gateway-url",
                "http://127.0.0.1:18081/gateway.do",
                "payment.alipay.gateway-url must use the official HTTPS production gateway");
        assertConfigurationFailure(valid, "payment.alipay.notify-url",
                "http://pay.example.com/callback",
                "payment.alipay.notify-url must be an HTTPS URL");
        assertConfigurationFailure(valid, "payment.alipay.return-url",
                "http://pay.example.com/return",
                "payment.alipay.return-url must be an HTTPS URL");
        assertConfigurationFailure(valid, "payment.alipay.charset", "GBK",
                "payment.alipay.charset must be UTF-8");
        assertConfigurationFailure(valid, "payment.alipay.sign-type", "RSA",
                "payment.alipay.sign-type must be RSA2");
        assertConfigurationFailure(valid, "payment.alipay.connect-timeout", "1ns",
                "payment.alipay.connect-timeout must be a positive bounded duration");
        assertConfigurationFailure(valid, "payment.alipay.total-operation-timeout", "2s",
                "payment.alipay.total-operation-timeout must not be shorter than connect-timeout plus read-timeout");

        Map<String, String> localGateway = new LinkedHashMap<>(valid);
        localGateway.put("payment.alipay.gateway-url", "http://127.0.0.1:18081/gateway.do");
        runner.withPropertyValues(properties(localGateway))
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "payment.alipay.gateway-url must use the official HTTPS production gateway or an HTTPS loopback URL in the local profile");
                });

        Map<String, String> localHttpsGateway = new LinkedHashMap<>(valid);
        localHttpsGateway.put(
                "payment.alipay.gateway-url", "https://127.0.0.1:18081/gateway.do");
        runner.withPropertyValues(properties(localHttpsGateway))
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());

        Map<String, String> nonLoopbackLocalGateway = new LinkedHashMap<>(valid);
        nonLoopbackLocalGateway.put("payment.alipay.gateway-url", "http://example.com/gateway.do");
        runner.withPropertyValues(properties(nonLoopbackLocalGateway))
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "payment.alipay.gateway-url must use the official HTTPS production gateway or an HTTPS loopback URL in the local profile");
                });

        assertValidAlipayConfiguration(valid);
    }

    @Test
    void alipayCredentialFingerprintChangesWhenEitherKeyRotates() {
        Map<String, String> valid = validAlipayProperties();
        String original = alipayFingerprint(valid);

        Map<String, String> rotatedPrivate = new LinkedHashMap<>(valid);
        rotatedPrivate.put("payment.alipay.application-private-key-resource",
                writePrivateKey(rsaKeyPair(), "rotated-alipay-private.pem"));
        assertThat(alipayFingerprint(rotatedPrivate)).isNotEqualTo(original);

        Map<String, String> rotatedPublic = new LinkedHashMap<>(valid);
        rotatedPublic.put("payment.alipay.alipay-public-key-resource",
                writePublicKey(rsaKeyPair(), "rotated-alipay-public.pem"));
        assertThat(alipayFingerprint(rotatedPublic)).isNotEqualTo(original);
    }

    @Test
    void alipayIdentifiersUseExactProviderFormatsAndRejectPlaceholders() {
        Map<String, String> valid = validAlipayProperties();
        assertConfigurationFailure(valid, "payment.alipay.app-id", "202100000000001",
                "payment.alipay.app-id must be a 16-digit Alipay identifier");
        assertConfigurationFailure(valid, "payment.alipay.app-id", "202100000000000A",
                "payment.alipay.app-id must be a 16-digit Alipay identifier");
        assertConfigurationFailure(valid, "payment.alipay.seller-id", "208800000000001",
                "payment.alipay.seller-id must be a 16-digit Alipay identifier");
        assertConfigurationFailure(valid, "payment.alipay.seller-id", "208800000000000A",
                "payment.alipay.seller-id must be a 16-digit Alipay identifier");

        for (String placeholder : new String[]{"dummy", "test", "todo", "xxx"}) {
            assertConfigurationFailure(valid, "payment.alipay.app-id", placeholder,
                    "payment.alipay.app-id must not be a placeholder");
            assertConfigurationFailure(valid, "payment.alipay.seller-id", placeholder,
                    "payment.alipay.seller-id must not be a placeholder");
        }
        assertConfigurationFailure(valid, "payment.alipay.app-id", "0000000000000000",
                "payment.alipay.app-id must not be a placeholder");
        assertConfigurationFailure(valid, "payment.alipay.seller-id", "1111111111111111",
                "payment.alipay.seller-id must not be a placeholder");
    }

    @Test
    void officialAlipaySdkFormsAreConvertedToStructuredActionsWithoutNetworkAccess() {
        runner.withPropertyValues(properties(validAlipayProperties())).run(context -> {
            assertThat(context).hasNotFailed();
            assertSdkLoggingDisabled();
            AlipayAdapter adapter = context.getBean(AlipayAdapter.class);
            for (PaymentProduct product :
                    new PaymentProduct[]{PaymentProduct.ALIPAY_PAGE, PaymentProduct.ALIPAY_WAP}) {
                PaymentAdapter.PaymentCreation creation = adapter.createPayment(
                        new PaymentAdapter.PaymentCommand(
                                "pay_sdk_" + product.name().toLowerCase(), "ord_sdk_form", 399,
                                "CNY", product,
                                URI.create("https://pay.example.com/alipay/callback"),
                                "203.0.113.10", Instant.now().plusSeconds(900)));
                assertThat(creation.outcome()).isEqualTo(ProviderOutcome.PENDING);
                assertThat(creation.action().type()).isEqualTo(PaymentActionType.HTML_FORM);
                assertThat(creation.action().fields().keySet())
                        .isSubsetOf(PaymentAdapter.HTML_FORM_FIELDS);
                assertThat(creation.toString()).doesNotContain("<form", "<script");
            }
        });
    }

    private void assertSdkLoggingDisabled() {
        try {
            Class<?> logger = Class.forName("com.alipay.api.internal.util.AlipayLogger");
            var enabled = logger.getDeclaredField("needEnableLogger");
            enabled.setAccessible(true);
            assertThat(enabled.getBoolean(null)).isFalse();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("unable to inspect Alipay SDK logging state", exception);
        }
    }

    @Test
    void wechatPayConfigurationActivationIsFailClosed() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(WechatPayGateway.class);
            assertThat(context).doesNotHaveBean(PaymentAdapter.class);
        });

        Map<String, String> valid = validWechatPayProperties();
        for (String property : valid.keySet().stream()
                .filter(candidate -> !candidate.endsWith(".enabled"))
                .toList()) {
            Map<String, String> incomplete = new LinkedHashMap<>(valid);
            incomplete.remove(property);
            runner.withPropertyValues(properties(incomplete)).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(property + " must be configured");
            });
        }

        Map<String, String> shortApiKey = new LinkedHashMap<>(valid);
        shortApiKey.put("payment.wechat-pay.api-v3-key", "only-31-bytes-api-v3-key-value!");
        runner.withPropertyValues(properties(shortApiKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.api-v3-key must be exactly 32 bytes");
        });

        Map<String, String> invalidPrivateKey = new LinkedHashMap<>(valid);
        invalidPrivateKey.put("payment.wechat-pay.merchant-private-key-resource",
                tempDir.resolve("missing-private-key.pem").toUri().toString());
        runner.withPropertyValues(properties(invalidPrivateKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.merchant-private-key-resource must be a readable RSA private key resource");
        });

        Map<String, String> remotePrivateKey = new LinkedHashMap<>(valid);
        remotePrivateKey.put("payment.wechat-pay.merchant-private-key-resource",
                "https://keys.example.com/merchant-private-key.pem");
        runner.withPropertyValues(properties(remotePrivateKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.merchant-private-key-resource must be a local file resource");
        });

        Map<String, String> invalidPublicKey = new LinkedHashMap<>(valid);
        invalidPublicKey.put("payment.wechat-pay.platform-public-key-resource",
                tempDir.resolve("missing-public-key.pem").toUri().toString());
        runner.withPropertyValues(properties(invalidPublicKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.platform-public-key-resource must be a readable RSA public key resource");
        });

        Map<String, String> placeholder = new LinkedHashMap<>(valid);
        placeholder.put("payment.wechat-pay.app-id", "change-me");
        runner.withPropertyValues(properties(placeholder)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.app-id must not be a placeholder");
        });

        Map<String, String> insecureNotifyUrl = new LinkedHashMap<>(valid);
        insecureNotifyUrl.put("payment.wechat-pay.notify-url", "http://pay.example.com/callback");
        runner.withPropertyValues(properties(insecureNotifyUrl)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.notify-url must use HTTPS outside the local profile");
        });

        runner.withPropertyValues(properties(insecureNotifyUrl))
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "payment.wechat-pay.notify-url must use HTTPS outside the local profile");
                });

        Map<String, String> loopbackNotifyUrl = new LinkedHashMap<>(valid);
        loopbackNotifyUrl.put(
                "payment.wechat-pay.notify-url", "http://127.0.0.1:8080/wechat/callback");
        runner.withPropertyValues(properties(loopbackNotifyUrl))
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());

        Map<String, String> subMillisecondTimeout = new LinkedHashMap<>(valid);
        subMillisecondTimeout.put("payment.wechat-pay.connect-timeout", "1ns");
        runner.withPropertyValues(properties(subMillisecondTimeout)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.connect-timeout must be a positive bounded duration");
        });

        Map<String, String> shortTotalTimeout = new LinkedHashMap<>(valid);
        shortTotalTimeout.put("payment.wechat-pay.total-operation-timeout", "2s");
        runner.withPropertyValues(properties(shortTotalTimeout)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.total-operation-timeout must not be shorter than connect-timeout plus read-timeout");
        });

        Map<String, String> weakPrivateKey = new LinkedHashMap<>(valid);
        weakPrivateKey.put("payment.wechat-pay.merchant-private-key-resource",
                writePrivateKey(rsaKeyPair(1024), "weak-private.pem"));
        runner.withPropertyValues(properties(weakPrivateKey)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "payment.wechat-pay.merchant-private-key-resource must use RSA with at least 2048 bits");
        });

        runner.withPropertyValues(properties(valid)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WechatPayGateway.class);
            assertThat(context).hasSingleBean(WechatPayAdapter.class);
            assertThat(context).hasSingleBean(PaymentAdapter.class);
            assertThat(context.getBean("wechatPayRuntime").toString())
                    .doesNotContain("0123456789abcdef0123456789abcdef")
                    .doesNotContain("PRIVATE KEY");
        });

        Map<String, String> localHttp = new LinkedHashMap<>(valid);
        localHttp.put("spring.profiles.active", "local");
        localHttp.put("payment.wechat-pay.notify-url", "http://localhost:8080/wechat/callback");
        runner.withPropertyValues(properties(localHttp)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WechatPayAdapter.class);
        });
    }

    @Test
    void wechatPayCredentialFingerprintChangesWhenSecretMaterialRotates() {
        Map<String, String> valid = validWechatPayProperties();
        String original = fingerprint(valid);

        Map<String, String> rotatedApiV3 = new LinkedHashMap<>(valid);
        rotatedApiV3.put(
                "payment.wechat-pay.api-v3-key", "abcdef0123456789abcdef0123456789");
        assertThat(fingerprint(rotatedApiV3)).isNotEqualTo(original);

        Map<String, String> rotatedPrivateKey = new LinkedHashMap<>(valid);
        rotatedPrivateKey.put("payment.wechat-pay.merchant-private-key-resource",
                writePrivateKey(rsaKeyPair(), "rotated-merchant-private-key.pem"));
        assertThat(fingerprint(rotatedPrivateKey)).isNotEqualTo(original);
    }

    private Map<String, String> validWechatPayProperties() {
        KeyPair merchantKeyPair = rsaKeyPair();
        KeyPair platformKeyPair = rsaKeyPair();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("payment.wechat-pay.enabled", "true");
        properties.put("payment.wechat-pay.app-id", "wx-test-app");
        properties.put("payment.wechat-pay.merchant-id", "1900000109");
        properties.put("payment.wechat-pay.merchant-private-key-resource",
                writePrivateKey(merchantKeyPair, "merchant-private-key.pem"));
        properties.put("payment.wechat-pay.api-v3-key", "0123456789abcdef0123456789abcdef");
        properties.put("payment.wechat-pay.certificate-serial", "7777777777777777777777777777777777777777");
        properties.put("payment.wechat-pay.platform-public-key-resource",
                writePublicKey(platformKeyPair, "platform-public-key.pem"));
        properties.put("payment.wechat-pay.platform-public-key-id", "PUB_KEY_ID_01142321344320250709");
        properties.put("payment.wechat-pay.notify-url", "https://pay.example.com/wechat/callback");
        properties.put("payment.wechat-pay.connect-timeout", "1s");
        properties.put("payment.wechat-pay.read-timeout", "2s");
        properties.put("payment.wechat-pay.total-operation-timeout", "4s");
        return properties;
    }

    private Map<String, String> validAlipayProperties() {
        KeyPair applicationKeyPair = rsaKeyPair();
        KeyPair alipayKeyPair = rsaKeyPair();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("payment.alipay.enabled", "true");
        properties.put("payment.alipay.app-id", "2021000000000001");
        properties.put("payment.alipay.application-private-key-resource",
                writePrivateKey(applicationKeyPair, "alipay-application-private-key.pem"));
        properties.put("payment.alipay.alipay-public-key-resource",
                writePublicKey(alipayKeyPair, "alipay-public-key.pem"));
        properties.put("payment.alipay.seller-id", "2088000000000001");
        properties.put("payment.alipay.gateway-url", "https://openapi.alipay.com/gateway.do");
        properties.put("payment.alipay.notify-url", "https://pay.example.com/alipay/callback");
        properties.put("payment.alipay.return-url", "https://pay.example.com/orders/return");
        properties.put("payment.alipay.charset", "UTF-8");
        properties.put("payment.alipay.sign-type", "RSA2");
        properties.put("payment.alipay.connect-timeout", "1s");
        properties.put("payment.alipay.read-timeout", "2s");
        properties.put("payment.alipay.total-operation-timeout", "4s");
        return properties;
    }

    private void assertValidAlipayConfiguration(Map<String, String> properties) {
        runner.withPropertyValues(properties(properties)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AlipayGateway.class);
            assertThat(context).hasSingleBean(AlipayAdapter.class);
            assertThat(context).hasSingleBean(PaymentAdapter.class);
            assertThat(context.getBean("alipayRuntime").toString())
                    .doesNotContain("PRIVATE KEY", "application-private-key", "alipay-public-key");
        });
    }

    private void assertConfigurationFailure(
            Map<String, String> valid, String property, String value, String message) {
        Map<String, String> invalid = new LinkedHashMap<>(valid);
        invalid.put(property, value);
        runner.withPropertyValues(properties(invalid)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(message);
        });
    }

    private String alipayFingerprint(Map<String, String> properties) {
        AtomicReference<String> fingerprint = new AtomicReference<>();
        runner.withPropertyValues(properties(properties)).run(context -> {
            assertThat(context).hasNotFailed();
            fingerprint.set(context.getBean(AlipayAdapter.class)
                    .configuration().credentialFingerprint());
        });
        return fingerprint.get();
    }

    private String fingerprint(Map<String, String> properties) {
        AtomicReference<String> fingerprint = new AtomicReference<>();
        runner.withPropertyValues(properties(properties)).run(context -> {
            assertThat(context).hasNotFailed();
            fingerprint.set(context.getBean(WechatPayAdapter.class)
                    .configuration().credentialFingerprint());
        });
        return fingerprint.get();
    }

    private String[] properties(Map<String, String> properties) {
        return properties.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private KeyPair rsaKeyPair() {
        return rsaKeyPair(2048);
    }

    private KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String writePrivateKey(KeyPair keyPair, String filename) {
        return writeKey(filename, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
    }

    private String writePublicKey(KeyPair keyPair, String filename) {
        return writeKey(filename, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
    }

    private String writeKey(String filename, String content) {
        try {
            Path path = tempDir.resolve(filename);
            Files.writeString(path, content, StandardCharsets.US_ASCII);
            return path.toUri().toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = PaymentAdapter.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = ".*\\.payment\\.(Alipay|WechatPay)PaymentConfiguration"))
    static class PaymentConfigurationScan {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return org.mockito.Mockito.mock(JdbcTemplate.class);
        }
    }
}
