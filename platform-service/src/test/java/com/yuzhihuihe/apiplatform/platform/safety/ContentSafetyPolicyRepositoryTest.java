package com.yuzhihuihe.apiplatform.platform.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ContentSafetyPolicyRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void convertsPublishedTimestampFromPostgresOffsetDateTime() throws Exception {
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-09-01T17:30:00+08:00");
        ResultSet result = mock(ResultSet.class);
        when(result.getLong("version")).thenReturn(12L);
        when(result.getString("config")).thenReturn("{\"enabled\":true}");
        when(result.getString("config_sha256")).thenReturn("a".repeat(64));
        when(result.getObject("published_at", OffsetDateTime.class)).thenReturn(publishedAt);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(result, 0));
        });

        var snapshot = new ContentSafetyPolicyRepository(jdbc).publishedSnapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.publishedAt()).isEqualTo(Instant.parse("2026-09-01T09:30:00Z"));
    }
}
