package com.yuzhihuihe.apiplatform.gateway.apikey;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.gateway.security.TerminalDeviceValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/console/gateway/client")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ClientBootstrapController {

    private static final Pattern DEVICE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final ManagedApiKeyService managedApiKeyService;
    private final ClientBootstrapRateLimiter rateLimiter;
    private final TerminalDeviceValidator terminalDeviceValidator;
    private final String apiBaseUrl;

    public ClientBootstrapController(
            ManagedApiKeyService managedApiKeyService,
            ClientBootstrapRateLimiter rateLimiter,
            TerminalDeviceValidator terminalDeviceValidator,
            @Value("${gateway.client.api-base-url:https://api.yuzhh.com/v1}")
            String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("gateway_client_api_base_url_required");
        }
        this.managedApiKeyService = managedApiKeyService;
        this.rateLimiter = rateLimiter;
        this.terminalDeviceValidator = terminalDeviceValidator;
        this.apiBaseUrl = apiBaseUrl;
    }

    @PostMapping("/bootstrap")
    Mono<ResponseEntity<ClientBootstrapResponse>> bootstrap(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestParam(value = "usageDimension", required = false) String usageDimension) {
        ClientIdentity identity = requireClientIdentity(jwt, deviceId);
        return terminalDeviceValidator.validate(jwt.getTokenValue(), deviceId)
                .onErrorReturn(false)
                .defaultIfEmpty(false)
                .flatMap(valid -> {
                    if (!valid) return Mono.error(invalidCredentials());
                    return rateLimiter.acquire(identity.userId(), identity.sessionId())
                            .then(Mono.defer(() -> managedApiKeyService.bootstrap(
                                    identity.tenantId(), identity.userId(), usageDimension)))
                            .map(result -> ResponseEntity.ok()
                                    .cacheControl(CacheControl.noStore().cachePrivate())
                                    .header(HttpHeaders.PRAGMA, "no-cache")
                                    .body(new ClientBootstrapResponse(
                                            apiBaseUrl,
                                            result.apiKey(),
                                            result.packageInstanceId(),
                                            result.packageCode(),
                                            result.packageExpiresAt(),
                                            result.allowedModels(),
                                            result.usageDimension())));
                });
    }

    private ClientIdentity requireClientIdentity(Jwt jwt, String deviceId) {
        if (jwt == null
                || !"DESKTOP_WEB".equals(jwt.getClaimAsString("client_type"))
                || deviceId == null
                || !DEVICE_ID.matcher(deviceId).matches()) {
            throw invalidCredentials();
        }
        try {
            return new ClientIdentity(
                    UUID.fromString(jwt.getClaimAsString("tenant_id")),
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("session_id")));
        } catch (RuntimeException exception) {
            throw invalidCredentials();
        }
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "invalid_credentials");
    }

    public record ClientBootstrapResponse(
            String apiBaseUrl,
            String apiKey,
            UUID packageInstanceId,
            String packageCode,
            Instant packageExpiresAt,
            Set<String> allowedModels,
            String usageDimension) {
    }

    private record ClientIdentity(UUID tenantId, UUID userId, UUID sessionId) {
    }
}
