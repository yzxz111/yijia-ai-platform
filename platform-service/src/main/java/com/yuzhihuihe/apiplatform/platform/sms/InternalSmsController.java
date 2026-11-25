package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import com.yuzhihuihe.apiplatform.platform.subscription.SubscriptionExpiryNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/platform/sms")
public class InternalSmsController {

    private final SmsProofService proofs;
    private final CommercialNotificationService notifications;
    /**
     * 到期提醒的订阅分流器（设计 §9.4）。
     *
     * <p>用 {@code ObjectProvider} 获取：一是与本类既有的 {@code SmsProofService} 同一写法，
     * 二是让「没有订阅模块」的测试装配仍能构造本类，此时自动退回既有的套餐到期文案。
     */
    private final SubscriptionExpiryNotifier expiryNotifier;
    private final ObjectMapper objectMapper;
    private final InternalRequestSigner signer;
    private final Clock clock;

    @Autowired
    public InternalSmsController(
            ObjectProvider<SmsProofService> proofs,
            ObjectProvider<CommercialNotificationService> notifications,
            ObjectProvider<SubscriptionExpiryNotifier> expiryNotifier,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this(proofs.getIfAvailable(), notifications.getIfAvailable(),
                expiryNotifier.getIfAvailable(), objectMapper, clock, hmacSecret);
    }

    InternalSmsController(
            SmsProofService proofs,
            CommercialNotificationService notifications,
            ObjectMapper objectMapper,
            Clock clock,
            String hmacSecret) {
        this(proofs, notifications, null, objectMapper, clock, hmacSecret);
    }

    InternalSmsController(
            SmsProofService proofs,
            CommercialNotificationService notifications,
            SubscriptionExpiryNotifier expiryNotifier,
            ObjectMapper objectMapper,
            Clock clock,
            String hmacSecret) {
        this.proofs = proofs;
        this.notifications = notifications;
        this.expiryNotifier = expiryNotifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.signer = new InternalRequestSigner(hmacSecret, clock);
    }

    @PostMapping("/proofs/consume")
    SignedInternalResponse consumeProof(
            @RequestHeader("X-Internal-Request-Id") UUID requestId,
            @RequestBody ProofConsumeRequest request) {
        if (proofs == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
        try {
            if (request == null) {
                throw new IllegalArgumentException("invalid_sms_proof");
            }
            proofs.consumeForOperation(
                    request.proof(), request.tenantId(), request.userId(),
                    SmsPurpose.API_KEY_RESET, request.operationId());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_proof");
        } catch (ResponseStatusException error) {
            throw new IllegalStateException("sms_proof_consume_failed", error);
        }
        return signed(requestId, Map.of("consumed", true));
    }

    @PostMapping("/signals/package-expiry")
    SignedInternalResponse packageExpiry(
            @RequestHeader("X-Internal-Request-Id") UUID requestId,
            @RequestBody PackageExpirySignal request) {
        requireNotifications();
        if (request == null || request.tenantId() == null || request.userId() == null
                || request.packageId() == null || request.expiresAt() == null) {
            throw invalidSignal();
        }
        Instant now = clock.instant();
        if (request.expiresAt().isAfter(now.plus(Duration.ofDays(3)))) {
            throw invalidSignal();
        }
        // 订阅用户走「将于 X 日自动续费 ¥Y」，非订阅用户保持既有的「套餐即将到期」。
        // 分流只发生在这个调用点，notifyPackageExpiry 本身的分支语义一字未改——
        // 那条路径承载着所有非订阅用户的到期提醒，改它的风险远大于收益。
        if (expiryNotifier != null) {
            expiryNotifier.notifyExpiry(
                    request.tenantId(), request.userId(), request.packageId(), request.expiresAt());
        } else {
            notifications.notifyPackageExpiry(
                    request.tenantId(), request.userId(), request.packageId(), request.expiresAt());
        }
        return signed(requestId, Map.of("accepted", true));
    }

    @PostMapping("/signals/rate-limit")
    SignedInternalResponse rateLimit(
            @RequestHeader("X-Internal-Request-Id") UUID requestId,
            @RequestBody RateLimitSignal request) {
        requireNotifications();
        if (request == null || request.tenantId() == null || request.userId() == null
                || request.modelCode() == null
                || !request.modelCode().matches("[A-Za-z0-9._:/-]{1,128}")
                || request.cooldownBucket() < 0) {
            throw invalidSignal();
        }
        notifications.notifyApiRateLimited(
                request.tenantId(), request.userId(),
                request.modelCode(), request.cooldownBucket());
        return signed(requestId, Map.of("accepted", true));
    }

    private void requireNotifications() {
        if (notifications == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
    }

    private ResponseStatusException invalidSignal() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_signal");
    }

    private SignedInternalResponse signed(UUID requestId, Object payload) {
        try {
            InternalRequestSigner.SignedResponse response = signer.signResponse(
                    requestId, HttpStatus.OK.value(), objectMapper.writeValueAsBytes(payload));
            return new SignedInternalResponse(
                    response.requestId(), response.timestamp(), response.contentSha256(),
                    response.signature(), response.payloadBase64());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "internal_response_unavailable");
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "internal_response_serialization_failed");
        }
    }

    public record ProofConsumeRequest(
            String proof, UUID tenantId, UUID userId, UUID operationId) {
    }

    public record PackageExpirySignal(
            UUID tenantId, UUID userId, UUID packageId, Instant expiresAt) {
    }

    public record RateLimitSignal(
            UUID tenantId, UUID userId, String modelCode, long cooldownBucket) {
    }

    public record SignedInternalResponse(
            UUID requestId,
            long timestamp,
            String contentSha256,
            String signature,
            String payloadBase64) {
    }
}
