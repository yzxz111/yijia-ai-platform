package com.yuzhihuihe.apiplatform.platform.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderQueryService {

    private static final Set<String> ORDER_STATUSES = Set.of(
            "PENDING", "PAID", "CLOSED", "REFUNDED", "PARTIALLY_REFUNDED");

    private final OrderQueryRepository repository;
    private final ObjectMapper objectMapper;

    OrderQueryService(OrderQueryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public PageResult<OrderView> list(
            UUID tenantId, UUID userId, int page, int size, String status) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid pagination");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page, size);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid pagination", overflow);
        }
        String normalizedStatus = normalizeStatus(status);
        OrderQueryRepository.OrderPageRows rows = repository.list(
                tenantId, userId, normalizedStatus, size, offset);
        List<OrderView> items = new ArrayList<>(rows.items().size());
        rows.items().forEach(row -> items.add(orderView(row)));
        return new PageResult<>(items, rows.total(), page, size);
    }

    public OrderDetail detail(UUID tenantId, UUID userId, String orderNo) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found");
        }
        OrderQueryRepository.DetailBaseRow row = repository
                .findDetailBase(tenantId, userId, orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));
        DetailBase base = new DetailBase(
                row.orderId(), orderView(row.order()), readJson(row.packageSnapshot()), row.expiresAt());

        List<PaymentAttemptView> paymentAttempts = repository.findPaymentAttempts(base.orderId())
                .stream().map(this::paymentAttempt).toList();
        GrantView grant = repository.findGrant("order:" + orderNo).map(this::grantView).orElse(null);
        RefundView refund = repository.findLatestRefund(base.orderId())
                .map(this::refundView).orElse(null);
        return new OrderDetail(
                base.order(), base.packageSnapshot(), base.expiresAt(),
                List.copyOf(paymentAttempts), grant, refund);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid order status");
        }
        return normalized;
    }

    private OrderView orderView(OrderQueryRepository.OrderRow row) {
        return new OrderView(
                row.id(), row.orderNo(), row.packageCode(), row.packageName(), row.amount(),
                row.currency(), row.status(), row.settlementMethod(), row.paymentProvider(),
                row.paymentStatus(), row.refundStatus(), row.paidAt(), row.closedAt(),
                row.createdAt(), row.updatedAt());
    }

    private PaymentAttemptView paymentAttempt(OrderQueryRepository.PaymentAttemptRow row) {
        return new PaymentAttemptView(
                row.transactionNo(), PaymentProvider.valueOf(row.provider()),
                paymentProduct(row.paymentProduct()), row.status(), row.providerStatus(),
                row.providerErrorCode(), readPaymentAction(row.paymentAction()),
                row.paidAt(), row.createdAt());
    }

    private GrantView grantView(OrderQueryRepository.GrantRow row) {
        return new GrantView(row.id(), row.status(), row.updatedAt());
    }

    private RefundView refundView(OrderQueryRepository.RefundRow row) {
        return new RefundView(
                row.refundNo(), row.status(), row.refundType(), row.amount(), row.requestedAt(),
                row.reviewedAt(), row.completedAt());
    }

    private PaymentProduct paymentProduct(String value) {
        return value == null ? null : PaymentProduct.valueOf(value);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("order_snapshot_deserialization_failed", exception);
        }
    }

    private PaymentAdapter.PaymentAction readPaymentAction(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, PaymentAdapter.PaymentAction.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("payment_action_deserialization_failed", exception);
        }
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    public record OrderView(
            UUID id, String orderNo, String packageCode, String packageName,
            long amount, String currency, String status, String settlementMethod,
            String paymentProvider,
            String paymentStatus, String refundStatus, Instant paidAt, Instant closedAt,
            Instant createdAt, Instant updatedAt) {
    }

    public record OrderDetail(
            OrderView order,
            JsonNode packageSnapshot,
            Instant expiresAt,
            List<PaymentAttemptView> paymentAttempts,
            GrantView grant,
            RefundView refund) {
    }

    public record PaymentAttemptView(
            String transactionNo,
            PaymentProvider provider,
            PaymentProduct product,
            String status,
            String providerStatus,
            String providerErrorCode,
            PaymentAdapter.PaymentAction action,
            Instant paidAt,
            Instant createdAt) {
    }

    public record GrantView(UUID grantRequestId, String status, Instant updatedAt) {
    }

    public record RefundView(
            String refundNo,
            String status,
            String refundType,
            Long amount,
            Instant requestedAt,
            Instant reviewedAt,
            Instant completedAt) {
    }

    private record DetailBase(
            UUID orderId,
            OrderView order,
            JsonNode packageSnapshot,
            Instant expiresAt) {
    }
}
