package com.yuzhihuihe.apiplatform.platform.identity;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class LoginActivityService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String LOCAL_PEPPER = "local-login-risk-pepper-not-for-production";

    private final LoginActivityRepository repository;
    private final NotificationEventService events;
    private final IpLocationResolver locations;
    private final LoginRiskEvaluator evaluator;
    private final Clock clock;
    private final byte[] pepper;

    @Autowired
    public LoginActivityService(
            LoginActivityRepository repository,
            NotificationEventService events,
            IpLocationResolver locations,
            LoginRiskEvaluator evaluator,
            @Value("${identity.login-risk.pepper:}") String configuredPepper,
            Environment environment) {
        this(repository, events, locations, evaluator, Clock.systemUTC(), configuredPepper,
                allowsLocalFixture(environment));
    }

    LoginActivityService(
            LoginActivityRepository repository,
            NotificationEventService events,
            IpLocationResolver locations,
            LoginRiskEvaluator evaluator,
            Clock clock,
            String configuredPepper,
            boolean allowLocalFixture) {
        this.repository = repository;
        this.events = events;
        this.locations = locations;
        this.evaluator = evaluator;
        this.clock = clock;
        String effectivePepper = configuredPepper == null || configuredPepper.isBlank()
                ? (allowLocalFixture ? LOCAL_PEPPER : null) : configuredPepper;
        if (effectivePepper == null || effectivePepper.length() < 32) {
            throw new IllegalStateException("identity_login_risk_pepper_is_required");
        }
        this.pepper = effectivePepper.getBytes(StandardCharsets.UTF_8);
    }

    public void recordSuccess(IdentityService.UserProfile user, LoginContext context) {
        if (user == null) return;
        LoginContext safeContext = context == null ? LoginContext.empty() : context;
        String rawIp = normalizedValue(safeContext.ipAddress());
        String network = network(rawIp);
        String ipHmac = hmac("ip", rawIp);
        String subnetHmac = hmac("network", network);
        String deviceHash = hmac("device", normalizedValue(safeContext.deviceId()));
        String userAgentHash = hmac("user-agent", normalizedValue(safeContext.userAgent()));
        String region = location(rawIp);

        boolean knownDevice = repository.knownDevice(user.id(), deviceHash);
        boolean knownNetwork = repository.knownNetwork(user.id(), subnetHmac);
        String previousRegion = repository.previousRegion(user.id());
        LoginRiskEvaluator.RiskLevel risk = evaluator.evaluate(
                knownDevice, knownNetwork, previousRegion, region);
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        repository.insert(
                user.tenantId(), user.id(), ipHmac, subnetHmac, deviceHash,
                userAgentHash, nullableRegion(region), occurredAt);

        if (risk == LoginRiskEvaluator.RiskLevel.LOW) return;
        LocalDate day = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        String dedupHash = hmac("event-dedup", deviceHash + ":" + subnetHmac);
        String eventKey = "risk-login:v1:%s:%s:%s".formatted(user.id(), dedupHash, day);
        repository.lockEventKey(eventKey);
        if (repository.eventExists(eventKey)) return;
        events.recordAuthoritativeBusinessEvent(eventKey, "RISK_LOGIN", user.tenantId(), user.id(),
                "LOGIN_ACTIVITY", eventKey,
                Map.of("loginAddr", displayRegion(region), "riskLevel", risk.name()),
                occurredAt);
    }

    private static boolean allowsLocalFixture(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 && Arrays.stream(activeProfiles)
                .allMatch(profile -> "local".equals(profile) || "test".equals(profile));
    }

    private String location(String rawIp) {
        try {
            String resolved = locations.resolve(rawIp);
            return resolved == null || resolved.isBlank() ? IpLocationResolver.UNKNOWN : resolved;
        } catch (RuntimeException ignored) {
            return IpLocationResolver.UNKNOWN;
        }
    }

    private String network(String rawIp) {
        try {
            if (!isIpLiteral(rawIp)) return IpLocationResolver.UNKNOWN;
            byte[] address = InetAddress.getByName(rawIp).getAddress();
            if (address.length == 4) {
                return "%d.%d.%d.0/24".formatted(
                        address[0] & 0xff, address[1] & 0xff, address[2] & 0xff);
            }
            return "%02x%02x:%02x%02x:%02x%02x::/48".formatted(
                    address[0] & 0xff, address[1] & 0xff, address[2] & 0xff,
                    address[3] & 0xff, address[4] & 0xff, address[5] & 0xff);
        } catch (Exception ignored) {
            return IpLocationResolver.UNKNOWN;
        }
    }

    private boolean isIpLiteral(String value) {
        return value.matches("[0-9.]+")
                || (value.indexOf(':') >= 0 && value.matches("[0-9A-Fa-f:.]+"));
    }

    private String hmac(String domain, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    ("login-risk:v1:" + domain + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("login_activity_hash_failed", exception);
        }
    }

    private String normalizedValue(String value) {
        return value == null || value.isBlank() ? IpLocationResolver.UNKNOWN : value.trim();
    }

    private String nullableRegion(String region) {
        return IpLocationResolver.UNKNOWN.equalsIgnoreCase(region) ? null : region;
    }

    private String displayRegion(String region) {
        return IpLocationResolver.UNKNOWN.equalsIgnoreCase(region) ? "Unknown region" : region;
    }

    public record LoginContext(String ipAddress, String deviceId, String userAgent, boolean deviceRecoverySupported) {
        public LoginContext(String ipAddress, String deviceId, String userAgent) {
            this(ipAddress, deviceId, userAgent, false);
        }
        public static LoginContext empty() {
            return new LoginContext(null, null, null);
        }
    }
}
