package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.Test;

/**
 * 音频计费里两个**纯算术**决定的回归测试。
 *
 * <p>它们被单独测，而不是只靠一次端到端调用覆盖，因为这两个函数决定的是钱，
 * 而它们的错误方向都是「不会失败」的那一种：
 *
 * <ul>
 *   <li>{@code clampSeconds} 少算一次，平台自己吃掉成本；多算一次，
 *       客户在没有任何产出证据的情况下被扣款。</li>
 *   <li>{@code reservedSecondsForLipSync} 取错一方，会让一次短视频 + 长音频的
 *       调用白白占住额度——而那条额度正是同一分钟内别的客户被限流的原因。</li>
 * </ul>
 *
 * <p>两个函数都是静态的、只依赖入参，因此这里不需要构造服务，
 * 也不需要数据库或桩：它们就是本测试要证明的东西的全部。
 */
class AudioGenerationBillingTest {

    // -----------------------------------------------------------------------
    // 结算秒数：以预留为上限，缺失即零
    // -----------------------------------------------------------------------

    @Test
    void billsWithinTheReservationUntouched() {
        assertThat(AudioGenerationService.clampSeconds(7, 30)).isEqualTo(7);
        assertThat(AudioGenerationService.clampSeconds(30, 30)).isEqualTo(30);
    }

    /**
     * 厂商报告的比预留多时，**按预留收**。
     *
     * <p>这只会发生在客户少报秒数的情况（例如口型替换里预留取了较短视频的长度，
     * 而厂商扩展了画面）。按厂商的数字结算会突破套餐额度——即把一次谎报
     * 变成一笔超出客户余额的账——因此多出的部分被丢弃。
     */
    @Test
    void neverBillsMoreThanTheReservation() {
        assertThat(AudioGenerationService.clampSeconds(120, 30)).isEqualTo(30);
        assertThat(AudioGenerationService.clampSeconds(Integer.MAX_VALUE, 5)).isEqualTo(5);
    }

    /**
     * 厂商没有报告用量时收零，而不是按预留全额收。
     *
     * <p>这是与「成功但缺少 usage 的响应仍然产出了结果」相反的一侧：
     * 对**失败**的调用（{@code failedSeconds}）视频路径的规则是
     * 「只有厂商明确报告了用量才收费」。此处是同一个判据的算术核心。
     * 把 null 当成「按预留收」会让每一次上游故障都按全额扣款。
     */
    @Test
    void aMissingOrNonPositiveVendorReportChargesNothing() {
        assertThat(AudioGenerationService.clampSeconds(null, 30)).isZero();
        assertThat(AudioGenerationService.clampSeconds(0, 30)).isZero();
        // 负数不是合法的用量；它同样必须收零而不是被当成一个很小的数。
        assertThat(AudioGenerationService.clampSeconds(-5, 30)).isZero();
    }

    /** 预留为 0 只可能来自一个已损坏的记录，此时绝不能凭空收费。 */
    @Test
    void aZeroReservationCannotBeBilledAgainst() {
        assertThat(AudioGenerationService.clampSeconds(10, 0)).isZero();
    }

    // -----------------------------------------------------------------------
    // 口型替换的预留：与厂商的截断行为一致
    // -----------------------------------------------------------------------

    /**
     * 默认按音频与视频中**较短**的一方预留。
     *
     * <p>厂商在两者不一致时默认按较短的一方截断，因此产出的秒数就是那个较短值。
     * 用较长的一方预留会多占额度，而结算到实际值时差额才被释放——
     * 在那一分钟里，多占的部分已经让别的客户被限流了。
     */
    @Test
    void lipSyncReservesTheShorterInputUnlessExtensionIsRequested() {
        // 视频 5 秒、音频 20 秒：不扩展时厂商截断音频，产出约 5 秒。
        assertThat(AudioGenerationService.reservedSecondsForLipSync(5, 20, false))
                .isEqualTo(5);
        // 视频 20 秒、音频 5 秒：厂商截断视频，产出约 5 秒。
        assertThat(AudioGenerationService.reservedSecondsForLipSync(20, 5, false))
                .isEqualTo(5);
        // 相等时就是那个值本身。
        assertThat(AudioGenerationService.reservedSecondsForLipSync(9, 9, false))
                .isEqualTo(9);
    }

