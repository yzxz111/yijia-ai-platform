package com.yuzhihuihe.apiplatform.platform.payment;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipayAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipayApplicationAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipayGateway;
import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipaySdkGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.alipay", name = "enabled", havingValue = "true")
class AlipayPaymentConfiguration {

    private static final URI OFFICIAL_GATEWAY =
            URI.create("https://openapi.alipay.com/gateway.do");
    private static final int MINIMUM_RSA_BITS = 2048;
    private static final int MAXIMUM_KEY_BYTES = 64 * 1024;

    @Bean
    AlipayRuntime alipayRuntime(
            @Value("${payment.alipay.app-id:}") String appId,
            @Value("${payment.alipay.application-private-key-resource:}")
            String applicationPrivateKeyResource,
            @Value("${payment.alipay.alipay-public-key-resource:}")
            String alipayPublicKeyResource,
            @Value("${payment.alipay.seller-id:}") String sellerId,
            @Value("${payment.alipay.gateway-url:}") String gatewayUrl,
            @Value("${payment.alipay.notify-url:}") String notifyUrl,
            @Value("${payment.alipay.return-url:}") String returnUrl,
            @Value("${payment.alipay.charset:}") String charset,
            @Value("${payment.alipay.sign-type:}") String signType,
            @Value("${payment.alipay.connect-timeout:}") String connectTimeout,
            @Value("${payment.alipay.read-timeout:}") String readTimeout,
            @Value("${payment.alipay.total-operation-timeout:}") String totalOperationTimeout,
            ResourceLoader resourceLoader,
            Environment environment) {
        required("payment.alipay.app-id", appId);
        required("payment.alipay.application-private-key-resource",
                applicationPrivateKeyResource);
        required("payment.alipay.alipay-public-key-resource", alipayPublicKeyResource);
        required("payment.alipay.seller-id", sellerId);
        required("payment.alipay.gateway-url", gatewayUrl);
        required("payment.alipay.notify-url", notifyUrl);
        required("payment.alipay.return-url", returnUrl);
        required("payment.alipay.charset", charset);
        required("payment.alipay.sign-type", signType);
        required("payment.alipay.connect-timeout", connectTimeout);
        required("payment.alipay.read-timeout", readTimeout);
        required("payment.alipay.total-operation-timeout", totalOperationTimeout);

        rejectPlaceholder("payment.alipay.app-id", appId);
        rejectPlaceholder("payment.alipay.application-private-key-resource",
                applicationPrivateKeyResource);
        rejectPlaceholder("payment.alipay.alipay-public-key-resource", alipayPublicKeyResource);
        rejectPlaceholder("payment.alipay.seller-id", sellerId);
        rejectPlaceholder("payment.alipay.gateway-url", gatewayUrl);
        rejectPlaceholder("payment.alipay.notify-url", notifyUrl);
        rejectPlaceholder("payment.alipay.return-url", returnUrl);
        alipayIdentifier("payment.alipay.app-id", appId);
        alipayIdentifier("payment.alipay.seller-id", sellerId);

        if (!"UTF-8".equalsIgnoreCase(charset)) {
            throw new IllegalStateException("payment.alipay.charset must be UTF-8");
        }
        if (!"RSA2".equals(signType)) {
            throw new IllegalStateException("payment.alipay.sign-type must be RSA2");
        }

        PrivateKey privateKey = privateKey(resourceLoader, applicationPrivateKeyResource);
        PublicKey publicKey = publicKey(resourceLoader, alipayPublicKeyResource);
        URI gateway = gatewayUri(gatewayUrl, environment);
        URI notify = httpsUri("payment.alipay.notify-url", notifyUrl);
        URI returned = httpsUri("payment.alipay.return-url", returnUrl);
        Duration connect = duration("payment.alipay.connect-timeout", connectTimeout);
        Duration read = duration("payment.alipay.read-timeout", readTimeout);
        Duration total = duration(
                "payment.alipay.total-operation-timeout", totalOperationTimeout);
        if (total.compareTo(connect.plus(read)) < 0) {
            throw new IllegalStateException(
                    "payment.alipay.total-operation-timeout must not be shorter than connect-timeout plus read-timeout");
        }
        String fingerprint = credentialFingerprint(
                appId, sellerId, privateKey, publicKey);
        return new AlipayRuntime(
                appId, privateKey, publicKey, sellerId, gateway, notify, returned,
                "UTF-8", "RSA2", connect, read, total, fingerprint);
    }

