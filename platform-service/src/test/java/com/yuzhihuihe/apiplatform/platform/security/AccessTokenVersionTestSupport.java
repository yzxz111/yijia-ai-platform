package com.yuzhihuihe.apiplatform.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class AccessTokenVersionTestSupport {

    private AccessTokenVersionTestSupport() { }

    public static void stubCurrentLegacyToken(JdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(
                contains("terminal_current"),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                isNull(), isNull(), isNull(),
                any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet result = mock(ResultSet.class);
                    when(result.getLong("auth_version")).thenReturn(0L);
                    when(result.getString("status")).thenReturn("ACTIVE");
                    when(result.getBoolean("terminal_current")).thenReturn(true);
                    return List.of(mapper.mapRow(result, 0));
                });
    }
}
