package com.yuzhihuihe.apiplatform.platform.refund;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform")
public class RefundController {

    private static final Set<String> APPLICATION_FIELDS = Set.of("reason");

    private final RefundApplicationService service;

    public RefundController(RefundApplicationService service) {
        this.service = service;
    }

    @PostMapping("/orders/{orderNo}/refunds")
    ResponseEntity<RefundApplicationService.RefundView> apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo,
            @RequestBody JsonNode body) {
        requireExactFields(body, APPLICATION_FIELDS, "invalid_refund_application");
        String reason = text(body, "reason", "invalid_refund_application");
        if (reason.isBlank() || reason.length() > 500) {
            throw unprocessable("invalid_refund_application");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.apply(
                tenant(jwt), user(jwt), orderNo,
                new RefundApplicationService.ApplyRefundCommand(reason)));
    }

    @PostMapping("/refunds/{refundNo}/cancel")
    RefundApplicationService.RefundView cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String refundNo) {
        return service.cancel(tenant(jwt), user(jwt), refundNo);
    }

    @GetMapping("/refunds/{refundNo}")
    RefundApplicationService.RefundView detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String refundNo) {
        return service.detail(tenant(jwt), user(jwt), refundNo);
    }

    static void requireExactFields(JsonNode body, Set<String> expected, String reason) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        }
        Set<String> actual = new java.util.HashSet<>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        }
    }

    static String text(JsonNode body, String field, String reason) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        }
        return value.textValue();
    }

    private UUID tenant(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }

    private UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
