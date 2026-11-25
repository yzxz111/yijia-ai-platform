package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SkillImageValidatorTest {

    @Test
    void acceptsRasterSignaturesAndAStaticSvg() {
        assertThatCode(() -> SkillImageValidator.validate(
                        new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                        "image/png"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SkillImageValidator.validate(
                        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                        "image/jpeg"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SkillImageValidator.validate(
                        "RIFF1234WEBP".getBytes(StandardCharsets.US_ASCII),
                        "image/webp"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SkillImageValidator.validate(
                        """
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                          <defs><linearGradient id="g"/></defs>
                          <rect width="16" height="16" fill="url(#g)"/>
                        </svg>
                        """.getBytes(StandardCharsets.UTF_8),
                        "image/svg+xml"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedRasterAndActiveSvgContent() {
        assertThatThrownBy(() -> SkillImageValidator.validate(
                        "not png".getBytes(StandardCharsets.UTF_8), "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String svg : new String[] {
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"https://example.test/x\"/></svg>",
                "<?xml-stylesheet href=\"https://example.test/x.css\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><animate attributeName=\"href\" to=\"https://example.test/x\"/></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><set attributeName=\"href\" to=\"data:text/html,x\"/></svg>",
                "<!DOCTYPE svg><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><foreignObject/></svg>"
        }) {
            assertThatThrownBy(() -> SkillImageValidator.validate(
                            svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
