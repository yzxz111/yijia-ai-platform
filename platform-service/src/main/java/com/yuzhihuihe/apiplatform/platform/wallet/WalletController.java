package com.yuzhihuihe.apiplatform.platform.wallet;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform/wallet")
public class WalletController {

    private static final Set<String> CREATE_FIELDS = Set.of("amount", "optionId");

    private final WalletQueryService queryService;
    private final RechargeOrderService rechargeService;
    private final RechargePaymentAttemptService paymentAttemptService;
    private final PaymentLifecycleService paymentLifecycleService;

    public WalletController(
            WalletQueryService queryService,
            RechargeOrderService rechargeService,
            RechargePaymentAttemptService paymentAttemptService,
            PaymentLifecycleService paymentLifecycleService) {
        this.queryService = queryService;
        this.rechargeService = rechargeService;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentLifecycleService = paymentLifecycleService;
    }

    @GetMapping
    WalletQueryService.WalletSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return queryService.summary(tenantId(jwt), userId(jwt));
    }

    @GetMapping("/ledger")
    WalletQueryService.PageResult<WalletQueryService.LedgerView> ledger(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return queryService.ledger(
                tenantId(jwt), userId(jwt), paginationValue(page), paginationValue(size),
                sort, direction);
    }

    @GetMapping("/recharge-options")
    RechargeOrderService.RechargeOptionCatalog rechargeOptions(
            @AuthenticationPrincipal Jwt jwt) {
        return rechargeService.options(tenantId(jwt));
    }

    @GetMapping("/recharges")
    RechargeOrderService.PageResult<RechargeOrderService.RechargeOrderView> recharges(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return rechargeService.list(
                tenantId(jwt), userId(jwt), paginationValue(page), paginationValue(size),
                sort, direction);
    }

    @PostMapping("/recharges")
    ResponseEntity<RechargeOrderService.RechargeOrderView> createRecharge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        RechargeOrderService.CreateRechargeCommand command = parseCreate(body);
        RechargeOrderService.CreationOutcome outcome = rechargeService.createWithOutcome(
                tenantId(jwt), userId(jwt), idempotencyKey, command);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.order());
    }

    @GetMapping("/recharges/{rechargeNo}")
    RechargeOrderService.RechargeOrderView rechargeDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String rechargeNo) {
        return rechargeService.detail(tenantId(jwt), userId(jwt), rechargeNo);
    }

    @PostMapping("/recharges/{rechargeNo}/payment-attempts")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    RechargePaymentAttemptService.PaymentAttemptResult createRechargePaymentAttempt(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String rechargeNo,
            @RequestBody RechargePaymentAttemptService.CreatePaymentAttemptRequest request,
            HttpServletRequest servletRequest) {
        return paymentAttemptService.create(
                tenantId(jwt), userId(jwt), rechargeNo, request,
                servletRequest.getRemoteAddr());
    }

    @PostMapping("/recharges/{rechargeNo}/close")
    RechargeOrderService.RechargeOrderView closeRecharge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String rechargeNo) {
        paymentLifecycleService.resolveAndCloseRecharge(
                rechargeNo,
                new PaymentLifecycleService.Actor(tenantId(jwt), userId(jwt)));
        return rechargeService.detail(tenantId(jwt), userId(jwt), rechargeNo);
    }

    private RechargeOrderService.CreateRechargeCommand parseCreate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalidRechargeRequest();
        }
        java.util.Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            if (!CREATE_FIELDS.contains(names.next())) {
                throw invalidRechargeRequest();
            }
        }
        JsonNode amount = body.get("amount");
        if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong()) {
            throw invalidRechargeRequest();
        }
        JsonNode optionIdNode = body.get("optionId");
        UUID optionId = null;
        if (optionIdNode != null && !optionIdNode.isNull()) {
            if (!optionIdNode.isTextual()) {
                throw invalidRechargeRequest();
            }
            try {
                optionId = UUID.fromString(optionIdNode.textValue());
            } catch (IllegalArgumentException malformed) {
                throw invalidRechargeRequest();
            }
        }
        return new RechargeOrderService.CreateRechargeCommand(amount.longValue(), optionId);
    }

    private ResponseStatusException invalidRechargeRequest() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_recharge_request");
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

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