    @Bean
    AlipayGateway alipayGateway(
            AlipayRuntime runtime,
            @Value("${reconciliation.max-bill-bytes:52428800}") int maxBillBytes) {
        return new AlipaySdkGateway(
                runtime.gatewayUrl(), runtime.appId(), runtime.sellerId(),
                runtime.applicationPrivateKey(),
                runtime.alipayPublicKey(), runtime.charset(), runtime.signType(),
                runtime.connectTimeout(), runtime.readTimeout(),
                runtime.totalOperationTimeout(), maxBillBytes);
    }

    @Bean
    AlipayAdapter.PaymentExpectationLookup alipayPaymentExpectationLookup(
            JdbcTemplate jdbcTemplate) {
        return transactionNo -> jdbcTemplate.query("""
                        SELECT payment.amount,
                               COALESCE(customer_order.currency, recharge_order.currency) AS currency
                        FROM platform.payment_transaction AS payment
                        LEFT JOIN platform.customer_order AS customer_order
                          ON customer_order.id = payment.order_id
                        LEFT JOIN platform.recharge_order AS recharge_order
                          ON recharge_order.id = payment.recharge_order_id
                        WHERE payment.transaction_no = ?
                          AND payment.provider = 'ALIPAY'
                          AND ((customer_order.id IS NOT NULL AND payment.recharge_order_id IS NULL)
                            OR (recharge_order.id IS NOT NULL AND payment.order_id IS NULL))
                        """,
                (result, rowNumber) -> new AlipayAdapter.ExpectedPayment(
                        result.getLong("amount"), result.getString("currency")),
                transactionNo).stream().findFirst().orElseThrow(
                        () -> new IllegalStateException(
                                "alipay_payment_expectation_not_found"));
    }

    @Bean
    PaymentAdapter alipayAdapter(
            AlipayGateway gateway,
            AlipayRuntime runtime,
            Clock clock,
            AlipayAdapter.PaymentExpectationLookup paymentExpectationLookup,
            Environment environment,
            ResourceLoader resourceLoader,
            JdbcTemplate jdbcTemplate) {
        AlipayAdapter desktop = new AlipayAdapter(
                gateway, clock, runtime.appId(), runtime.sellerId(), runtime.alipayPublicKey(),
                runtime.gatewayUrl(), runtime.notifyUrl(), runtime.returnUrl(),
                runtime.fingerprint(), paymentExpectationLookup);
        String mobileAppId = environment.getProperty("payment.alipay.wap.app-id", "");
        boolean mobileEnabled = environment.getProperty("payment.alipay.wap.enabled", Boolean.class, false);
        if (mobileAppId.isBlank()) {
            if (mobileEnabled) throw new IllegalStateException("payment.alipay.wap.app-id must be configured");
            return desktop;
        }
        if (mobileAppId.equals(runtime.appId())) {
            throw new IllegalStateException("payment.alipay.wap.app-id must differ from the desktop app-id");
        }
        AlipayRuntime mobile = alipayRuntime(mobileAppId,
                environment.getProperty("payment.alipay.wap.application-private-key-resource", ""),
                environment.getProperty("payment.alipay.wap.alipay-public-key-resource", ""),
                environment.getProperty("payment.alipay.wap.seller-id", runtime.sellerId()),
                runtime.gatewayUrl().toString(), runtime.notifyUrl().toString(),
                environment.getProperty("payment.alipay.wap.return-url", runtime.returnUrl().toString()),
                runtime.charset(), runtime.signType(), runtime.connectTimeout().toString(),
                runtime.readTimeout().toString(), runtime.totalOperationTimeout().toString(),
                resourceLoader, environment);
        AlipaySdkGateway mobileGateway = new AlipaySdkGateway(mobile.gatewayUrl(), mobile.appId(),
                mobile.sellerId(), mobile.applicationPrivateKey(), mobile.alipayPublicKey(),
                mobile.charset(), mobile.signType(), mobile.connectTimeout(), mobile.readTimeout(),
                mobile.totalOperationTimeout(), environment.getProperty("reconciliation.max-bill-bytes", Integer.class, 52428800));
        AlipayAdapter mobileAdapter = new AlipayAdapter(mobileGateway, clock, mobile.appId(), mobile.sellerId(),
                mobile.alipayPublicKey(), mobile.gatewayUrl(), mobile.notifyUrl(), mobile.returnUrl(),
                mobile.fingerprint(), paymentExpectationLookup);
        return new AlipayApplicationAdapter(runtime.appId(), desktop, mobile.appId(), mobileAdapter,
                new AlipayApplicationRepository(jdbcTemplate, runtime.appId()), mobileEnabled,
                clock, mobileGateway::close);
    }

