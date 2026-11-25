package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 单位守卫的完整契约，逐个边界地验证。
 *
 * <p>这就是必须阻止 token 速率被写进秒数列的那个守卫：
 * V63 正是这么做的（视频套餐方案的 60 / 190 / 600 变成了 100,000 / 300,000 / 500,000），
 * 而控制台一直渲染「SPM 100,000」，直到 V64 修正了数据。因此这两个维度
 * 都按双向断言 —— 秒版本可以卖什么，以及 token 版本必须
 * 卖到什么程度才能承载一段对话。
 */
class PackageRateCeilingPolicyTest {

    private static final Set<PackageVersionService.ModelPermission> VIDEO_PERMISSION =
            Set.of(new PackageVersionService.ModelPermission("video-standard", null, Set.of("VIDEO")));
    private static final Set<PackageVersionService.ModelPermission> IMAGE_PERMISSION =
            Set.of(new PackageVersionService.ModelPermission("image-basic", null, Set.of("IMAGE")));
    private static final Set<PackageVersionService.ModelPermission> TEXT_PERMISSION =
            Set.of(new PackageVersionService.ModelPermission("text-standard", null, Set.of("TEXT")));
    private static final Set<PackageVersionService.ModelPermission> FILE_ONLY_PERMISSION =
            Set.of(new PackageVersionService.ModelPermission("file-basic", null, Set.of("FILE")));

    @Test
    void acceptsARealSecondsPerMinuteCeilingForAVideoVersion() {
        for (long ceiling : new long[] {1L, 60L, 190L, 600L, 3_600L, 86_399L, 86_400L}) {
            assertThatCode(() -> PackageRateCeilingPolicy.requireSoldRate(
                    "VIDEO_SECOND", ceiling, VIDEO_PERMISSION))
                    .as("a seconds ceiling of %d is a rate a video plan can sell", ceiling)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void refusesATokenRateInAVideoVersionsSecondsColumn() {
        // V63 写入的那三个值，外加好心的运维可能会输入的整数形态。
        for (long tokenRate : new long[] {86_401L, 100_000L, 300_000L, 500_000L, 2_000_000L}) {
            assertThatThrownBy(() -> PackageRateCeilingPolicy.requireSoldRate(
                    "VIDEO_SECOND", tokenRate, VIDEO_PERMISSION))
                    .as("a token rate of %d must not pass as a seconds ceiling", tokenRate)
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode().value()).isEqualTo(422);
                        assertThat(error.getReason())
                                .isEqualTo(PackageRateCeilingPolicy.VIDEO_SECONDS_CODE);
                    });
        }
    }

    @Test
    void refusesANonPositiveOrAboveTheCeilingSecondsValue() {
        for (long invalid : new long[] {0L, -1L, -100_000L, 86_401L}) {
            assertThatThrownBy(() -> PackageRateCeilingPolicy.requireSoldRate(
                    "VIDEO_SECOND", invalid, VIDEO_PERMISSION))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                            assertThat(error.getReason())
                                    .isEqualTo(PackageRateCeilingPolicy.VIDEO_SECONDS_CODE));
        }
    }

    @Test
    void theImageDimensionIsBoundByTheSameCeilingWithItsOwnCode() {
        assertThatCode(() -> PackageRateCeilingPolicy.requireSoldRate(
                "IMAGE", 600L, IMAGE_PERMISSION)).doesNotThrowAnyException();

        assertThatThrownBy(() -> PackageRateCeilingPolicy.requireSoldRate(
                "IMAGE", 100_000L, IMAGE_PERMISSION))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason())
                                .isEqualTo(PackageRateCeilingPolicy.IMAGE_SECONDS_CODE));
    }

    @Test
    void refusesATextVersionWhoseTokenRateCannotServeAConversation() {
        for (long tooLow : new long[] {1L, 60L, 190L, 600L, 9_999L}) {
            assertThatThrownBy(() -> PackageRateCeilingPolicy.requireSoldRate(
                    "TOKEN", tooLow, TEXT_PERMISSION))
                    .as("a TEXT version selling %d tokens per minute is unusable", tooLow)
                    .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                            assertThat(error.getReason())
                                    .isEqualTo(PackageRateCeilingPolicy.TOKEN_RATE_CODE));
        }

        for (long usable : new long[] {10_000L, 100_000L, 2_000_000L}) {
            assertThatCode(() -> PackageRateCeilingPolicy.requireSoldRate(
                    "TOKEN", usable, TEXT_PERMISSION)).doesNotThrowAnyException();
        }
    }

    @Test
    void leavesDimensionsWhoseRateIsNotTheConfusedUnitAlone() {
        // 未授予 TEXT 的 token 版本卖的不是聊天速率，因此 token 下限
        // 不是它的规则；而没有每分钟语义的维度根本不做判定。
        assertThatCode(() -> PackageRateCeilingPolicy.requireSoldRate(
                "TOKEN", 1L, FILE_ONLY_PERMISSION)).doesNotThrowAnyException();
        assertThatCode(() -> PackageRateCeilingPolicy.requireSoldRate(
                "PER_MINUTE", 500_000L, FILE_ONLY_PERMISSION)).doesNotThrowAnyException();
    }

    @Test
    void theDocumentedBoundsAreTheOnesV64AssertsForPublishedRows() {
        // 迁移脚本、该守卫和控制台消息都引用了这两个数字；如果其中一个
        // 变动，其他几个必须随之变动。
        assertThat(PackageRateCeilingPolicy.MAXIMUM_SECONDS_PER_MINUTE).isEqualTo(86_400L);
        assertThat(PackageRateCeilingPolicy.MINIMUM_TOKEN_RATE_FOR_TEXT).isEqualTo(10_000L);
    }
}
