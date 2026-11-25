package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletUsageReservationExpiryJobTest {

    @Test
    void continuesAfterOneReservationFailsToExpire() {
        WalletUsageReservationService reservations = mock(WalletUsageReservationService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(reservations.expiredReservationIds(100)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("temporary failure")).when(reservations).expire(first);

        new WalletUsageReservationExpiryJob(reservations, 100).expireOnce();

        verify(reservations).expire(first);
        verify(reservations).expire(second);
    }
}
