package com.yuzhihuihe.apiplatform.platform.wallet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/admin/wallet")
public class AdminWalletController {

    private static final Set<String> REPLACEMENT_FIELDS = Set.of("options");
    private static final Set<String> OPTION_FIELDS = Set.of(
            "amount", "enabled", "displayOrder");

    private final AdminWalletService service;

    public AdminWalletController(AdminWalletService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    AdminWalletService.PageResult<AdminWalletService.AccountView> accounts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.accounts(tenantId(jwt), actorId(jwt), new AdminWalletService.AccountQuery(
                paginationValue(page), paginationValue(size), query, status, from, to, sort, direction));
    }

    @GetMapping("/ledger")
    AdminWalletService.PageResult<AdminWalletService.LedgerView> ledger(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.ledger(tenantId(jwt), actorId(jwt), new AdminWalletService.LedgerQuery(
                paginationValue(page), paginationValue(size), query, entryType, from, to, sort, direction));
    }

    @GetMapping("/recharges")
    AdminWalletService.PageResult<AdminWalletService.RechargeView> recharges(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.recharges(tenantId(jwt), actorId(jwt), new AdminWalletService.RechargeQuery(
                paginationValue(page), paginationValue(size), query, status, from, to, sort, direction));
    }

    @GetMapping("/overview")
    AdminWalletService.Overview overview(@AuthenticationPrincipal Jwt jwt) {
        return service.overview(tenantId(jwt), actorId(jwt));
    }

    @GetMapping("/recharge-options")
    List<AdminWalletService.OptionView> options(@AuthenticationPrincipal Jwt jwt) {
        return service.options(tenantId(jwt));
    }

    @PutMapping("/recharge-options")
    List<AdminWalletService.OptionView> replaceOptions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody JsonNode body) {
        return service.replaceOptions(tenantId(jwt), actorId(jwt), parseReplacements(body));
    }

    private List<AdminWalletService.OptionReplacement> parseReplacements(JsonNode body) {
        if (body == null || !body.isObject() || !exactFields(body, REPLACEMENT_FIELDS)) {
            throw invalidOptions();
        }
        JsonNode options = body.get("options");
        if (options == null || !options.isArray() || options.size() > 20) {
            throw invalidOptions();
        }
        List<AdminWalletService.OptionReplacement> replacements = new ArrayList<>();
        Set<Long> amounts = new HashSet<>();
        for (JsonNode option : options) {
            if (!option.isObject() || !exactFields(option, OPTION_FIELDS)) {
                throw invalidOptions();
            }
            JsonNode amount = option.get("amount");
            JsonNode enabled = option.get("enabled");
            JsonNode displayOrder = option.get("displayOrder");
            if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong()
                    || enabled == null || !enabled.isBoolean()
                    || displayOrder == null || !displayOrder.isIntegralNumber()
                    || !displayOrder.canConvertToInt()) {
                throw invalidOptions();
            }
            long amountValue = amount.longValue();
            if (amountValue < RechargeOrderService.MINIMUM_AMOUNT
                    || amountValue > RechargeOrderService.MAXIMUM_AMOUNT
                    || displayOrder.intValue() < 0
                    || !amounts.add(amountValue)) {
                throw invalidOptions();
            }
            replacements.add(new AdminWalletService.OptionReplacement(
                    amountValue, enabled.booleanValue(), displayOrder.intValue()));
        }
        return replacements;
    }

    private boolean exactFields(JsonNode body, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = body.fieldNames();
        fields.forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private int paginationValue(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination", error);
        }
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }

    private UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private ResponseStatusException invalidOptions() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_recharge_options");
    }
}