    /**
     * 客户明确要求扩展画面时，按**音频**长度预留。
     *
     * <p>{@code video_extension = true} 让厂商用原视频画面「倒放-正放」交替
     * 填充到音频长度，因此产出就是音频的秒数。此时仍按视频预留会让一次
     * 长配音 + 短视频的调用在结算时顶到预留上限，多出的部分收不到。
     */
    @Test
    void requestingExtensionReservesTheAudioLength() {
        assertThat(AudioGenerationService.reservedSecondsForLipSync(5, 20, true))
                .isEqualTo(20);
        // 反向：音频比视频短时，扩展没有任何可扩展的东西，
        // 但客户仍然明确要求了这条规则，因此按音频预留。
        assertThat(AudioGenerationService.reservedSecondsForLipSync(20, 5, true))
                .isEqualTo(5);
    }

    /**
     * 预留与结算一起看：一次「视频 5 秒、音频 20 秒、未开启扩展」的调用，
     * 即使厂商报告了 20 秒，也只收 5 秒。
     *
     * <p>这条把两个函数串起来，因为它正是客户少报那一侧的完整形状：
     * 单独看每个函数都对，而把它们接错（例如结算时不 clamp）会让一次
     * 5 秒的产出按 20 秒计费。
     */
    @Test
    void anUnderDeclaredExtensionCannotBeBilledAtTheVendorsLongerReport() {
        int reserved = AudioGenerationService.reservedSecondsForLipSync(5, 20, false);
        assertThat(AudioGenerationService.clampSeconds(20, reserved)).isEqualTo(5);
    }

    // -----------------------------------------------------------------------
    // 「这次失败是不是一次确定性的拒绝」——决定预留是释放还是保持
    // -----------------------------------------------------------------------

    /**
     * 只有上游显式的 4xx（**408 除外**）才算确定性拒绝。
     *
     * <p>判据本身决定钱：确定性拒绝会**释放**预留（厂商拒绝了，没有消耗），
     * 而结果未知的故障**保持**预留（厂商可能已经接单，释放会让重试买第二次）。
     *
     * <p>408 被排除在「确定性」之外是最容易被写错的一处：请求超时的形态
     * 看起来像一次 4xx，但它意味着厂商**可能已经接单**。把它当成拒绝会
     * 在每一次网关侧超时时白白送出一次可能已经完成的处理。
     *
     * <p>这条判据必须与 {@code ImageGenerationService.isDefiniteUpstreamRejection}
     * 完全一致：两条按单位计费的路径对「能否释放预留」给出不同答案，
     * 会让同一类故障在图片上退款、在音频上扣款。
     */
    @Test
    void onlyAnExplicitNonTimeoutClientRejectionIsDefinite() {
        // 确定性的拒绝：厂商明确拒绝了这次请求，没有任何东西被处理。
        for (int status : new int[] {400, 401, 403, 404, 409, 413, 422, 429}) {
            assertThat(AudioGenerationService.isDefiniteUpstreamRejection(
                    new ProviderAdapter.ProviderException(status, "provider_rejected")))
                    .as("status %s is a definite rejection", status)
                    .isTrue();
        }

        // 408 是超时形态：它可能已经被接单了，因此**不是**确定性拒绝。
        assertThat(AudioGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(408, "provider_rejected")))
                .as("408 means the vendor may already have accepted the work")
                .isFalse();

        // 5xx：上游故障，结果未知。
        for (int status : new int[] {500, 502, 503, 504}) {
            assertThat(AudioGenerationService.isDefiniteUpstreamRejection(
                    new ProviderAdapter.ProviderException(status, "provider_error")))
                    .as("status %s leaves the outcome unknown", status)
                    .isFalse();
        }

        // 非 ProviderException（网络超时、连接重置、序列化失败）同样保持预留：
        // 它们都无法证明厂商没有接单。
        assertThat(AudioGenerationService.isDefiniteUpstreamRejection(
                new java.util.concurrent.TimeoutException("connect timed out")))
                .isFalse();
        assertThat(AudioGenerationService.isDefiniteUpstreamRejection(
                new IllegalStateException("audio_transcription_payload_unreadable")))
                .isFalse();
        assertThat(AudioGenerationService.isDefiniteUpstreamRejection(null))
                .isFalse();
    }
}
