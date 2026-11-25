package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 公网匿名读图签名的准入语义。
 *
 * <p>这里守的是一条**免鉴权的公网端点**，所以每条拒绝路径都必须逐条钉住，而不是
 * 只测一条 happy path：只要签名可以被跨文件复用、可以被改期、或者在密钥没配时
 * 仍然放行，任何人都能枚举并读走对象存储里的图片。
 */
class FileContentTokenSignerTest {

    private static final String FILE_ID = "file_0123456789abcdef0123456789abcdef";
    private static final String OTHER_FILE_ID = "file_fedcba9876543210fedcba9876543210";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    @Test
    void signsAnAddressThatCarriesNothingButTheOpaqueFileId() {
        String url = signer(SECRET, TTL).signedUrl(FILE_ID, NOW).orElseThrow();

        assertThat(url).startsWith("https://api.yuzhh.com/v1/files/" + FILE_ID + "/content?");
        assertThat(url).contains("exp=" + NOW.plus(TTL).getEpochSecond());
        assertThat(url).contains("sig=");
        // 这个地址会被转发给第三方厂商，因此它不得携带对象存储的键或原始文件名。
        assertThat(url).doesNotContain("clean/").doesNotContain(".png").doesNotContain("TENANT");
    }

    @Test
    void acceptsItsOwnSignatureUpToButNotIncludingTheExpiry() {
        FileContentTokenSigner signer = signer(SECRET, TTL);
        URI uri = URI.create(signer.signedUrl(FILE_ID, NOW).orElseThrow());
        String expires = parameter(uri, "exp");
        String signature = parameter(uri, "sig");

        assertThatCode(() -> signer.requireValid(FILE_ID, expires, signature, NOW))
                .doesNotThrowAnyException();
        // 有效期最后一秒仍然可用：厂商的排队与重试都发生在这一侧。
        assertThatCode(() -> signer.requireValid(
                FILE_ID, expires, signature, NOW.plus(TTL).minusSeconds(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTheAddressFromTheExpiryMomentOnward() {
        FileContentTokenSigner signer = signer(SECRET, TTL);
        URI uri = URI.create(signer.signedUrl(FILE_ID, NOW).orElseThrow());

        assertNotFound(signer, FILE_ID, parameter(uri, "exp"), parameter(uri, "sig"),
                NOW.plus(TTL));
        assertNotFound(signer, FILE_ID, parameter(uri, "exp"), parameter(uri, "sig"),
                NOW.plus(TTL).plusSeconds(1));
    }

    @Test
    void neverLetsASignatureBeReusedForAnotherFile() {
        // 跨文件复用是这类端点最典型的越权方式：签名必须与 fileId 绑死。
        FileContentTokenSigner signer = signer(SECRET, TTL);
        URI uri = URI.create(signer.signedUrl(FILE_ID, NOW).orElseThrow());

        assertNotFound(signer, OTHER_FILE_ID, parameter(uri, "exp"), parameter(uri, "sig"), NOW);
    }

    @Test
    void rejectsATamperedExpiry() {
        FileContentTokenSigner signer = signer(SECRET, TTL);
        URI uri = URI.create(signer.signedUrl(FILE_ID, NOW).orElseThrow());
        String extended = String.valueOf(
                NOW.plus(TTL).plus(Duration.ofDays(365)).getEpochSecond());

        // 把 exp 改到一年后，但签名仍是原来那个：必须被拒，否则有效期形同虚设。
        assertNotFound(signer, FILE_ID, extended, parameter(uri, "sig"), NOW);
    }

    @Test
    void rejectsMissingOrMalformedParameters() {
        FileContentTokenSigner signer = signer(SECRET, TTL);
        URI uri = URI.create(signer.signedUrl(FILE_ID, NOW).orElseThrow());

        assertNotFound(signer, FILE_ID, null, parameter(uri, "sig"), NOW);
        assertNotFound(signer, FILE_ID, parameter(uri, "exp"), null, NOW);
        assertNotFound(signer, FILE_ID, parameter(uri, "exp"), "", NOW);
        assertNotFound(signer, FILE_ID, "not-a-number", parameter(uri, "sig"), NOW);
        assertNotFound(signer, null, parameter(uri, "exp"), parameter(uri, "sig"), NOW);
    }

    @Test
    void isDisabledAndRejectsEverythingWhenNoSecretIsConfigured() {
        FileContentTokenSigner disabled = signer("", TTL);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.signedUrl(FILE_ID, NOW)).isEmpty();
        // 关掉不是「跳过校验」：没有密钥时一次也不放行。
        assertNotFound(disabled, FILE_ID, "99999999999", "whatever", NOW);
    }

    @Test
    void refusesToStartOnASecretThatIsTooShortToBeASecret() {
        // 短密钥可以被离线暴力破解，而破解者拿到的是「读任意已上传文件」的能力，
        // 所以这在启动期就该失败，而不是等到被人利用。
        assertThatThrownBy(() -> signer(
                Base64.getEncoder().encodeToString(new byte[16]), TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content_token_secret_must_be_at_least_256_bits");
    }

    @Test
    void refusesToStartOnASecretThatIsNotBase64() {
        assertThatThrownBy(() -> signer("not base64!!", TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content_token_secret_must_be_base64");
    }

    private static void assertNotFound(
            FileContentTokenSigner signer, String fileId, String expires, String signature,
            Instant now) {
        assertThatThrownBy(() -> signer.requireValid(fileId, expires, signature, now))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getReason()).isEqualTo(FileContentTokenSigner.NOT_FOUND);
                });
    }

    private static String parameter(URI uri, String name) {
        for (String pair : uri.getQuery().split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue[0].equals(name)) {
                return keyValue[1];
            }
        }
        throw new IllegalArgumentException("missing_parameter:" + name);
    }

    private static FileContentTokenSigner signer(String secret, Duration ttl) {
        FileContentTokenProperties properties = new FileContentTokenProperties();
        properties.setSecret(secret);
        properties.setTtl(ttl);
        properties.setPublicBaseUrl(URI.create("https://api.yuzhh.com"));
        return new FileContentTokenSigner(properties);
    }
}
