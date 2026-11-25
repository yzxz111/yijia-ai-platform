package com.yuzhihuihe.apiplatform.platform.refund;

import com.yuzhihuihe.apiplatform.platform.notification.CommercialNotificationService;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletServiceTestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RefundServiceTestFactory {

    private RefundServiceTestFactory() {
    }

    public static RefundOutboxCompletionService outboxCompletion(
            JdbcTemplate jdbc, CommercialNotificationService notifications) {
        return new RefundOutboxCompletionService(
                new RefundOutboxCompletionRepository(jdbc),
                WalletServiceTestFactory.balancePosting(jdbc), notifications);
    }
}
