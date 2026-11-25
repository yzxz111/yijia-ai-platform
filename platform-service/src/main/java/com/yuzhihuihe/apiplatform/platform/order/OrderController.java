package com.yuzhihuihe.apiplatform.platform.order;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentLifecycleService;
import com.yuzhihuihe.apiplatform.platform.wallet.BalanceOrderPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;
    private final OrderApplicationService orderApplicationService;
    private final PaymentLifecycleService paymentLifecycleService;
    private final BalanceOrderPaymentService balanceOrderPaymentService;
    private final CommercialTransactionMetrics metrics;

    public OrderController(
            OrderQueryService orderQueryService,
            OrderApplicationService orderApplicationService,
            PaymentLifecycleService paymentLifecycleService,
            BalanceOrderPaymentService balanceOrderPaymentService,
            CommercialTransactionMetrics metrics) {
        this.orderQueryService = orderQueryService;
        this.orderApplicationService = orderApplicationService;
        this.paymentLifecycleService = paymentLifecycleService;
        this.balanceOrderPaymentService = balanceOrderPaymentService;
        this.metrics = metrics;
    }

    @GetMapping
    OrderQueryService.PageResult<OrderQueryService.OrderView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return orderQueryService.list(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getSubject()), page, size, status);
    }

    @PostMapping
    ResponseEntity<OrderApplicationService.OrderCreated> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrderApplicationService.CreateOrderCommand command) {
        OrderApplicationService.CreationOutcome result;
        try {
            result = orderApplicationService.createWithOutcome(
                    UUID.fromString(jwt.getClaimAsString("tenant_id")),
                    UUID.fromString(jwt.getSubject()), idempotencyKey, command);
        } catch (RuntimeException exception) {
            metrics.orderCreated("FAILED");
            throw exception;
        }
        metrics.orderCreated(result.created() ? "CREATED" : "REPLAY");
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.order());
    }

    @GetMapping("/{orderNo}")
    OrderQueryService.OrderDetail detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo) {
        return orderQueryService.detail(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getSubject()), orderNo);
    }

    @PostMapping("/{orderNo}/close")
    PaymentLifecycleService.CloseOrderResult close(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo) {
        return paymentLifecycleService.resolveAndClose(
                orderNo,
                new PaymentLifecycleService.Actor(
                        UUID.fromString(jwt.getClaimAsString("tenant_id")),
                        UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/{orderNo}/balance-payment")
    BalanceOrderPaymentService.BalancePaymentResult payFromBalance(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return balanceOrderPaymentService.pay(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getSubject()), orderNo, idempotencyKey);
    }
}
