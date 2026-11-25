package com.yuzhihuihe.apiplatform.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PKCE（RFC 7636）辅助方法。仅支持 S256 变换；
 * plain 变换从不被接受。
 */
final class PkceVerifier {

    private static final Pattern CODE_VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");
    private static final Pattern CODE_CHALLENGE = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");

    private PkceVerifier() {
    }

    static void requireValidChallenge(String codeChallenge) {
        if (codeChallenge == null || !CODE_CHALLENGE.matcher(codeChallenge).matches()) {
            throw OAuthException.invalidRequest(
                    "code_challenge must be a 43 to 128 character base64url string.");
        }
    }

    static void requireS256(String codeChallengeMethod) {
        if (!"S256".equals(codeChallengeMethod)) {
            throw OAuthException.invalidRequest("code_challenge_method must be S256.");
        }
    }

    static void verify(String codeChallenge, String codeVerifier) {
        if (codeVerifier == null || !CODE_VERIFIER.matcher(codeVerifier).matches()) {
            throw OAuthException.pkceVerificationFailed();
        }
        byte[] expected = codeChallenge.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = s256(codeVerifier).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw OAuthException.pkceVerificationFailed();
        }
    }

    /** 为校验码（verifier）计算 S256 授权码挑战值（code challenge）。 */
    static String s256(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("sha256_unavailable", error);
        }
    }
}
