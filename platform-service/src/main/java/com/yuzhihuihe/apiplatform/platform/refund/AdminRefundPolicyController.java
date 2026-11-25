package com.yuzhihuihe.apiplatform.platform.refund;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/admin/refund-policies")
public class AdminRefundPolicyController {

    private static final Set<String> PUBLICATION_FIELDS = Set.of(
            "version", "feeBps", "minimumFee", "refundWindowDays",
            "maximumUsageBps", "effectiveAt");

    private final RefundPolicyService service;

    public AdminRefundPolicyController(RefundPolicyService service) {
        this.service = service;
    }

    @GetMapping
    RefundPolicyService.PageResult<RefundPolicyService.RefundPolicy> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listPublished(page, size);
    }

    @PostMapping
    ResponseEntity<RefundPolicyService.RefundPolicy> publish(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody JsonNode body) {
        RefundController.requireExactFields(body, PUBLICATION_FIELDS, "invalid_refund_policy");
        RefundPolicyService.PublishPolicyCommand command = parse(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.publish(UUID.fromString(jwt.getSubject()), command));
    }

    private RefundPolicyService.PublishPolicyCommand parse(JsonNode body) {
        try {
            JsonNode version = integralInt(body, "version");
            JsonNode feeBps = integralInt(body, "feeBps");
            JsonNode minimumFee = integralLong(body, "minimumFee");
            JsonNode refundWindowDays = integralInt(body, "refundWindowDays");
            JsonNode maximumUsageBps = integralInt(body, "maximumUsageBps");
            JsonNode effectiveAt = body.get("effectiveAt");
            if (effectiveAt == null || !effectiveAt.isTextual()) {
                throw invalidPolicy();
            }
            return new RefundPolicyService.PublishPolicyCommand(
                    version.intValue(),
                    feeBps.intValue(),
                    minimumFee.longValue(),
                    refundWindowDays.intValue(),
                    maximumUsageBps.intValue(),
                    Instant.parse(effectiveAt.textValue()));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidPolicy();
        }
    }

    private JsonNode integralInt(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidPolicy();
        }
        return value;
    }

    private JsonNode integralLong(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidPolicy();
        }
        return value;
    }

    private ResponseStatusException invalidPolicy() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_refund_policy");
    }
}