    private void required(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be configured");
        }
    }

    private void rejectPlaceholder(String property, String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.contains("${")
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.contains("placeholder")
                || normalized.startsWith("your-")
                || java.util.Set.of("dummy", "test", "todo", "xxx").contains(normalized)) {
            throw new IllegalStateException(property + " must not be a placeholder");
        }
    }

    private void alipayIdentifier(String property, String value) {
        if (value.matches("(\\d)\\1{15}")) {
            throw new IllegalStateException(property + " must not be a placeholder");
        }
        if (!value.matches("\\d{16}")) {
            throw new IllegalStateException(
                    property + " must be a 16-digit Alipay identifier");
        }
    }

    private PrivateKey privateKey(ResourceLoader loader, String location) {
        String property = "payment.alipay.application-private-key-resource";
        requireLocalFileResource(property, location);
        try {
            byte[] encoded = pem(read(loader, location), "PRIVATE KEY");
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (!(key instanceof RSAPrivateKey rsaKey)
                    || rsaKey.getModulus().bitLength() < MINIMUM_RSA_BITS) {
                throw new UnsupportedRsaSizeException();
            }
            return key;
        } catch (UnsupportedRsaSizeException exception) {
            throw new IllegalStateException(property + " must use RSA with at least 2048 bits");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    property + " must be a readable RSA private key resource");
        }
    }

    private PublicKey publicKey(ResourceLoader loader, String location) {
        String property = "payment.alipay.alipay-public-key-resource";
        requireLocalFileResource(property, location);
        try {
            byte[] encoded = pem(read(loader, location), "PUBLIC KEY");
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            if (!(key instanceof RSAPublicKey rsaKey)
                    || rsaKey.getModulus().bitLength() < MINIMUM_RSA_BITS) {
                throw new UnsupportedRsaSizeException();
            }
            return key;
        } catch (UnsupportedRsaSizeException exception) {
            throw new IllegalStateException(property + " must use RSA with at least 2048 bits");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    property + " must be a readable RSA public key resource");
        }
    }

    private byte[] read(ResourceLoader loader, String location) throws Exception {
        Resource resource = loader.getResource(location);
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("unreadable resource");
        }
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(MAXIMUM_KEY_BYTES + 1);
            if (bytes.length > MAXIMUM_KEY_BYTES) {
                throw new IllegalArgumentException("key resource is too large");
            }
            return bytes;
        }
    }

    private void requireLocalFileResource(String property, String location) {
        try {
            URI uri = URI.create(location);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getHost() != null) {
                throw new IllegalArgumentException("not a local file URI");
            }
            java.nio.file.Path.of(uri);
        } catch (Exception exception) {
            throw new IllegalStateException(property + " must be a local file resource");
        }
    }

    private byte[] pem(byte[] bytes, String type) {
        String value = new String(bytes, StandardCharsets.US_ASCII).strip();
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!value.startsWith(begin) || !value.endsWith(end)) {
            throw new IllegalArgumentException("invalid PEM");
        }
        String body = value.substring(begin.length(), value.length() - end.length())
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private URI gatewayUri(String value, Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        try {
            URI uri = URI.create(value);
            if (sameEndpoint(uri, OFFICIAL_GATEWAY)) {
                return uri;
            }
            if (local && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && isLoopbackHost(uri.getHost())
                    && uri.getUserInfo() == null && uri.getFragment() == null
                    && uri.getQuery() == null) {
                return uri;
            }
        } catch (Exception exception) {
            // 下面稳定的配置错误有意省略了所提供的 URL。
        }
        if (local) {
            throw new IllegalStateException(
                    "payment.alipay.gateway-url must use the official HTTPS production gateway or an HTTPS loopback URL in the local profile");
        }
        throw new IllegalStateException(
                "payment.alipay.gateway-url must use the official HTTPS production gateway");
    }

    private boolean sameEndpoint(URI left, URI right) {
        return left.getUserInfo() == null && left.getFragment() == null
                && left.getQuery() == null
                && right.getScheme().equalsIgnoreCase(left.getScheme())
                && right.getHost().equalsIgnoreCase(left.getHost())
                && normalizedPort(left) == normalizedPort(right)
                && right.getPath().equals(left.getPath());
    }

    private int normalizedPort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private URI httpsUri(String property, String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid HTTPS URI");
            }
            return uri;
        } catch (Exception exception) {
            throw new IllegalStateException(property + " must be an HTTPS URL");
        }
    }

    private boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) {
            return true;
        }
        String literal = normalized.startsWith("[") && normalized.endsWith("]")
                ? normalized.substring(1, normalized.length() - 1) : normalized;
        boolean numericLiteral = literal.chars().allMatch(
                character -> Character.isDigit(character) || character == '.')
                || literal.indexOf(':') >= 0;
        if (!numericLiteral) {
            return false;
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private Duration duration(String property, String value) {
        try {
            Duration duration = DurationStyle.detectAndParse(value);
            if (duration.isZero() || duration.isNegative()
                    || duration.toMillis() < 1
                    || duration.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid duration");
            }
            return duration;
        } catch (Exception exception) {
            throw new IllegalStateException(property + " must be a positive bounded duration");
        }
    }

    private String credentialFingerprint(
            String appId,
            String sellerId,
            PrivateKey privateKey,
            PublicKey publicKey) {
        byte[] privateKeyBytes = privateKey.getEncoded();
        byte[] publicKeyBytes = publicKey.getEncoded();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, appId.getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, sellerId.getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, privateKeyBytes);
            updateDigest(digest, publicKeyBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("payment.alipay credential validation failed");
        } finally {
            Arrays.fill(privateKeyBytes, (byte) 0);
        }
    }

    private void updateDigest(MessageDigest digest, byte[] value) {
        int length = value.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(value);
    }

    record AlipayRuntime(
            String appId,
            PrivateKey applicationPrivateKey,
            PublicKey alipayPublicKey,
            String sellerId,
            URI gatewayUrl,
            URI notifyUrl,
            URI returnUrl,
            String charset,
            String signType,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalOperationTimeout,
            String fingerprint) {
        @Override
        public String toString() {
            return "AlipayRuntime[configured=true,fingerprint=" + fingerprint + "]";
        }
    }

    private static final class UnsupportedRsaSizeException extends RuntimeException {
    }
}
