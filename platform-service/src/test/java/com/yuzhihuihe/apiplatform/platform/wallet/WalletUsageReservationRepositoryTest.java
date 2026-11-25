package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WalletUsageReservationRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void convertsReservationExpiryFromPostgresOffsetDateTime() throws Exception {
        UUID reservationId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.parse("2026-09-01T18:00:00+08:00");
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("id", UUID.class)).thenReturn(reservationId);
        when(result.getObject("tenant_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(result.getObject("user_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(result.getObject("account_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(result.getObject("expires_at", OffsetDateTime.class)).thenReturn(expiresAt);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });

        var reservation = new WalletUsageReservationRepository(jdbc)
                .findReservation(reservationId);

        assertThat(reservation).isNotNull();
        assertThat(reservation.expiresAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    }
}
