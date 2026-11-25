package com.yuzhihuihe.apiplatform.platform.payment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentAttemptService {

    private final PaymentAttemptRepository repository;
    private final PaymentAvailabilityService availabilityService;
    private final CommercialTransactionMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SmsProofService smsProofService;
    private final CommercialNotificationService notifications;
    private final ObjectMapper objectMapper;

    @Autowired
    PaymentAttemptService(
            PaymentAttemptRepository repository,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ObjectProvider<SmsProofService> smsProofService,
            ObjectProvider<CommercialNotificationService> notifications,
            ObjectMapper objectMapper) {
        this(repository, availabilityService, metrics, transactionManager, clock,
                smsProofService.getIfAvailable(), notifications.getIfAvailable(), objectMapper);
    }

    PaymentAttemptService(
            PaymentAttemptRepository repository,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(repository, availabilityService, metrics, transactionManager, clock,
                (SmsProofService) null, (CommercialNotificationService) null,
                defaultObjectMapper());
    }

    PaymentAttemptService(
            PaymentAttemptRepository repository,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SmsProofService smsProofService,
            CommercialNotificationService notifications) {
        this(repository, availabilityService, metrics, transactionManager, clock,
                smsProofService, notifications, defaultObjectMapper());
    }

    PaymentAttemptService(
            PaymentAttemptRepository repository,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SmsProofService smsProofService,
            CommercialNotificationService notifications,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.availabilityService = availabilityService;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.smsProofService = smsProofService;
        this.notifications = notifications;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public PaymentAttemptResult create(
            UUID tenantId,
            UUID userId,
            String orderNo,
            CreatePaymentAttemptRequest request,
            String clientIp) {
        validate(tenantId, userId, orderNo, request, clientIp);
        AttemptDraft draft = Objects.requireNonNull(transactions.execute(status ->
                prepare(tenantId, userId, orderNo, request, clientIp)));

        PaymentAdapter.PaymentCreation creation;
        try {
            creation = Objects.requireNonNull(
                    draft.adapter().createPayment(draft.command()),
                    "payment adapter returned no creation result");
        } catch (RuntimeException exception) {
            creation = new PaymentAdapter.PaymentCreation(
                    ProviderOutcome.UNCERTAIN, null, "ADAPTER_EXCEPTION",
                    new PaymentAdapter.PaymentAction(
                            PaymentActionType.NONE, null, Map.of(), null,
                            min(draft.expiresAt(), clock.instant().plusSeconds(300))));
        }
        metrics.paymentResult(request.provider(), request.product(), creation.outcome());
        PaymentAdapter.PaymentCreation finalCreation = creation;
        return Objects.requireNonNull(transactions.execute(status -> apply(draft, finalCreation)));
    }

    private AttemptDraft prepare(
            UUID tenantId,
            UUID userId,
            String orderNo,
            CreatePaymentAttemptRequest request,
            String clientIp) {
        PaymentAttemptRepository.OrderRow order = repository.lockOrder(tenantId, userId, orderNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "order_not_found"));
        if (!"PENDING".equals(order.status())) {
            throw invalid("order_not_payable");
        }
        if (!order.expiresAt().isAfter(clock.instant())) {
            throw invalid("order_expired");
        }
        PaymentAdapter adapter = availabilityService.requireCheckout(
                request.provider(), request.product());
        boolean active = repository.hasActiveAttempt(order.id());
        if (active) {
            throw invalid("active_payment_attempt_exists");
        }
        boolean phoneVerified = repository.isPhoneVerified(tenantId, userId);
        if (phoneVerified) {
            if (smsProofService == null) {
                throw new IllegalArgumentException("invalid_sms_proof");
            }
            smsProofService.consume(
                    request.smsProof(), tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);
        }

        String transactionNo = "pay_" + UUID.randomUUID().toString().replace("-", "");
        UUID paymentId = UUID.randomUUID();
        String requestHash = sha256(String.join("\n",
                transactionNo, order.orderNo(), Long.toString(order.amount()), order.currency(),
                request.provider().name(), request.product().name(), order.expiresAt().toString()));
        repository.insertAttempt(
                paymentId, transactionNo, order.id(), request.provider(), order.amount(),
                request.product(), order.expiresAt(), requestHash);

        PaymentAdapter.PaymentCommand command = new PaymentAdapter.PaymentCommand(
                transactionNo, order.orderNo(), order.amount(), order.currency(), request.product(),
                callbackUri(request.provider()), clientIp, order.expiresAt());
        return new AttemptDraft(
                adapter, command, paymentId, tenantId, userId, order.amount(),
                transactionNo, order.expiresAt());
    }

    private PaymentAttemptResult apply(
            AttemptDraft draft,
            PaymentAdapter.PaymentCreation creation) {
        PaymentAdapter.PaymentAction action = normalizedAction(
                creation.action(), draft.expiresAt());
        String status = switch (creation.outcome()) {
            case FAILED, CLOSED, NOT_FOUND -> "FAILED";
            case SUCCEEDED, PENDING, UNCERTAIN -> "PENDING";
        };
        int updated = repository.applyCreation(
                draft.transactionNo(), status, creation.externalTradeNo(),
                creation.providerStatus(), serializeAction(action));
        if (updated != 1) {
            throw new IllegalStateException("payment_attempt_state_changed");
        }
        if ("FAILED".equals(status) && notifications != null) {
            notifications.notifyPaymentFailed(
                    draft.paymentId(), draft.tenantId(), draft.userId(), draft.amount());
        }
        return new PaymentAttemptResult(
                draft.transactionNo(), status, action, action.expiresAt());
    }

    private PaymentAdapter.PaymentAction normalizedAction(
            PaymentAdapter.PaymentAction action,
            Instant orderExpiresAt) {
        return new PaymentAdapter.PaymentAction(
                action.type(), action.targetUrl(), action.fields(), action.qrContent(),
                min(orderExpiresAt, action.expiresAt()));
    }

    private String serializeAction(PaymentAdapter.PaymentAction action) {
        try {
            return objectMapper.writeValueAsString(action);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("payment_action_serialization_failed", exception);
        }
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private void validate(
            UUID tenantId,
            UUID userId,
            String orderNo,
            CreatePaymentAttemptRequest request,
            String clientIp) {
        if (tenantId == null || userId == null || orderNo == null || orderNo.isBlank()
                || orderNo.length() > 64 || request == null || request.provider() == null
                || request.product() == null || clientIp == null || clientIp.isBlank()
                || clientIp.length() > 64) {
            throw invalid("invalid_payment_attempt");
        }
    }

    private URI callbackUri(PaymentProvider provider) {
        String segment = switch (provider) {
            case MOCK -> "mock";
            case WECHAT_PAY -> "wechat-pay";
            case ALIPAY -> "alipay";
        };
        return URI.create("https://localhost/console/platform/payments/" + segment + "/callback");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_request_hash_failed", exception);
        }
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record CreatePaymentAttemptRequest(
            PaymentProvider provider,
            PaymentProduct product,
            String smsProof) {
        public CreatePaymentAttemptRequest(PaymentProvider provider, PaymentProduct product) {
            this(provider, product, null);
        }
    }

    public record PaymentAttemptResult(
            String transactionNo,
            String status,
            PaymentAdapter.PaymentAction action,
            Instant expiresAt) {
    }

    private record AttemptDraft(
            PaymentAdapter adapter,
            PaymentAdapter.PaymentCommand command,
            UUID paymentId,
            UUID tenantId,
            UUID userId,
            long amount,
            String transactionNo,
            Instant expiresAt) {
    }
}
