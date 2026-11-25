package com.yuzhihuihe.apiplatform.platform.refund;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/admin/refunds")
public class AdminRefundController {

    private static final Set<String> REJECTION_FIELDS = Set.of("reviewNote");

    private final RefundApplicationService service;

    public AdminRefundController(RefundApplicationService service) {
        this.service = service;
    }

    @PostMapping("/{refundNo}/approve")
    RefundApplicationService.RefundView approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String refundNo) {
        return service.approve(actor(jwt), refundNo);
    }

    @PostMapping("/{refundNo}/reject")
    RefundApplicationService.RefundView reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String refundNo,
            @RequestBody JsonNode body) {
        RefundController.requireExactFields(
                body, REJECTION_FIELDS, "invalid_refund_review_note");
        String note = RefundController.text(body, "reviewNote", "invalid_refund_review_note");
        if (note.isBlank() || note.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_refund_review_note");
        }
        return service.reject(
                actor(jwt), refundNo,
                new RefundApplicationService.RejectRefundCommand(note));
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
