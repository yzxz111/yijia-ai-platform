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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.payment.wechat.WechatPayAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.wechat.WechatPayGateway;
import com.yuzhihuihe.apiplatform.platform.payment.wechat.WechatPaySdkGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.wechat-pay", name = "enabled", havingValue = "true")
class WechatPayPaymentConfiguration {

    private static final int MINIMUM_RSA_BITS = 2048;
    private static final int MAXIMUM_KEY_BYTES = 64 * 1024;

    @Bean
    WechatPayRuntime wechatPayRuntime(
            @Value("${payment.wechat-pay.app-id:}") String appId,
            @Value("${payment.wechat-pay.merchant-id:}") String merchantId,
            @Value("${payment.wechat-pay.merchant-private-key-resource:}")
            String merchantPrivateKeyResource,
            @Value("${payment.wechat-pay.api-v3-key:}") String apiV3Key,
            @Value("${payment.wechat-pay.certificate-serial:}") String certificateSerial,
            @Value("${payment.wechat-pay.platform-public-key-resource:}")
            String platformPublicKeyResource,
            @Value("${payment.wechat-pay.platform-public-key-id:}") String platformPublicKeyId,
            @Value("${payment.wechat-pay.notify-url:}") String notifyUrl,
            @Value("${payment.wechat-pay.connect-timeout:}") String connectTimeout,
            @Value("${payment.wechat-pay.read-timeout:}") String readTimeout,
            @Value("${payment.wechat-pay.total-operation-timeout:}") String totalOperationTimeout,
            ResourceLoader resourceLoader,
            Environment environment) {
        required("payment.wechat-pay.app-id", appId);
        required("payment.wechat-pay.merchant-id", merchantId);
        required("payment.wechat-pay.merchant-private-key-resource", merchantPrivateKeyResource);
        required("payment.wechat-pay.api-v3-key", apiV3Key);
        required("payment.wechat-pay.certificate-serial", certificateSerial);
        required("payment.wechat-pay.platform-public-key-resource", platformPublicKeyResource);
        required("payment.wechat-pay.platform-public-key-id", platformPublicKeyId);
        required("payment.wechat-pay.notify-url", notifyUrl);
        required("payment.wechat-pay.connect-timeout", connectTimeout);
        required("payment.wechat-pay.read-timeout", readTimeout);
        required("payment.wechat-pay.total-operation-timeout", totalOperationTimeout);

        rejectPlaceholder("payment.wechat-pay.app-id", appId);
        rejectPlaceholder("payment.wechat-pay.merchant-id", merchantId);
        rejectPlaceholder("payment.wechat-pay.merchant-private-key-resource",
                merchantPrivateKeyResource);
        rejectPlaceholder("payment.wechat-pay.api-v3-key", apiV3Key);
        rejectPlaceholder("payment.wechat-pay.certificate-serial", certificateSerial);
        rejectPlaceholder("payment.wechat-pay.platform-public-key-resource",
                platformPublicKeyResource);
        rejectPlaceholder("payment.wechat-pay.platform-public-key-id", platformPublicKeyId);
        rejectPlaceholder("payment.wechat-pay.notify-url", notifyUrl);

        byte[] apiV3Bytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
        if (apiV3Bytes.length != 32) {
            throw new IllegalStateException(
                    "payment.wechat-pay.api-v3-key must be exactly 32 bytes");
        }
        PrivateKey privateKey = privateKey(resourceLoader, merchantPrivateKeyResource);
        PublicKey publicKey = publicKey(resourceLoader, platformPublicKeyResource);
        URI callbackUri = notifyUri(notifyUrl, environment);
        Duration connect = duration("payment.wechat-pay.connect-timeout", connectTimeout);
        Duration read = duration("payment.wechat-pay.read-timeout", readTimeout);
        Duration total = duration(
                "payment.wechat-pay.total-operation-timeout", totalOperationTimeout);
        if (total.compareTo(connect.plus(read)) < 0) {
            throw new IllegalStateException(
                    "payment.wechat-pay.total-operation-timeout must not be shorter than connect-timeout plus read-timeout");
        }
        String fingerprint = credentialFingerprint(
                appId, merchantId, certificateSerial, platformPublicKeyId,
                privateKey, apiV3Bytes, publicKey);
        return new WechatPayRuntime(
                appId, merchantId, privateKey, apiV3Bytes, certificateSerial,
                publicKey, platformPublicKeyId, callbackUri, connect, read, total, fingerprint);
    }

