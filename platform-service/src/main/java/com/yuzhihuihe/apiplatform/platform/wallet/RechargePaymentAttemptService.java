package com.yuzhihuihe.apiplatform.platform.wallet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RechargePaymentAttemptService {

    private final RechargePaymentAttemptRepository repository;
    private final PaymentAvailabilityService availabilityService;
    private final CommercialTransactionMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SmsProofService smsProofService;

    @Autowired
    RechargePaymentAttemptService(
            RechargePaymentAttemptRepository repository,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ObjectProvider<SmsProofService> smsProofService) {
        this(repository, availabilityService, metrics, transactionManager, clock,
                smsProofService.getIfAvailable());
    }

    RechargePaymentAttemptService(
            RechargePaymentAttemptRepository repository,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SmsProofService smsProofService) {
        this.repository = repository;
        this.availabilityService = availabilityService;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.smsProofService = smsProofService;
    }

    public PaymentAttemptResult create(
            UUID tenantId,
            UUID userId,
            String rechargeNo,
            CreatePaymentAttemptRequest request,
            String clientIp) {
        validate(tenantId, userId, rechargeNo, request, clientIp);
        AttemptDraft draft = Objects.requireNonNull(transactions.execute(ignored ->
                prepare(tenantId, userId, rechargeNo, request, clientIp)));

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
        return Objects.requireNonNull(transactions.execute(ignored ->
                apply(draft, finalCreation)));
    }

    private AttemptDraft prepare(
            UUID tenantId,
            UUID userId,
            String rechargeNo,
            CreatePaymentAttemptRequest request,
            String clientIp) {
        RechargePaymentAttemptRepository.RechargeRow row = repository.lockRecharge(
                tenantId, userId, rechargeNo);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recharge_order_not_found");
        }
        RechargeForPayment recharge = new RechargeForPayment(
                row.id(), row.rechargeNo(), row.amount(), row.currency(),
                row.status(), row.expiresAt());
        if (!"PENDING".equals(recharge.status())
                || !recharge.expiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "recharge_order_not_payable");
        }

        PaymentAdapter adapter = availabilityService.requireCheckout(
                request.provider(), request.product());
        if (repository.activeAttemptExists(recharge.id())) {
            throw invalid("active_payment_attempt_exists");
        }
        if (repository.phoneVerified(tenantId, userId)) {
            if (smsProofService == null) {
                throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_proof");
            }
            try {
                smsProofService.consume(
                        request.smsProof(), tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);
            } catch (IllegalArgumentException error) {
                if (!"invalid_sms_proof".equals(error.getMessage())) throw error;
                throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_proof");
            }
        }

        UUID paymentId = UUID.randomUUID();
        String transactionNo = "pay_" + paymentId.toString().replace("-", "");
        String requestHash = sha256(String.join("\n",
                transactionNo, recharge.rechargeNo(), Long.toString(recharge.amount()),
                recharge.currency(), request.provider().name(), request.product().name(),
                recharge.expiresAt().toString()));
        repository.insertTransaction(
                paymentId, transactionNo, recharge.id(), request.provider().name(),
                recharge.amount(), request.product().name(), recharge.expiresAt(), requestHash);

        PaymentAdapter.PaymentCommand command = new PaymentAdapter.PaymentCommand(
                transactionNo, recharge.rechargeNo(), recharge.amount(), recharge.currency(),
                request.product(), callbackUri(request.provider()), clientIp,
                recharge.expiresAt());
        return new AttemptDraft(
                adapter, command, transactionNo, recharge.expiresAt());
    }

    private PaymentAttemptResult apply(
            AttemptDraft draft,
            PaymentAdapter.PaymentCreation creation) {
        String status = switch (creation.outcome()) {
            case FAILED, CLOSED, NOT_FOUND -> "FAILED";
            case SUCCEEDED, PENDING, UNCERTAIN -> "PENDING";
        };
        int updated = repository.updateTransaction(
                draft.transactionNo(), status, creation.externalTradeNo(),
                creation.providerStatus());
        if (updated != 1) {
            throw new IllegalStateException("payment_attempt_state_changed");
        }
        return new PaymentAttemptResult(
                draft.transactionNo(), status, creation.action(),
                min(draft.expiresAt(), creation.action().expiresAt()));
    }

    private void validate(
            UUID tenantId,
            UUID userId,
            String rechargeNo,
            CreatePaymentAttemptRequest request,
            String clientIp) {
        if (tenantId == null || userId == null || rechargeNo == null
                || rechargeNo.isBlank() || rechargeNo.length() > 64
                || request == null || request.provider() == null
                || request.product() == null || clientIp == null
                || clientIp.isBlank() || clientIp.length() > 64) {
            throw invalid("invalid_payment_attempt");
        }
    }

    private URI callbackUri(PaymentProvider provider) {
        String segment = switch (provider) {
            case MOCK -> "mock";
            case WECHAT_PAY -> "wechat-pay";
            case ALIPAY -> "alipay";
        };
        return URI.create(
                "https://localhost/console/platform/payments/" + segment + "/callback");
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

    private record RechargeForPayment(
            UUID id,
            String rechargeNo,
            long amount,
            String currency,
            String status,
            Instant expiresAt) {
    }

    private record AttemptDraft(
            PaymentAdapter adapter,
            PaymentAdapter.PaymentCommand command,
            String transactionNo,
            Instant expiresAt) {
    }
}
