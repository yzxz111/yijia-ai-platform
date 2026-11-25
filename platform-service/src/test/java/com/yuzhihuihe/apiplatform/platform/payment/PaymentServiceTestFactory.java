package com.yuzhihuihe.apiplatform.platform.payment;

import java.time.Clock;
import java.time.Duration;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.order.PackageOrderSettlementService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.wallet.BalancePostingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public final class PaymentServiceTestFactory {

    private PaymentServiceTestFactory() {
    }

    public static PaymentAvailabilityService availability(
            JdbcTemplate jdbc,
            PaymentAdapterRegistry registry,
            PlatformTransactionManager transactionManager) {
        return new PaymentAvailabilityService(
                new PaymentAvailabilityRepository(jdbc), registry, transactionManager);
    }

    public static PaymentLifecycleService lifecycle(
            JdbcTemplate jdbc,
            PaymentAvailabilityService availabilityService,
            PaymentPostingService postingService,
            PaymentAnomalyService anomalyService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration initialBackoff,
            CommercialNotificationService notifications) {
        return new PaymentLifecycleService(
                new PaymentLifecycleRepository(jdbc), availabilityService, postingService,
                anomalyService, metrics, transactionManager, clock, initialBackoff,
                null, notifications);
    }

    public static PaymentAttemptService paymentAttempt(
            JdbcTemplate jdbc,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SmsProofService smsProofService,
            CommercialNotificationService notifications) {
        return new PaymentAttemptService(
                new PaymentAttemptRepository(jdbc), availabilityService, metrics,
                transactionManager, clock, smsProofService, notifications);
    }

    public static PaymentPostingService paymentPosting(
            JdbcTemplate jdbc,
            PackageOrderSettlementService settlementService,
            BalancePostingService balancePostingService,
            CommercialNotificationService notifications,
            Clock clock,
            Duration anomalyWindow) {
        PackagePaymentPostingService packagePosting = new PackagePaymentPostingService(
                new PackagePaymentPostingRepository(jdbc), settlementService);
        RechargePaymentPostingService rechargePosting = new RechargePaymentPostingService(
                new RechargePaymentPostingRepository(jdbc), balancePostingService, notifications);
        return new PaymentPostingService(
                new PaymentPostingRepository(jdbc), packagePosting, rechargePosting,
                clock, anomalyWindow);
    }
}
