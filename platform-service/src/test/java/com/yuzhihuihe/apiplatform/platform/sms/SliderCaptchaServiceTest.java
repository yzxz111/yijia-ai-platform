package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SliderCaptchaServiceTest {

    private static final String IP = "a".repeat(64);
    private static final String OTHER_IP = "b".repeat(64);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    final SmsPostgresTestSupport.MutableClock clock =
            new SmsPostgresTestSupport.MutableClock(Instant.parse("2026-08-18T08:00:00Z"));

    SliderCaptchaService service;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void reset() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        service = new SliderCaptchaService(
                redis, clock, new SecureRandom(), true,
                Duration.ofMinutes(2), Duration.ofMinutes(2), 10);
    }

    @Test
    void issuesPngPuzzleWithoutExposingTheTargetAndCreatesOneTimeProof() throws Exception {
        SliderCaptchaService.Challenge challenge = service.issue(IP);

        assertThat(challenge.imageWidth()).isEqualTo(320);
        assertThat(challenge.imageHeight()).isEqualTo(160);
        assertThat(challenge.pieceSize()).isEqualTo(48);
        assertPng(challenge.backgroundImage(), 320, 160);
        assertPng(challenge.pieceImage(), 48, 48);
        assertThat(challenge.expiresAt()).isEqualTo(clock.instant().plusSeconds(120));

        int target = storedTarget(challenge);
        SliderCaptchaService.VerifiedProof verified = service.verify(challenge.id(), target, IP);
        assertThat(verified.proof()).matches("[A-Za-z0-9_-]{43}");

        SliderCaptchaService.ProofReservation reservation =
                service.reserveProof(verified.proof(), IP);
        assertThat(reservation.bypass()).isFalse();
        assertInvalidCaptcha(() -> service.reserveProof(verified.proof(), IP));
    }

    @Test
    void rejectsOtherIpAndExhaustsWrongPositionAfterFiveAttempts() {
        SliderCaptchaService.Challenge challenge = service.issue(IP);
        int target = storedTarget(challenge);

        assertInvalidCaptcha(() -> service.verify(challenge.id(), target, OTHER_IP));
        for (int attempt = 1; attempt < 5; attempt++) {
            assertInvalidCaptcha(() -> service.verify(challenge.id(), wrongOffset(target), IP));
        }
        assertThatThrownBy(() -> service.verify(challenge.id(), wrongOffset(target), IP))
                .isInstanceOfSatisfying(SmsApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.code()).isEqualTo("captcha_attempts_exhausted");
                });
        assertThatThrownBy(() -> service.verify(challenge.id(), target, IP))
                .isInstanceOfSatisfying(SmsApiException.class,
                        error -> assertThat(error.code()).isEqualTo("captcha_expired"));
    }

    @Test
    void releasesFailedSmsReservationOnlyOnceForLoginRegistrationFallback() {
        SliderCaptchaService.Challenge challenge = service.issue(IP);
        SliderCaptchaService.VerifiedProof verified =
                service.verify(challenge.id(), storedTarget(challenge), IP);
        SliderCaptchaService.ProofReservation first = service.reserveProof(verified.proof(), IP);

        service.release(first);
        service.release(first);

        assertThat(service.reserveProof(verified.proof(), IP)).isNotNull();
        assertInvalidCaptcha(() -> service.reserveProof(verified.proof(), IP));
    }

    @Test
    void doesNotRestoreAProofAfterItsOriginalExpiry() {
        SliderCaptchaService.Challenge challenge = service.issue(IP);
        SliderCaptchaService.VerifiedProof verified =
                service.verify(challenge.id(), storedTarget(challenge), IP);
        SliderCaptchaService.ProofReservation reservation =
                service.reserveProof(verified.proof(), IP);

        clock.advance(Duration.ofMinutes(3));
        service.release(reservation);

        assertInvalidCaptcha(() -> service.reserveProof(verified.proof(), IP));
    }

    @Test
    void limitsPuzzleIssuancePerIpAndFailsClosedWithoutRedis() {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(service.issue(IP)).isNotNull();
        }
        assertThatThrownBy(() -> service.issue(IP))
                .isInstanceOfSatisfying(SmsApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.code()).isEqualTo("captcha_rate_limited");
                });

        SliderCaptchaService unavailable = new SliderCaptchaService(
                null, clock, new SecureRandom(), true,
                Duration.ofMinutes(2), Duration.ofMinutes(2), 10);
        assertThatThrownBy(() -> unavailable.issue(IP))
                .isInstanceOfSatisfying(SmsApiException.class,
                        error -> assertThat(error.code()).isEqualTo("captcha_unavailable"));
    }

    private int storedTarget(SliderCaptchaService.Challenge challenge) {
        Object value = redis.opsForHash().get(
                "identity:slider-captcha:v1:challenge:" + challenge.id(), "target");
        return Integer.parseInt(String.valueOf(value));
    }

    private int wrongOffset(int target) {
        return target > 30 ? 0 : SliderCaptchaService.IMAGE_WIDTH - SliderCaptchaService.PIECE_SIZE;
    }

    private void assertPng(String dataUri, int width, int height) throws Exception {
        assertThat(dataUri).startsWith("data:image/png;base64,");
        byte[] bytes = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(width);
        assertThat(image.getHeight()).isEqualTo(height);
    }

    private void assertInvalidCaptcha(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SmsApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(error.code()).isEqualTo("captcha_invalid");
        });
    }
}
