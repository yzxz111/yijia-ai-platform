package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.r2dbc.spi.Row;

import org.junit.jupiter.api.Test;

class SkillVersionRepositoryTest {

    @Test
    void highestUsesSemanticPrereleaseOrdering() {
        assertThat(SkillVersionRepository.highest(List.of("1.0.0-beta", "1.0.0")))
                .isEqualTo("1.0.0");
        assertThat(SkillVersionRepository.highest(List.of(
                        "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-alpha")))
                .isEqualTo("1.0.0-beta.11");
        assertThat(SkillVersionRepository.highest(List.of("1.99.9", "2.0.0-alpha")))
                .isEqualTo("2.0.0-alpha");
    }

    @Test
    void sameSemanticVersionReadySuccessorIsNotMarkedOnline() {
        Row row = versionRow("READY", 2);

        assertThat(SkillRepository.versionView(row, "1.0.0").online()).isFalse();
    }

    @Test
    void publishedRevisionRemainsTheOnlyOnlineRowForItsSemanticVersion() {
        Row row = versionRow("PUBLISHED", 1);

        assertThat(SkillRepository.versionView(row, "1.0.0").online()).isTrue();
    }

    private static Row versionRow(String status, int revision) {
        Map<String, Object> values = Map.of(
                "id", UUID.randomUUID(),
                "skill_id", UUID.randomUUID(),
                "version", "1.0.0",
                "revision", revision,
                "status", status);
        Row row = mock(Row.class);
        when(row.get(anyString(), any(Class.class)))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        return row;
    }
}
