package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.Test;

class VideoSubmitReleasePolicyTest {

    @Test
    void releasesOnlyForAnExplicitUpstreamFourHundredResponse() {
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(400, "provider_rejected_generation")))
                .isTrue();
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(429, "provider_rate_limited")))
                .isTrue();
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(499, "provider_client_closed_request")))
                .isTrue();
    }

    @Test
    void keepsTheReservationForUnknownOrNonProviderFailures() {
        // 没有 task id 的 2xx 响应被映射为协议 502，因此它
        // 也被有意纳入未知结果分支的覆盖范围。
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(502, "provider_invalid_submission")))
                .isFalse();
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(503, "provider_unavailable")))
                .isFalse();
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(504, "provider_attempt_timeout")))
                .isFalse();
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new ProviderAdapter.ProviderException(408, "provider_request_timeout")))
                .isFalse();
        assertThat(VideoGenerationService.isDefiniteUpstreamRejection(
                new IllegalStateException("connection reset")))
                .isFalse();
    }
}
