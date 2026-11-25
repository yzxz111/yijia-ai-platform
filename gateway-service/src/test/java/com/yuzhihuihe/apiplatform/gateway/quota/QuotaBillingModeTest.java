package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuotaBillingModeTest {

    @Test
    void sendsHybridOnlyWhenPackageAndWalletBothSettle() {
        assertThat(QuotaService.walletBillingMode(100, 20)).isEqualTo("HYBRID");
        assertThat(QuotaService.walletBillingMode(0, 20)).isEqualTo("WALLET_ONLY");
    }
}
