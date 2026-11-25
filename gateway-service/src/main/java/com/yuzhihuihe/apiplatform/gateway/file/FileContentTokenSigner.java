package com.yuzhihuihe.apiplatform.gateway.file;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 公网匿名读图地址的签名。
 *
 * <p><b>签名只绑定 {@code (fileId, exp)} 两个值。</b>于是地址里只有不透明的文件 id：
 * 没有对象存储 key、没有原始文件名、没有归属信息。一个被转发出去的地址不会顺带
 * 泄露任何内部结构，也不会因为「谁是作者」而变成一个身份凭证。
 *
 * <p>厂商（天翼）不会带我们的 {@code Authorization} 头，因此这个端点必须匿名——
 * **签名就是这一次读取的凭证**，而它的权限恰好只有「读这一张图，到某个时刻为止」。
 *
 * <p>校验失败一律 404 {@link #NOT_FOUND}，不区分「文件不存在」「没有签名」「签名错」
 * 「已过期」：区分它们等于给枚举者一个反馈信号，而正确的做法是让他无法从响应里
 * 分辨自己猜到了什么。
 */
public final class FileContentTokenSigner {

    /** 与 URL 里的 {@code fileId} 一样是稳定的对外字符串，客户端与测试都据它断言。 */
    static final String NOT_FOUND = "file_content_not_found";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final byte[] secret;
    private final Duration ttl;
    private final String publicBaseUrl;

    public FileContentTokenSigner(FileContentTokenProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("invalid_file_content_token_properties");
        }
        String configured = properties.getSecret() == null ? "" : properties.getSecret().strip();
        this.secret = configured.isEmpty() ? null : decode(configured);
        this.ttl = properties.getTtl() == null ? Duration.ofMinutes(10) : properties.getTtl();
        String base = properties.getPublicBaseUrl() == null
                ? "https://api.yuzhh.com"
                : properties.getPublicBaseUrl().toString();
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 未配置密钥时为假：功能关闭，调用方据此明确失败而不是发出一个无效地址。 */
    public boolean enabled() {
        return secret != null;
    }

    /** 现签一个短时效地址。功能关闭、或文件 id 非法时返回空。 */
    public Optional<String> signedUrl(String externalFileId, Instant now) {
        if (secret == null || externalFileId == null || externalFileId.isBlank() || now == null) {
            return Optional.empty();
        }
        long expiresAt = now.plus(ttl).getEpochSecond();
        return Optional.of(publicBaseUrl + "/v1/files/" + externalFileId + "/content?exp="
                + expiresAt + "&sig=" + signature(externalFileId, expiresAt));
    }

    /**
     * 校验一次读取。任何不通过都抛 404 {@link #NOT_FOUND}。
     *
     * <p>比较用 {@link MessageDigest#isEqual}：把「逐字节早退」这条侧信道关掉，
     * 否则一个能反复试探的调用方可以从响应耗时里逐位还原出正确签名。
     */
    public void requireValid(String externalFileId, String expires, String signature, Instant now) {
        if (secret == null
                || externalFileId == null || externalFileId.isBlank()
                || expires == null || expires.isBlank()
                || signature == null || signature.isBlank()
                || now == null) {
            throw notFound();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(expires.strip());
        } catch (NumberFormatException invalid) {
            throw notFound();
        }
        // 毫秒比较而不是秒比较：秒级的边界留给调用方一次「刚好过期」的歧义，
        // 而这里没有理由让边界含糊。
        if (expiresAt * 1000L <= now.toEpochMilli()) {
            throw notFound();
        }
        byte[] expected = signature(externalFileId, expiresAt).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = signature.strip().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw notFound();
        }
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }

    private String signature(String externalFileId, long expiresAt) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] payload = (externalFileId + "|" + expiresAt).getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("content_token_signing_failed", failure);
        }
    }

    /**
     * 密钥太短就不是「配置得不够好」，而是「没有提供安全边界」：
     * 一个 8 字节的 HMAC 密钥可以被离线暴力破解，而破解者拿到的是
     * 「读任意已上传文件」的能力。因此这里宁可在启动期就失败。
     */
    private static byte[] decode(String configured) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("content_token_secret_must_be_base64");
        }
        if (decoded.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("content_token_secret_must_be_at_least_256_bits");
        }
        return decoded;
    }
}
