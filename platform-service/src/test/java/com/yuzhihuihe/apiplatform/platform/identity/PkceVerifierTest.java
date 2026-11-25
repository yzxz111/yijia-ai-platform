package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PkceVerifierTest {

    /** RFC 7636 附录 B 的测试向量。 */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void derivesTheRfc7636S256Challenge() {
        assertThat(PkceVerifier.s256(VERIFIER)).isEqualTo(CHALLENGE);
    }

    @Test
    void acceptsTheMatchingVerifier() {
        assertThatCode(() -> PkceVerifier.verify(CHALLENGE, VERIFIER))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAWrongVerifier() {
        assertThatThrownBy(() -> PkceVerifier.verify(CHALLENGE, VERIFIER + "x"))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("pkce_verification_failed");
    }

    @Test
    void rejectsAMalformedVerifier() {
        assertThatThrownBy(() -> PkceVerifier.verify(CHALLENGE, "too-short"))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("pkce_verification_failed");
    }

    @Test
    void rejectsPlainAndMalformedChallenges() {
        assertThatThrownBy(() -> PkceVerifier.requireS256("plain"))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("invalid_request");

        assertThatThrownBy(() -> PkceVerifier.requireValidChallenge("short"))
                .isInstanceOf(OAuthException.class)
                .extracting(error -> ((OAuthException) error).error())
                .isEqualTo("invalid_request");

        assertThatCode(() -> PkceVerifier.requireValidChallenge(CHALLENGE))
                .doesNotThrowAnyException();
    }
}
