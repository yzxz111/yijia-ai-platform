package com.yuzhihuihe.apiplatform.platform.wallet;

import java.time.Clock;

import com.yuzhihuihe.apiplatform.platform.notification.WalletUsageNotificationService;
import com.yuzhihuihe.apiplatform.platform.observability.CommercialTransactionMetrics;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAvailabilityService;
import com.yuzhihuihe.apiplatform.platform.reporting.OperatingFactService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public final class WalletServiceTestFactory {

    private WalletServiceTestFactory() {
    }

    public static WalletAccountService account(JdbcTemplate jdbc) {
        return new WalletAccountService(new WalletAccountRepository(jdbc));
    }

    public static BalancePostingService balancePosting(JdbcTemplate jdbc) {
        return new BalancePostingService(
                new BalancePostingRepository(jdbc),
                new CommercialTransactionMetrics(new SimpleMeterRegistry()));
    }

    public static WalletUsageReservationService usageReservation(
            JdbcTemplate jdbc,
            OperatingFactService operatingFacts,
            WalletUsageNotificationService notifications) {
        return new WalletUsageReservationService(
                new WalletUsageReservationRepository(jdbc), operatingFacts, notifications);
    }

    public static RechargePaymentAttemptService rechargePaymentAttempt(
            JdbcTemplate jdbc,
            PaymentAvailabilityService availabilityService,
            CommercialTransactionMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SmsProofService smsProofService) {
        return new RechargePaymentAttemptService(
                new RechargePaymentAttemptRepository(jdbc), availabilityService, metrics,
                transactionManager, clock, smsProofService);
    }
}
