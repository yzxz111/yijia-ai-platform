package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SkillPackageFileNamesTest {

    @ParameterizedTest
    @CsvSource({
            "demo-1.0.0.ycps.zip, demo-1.0.0.ycps.zip",
            "demo-1.0.0.ycps(1).zip, demo-1.0.0.ycps.zip",
            "demo-1.0.0.ycps (1).zip, demo-1.0.0.ycps.zip",
            "demo-1.0.0 (1).ycps.zip, demo-1.0.0.ycps.zip",
            "demo-1.0.0 (1) (2).ycps.zip, demo-1.0.0.ycps.zip",
            "C:\\Downloads\\demo-1.0.0.ycps(3).zip, demo-1.0.0.ycps.zip",
            "demo-1.0.0.bundle(1).zip, demo-1.0.0.bundle.zip"
    })
    void canonicalisesBrowserDuplicateDownloadNames(String input, String expected) {
        assertThat(SkillPackageFileNames.safeFileName(input, "fallback.ycps.zip"))
                .isEqualTo(expected);
    }
}
