package com.yuzhihuihe.apiplatform.platform.sms;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class SliderCaptchaService {

    static final int IMAGE_WIDTH = 320;
    static final int IMAGE_HEIGHT = 160;
    static final int PIECE_SIZE = 48;
    private static final int POSITION_TOLERANCE = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final String CHALLENGE_PREFIX = "identity:slider-captcha:v1:challenge:";
    private static final String PROOF_PREFIX = "identity:slider-captcha:v1:proof:";
    private static final String ISSUE_PREFIX = "identity:slider-captcha:v1:issue:";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private static final RedisScript<Long> RESERVE_ISSUE = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if count > tonumber(ARGV[2]) then
              redis.call('DECR', KEYS[1])
              return 0
            end
            return count
            """, Long.class);

    private static final RedisScript<Long> STORE_CHALLENGE = RedisScript.of("""
            redis.call('HSET', KEYS[1],
              'ip', ARGV[1], 'target', ARGV[2], 'attempts', '0')
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private static final RedisScript<Long> VERIFY = RedisScript.of("""
            local expected_ip = redis.call('HGET', KEYS[1], 'ip')
            if not expected_ip then return -1 end
            if expected_ip ~= ARGV[1] then return -2 end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            local target = tonumber(redis.call('HGET', KEYS[1], 'target'))
            local supplied = tonumber(ARGV[2])
            if target and supplied and math.abs(target - supplied) <= tonumber(ARGV[3]) then
              local stored = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'EX', ARGV[5])
              if not stored then return -4 end
              redis.call('DEL', KEYS[1])
              return 1
            end
            if attempts >= tonumber(ARGV[4]) then
              redis.call('DEL', KEYS[1])
              return -3
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RESERVE_PROOF = RedisScript.of("""
            local expected_ip = redis.call('GET', KEYS[1])
            if not expected_ip then return 0 end
            if expected_ip ~= ARGV[1] then return -1 end
            local remaining_millis = redis.call('PTTL', KEYS[1])
            if remaining_millis <= 0 then
              redis.call('DEL', KEYS[1])
              return 0
            end
            redis.call('DEL', KEYS[1])
            return remaining_millis
            """, Long.class);

    private static final RedisScript<Long> RESTORE_PROOF = RedisScript.of("""
            local restored = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
            if restored then return 1 end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SecureRandom random;
    private final boolean enabled;
    private final Duration challengeLifetime;
    private final Duration proofLifetime;
    private final int issueLimit;

    @Autowired
    public SliderCaptchaService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            Clock clock,
            @Value("${sms.enabled:false}") boolean smsEnabled,
            @Value("${sms.captcha.enabled:true}") boolean captchaEnabled,
            @Value("${sms.captcha.challenge-lifetime:120s}") Duration challengeLifetime,
            @Value("${sms.captcha.proof-lifetime:120s}") Duration proofLifetime,
            @Value("${sms.captcha.issue-ip-limit:10}") int issueLimit) {
        this(redisProvider.getIfAvailable(), clock, new SecureRandom(),
                smsEnabled && captchaEnabled, challengeLifetime, proofLifetime, issueLimit);
    }

    SliderCaptchaService(
            StringRedisTemplate redis,
            Clock clock,
            SecureRandom random,
            boolean enabled,
            Duration challengeLifetime,
            Duration proofLifetime,
            int issueLimit) {
        this.redis = redis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.enabled = enabled;
        this.challengeLifetime = positive(challengeLifetime, "captcha challenge lifetime");
        this.proofLifetime = positive(proofLifetime, "captcha proof lifetime");
        this.issueLimit = positive(issueLimit, "captcha issue IP limit");
    }

    static SliderCaptchaService disabled() {
        return new SliderCaptchaService(
                null, Clock.systemUTC(), new SecureRandom(), false,
                Duration.ofMinutes(2), Duration.ofMinutes(2), 10);
    }

    public Challenge issue(String requestIpHash) {
        requireEnabled();
        requireFingerprint(requestIpHash);
        reserveIssue(requestIpHash);

        UUID id = UUID.randomUUID();
        int targetX = 72 + random.nextInt(IMAGE_WIDTH - PIECE_SIZE - 88);
        int pieceY = 14 + random.nextInt(IMAGE_HEIGHT - PIECE_SIZE - 28);
        PuzzleImages images;
        try {
            images = renderPuzzle(targetX, pieceY);
            Long stored = redis.execute(
                    STORE_CHALLENGE,
                    List.of(challengeKey(id)),
                    requestIpHash,
                    Integer.toString(targetX),
                    Long.toString(challengeLifetime.toSeconds()));
            if (stored == null || stored != 1) {
                throw unavailable();
            }
        } catch (SmsApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable();
        }
        return new Challenge(
                id,
                images.backgroundImage(),
                images.pieceImage(),
                pieceY,
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                PIECE_SIZE,
                clock.instant().plus(challengeLifetime));
    }

    public VerifiedProof verify(UUID challengeId, Integer offsetX, String requestIpHash) {
        requireEnabled();
        requireFingerprint(requestIpHash);
        if (challengeId == null || offsetX == null
                || offsetX < 0 || offsetX > IMAGE_WIDTH - PIECE_SIZE) {
            throw invalid();
        }
        String proof = randomToken();
        Long result;
        try {
            result = redis.execute(
                    VERIFY,
                    List.of(challengeKey(challengeId), proofKey(proof)),
                    requestIpHash,
                    Integer.toString(offsetX),
                    Integer.toString(POSITION_TOLERANCE),
                    Integer.toString(MAX_ATTEMPTS),
                    Long.toString(proofLifetime.toSeconds()));
        } catch (RuntimeException error) {
            throw unavailable();
        }
        if (result == null || result == -4) {
            throw unavailable();
        }
        if (result == -1) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "captcha_expired");
        }
        if (result == -3) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "captcha_attempts_exhausted");
        }
        if (result != 1) {
            throw invalid();
        }
        return new VerifiedProof(proof, clock.instant().plus(proofLifetime));
    }

    ProofReservation reserveProof(String proof, String requestIpHash) {
        if (!enabled) {
            return ProofReservation.bypassed();
        }
        requireFingerprint(requestIpHash);
        if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "captcha_required");
        }
        String key = proofKey(proof);
        Long result;
        try {
            result = redis.execute(RESERVE_PROOF, List.of(key), requestIpHash);
        } catch (RuntimeException error) {
            throw unavailable();
        }
        if (result == null) {
            throw unavailable();
        }
        if (result <= 0) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "captcha_invalid");
        }
        return new ProofReservation(
                key,
                requestIpHash,
                clock.instant().plusMillis(result),
                false);
    }

    void release(ProofReservation reservation) {
        if (reservation == null || reservation.bypass() || !reservation.beginRelease()) {
            return;
        }
        long remainingMillis = Duration.between(clock.instant(), reservation.expiresAt()).toMillis();
        if (remainingMillis <= 0) {
            return;
        }
        try {
            redis.execute(
                    RESTORE_PROOF,
                    List.of(reservation.key()),
                    reservation.requestIpHash(),
                    Long.toString(remainingMillis));
        } catch (RuntimeException ignored) {
            // 恢复失败会使该凭证保持已消费状态，这正是安全的失败模式。
        }
    }

    private void reserveIssue(String requestIpHash) {
        String key = ISSUE_PREFIX + requestIpHash + ":" + clock.instant().getEpochSecond() / 60;
        try {
            Long result = redis.execute(
                    RESERVE_ISSUE, List.of(key), "120", Integer.toString(issueLimit));
            if (result == null || result < 0 || result > issueLimit) {
                throw unavailable();
            }
            if (result == 0) {
                throw new SmsApiException(HttpStatus.TOO_MANY_REQUESTS, "captcha_rate_limited");
            }
        } catch (SmsApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private PuzzleImages renderPuzzle(int targetX, int pieceY) {
        try {
            BufferedImage original = new BufferedImage(
                    IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = original.createGraphics();
            configure(graphics);
            Color[] palette = {
                    new Color(222, 235, 248), new Color(201, 222, 241),
                    new Color(232, 240, 231), new Color(247, 231, 205),
                    new Color(214, 229, 220)
            };
            graphics.setColor(palette[random.nextInt(palette.length)]);
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            for (int band = 0; band < 7; band++) {
                graphics.setColor(palette[random.nextInt(palette.length)]);
                int x = band * 52 - 18 + random.nextInt(20);
                graphics.fillRoundRect(x, -12, 74, IMAGE_HEIGHT + 24, 36, 36);
            }
            for (int shape = 0; shape < 32; shape++) {
                int alpha = 45 + random.nextInt(70);
                graphics.setColor(new Color(
                        36 + random.nextInt(130),
                        70 + random.nextInt(130),
                        100 + random.nextInt(130),
                        alpha));
                int size = 7 + random.nextInt(22);
                graphics.fillOval(
                        random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT), size, size);
            }
            graphics.setStroke(new BasicStroke(2f));
            for (int line = 0; line < 6; line++) {
                graphics.setColor(new Color(255, 255, 255, 120));
                int y = 12 + random.nextInt(IMAGE_HEIGHT - 24);
                graphics.drawLine(0, y, IMAGE_WIDTH, Math.max(0, Math.min(IMAGE_HEIGHT, y - 24 + random.nextInt(49))));
            }
            graphics.dispose();

            BufferedImage background = new BufferedImage(
                    IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D backgroundGraphics = background.createGraphics();
            configure(backgroundGraphics);
            backgroundGraphics.drawImage(original, 0, 0, null);
            Area targetShape = puzzleShape(targetX, pieceY);
            backgroundGraphics.setColor(new Color(22, 42, 70, 125));
            backgroundGraphics.fill(targetShape);
            backgroundGraphics.setColor(new Color(255, 255, 255, 205));
            backgroundGraphics.setStroke(new BasicStroke(2f));
            backgroundGraphics.draw(targetShape);
            backgroundGraphics.dispose();

            BufferedImage piece = new BufferedImage(
                    PIECE_SIZE, PIECE_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pieceGraphics = piece.createGraphics();
            configure(pieceGraphics);
            Area localShape = puzzleShape(0, 0);
            pieceGraphics.setClip(localShape);
            pieceGraphics.drawImage(original, -targetX, -pieceY, null);
            pieceGraphics.setClip(null);
            pieceGraphics.setComposite(AlphaComposite.SrcOver);
            pieceGraphics.setColor(new Color(255, 255, 255, 230));
            pieceGraphics.setStroke(new BasicStroke(2f));
            pieceGraphics.draw(localShape);
            pieceGraphics.dispose();

            return new PuzzleImages(dataUri(background), dataUri(piece));
        } catch (Exception error) {
            throw unavailable();
        }
    }

    private Area puzzleShape(int x, int y) {
        Area shape = new Area(new RoundRectangle2D.Double(x + 6, y + 6, 36, 36, 5, 5));
        shape.add(new Area(new Ellipse2D.Double(x + 18, y, 12, 12)));
        shape.add(new Area(new Ellipse2D.Double(x + 36, y + 18, 12, 12)));
        shape.subtract(new Area(new Ellipse2D.Double(x, y + 18, 12, 12)));
        shape.subtract(new Area(new Ellipse2D.Double(x + 18, y + 36, 12, 12)));
        return shape;
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private String dataUri(BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IllegalStateException("PNG writer unavailable");
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String challengeKey(UUID id) {
        return CHALLENGE_PREFIX + id;
    }

    private String proofKey(String proof) {
        return PROOF_PREFIX + proof;
    }

    private void requireEnabled() {
        if (!enabled || redis == null) {
            throw unavailable();
        }
    }

    private void requireFingerprint(String requestIpHash) {
        if (requestIpHash == null || !FINGERPRINT.matcher(requestIpHash).matches()) {
            throw unavailable();
        }
    }

    private SmsApiException invalid() {
        return new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "captcha_invalid");
    }

    private SmsApiException unavailable() {
        return new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "captcha_unavailable");
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public record Challenge(
            UUID id,
            String backgroundImage,
            String pieceImage,
            int pieceY,
            int imageWidth,
            int imageHeight,
            int pieceSize,
            Instant expiresAt) { }

    public record VerifiedProof(String proof, Instant expiresAt) { }

    record ProofReservation(
            String key,
            String requestIpHash,
            Instant expiresAt,
            boolean bypass,
            AtomicBoolean released) {

        ProofReservation(String key, String requestIpHash, Instant expiresAt, boolean bypass) {
            this(key, requestIpHash, expiresAt, bypass, new AtomicBoolean(false));
        }

        static ProofReservation bypassed() {
            return new ProofReservation(null, null, null, true);
        }

        boolean beginRelease() {
            return released.compareAndSet(false, true);
        }
    }

    private record PuzzleImages(String backgroundImage, String pieceImage) { }
}
