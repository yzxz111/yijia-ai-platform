package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClawhubSkillImportServiceTest {

    @Test
    void createsSourceAndOwnerTagsWithoutDuplicates() {
        assertThat(ClawhubSkillImportService.sourceTags("why20261"))
                .containsExactly("ClawHub", "why20261");
        assertThat(ClawhubSkillImportService.sourceTags("clawhub"))
                .containsExactly("ClawHub");
    }

    @Test
    void keepsImportWithinCatalogTagLengthLimit() {
        assertThat(ClawhubSkillImportService.sourceTags("a".repeat(64)))
                .containsExactly("ClawHub", "a".repeat(64));
        assertThat(ClawhubSkillImportService.sourceTags("a".repeat(65)))
                .containsExactly("ClawHub");
    }
}
