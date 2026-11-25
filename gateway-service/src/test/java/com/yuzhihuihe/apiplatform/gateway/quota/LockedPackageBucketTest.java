package com.yuzhihuihe.apiplatform.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 方案 C 里"本次请求扣哪一个桶"的那一条判据。
 *
 * <p>{@code QuotaReservationRepository.LockedPackage#usesSecondBucket} 是整个第 5 步
 * 唯一的决策点：预留、结算、释放与三处余额计算都问它。它没有数据库依赖、
 * 也没有服务实例，因此可以直接测——而它值得被直接测，因为**两个方向错了都不会
 * 报错**：判成主桶会把一次 ASR 按视频秒计费，判成第二桶会让一个纯音频套餐
 * 的每一次请求都找不到桶。
 *
 * <p>最容易写错的恰恰是**纯音频套餐**（usage_dimension = AUDIO_SECOND）：
 * 它的音频秒住在主列 {@code total_token_quota} 里，第二份桶是空的。
 * 一个只看"请求维度是不是 AUDIO_SECOND"的实现会让它每次请求都失败。
 *
 * <p>这里刻意**不测**"两份额度都用尽才算终结"那条规则的 Java 镜像：它不在 Java 里，
 * 而是写在 {@code closeTerminalPackage} 与 {@code closeTerminalActive} 两条 UPDATE 的
 * WHERE 子句里，由数据库原子地判定。为它写一个本地副本再断言那个副本，只会得到
 * 一个"测自己"的测试。它的行为断言在 {@code AudioSecondBucketSettlementTest}：
 * 视频桶花完而音频桶还有余额时，实例必须保持 {@code ACTIVE}，且音频确实还能用。
 */
class LockedPackageBucketTest {

    private static final UUID PACKAGE_ID = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("74000000-0000-4000-8000-000000000001");

    @Test
    void onlyAnAudioRequestAgainstANonAudioInstanceUsesTheSecondBucket() {
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "VIDEO_SECOND", "AUDIO_SECOND")).isTrue();
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "TOKEN", "AUDIO_SECOND")).isTrue();
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "IMAGE", "AUDIO_SECOND")).isTrue();
        // 纯音频实例：请求维度就是主维度，主列才是它的音频秒。
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "AUDIO_SECOND", "AUDIO_SECOND")).isFalse();
        // 第二份桶只属于 AUDIO_SECOND；其余维度一律走主桶。
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "VIDEO_SECOND", "VIDEO_SECOND")).isFalse();
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "VIDEO_SECOND", "TOKEN")).isFalse();
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "VIDEO_SECOND", "IMAGE")).isFalse();
        assertThat(QuotaReservationRepository.LockedPackage.usesSecondBucket(
                "VIDEO_SECOND", null)).isFalse();
    }

    @Test
    void aMixedInstanceSupportsBothOfItsDimensionsAndNothingElse() {
        QuotaReservationRepository.LockedPackage mixed = mixed(60, 600, 0, 0, 0, 0);

        assertThat(mixed.supports("VIDEO_SECOND")).isTrue();
        assertThat(mixed.supports("AUDIO_SECOND")).isTrue();
        // token 与图片在这个实例上没有额度列，因此必须在这里就失败，
        // 而不是落进某个桶的算术。
        assertThat(mixed.supports("TOKEN")).isFalse();
        assertThat(mixed.supports("IMAGE")).isFalse();
        assertThat(mixed.supports(null)).isFalse();
        assertThat(mixed.supports("")).isFalse();
    }

    @Test
    void aVideoInstanceWithoutAnAudioBucketRefusesAudioEvenThoughTheDimensionsCoexist() {
        QuotaReservationRepository.LockedPackage videoOnly = new QuotaReservationRepository.LockedPackage(
                PACKAGE_ID, USER_ID, 60, 0, 0, Instant.now().plusSeconds(3600), "VIDEO_SECOND");

        assertThat(videoOnly.supports("VIDEO_SECOND")).isTrue();
        assertThat(videoOnly.supports("AUDIO_SECOND")).isFalse();
    }

    @Test
    void aPureAudioInstanceCarriesItsSecondsInThePrimaryBucket() {
        QuotaReservationRepository.LockedPackage pureAudio = new QuotaReservationRepository.LockedPackage(
                PACKAGE_ID, USER_ID, 600, 120, 300, Instant.now().plusSeconds(3600), "AUDIO_SECOND");

        assertThat(pureAudio.supports("AUDIO_SECOND")).isTrue();
        assertThat(pureAudio.supports("VIDEO_SECOND")).isFalse();
        // 主桶的算术：600 - 300 - 120 = 180。
        assertThat(pureAudio.availableFor("AUDIO_SECOND")).isEqualTo(180);
        assertThat(pureAudio.availableFor("VIDEO_SECOND")).isEqualTo(180);
    }

    @Test
    void eachBucketReportsItsOwnBalanceAndNothingBorrowsFromTheOther() {
        // 视频桶几乎用光（60 里已用 55），音频桶基本没动。
        QuotaReservationRepository.LockedPackage mixed = mixed(60, 600, 5, 55, 0, 0);

        assertThat(mixed.availableFor("VIDEO_SECOND")).isZero();
        assertThat(mixed.availableFor("AUDIO_SECOND")).isEqualTo(600);

        // 反过来：视频桶满额，音频桶空。四个读数各归各的桶——把音频的已用量
        // 读成视频的，会让一次视频预付被误判为"视频额度还有 600 秒"。
        QuotaReservationRepository.LockedPackage spentAudio = mixed(60, 600, 3, 0, 7, 600);
        assertThat(spentAudio.totalFor("VIDEO_SECOND")).isEqualTo(60);
        assertThat(spentAudio.totalFor("AUDIO_SECOND")).isEqualTo(600);
        assertThat(spentAudio.reservedFor("VIDEO_SECOND")).isEqualTo(3);
        assertThat(spentAudio.reservedFor("AUDIO_SECOND")).isEqualTo(7);
        assertThat(spentAudio.usedFor("VIDEO_SECOND")).isZero();
        assertThat(spentAudio.usedFor("AUDIO_SECOND")).isEqualTo(600);
        assertThat(spentAudio.availableFor("VIDEO_SECOND")).isEqualTo(57);
        assertThat(spentAudio.availableFor("AUDIO_SECOND")).isZero();
    }

    private static QuotaReservationRepository.LockedPackage mixed(
            long videoQuota, long audioQuota, long videoReserved, long videoUsed,
            long audioReserved, long audioUsed) {
        return new QuotaReservationRepository.LockedPackage(
                PACKAGE_ID, USER_ID, videoQuota, videoReserved, videoUsed,
                audioQuota, audioReserved, audioUsed,
                Instant.now().plusSeconds(3600), "VIDEO_SECOND");
    }
}
