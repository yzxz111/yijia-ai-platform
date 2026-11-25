package com.yuzhihuihe.apiplatform.platform.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.order.PackageInventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentLifecycleService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PaymentLifecycleService.class);

    private final PaymentLifecycleRepository repository;
    private final PaymentAvailabilityService availabilityService;
    private final PaymentPostingService postingService;
    private final PaymentAnomalyService anomalyService;
    private final CommercialTransactionMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration initialBackoff;
    private final PackageInventoryService inventoryService;
    private final CommercialNotificationService notifications;

    @Autowired
    PaymentLifecycleService(
            PaymentLifecycleRepository repository,
            PaymentAvailabilityService availabilityService,
            PaymentPostingService postingService,
            PaymentAnomalyService anomalyService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${payment.jobs.initial-backoff:5s}") Duration initialBackoff,
            ObjectProvider<PackageInventoryService> inventoryService,
            ObjectProvider<CommercialNotificationService> notifications) {
        this(repository, availabilityService, postingService, anomalyService, metrics,
                transactionManager, clock, initialBackoff, inventoryService.getIfAvailable(),
                notifications.getIfAvailable());
    }

    PaymentLifecycleService(
            PaymentLifecycleRepository repository,
            PaymentAvailabilityService availabilityService,
            PaymentPostingService postingService,
            PaymentAnomalyService anomalyService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration initialBackoff,
            PackageInventoryService inventoryService,
            CommercialNotificationService notifications) {
        this.repository = repository;
        this.availabilityService = availabilityService;
        this.postingService = postingService;
        this.anomalyService = anomalyService;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.initialBackoff = initialBackoff;
        this.inventoryService = inventoryService;
        this.notifications = notifications;
    }

    public CloseOrderResult resolveAndClose(String orderNo, Actor actor) {
        validateOrderRequest(orderNo, actor);
        return resolveAndCloseInternal(orderNo, actor);
    }

    public CloseOrderResult resolveAndCloseRecharge(String rechargeNo, Actor actor) {
        validateRechargeRequest(rechargeNo, actor);
        return resolveAndCloseRechargeInternal(rechargeNo, actor);
    }

    public CloseOrderResult resolveAndCloseSystem(String orderNo) {
        if (blank(orderNo) || orderNo.length() > 64) {
            throw invalid("invalid_order_close");
        }
        return resolveAndCloseInternal(orderNo, null);
    }

    public PaymentQueryResult queryForCompensation(String transactionNo) {
        if (blank(transactionNo) || transactionNo.length() > 64) {
            throw invalid("invalid_payment_query");
        }
        TransactionDraft draft = transactions.execute(
                ignored -> prepareTransaction(transactionNo));
        if (draft == null) {
            throw new IllegalStateException("payment_query_prepare_failed");
        }
        if (!"PENDING".equals(draft.transactionStatus())
                && !"CREATED".equals(draft.transactionStatus())) {
            return new PaymentQueryResult(
                    draft.transactionNo(), draft.transactionStatus(), false);
        }
        PaymentAdapter adapter;
        try {
            adapter = availabilityService.requireOperational(
                    draft.provider(), draft.product());
        } catch (RuntimeException exception) {
            return applyQueryUncertain(draft);
        }
        PaymentAdapter.ProviderPayment queried = query(adapter, draft);
        return applyCompensationResult(draft, queried);
    }

    private CloseOrderResult resolveAndCloseInternal(String orderNo, Actor actor) {
        OrderDraft draft = transactions.execute(
                ignored -> prepareOrder(orderNo, actor));
        if (draft == null) {
            throw new IllegalStateException("payment_close_prepare_failed");
        }
        if (draft.terminalResult() != null) {
            return draft.terminalResult();
        }

        PaymentAdapter adapter;
        try {
            adapter = availabilityService.requireOperational(
                    draft.provider(), draft.product());
        } catch (RuntimeException exception) {
            return applyCloseUncertain(draft, "QUERY_UNCERTAIN");
        }
        PaymentAdapter.ProviderPayment queried = query(adapter, draft.transaction());
        if (queried.outcome() == ProviderOutcome.SUCCEEDED) {
            PaymentPostingService.PostingResult posted = postingService.postSucceeded(queried);
            return new CloseOrderResult(
                    posted.orderNo(), posted.orderStatus(), "SUCCEEDED", false);
        }
        if (queried.outcome() == ProviderOutcome.PENDING) {
            return applyPendingClose(draft, queried);
        }
        if (queried.outcome() == ProviderOutcome.UNCERTAIN) {
            return applyCloseUncertain(draft, "QUERY_UNCERTAIN");
        }
        if (queried.outcome() == ProviderOutcome.FAILED) {
            PaymentAdapter.ProviderPayment closed = close(adapter, draft.transaction());
            if (closed.outcome() == ProviderOutcome.SUCCEEDED) {
                PaymentPostingService.PostingResult posted = postingService.postSucceeded(closed);
                return new CloseOrderResult(
                        posted.orderNo(), posted.orderStatus(), "SUCCEEDED", false);
            }
            if (closed.outcome() == ProviderOutcome.UNCERTAIN
                    || closed.outcome() == ProviderOutcome.PENDING) {
                return applyCloseUncertain(draft, "CLOSE_UNCERTAIN");
            }
        }
        return applyClosed(draft);
    }

    private CloseOrderResult resolveAndCloseRechargeInternal(
            String rechargeNo,
            Actor actor) {
        RechargeDraft draft = transactions.execute(
                ignored -> prepareRecharge(rechargeNo, actor));
        if (draft == null) {
            throw new IllegalStateException("recharge_payment_close_prepare_failed");
        }
        if (draft.terminalResult() != null) {
            return draft.terminalResult();
        }

        PaymentAdapter adapter;
        try {
            adapter = availabilityService.requireOperational(
                    draft.provider(), draft.product());
        } catch (RuntimeException exception) {
            return applyRechargeCloseUncertain(draft, "QUERY_UNCERTAIN");
        }
        PaymentAdapter.ProviderPayment queried = query(adapter, draft.transaction());
        if (queried.outcome() == ProviderOutcome.SUCCEEDED) {
            PaymentPostingService.PostingResult posted = postingService.postSucceeded(queried);
            return new CloseOrderResult(
                    posted.orderNo(), posted.orderStatus(), "SUCCEEDED", false);
        }
        if (queried.outcome() == ProviderOutcome.PENDING) {
            return applyPendingRechargeClose(draft, queried);
        }
        if (queried.outcome() == ProviderOutcome.UNCERTAIN) {
            return applyRechargeCloseUncertain(draft, "QUERY_UNCERTAIN");
        }
        if (queried.outcome() == ProviderOutcome.FAILED) {
            PaymentAdapter.ProviderPayment closed = close(adapter, draft.transaction());
            if (closed.outcome() == ProviderOutcome.SUCCEEDED) {
                PaymentPostingService.PostingResult posted = postingService.postSucceeded(closed);
                return new CloseOrderResult(
                        posted.orderNo(), posted.orderStatus(), "SUCCEEDED", false);
            }
            if (closed.outcome() == ProviderOutcome.UNCERTAIN
                    || closed.outcome() == ProviderOutcome.PENDING) {
                return applyRechargeCloseUncertain(draft, "CLOSE_UNCERTAIN");
            }
        }
        return applyRechargeClosed(draft);
    }

    private PaymentAdapter.ProviderPayment query(
            PaymentAdapter adapter,
            TransactionDraft draft) {
        long started = System.nanoTime();
        PaymentAdapter.ProviderPayment payment;
        try {
            payment = adapter.queryPayment(new PaymentAdapter.PaymentQuery(
                    draft.transactionNo(), draft.amount(), draft.currency()));
            if (payment == null || !draft.transactionNo().equals(payment.transactionNo())) {
                payment = uncertain(draft, "INVALID_QUERY_RESULT");
            }
        } catch (RuntimeException exception) {
            payment = uncertain(draft, "QUERY_EXCEPTION");
        }
        metrics.queryDuration(
                draft.provider(), payment.outcome(),
                Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
        return payment;
    }

    private PaymentAdapter.ProviderPayment close(
            PaymentAdapter adapter,
            TransactionDraft draft) {
        PaymentAdapter.ProviderPayment payment;
        try {
            payment = adapter.closePayment(new PaymentAdapter.PaymentQuery(
                    draft.transactionNo(), draft.amount(), draft.currency()));
            if (payment == null || !draft.transactionNo().equals(payment.transactionNo())) {
                payment = uncertain(draft, "INVALID_CLOSE_RESULT");
            }
        } catch (RuntimeException exception) {
            payment = uncertain(draft, "CLOSE_EXCEPTION");
        }
        metrics.close(draft.provider(), payment.outcome());
        return payment;
    }

    private PaymentAdapter.ProviderPayment uncertain(
            TransactionDraft draft,
            String providerStatus) {
        return new PaymentAdapter.ProviderPayment(
                ProviderOutcome.UNCERTAIN, draft.transactionNo(), null,
                Math.max(1, draft.amount()), draft.currency(), null,
                providerStatus, null, null);
    }

    private PaymentQueryResult applyCompensationResult(
            TransactionDraft draft,
            PaymentAdapter.ProviderPayment queried) {
        if (queried.outcome() == ProviderOutcome.SUCCEEDED) {
            PaymentPostingService.PostingResult posted = postingService.postSucceeded(queried);
            return new PaymentQueryResult(
                    posted.transactionNo(), "SUCCEEDED", false);
        }
        if (queried.outcome() == ProviderOutcome.PENDING) {
            return updateQueriedTransaction(draft, queried, "PENDING", true, false);
        }
        // PAGE/WAP creates the provider trade only when the browser submits the form.
        // A query before that point is not evidence that an unexpired checkout closed.
        if (queried.outcome() == ProviderOutcome.NOT_FOUND
                && draft.provider() == PaymentProvider.ALIPAY
                && (draft.product() == PaymentProduct.ALIPAY_PAGE
                    || draft.product() == PaymentProduct.ALIPAY_WAP)
                && repository.paymentWindowOpen(draft.transactionId(), clock.instant())) {
            return updateQueriedTransaction(draft, queried, "PENDING", true, false);
        }
        if (queried.outcome() == ProviderOutcome.UNCERTAIN) {
            return applyQueryUncertain(draft);
        }
        String terminal = queried.outcome() == ProviderOutcome.FAILED
                ? "FAILED" : "CLOSED";
        return updateQueriedTransaction(draft, queried, terminal, false, false);
    }

    private PaymentQueryResult applyQueryUncertain(TransactionDraft draft) {
        return updateQueriedTransaction(
                draft, uncertain(draft, "QUERY_UNCERTAIN"),
                "PENDING", true, true);
    }

    private PaymentQueryResult updateQueriedTransaction(
            TransactionDraft draft,
            PaymentAdapter.ProviderPayment queried,
            String targetStatus,
            boolean retry,
            boolean anomaly) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transactionId());
            if (!"PENDING".equals(state.transactionStatus())
                    && !"CREATED".equals(state.transactionStatus())) {
                return new PaymentQueryResult(
                        state.transactionNo(), state.transactionStatus(), false);
            }
            int updated = repository.updateQueryState(
                    draft.transactionId(), targetStatus, queried.providerStatus(),
                    queried.providerErrorCode(), clock.instant(),
                    clock.instant().plus(initialBackoff));
            if (updated != 1) {
                throw new IllegalStateException("payment_query_state_changed");
            }
            if (draft.orderId() != null
                    && ("FAILED".equals(targetStatus) || "CLOSED".equals(targetStatus))
                    && notifications != null) {
                notifications.notifyPaymentFailed(
                        draft.transactionId(), draft.tenantId(), draft.userId(), draft.amount());
            }
            if (anomaly) {
                anomalyService.recordQueryUncertain(
                        draft.provider(), draft.orderId(), draft.rechargeOrderId(),
                        draft.transactionId(), draft.transactionNo());
            }
            logState(draft, targetStatus);
            return new PaymentQueryResult(draft.transactionNo(), targetStatus, retry);
        });
    }

    private CloseOrderResult applyPendingClose(
            OrderDraft draft,
            PaymentAdapter.ProviderPayment queried) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transaction().transactionId());
            if ("PAID".equals(state.orderStatus())
                    || "SUCCEEDED".equals(state.transactionStatus())) {
                return new CloseOrderResult(
                        draft.orderNo(), state.orderStatus(),
                        state.transactionStatus(), false);
            }
            repository.schedulePendingQuery(
                    draft.transaction().transactionId(), queried.providerStatus(),
                    queried.providerErrorCode(), clock.instant(),
                    clock.instant().plus(initialBackoff));
            return new CloseOrderResult(
                    draft.orderNo(), "PENDING", "PENDING", true);
        });
    }

    private CloseOrderResult applyCloseUncertain(OrderDraft draft, String category) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transaction().transactionId());
            if ("PAID".equals(state.orderStatus())
                    || "SUCCEEDED".equals(state.transactionStatus())) {
                return new CloseOrderResult(
                        draft.orderNo(), state.orderStatus(),
                        state.transactionStatus(), false);
            }
            repository.scheduleRetry(
                    draft.transaction().transactionId(), clock.instant(),
                    clock.instant().plus(initialBackoff));
            if ("CLOSE_UNCERTAIN".equals(category)) {
                anomalyService.recordCloseUncertain(
                        draft.provider(), draft.orderId(),
                        draft.transaction().transactionId(),
                        draft.transaction().transactionNo());
            } else {
                anomalyService.recordQueryUncertain(
                        draft.provider(), draft.orderId(),
                        draft.transaction().transactionId(),
                        draft.transaction().transactionNo());
            }
            return new CloseOrderResult(
                    draft.orderNo(), "PENDING", "PENDING", true);
        });
    }

    private CloseOrderResult applyClosed(OrderDraft draft) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transaction().transactionId());
            if ("PAID".equals(state.orderStatus())
                    || "SUCCEEDED".equals(state.transactionStatus())) {
                return new CloseOrderResult(
                        draft.orderNo(), state.orderStatus(),
                        state.transactionStatus(), false);
            }
            repository.closeTransaction(
                    draft.transaction().transactionId(), clock.instant());
            closeOrderLocked(
                    draft.orderId(), draft.orderNo(), draft.tenantId(), draft.userId(),
                    "PROVIDER_CONFIRMED_UNPAID", draft.systemClose());
            logState(draft.transaction(), "CLOSED");
            return new CloseOrderResult(
                    draft.orderNo(), "CLOSED", "CLOSED", false);
        });
    }

    private CloseOrderResult applyPendingRechargeClose(
            RechargeDraft draft,
            PaymentAdapter.ProviderPayment queried) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transaction().transactionId());
            if ("PAID".equals(state.orderStatus())
                    || "SUCCEEDED".equals(state.transactionStatus())) {
                return new CloseOrderResult(
                        draft.rechargeNo(), state.orderStatus(),
                        state.transactionStatus(), false);
            }
            repository.schedulePendingQuery(
                    draft.transaction().transactionId(), queried.providerStatus(),
                    queried.providerErrorCode(), clock.instant(),
                    clock.instant().plus(initialBackoff));
            return new CloseOrderResult(
                    draft.rechargeNo(), "PENDING", "PENDING", true);
        });
    }

    private CloseOrderResult applyRechargeCloseUncertain(
            RechargeDraft draft,
            String category) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transaction().transactionId());
            if ("PAID".equals(state.orderStatus())
                    || "SUCCEEDED".equals(state.transactionStatus())) {
                return new CloseOrderResult(
                        draft.rechargeNo(), state.orderStatus(),
                        state.transactionStatus(), false);
            }
            repository.scheduleRetry(
                    draft.transaction().transactionId(), clock.instant(),
                    clock.instant().plus(initialBackoff));
            if ("CLOSE_UNCERTAIN".equals(category)) {
                anomalyService.recordCloseUncertain(
                        draft.provider(), null, draft.rechargeId(),
                        draft.transaction().transactionId(),
                        draft.transaction().transactionNo());
            } else {
                anomalyService.recordQueryUncertain(
                        draft.provider(), null, draft.rechargeId(),
                        draft.transaction().transactionId(),
                        draft.transaction().transactionNo());
            }
            return new CloseOrderResult(
                    draft.rechargeNo(), "PENDING", "PENDING", true);
        });
    }

    private CloseOrderResult applyRechargeClosed(RechargeDraft draft) {
        return transactions.execute(ignored -> {
            LockedState state = lockState(draft.transaction().transactionId());
            if ("PAID".equals(state.orderStatus())
                    || "SUCCEEDED".equals(state.transactionStatus())) {
                return new CloseOrderResult(
                        draft.rechargeNo(), state.orderStatus(),
                        state.transactionStatus(), false);
            }
            repository.closeTransaction(
                    draft.transaction().transactionId(), clock.instant());
            closeRechargeLocked(
                    draft.rechargeId(), draft.rechargeNo(), "PROVIDER_CONFIRMED_UNPAID");
            logState(draft.transaction(), "CLOSED");
            return new CloseOrderResult(
                    draft.rechargeNo(), "CLOSED", "CLOSED", false);
        });
    }

    private OrderDraft prepareOrder(String orderNo, Actor actor) {
        OrderRow order = findOrder(orderNo, actor);
        TransactionDraft transaction = findLatestTransaction(order.id());
        if (transaction == null) {
            OrderRow locked = lockOrder(order.id());
            return prepareLockedOrder(
                    locked, findLatestTransaction(locked.id()), actor == null);
        }
        lockTransaction(transaction.transactionId());
        OrderRow lockedOrder = lockOrder(order.id());
        TransactionDraft latestTransaction = findLatestTransaction(lockedOrder.id());
        return prepareLockedOrder(lockedOrder, latestTransaction, actor == null);
    }

    private RechargeDraft prepareRecharge(String rechargeNo, Actor actor) {
        RechargeRow recharge = findRecharge(rechargeNo, actor);
        TransactionDraft transaction = findLatestRechargeTransaction(recharge.id());
        if (transaction == null) {
            RechargeRow locked = lockRecharge(recharge.id());
            return prepareLockedRecharge(
                    locked, findLatestRechargeTransaction(locked.id()));
        }
        lockTransaction(transaction.transactionId());
        RechargeRow lockedRecharge = lockRecharge(recharge.id());
        TransactionDraft latestTransaction = findLatestRechargeTransaction(
                lockedRecharge.id());
        return prepareLockedRecharge(lockedRecharge, latestTransaction);
    }

    private RechargeDraft prepareLockedRecharge(
            RechargeRow recharge,
            TransactionDraft latestTransaction) {
        if (!"PENDING".equals(recharge.status())) {
            return terminalRechargeDraft(
                    recharge,
                    latestTransaction == null ? null : latestTransaction.transactionStatus());
        }
        if (latestTransaction == null) {
            closeRechargeLocked(recharge.id(), recharge.rechargeNo(), "NO_PAYMENT_ATTEMPT");
            return terminalRechargeDraft(recharge, null, "CLOSED");
        }
        if ("CREATED".equals(latestTransaction.transactionStatus())) {
            return new RechargeDraft(
                    recharge.id(), recharge.rechargeNo(), null, null, null,
                    new CloseOrderResult(
                            recharge.rechargeNo(), "PENDING", "CREATED", true));
        }
        if ("FAILED".equals(latestTransaction.transactionStatus())
                || "CLOSED".equals(latestTransaction.transactionStatus())) {
            closeRechargeLocked(
                    recharge.id(), recharge.rechargeNo(), "PAYMENT_ATTEMPT_TERMINAL");
            return terminalRechargeDraft(
                    recharge, latestTransaction.transactionStatus(), "CLOSED");
        }
        return new RechargeDraft(
                recharge.id(), recharge.rechargeNo(), latestTransaction.provider(),
                latestTransaction.product(), latestTransaction, null);
    }

    private OrderDraft prepareLockedOrder(
            OrderRow lockedOrder,
            TransactionDraft latestTransaction,
            boolean systemClose) {
        if (!"PENDING".equals(lockedOrder.status())) {
            return terminalDraft(
                    lockedOrder, lockedOrder.status(),
                    latestTransaction == null ? null : latestTransaction.transactionStatus(),
                    systemClose);
        }
        if (latestTransaction == null) {
            closeOrderLocked(
                    lockedOrder.id(), lockedOrder.orderNo(), lockedOrder.tenantId(),
                    lockedOrder.userId(), "NO_PAYMENT_ATTEMPT", systemClose);
            return terminalDraft(lockedOrder, "CLOSED", null, systemClose);
        }
        if ("CREATED".equals(latestTransaction.transactionStatus())) {
            return new OrderDraft(
                    lockedOrder.id(), lockedOrder.orderNo(), lockedOrder.tenantId(),
                    lockedOrder.userId(), systemClose, null, null, null,
                    new CloseOrderResult(
                            lockedOrder.orderNo(), "PENDING", "CREATED", true));
        }
        if ("FAILED".equals(latestTransaction.transactionStatus())
                || "CLOSED".equals(latestTransaction.transactionStatus())) {
            closeOrderLocked(
                    lockedOrder.id(), lockedOrder.orderNo(), lockedOrder.tenantId(),
                    lockedOrder.userId(), "PAYMENT_ATTEMPT_TERMINAL", systemClose);
            return terminalDraft(
                    lockedOrder, "CLOSED", latestTransaction.transactionStatus(), systemClose);
        }
        PaymentProduct product = product(
                latestTransaction.provider(), latestTransaction.paymentProduct());
        return new OrderDraft(
                lockedOrder.id(), lockedOrder.orderNo(), lockedOrder.tenantId(),
                lockedOrder.userId(), systemClose,
                latestTransaction.provider(), product,
                latestTransaction, null);
    }

    private TransactionDraft prepareTransaction(String transactionNo) {
        TransactionDraft transaction = findTransaction(transactionNo);
        TransactionDraft locked = lockTransaction(transaction.transactionId());
        lockPaymentOwner(locked);
        return lockTransaction(locked.transactionId());
    }

    private OrderRow findOrder(String orderNo, Actor actor) {
        return (actor == null
                        ? repository.findOrder(orderNo)
                        : repository.findOrder(orderNo, actor.tenantId(), actor.userId()))
                .map(this::orderRow)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));
    }

    private RechargeRow findRecharge(String rechargeNo, Actor actor) {
        return repository.findRecharge(
                        rechargeNo, actor.tenantId(), actor.userId())
                .map(this::rechargeRow)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "recharge_order_not_found"));
    }

    private OrderRow lockOrder(UUID orderId) {
        return repository.lockOrder(orderId)
                .map(this::orderRow)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));
    }

    private RechargeRow lockRecharge(UUID rechargeId) {
        return repository.lockRecharge(rechargeId)
                .map(this::rechargeRow)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "recharge_order_not_found"));
    }

    private TransactionDraft findLatestTransaction(UUID orderId) {
        return repository.findLatestOrderTransaction(orderId)
                .map(this::transactionDraft)
                .orElse(null);
    }

    private TransactionDraft findLatestRechargeTransaction(UUID rechargeId) {
        return repository.findLatestRechargeTransaction(rechargeId)
                .map(this::transactionDraft)
                .orElse(null);
    }

    private TransactionDraft findTransaction(String transactionNo) {
        return repository.findTransaction(transactionNo)
                .map(this::transactionDraft)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_transaction_not_found"));
    }

    private TransactionDraft lockTransaction(UUID transactionId) {
        return repository.lockTransaction(transactionId)
                .map(this::transactionDraft)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "payment_transaction_not_found"));
    }

    private TransactionDraft transactionDraft(PaymentLifecycleRepository.TransactionRow row) {
        PaymentProvider provider = PaymentProvider.valueOf(row.provider());
        String productValue = row.paymentProduct();
        return new TransactionDraft(
                row.transactionId(), row.transactionNo(), row.orderId(),
                row.rechargeOrderId(), provider,
                productValue, product(provider, productValue),
                row.tenantId(), row.userId(), row.amount(), row.currency(),
                row.transactionStatus(), row.externalTradeNo());
    }

    private OrderRow orderRow(PaymentLifecycleRepository.OrderRow row) {
        return new OrderRow(
                row.id(), row.orderNo(), row.tenantId(), row.userId(), row.status());
    }

    private RechargeRow rechargeRow(PaymentLifecycleRepository.RechargeRow row) {
        return new RechargeRow(
                row.id(), row.rechargeNo(), row.tenantId(), row.userId(), row.status());
    }

    private LockedState lockState(UUID transactionId) {
        TransactionDraft transaction = lockTransaction(transactionId);
        String ownerStatus = lockPaymentOwner(transaction);
        return new LockedState(
                transaction.transactionNo(), transaction.transactionStatus(), ownerStatus);
    }

    private String lockPaymentOwner(TransactionDraft transaction) {
        boolean packageOwned = transaction.orderId() != null;
        boolean rechargeOwned = transaction.rechargeOrderId() != null;
        if (packageOwned == rechargeOwned) {
            throw invalid("payment_owner_invalid");
        }
        if (packageOwned) {
            return lockOrder(transaction.orderId()).status();
        }
        return repository.lockRechargeStatus(transaction.rechargeOrderId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "recharge_order_not_found"));
    }

    private void closeOrderLocked(
            UUID orderId,
            String orderNo,
            UUID tenantId,
            UUID userId,
            String reason,
            boolean systemClose) {
        int updated = repository.closeOrder(orderId, reason);
        if (updated == 1) {
            if (inventoryService != null) {
                inventoryService.release(orderId);
            }
            repository.auditOrderClosed(orderNo, reason);
            if (systemClose && notifications != null) {
                notifications.notifyOrderTimedOut(orderId, tenantId, userId, orderNo);
            }
        }
    }

    private void closeRechargeLocked(UUID rechargeId, String rechargeNo, String reason) {
        Instant closedAt = clock.instant();
        int updated = repository.closeRecharge(rechargeId, closedAt);
        if (updated == 1) {
            repository.auditRechargeClosed(rechargeNo, reason);
        }
    }

    private OrderDraft terminalDraft(
            OrderRow order,
            String orderStatus,
            String paymentStatus,
            boolean systemClose) {
        return new OrderDraft(
                order.id(), order.orderNo(), order.tenantId(), order.userId(), systemClose,
                null, null, null,
                new CloseOrderResult(
                        order.orderNo(), orderStatus, paymentStatus, false));
    }

    private RechargeDraft terminalRechargeDraft(
            RechargeRow recharge,
            String paymentStatus) {
        return terminalRechargeDraft(recharge, paymentStatus, recharge.status());
    }

    private RechargeDraft terminalRechargeDraft(
            RechargeRow recharge,
            String paymentStatus,
            String rechargeStatus) {
        return new RechargeDraft(
                recharge.id(), recharge.rechargeNo(), null, null, null,
                new CloseOrderResult(
                        recharge.rechargeNo(), rechargeStatus, paymentStatus, false));
    }

    private PaymentProduct product(PaymentProvider provider, String value) {
        if (value != null) {
            PaymentProduct product = PaymentProduct.valueOf(value);
            if (product.provider() != provider) {
                throw invalid("payment_product_provider_mismatch");
            }
            return product;
        }
        return switch (provider) {
            case MOCK -> PaymentProduct.MOCK;
            case WECHAT_PAY -> PaymentProduct.WECHAT_NATIVE;
            case ALIPAY -> PaymentProduct.ALIPAY_PAGE;
        };
    }

    private void logState(TransactionDraft draft, String state) {
        LOGGER.atInfo()
                .addKeyValue("transactionNo", draft.transactionNo())
                .addKeyValue("state", state)
                .addKeyValue("amount", draft.amount())
                .log("payment lifecycle state applied");
    }

    private void validateOrderRequest(String orderNo, Actor actor) {
        if (blank(orderNo) || orderNo.length() > 64 || actor == null
                || actor.tenantId() == null || actor.userId() == null) {
            throw invalid("invalid_order_close");
        }
    }

    private void validateRechargeRequest(String rechargeNo, Actor actor) {
        if (blank(rechargeNo) || rechargeNo.length() > 64 || actor == null
                || actor.tenantId() == null || actor.userId() == null) {
            throw invalid("invalid_recharge_close");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record Actor(UUID tenantId, UUID userId) {
    }

    public record CloseOrderResult(
            String orderNo,
            String orderStatus,
            String paymentStatus,
            boolean retryScheduled) {
    }

    public record PaymentQueryResult(
            String transactionNo,
            String paymentStatus,
            boolean retryScheduled) {
    }

    private record OrderRow(
            UUID id,
            String orderNo,
            UUID tenantId,
            UUID userId,
            String status) {
    }

    private record RechargeRow(
            UUID id,
            String rechargeNo,
            UUID tenantId,
            UUID userId,
            String status) {
    }

    private record OrderDraft(
            UUID orderId,
            String orderNo,
            UUID tenantId,
            UUID userId,
            boolean systemClose,
            PaymentProvider provider,
            PaymentProduct product,
            TransactionDraft transaction,
            CloseOrderResult terminalResult) {
    }

    private record RechargeDraft(
            UUID rechargeId,
            String rechargeNo,
            PaymentProvider provider,
            PaymentProduct product,
            TransactionDraft transaction,
            CloseOrderResult terminalResult) {
    }

    private record TransactionDraft(
            UUID transactionId,
            String transactionNo,
            UUID orderId,
            UUID rechargeOrderId,
            PaymentProvider provider,
            String paymentProduct,
            PaymentProduct product,
            UUID tenantId,
            UUID userId,
            long amount,
            String currency,
            String transactionStatus,
            String externalTradeNo) {
    }

    private record LockedState(
            String transactionNo,
            String transactionStatus,
            String orderStatus) {
    }
}
