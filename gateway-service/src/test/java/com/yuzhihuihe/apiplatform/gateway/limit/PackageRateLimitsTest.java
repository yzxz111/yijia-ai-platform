package com.yuzhihuihe.apiplatform.gateway.limit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 一列，两种单位。{@code package_instance.tokens_per_minute} 是携带维度的，因此读数必须来自
 * 套餐自身的维度——而在 {@code VIDEO_SECOND} 实例上，还取决于所存的数字究竟是不是平台可以按
 * token 速率发布的数字（V63 拒绝发布低于 {@link PackageRateLimits#TOKEN_RATE_FLOOR} 的、赋予
 * TEXT 权益的套餐方案）。
 *
 * <p>这里每个用例所捍卫的不变式是：任何已售数字都不会被按错误的维度计费：token 套餐完全保持
 * 它原有的行为，视频套餐的 SPM 绝不限制 token，视频套餐的 token 速率也绝不限制秒数。
 */
class PackageRateLimitsTest {

    private static final UUID PACKAGE_ID = UUID.fromString("9a500000-0000-4000-8000-000000000001");

    @Test
    void aTokenPlanKeepsItsSoldRateEvenWhenSmall() {
        PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "TOKEN", 600L);

        assertThat(rates.usageDimension()).isEqualTo("TOKEN");
        assertThat(rates.tokenLimit().enforced())
                .as("a low token rate on a token plan is a low rate, not an SPM")
                .isTrue();
        assertThat(rates.tokenLimit().tokensPerMinute()).isEqualTo(600L);
        assertThat(rates.secondLimit().enforced()).isFalse();
    }

    @Test
    void aVideoPlansSecondsRateBoundsSecondsAndNeverTokens() {
        for (long spm : new long[] {60L, 190L, 600L}) {
            PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "VIDEO_SECOND", spm);

            assertThat(rates.secondLimit().enforced())
                    .as("VIDEO_SECOND %s is seconds per minute and must bound generation", spm)
                    .isTrue();
            assertThat(rates.secondLimit().secondsPerMinute()).isEqualTo(spm);
            assertThat(rates.tokenLimit().enforced())
                    .as("the SPM value must never become a chat credit rate")
                    .isFalse();
        }
    }

    @Test
    void aVideoPlansTokenRateBoundsTokensAndNeverSeconds() {
        PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "VIDEO_SECOND", 300_000L);

        assertThat(rates.tokenLimit().enforced())
                .as("the platform's V63 shape: the plan sells a chat token rate on a video plan")
                .isTrue();
        assertThat(rates.tokenLimit().tokensPerMinute()).isEqualTo(300_000L);
        assertThat(rates.secondLimit().enforced()).isFalse();
        assertThat(rates.usageDimension()).isEqualTo("VIDEO_SECOND");
    }

    @Test
    void thePublicationFloorIsInclusive() {
        assertThat(PackageRateLimits.isTokenRate("VIDEO_SECOND", 10_000L)).isTrue();
        assertThat(PackageRateLimits.isSecondRate("VIDEO_SECOND", 10_000L)).isFalse();
        assertThat(PackageRateLimits.isTokenRate("VIDEO_SECOND", 9_999L)).isFalse();
        assertThat(PackageRateLimits.isSecondRate("VIDEO_SECOND", 9_999L)).isTrue();
    }

    @Test
    void aRowWithoutADimensionIsATokenPackage() {
        PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, null, 30L);

        assertThat(rates.usageDimension()).isEqualTo("TOKEN");
        assertThat(rates.tokenLimit().enforced()).isTrue();
        assertThat(rates.tokenLimit().tokensPerMinute()).isEqualTo(30L);
        assertThat(rates.secondLimit().enforced()).isFalse();
    }

    @Test
    void aPackageSellingNoRateIsUnlimitedInBothDirections() {
        for (Long sold : new Long[] {null, 0L, -1L}) {
            PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "VIDEO_SECOND", sold);
            assertThat(rates.tokenLimit().enforced()).isFalse();
            assertThat(rates.secondLimit().enforced()).isFalse();
        }
        PackageRateLimits noPackage = PackageRateLimits.of(null, "VIDEO_SECOND", 190L);
        assertThat(noPackage.tokenLimit().enforced()).isFalse();
        assertThat(noPackage.secondLimit().enforced()).isFalse();
    }

    @Test
    void anImagePlansPerMinuteValueBoundsImagesAndNeverTokens() {
        // 在 IMAGE 实例上，tokens_per_minute 是每分钟张数，与 VIDEO_SECOND 实例上的
        // 每分钟秒数完全同形。缺少这一分支曾让按张计费的套餐两个方向都不受限：
        // 500 既不是 token 速率（维度不是 TOKEN，且 500 < 10000），
        // 也不被当作秒/张（维度不是 VIDEO_SECOND），于是这个已售出的上限被静默忽略。
        for (long ipm : new long[] {1L, 60L, 500L, 9_999L}) {
            PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "IMAGE", ipm);

            assertThat(rates.secondLimit().enforced())
                    .as("IMAGE %s is images per minute and must bound generation", ipm)
                    .isTrue();
            assertThat(rates.secondLimit().secondsPerMinute()).isEqualTo(ipm);
            assertThat(rates.secondLimit().usageDimension())
                    .as("the rejection code names the unit from this dimension")
                    .isEqualTo("IMAGE");
            assertThat(rates.tokenLimit().enforced())
                    .as("500 images per minute is not 500 credits per minute")
                    .isFalse();
            assertThat(rates.usageDimension()).isEqualTo("IMAGE");
        }
    }

    @Test
    void anImagePlanAtATokenScaleEnforcesTokensAndNeverImages() {
        // 与 VIDEO_SECOND 相同的兜底：一个达到发布下限的数值是套餐方案的
        // chat token 速率（混合池版本正是如此），因此它约束 token，绝不约束张。
        PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "IMAGE", 300_000L);

        assertThat(rates.tokenLimit().enforced()).isTrue();
        assertThat(rates.tokenLimit().tokensPerMinute()).isEqualTo(300_000L);
        assertThat(rates.secondLimit().enforced()).isFalse();
    }

    @Test
    void theTwoDirectionsAreMutuallyExclusive() {
        long[] values = {1L, 59L, 60L, 190L, 600L, 9_999L, 10_000L, 100_000L, 2_000_000L};
        // AUDIO_SECOND 必须在被遍历的维度里：断言「不会同时按两个维度计费」的测试
        // 若从不把音频放进来，就永远不会发现音频被两个分支同时命中。
        for (String dimension : new String[] {
                "TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND", null}) {
            for (long value : values) {
                PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, dimension, value);
                assertThat(rates.tokenLimit().enforced() && rates.secondLimit().enforced())
                        .as("%s/%s must not be charged in both dimensions", dimension, value)
                        .isFalse();
            }
        }
    }

    /**
     * 音频是「按秒」的那一侧，与视频同形。
     *
     * <p>这条断言钉住 {@code AUDIO_SECOND} 被加进 {@code isSecondRate} 之前
     * 那个静默缺陷：对一个音频套餐，{@code tokens_per_minute} 装的是**每分钟秒数**，
     * 而漏掉这个维度会让这个已售出的上限被整体忽略——套餐照卖，
     * 但客户可以无限速地消耗它。那不是报错，是少收限制。
     */
    @Test
    void audioSecondIsReadAsSecondsPerMinuteNotAsATokenRate() {
        PackageRateLimits rates = PackageRateLimits.of(PACKAGE_ID, "AUDIO_SECOND", 60L);
        assertThat(rates.usageDimension()).isEqualTo("AUDIO_SECOND");
        assertThat(rates.secondLimit().enforced()).isTrue();
        assertThat(rates.secondLimit().secondsPerMinute()).isEqualTo(60L);
        // 60（一次调用最多一分钟的音频）绝不能同时被读成每分钟 60 个 token，
        // 那会让一个音频套餐的 chat 限额退化成几乎为零。
        assertThat(rates.tokenLimit().enforced()).isFalse();

        assertThat(PackageRateLimits.isSecondRate("AUDIO_SECOND", 60L)).isTrue();
        assertThat(PackageRateLimits.isSecondRate("AUDIO_SECOND", 10_000L)).isFalse();
    }
}
