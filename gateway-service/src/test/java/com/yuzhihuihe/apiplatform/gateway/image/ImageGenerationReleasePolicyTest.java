package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.Test;

/**
 * 一次生图失败之后，那张图的预留到底该不该放掉。
 *
 * <p>这条规则比它看起来重要：**放错了会重复收费**。放掉一个结果未知的预留，
 * 客户端拿同一个幂等键重试时就会针对同一套餐再买第二次生成，而第一次的图片
 * 可能已经产出了。反过来，把厂商显式的 4xx 拒绝也扣着不放，就是在客户余额上
 * 撒谎——那张图确定没有被生成。
 *
 * <p>本测试存在的直接原因：超时与连接失败此前不是 {@code ProviderException}，
 * 因此它们既没被翻译、也没被这条规则检验过；把它们翻译成 502/504 之后，
 * 必须钉住它们**仍然**落在「保持预留」那一侧。
 */
class ImageGenerationReleasePolicyTest {

    @Test
    void holdsTheReservationWhenTheUpstreamTimedOut() {
        // 超时意味着「厂商可能已经做完了，只是响应没回来」——这是结果未知，
        // 不是拒绝。放掉预留会让重试再买一次。
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                ProviderAdapter.ProviderException.ofTransport(
                        new TimeoutException("read timeout")))).isFalse();
    }

    @Test
    void holdsTheReservationWhenTheUpstreamWasUnreachable() {
        // 连不上同样无法证明厂商没有接单。
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                ProviderAdapter.ProviderException.ofTransport(
                        new IOException("connection refused")))).isFalse();
    }

    @Test
    void holdsTheReservationOnGatewayTimeoutsAndUnavailableUpstreams() {
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(504, "provider_attempt_timeout"))).isFalse();
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(503, "provider_unavailable"))).isFalse();
        // 408 是超时形态，不能当成拒绝。
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(408, "provider_request_timeout"))).isFalse();
        // 协议失败（2xx 但无法解释）表示为 502：厂商的接受状态无从得知。
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(502, "provider_invalid_response")))
                .isFalse();
    }

    @Test
    void releasesTheReservationOnlyOnAnExplicitProviderRejection() {
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(400, "provider_rejected_generation")))
                .isTrue();
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(422, "provider_rejected_generation")))
                .isTrue();
        // 非上游异常（网关自己的 bug）不该被当成可释放的拒绝。
        assertThat(ImageGenerationService.isDefiniteUpstreamRejection(
                new IllegalStateException("bug"))).isFalse();
    }
}
