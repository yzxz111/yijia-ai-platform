package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AdminWalletServiceTest {

    @Test
    void acceptsModelUsageAsALedgerEntryFilter() {
        AdminWalletRepository repository = mock(AdminWalletRepository.class);
        when(repository.findLedger(
                any(), any(), eq("MODEL_USAGE"), any(), any(),
                anyInt(), anyInt(), any(), any()))
                .thenReturn(new AdminWalletRepository.PageRows<>(List.of(), 0));
        AdminWalletService service = new AdminWalletService(repository);

        AdminWalletService.PageResult<AdminWalletService.LedgerView> result = service.ledger(
                UUID.randomUUID(), UUID.randomUUID(), new AdminWalletService.LedgerQuery(
                        0, 20, null, "MODEL_USAGE", null, null, null, null));

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void onlyVerifiedGlobalAdminCanReadOtherTenants() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        AdminWalletRepository repository = mock(AdminWalletRepository.class);
        when(repository.findAccounts(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new AdminWalletRepository.PageRows<>(List.of(), 0));
        when(repository.findLedger(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new AdminWalletRepository.PageRows<>(List.of(), 0));
        when(repository.findRecharges(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new AdminWalletRepository.PageRows<>(List.of(), 0));
        AdminWalletService service = new AdminWalletService(repository);
        var accounts = new AdminWalletService.AccountQuery(0, 20, "U00000053", null, null, null, null, null);
        var ledger = new AdminWalletService.LedgerQuery(0, 20, null, null, null, null, null, null);
        var recharges = new AdminWalletService.RechargeQuery(0, 20, null, null, null, null, null, null);
        service.accounts(tenant, actor, accounts);
        service.ledger(tenant, actor, ledger);
        service.recharges(tenant, actor, recharges);
        verify(repository).findAccounts(eq(tenant), eq("U00000053"), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(repository).findLedger(eq(tenant), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(repository).findRecharges(eq(tenant), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());

        when(repository.isGlobalAdmin(tenant, actor)).thenReturn(true);
        service.accounts(tenant, actor, accounts);
        service.ledger(tenant, actor, ledger);
        service.recharges(tenant, actor, recharges);
        verify(repository).findAccounts(isNull(), eq("U00000053"), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(repository).findLedger(isNull(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(repository).findRecharges(isNull(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void overviewUsesAggregateTotalsAndFillsSevenShanghaiDates() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        AdminWalletRepository repository = mock(AdminWalletRepository.class);
        when(repository.balanceTotals(tenant)).thenReturn(new AdminWalletRepository.BalanceTotals(150, 123456));
        when(repository.dailyFlows(eq(tenant), any(), any())).thenReturn(List.of(
                new AdminWalletRepository.DailyFlow(today.minusDays(6), 101, 1),
                new AdminWalletRepository.DailyFlow(today, 30100, 50)));
        var result = new AdminWalletService(repository).overview(tenant, actor);
        assertThat(result.accounts()).isEqualTo(150);
        assertThat(result.totalBalance()).isEqualTo(123456);
        assertThat(result.todayRecharge()).isEqualTo(30100);
        assertThat(result.todayDebit()).isEqualTo(50);
        assertThat(result.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.trend()).hasSize(7);
        assertThat(result.trend().get(1).recharge()).isZero();
        assertThat(result.trend().getFirst().date()).isEqualTo(today.minusDays(6));
        verify(repository).dailyFlows(tenant, today.minusDays(6).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),
                today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }
}