    @Bean
    WechatPayGateway wechatPayGateway(
            WechatPayRuntime runtime,
            @Value("${reconciliation.max-bill-bytes:52428800}") int maxBillBytes) {
        return new WechatPaySdkGateway(
                runtime.merchantId(), runtime.merchantPrivateKey(), runtime.certificateSerial(),
                new String(runtime.apiV3Bytes(), StandardCharsets.UTF_8),
                runtime.platformPublicKey(), runtime.platformPublicKeyId(),
                runtime.connectTimeout(), runtime.readTimeout(), runtime.totalOperationTimeout(),
                maxBillBytes);
    }

    @Bean
    WechatPayAdapter wechatPayAdapter(
            WechatPayGateway gateway,
            WechatPayRuntime runtime,
            ObjectMapper objectMapper,
            Clock clock) {
        return new WechatPayAdapter(
                gateway, objectMapper, clock, runtime.appId(), runtime.merchantId(),
                runtime.apiV3Bytes(), runtime.platformPublicKey(), runtime.platformPublicKeyId(),
                runtime.notifyUrl(), runtime.fingerprint());
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
                || normalized.startsWith("your-")) {
            throw new IllegalStateException(property + " must not be a placeholder");
        }
    }

    private PrivateKey privateKey(ResourceLoader loader, String location) {
        String property = "payment.wechat-pay.merchant-private-key-resource";
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
        String property = "payment.wechat-pay.platform-public-key-resource";
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

    private URI notifyUri(String value, Environment environment) {
        try {
            URI uri = URI.create(value);
            boolean local = environment.acceptsProfiles(Profiles.of("local"));
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttp = local && "http".equalsIgnoreCase(uri.getScheme())
                    && isLoopbackHost(uri.getHost());
            if (uri.getHost() == null || !https && !localHttp) {
                throw new IllegalArgumentException("invalid notify URI");
            }
            return uri;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "payment.wechat-pay.notify-url must use HTTPS outside the local profile");
        }
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
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
            String merchantId,
            String certificateSerial,
            String platformPublicKeyId,
            PrivateKey privateKey,
            byte[] apiV3Bytes,
            PublicKey publicKey) {
        byte[] privateKeyBytes = privateKey.getEncoded();
        byte[] publicKeyBytes = publicKey.getEncoded();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, appId.getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, merchantId.getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, certificateSerial.getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, platformPublicKeyId.getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, privateKeyBytes);
            updateDigest(digest, apiV3Bytes);
            updateDigest(digest, publicKeyBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("payment.wechat-pay credential validation failed");
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

    record WechatPayRuntime(
            String appId,
            String merchantId,
            PrivateKey merchantPrivateKey,
            byte[] apiV3Bytes,
            String certificateSerial,
            PublicKey platformPublicKey,
            String platformPublicKeyId,
            URI notifyUrl,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalOperationTimeout,
            String fingerprint) {
        WechatPayRuntime {
            apiV3Bytes = apiV3Bytes.clone();
        }

        @Override
        public byte[] apiV3Bytes() {
            return apiV3Bytes.clone();
        }

        @Override
        public String toString() {
            return "WechatPayRuntime[configured=true,fingerprint=" + fingerprint + "]";
        }
    }

    private static final class UnsupportedRsaSizeException extends RuntimeException {
    }
}
