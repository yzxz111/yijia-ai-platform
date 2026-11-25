package com.yuzhihuihe.apiplatform.platform.reconciliation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/admin/reconciliation-runs")
public class ReconciliationController {

    private static final Set<String> START_FIELDS = Set.of("provider", "billDate");

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ReconciliationService.RunDetail> start(
            @RequestBody JsonNode body,
            @AuthenticationPrincipal Jwt jwt) {
        exactFields(body);
        PaymentProvider provider = provider(requiredText(body, "provider"));
        LocalDate billDate = billDate(requiredText(body, "billDate"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.start(provider, billDate, actor(jwt)));
    }

    @GetMapping("/{id}")
    ReconciliationService.RunDetail detail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.detail(id, page, size, sort, direction);
    }

    private void exactFields(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid("invalid_reconciliation_request");
        Set<String> actual = new HashSet<>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(START_FIELDS)) throw invalid("invalid_reconciliation_request");
    }

    private String requiredText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > 64) {
            throw invalid("invalid_reconciliation_request");
        }
        return value.asText().strip();
    }

    private PaymentProvider provider(String value) {
        try {
            return PaymentProvider.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_reconciliation_provider");
        }
    }

    private LocalDate billDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid("invalid_reconciliation_bill_date");
        }
    }

    private UUID actor(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw invalid("invalid_reconciliation_actor");
        }
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
