package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelModelTestService.ProbeResult;
import com.yuzhihuihe.apiplatform.gateway.routing.GatewayAdminRepository.ChannelModelChoice;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 测试结论的翻译规则，不需要容器也不需要网络。
 *
 * <p>这些规则决定了渠道健康态，而健康态决定了分流是否会继续用这个渠道：把 401 记成
 * 暂时的会让每一次真实请求都先撞一次拒绝，把 429 记成永久故障又会让一次上游抖动
 * 把整条渠道摘掉。因此逐条钉住。
 */
class ChannelModelTestClassificationTest {

    private static ChannelModelChoice choice(
            String modelCode, String upstreamModel, boolean mappingEnabled, boolean modelEnabled,
            boolean textCapable) {
        return new ChannelModelChoice(
                modelCode, "Display " + modelCode, upstreamModel, mappingEnabled, modelEnabled,
                textCapable);
    }

    @Test
    void mapsUpstreamStatusCodesToActionableReasons() {
        assertThat(ChannelModelTestService.reasonForStatus(401)).isEqualTo("credential_rejected");
        assertThat(ChannelModelTestService.reasonForStatus(403)).isEqualTo("credential_rejected");
        assertThat(ChannelModelTestService.reasonForStatus(402))
                .isEqualTo("upstream_insufficient_balance");
        assertThat(ChannelModelTestService.reasonForStatus(404))
                .isEqualTo("model_not_found_on_provider");
        assertThat(ChannelModelTestService.reasonForStatus(429)).isEqualTo("upstream_overloaded");
        assertThat(ChannelModelTestService.reasonForStatus(500))
                .isEqualTo("upstream_server_error");
        assertThat(ChannelModelTestService.reasonForStatus(400)).isEqualTo("upstream_rejected");
    }

    @Test
    void keepsAChannelRoutableForTemporaryUpstreamFailures() {
        // 上游忙、上游 5xx、超时：渠道仍然可用，只是现在不稳。
        assertThat(ChannelModelTestService.statusFor(failed(429)))
                .isEqualTo("DEGRADED");
        assertThat(ChannelModelTestService.statusFor(failed(503)))
                .isEqualTo("DEGRADED");
        assertThat(ChannelModelTestService.statusFor(timeout()))
                .isEqualTo("DEGRADED");
    }

    @Test
    void stopsRoutingToAChannelThatCannotWorkAtAll() {
        // 配置本身错了：重试多少次都一样，留在健康态只会让每次请求都先撞一次失败。
        assertThat(ChannelModelTestService.statusFor(failed(401)))
                .isEqualTo("UNHEALTHY");
        assertThat(ChannelModelTestService.statusFor(failed(404)))
                .isEqualTo("UNHEALTHY");
        assertThat(ChannelModelTestService.statusFor(unreachable()))
                .isEqualTo("UNHEALTHY");
        assertThat(ChannelModelTestService.statusFor(bodyError()))
                .isEqualTo("UNHEALTHY");
        // 「地址打错」同样是配置错，不是上游抖动：它必须落到 UNHEALTHY。
        assertThat(ChannelModelTestService.statusFor(
                        failed(404, "upstream_endpoint_not_found")))
                .isEqualTo("UNHEALTHY");
    }

    @Test
    void splitsA404IntoAMissingModelAndAnUnreachableEndpoint() {
        // 上游原文里提到模型：这才是「模型名不对」。
        assertThat(ChannelModelTestService.reasonFor(404, envelope(
                        "model_not_found", "The model 'qwen-x' does not exist")))
                .isEqualTo("model_not_found_on_provider");
        // 上游也常常用中文说同一件事。
        assertThat(ChannelModelTestService.reasonFor(404, envelope(null, "模型不存在")))
                .isEqualTo("model_not_found_on_provider");
        // 上游什么都没说（HTML 404 页、空体、非 JSON）：那是地址/版本段，别赖模型名。
        assertThat(ChannelModelTestService.reasonFor(
                        404, new ProviderAdapter.ProviderException(404, "provider_http_404")))
                .isEqualTo("upstream_endpoint_not_found");
        // 说了话但没提模型：同样是地址问题。
        assertThat(ChannelModelTestService.reasonFor(404, envelope("NotFound", "url path not found")))
                .isEqualTo("upstream_endpoint_not_found");
        // 404 以外一律沿用状态码翻译，不受这条规则影响。
        assertThat(ChannelModelTestService.reasonFor(
                        401, new ProviderAdapter.ProviderException(401, "provider_http_401")))
                .isEqualTo("credential_rejected");
        assertThat(ChannelModelTestService.reasonFor(429, envelope(null, "model is busy")))
                .isEqualTo("upstream_overloaded");
        // 连不上时没有状态码：仍然是「连不上」，不是 404。
        assertThat(ChannelModelTestService.reasonFor(null, new IllegalStateException("boom")))
                .isEqualTo("upstream_unreachable");
    }

    @Test
    void surfacesTheUpstreamMessageInsteadOfTheInternalStatusString() {
        // 有信封：控制台看到的是上游那句话。
        assertThat(ProbeResult.upstreamText(envelope("model_not_found", "model not found")))
                .isEqualTo("model not found");
        // 没信封：只剩网关自己的状态码字符串，因为原始响应体刻意不保留。
        assertThat(ProbeResult.upstreamText(
                        new ProviderAdapter.ProviderException(404, "provider_http_404")))
                .isEqualTo("provider_http_404");
        assertThat(ProbeResult.upstreamText(new IllegalStateException("boom"))).isEqualTo("boom");
    }

    @Test
    void marksASuccessfulRealCallHealthy() {
        assertThat(ChannelModelTestService.statusFor(success())).isEqualTo("HEALTHY");
    }

    @Test
    void readsTheUpstreamStatusOutOfTheAdapterException() {
        assertThat(ChannelModelTestService.httpStatusOf(
                new ProviderAdapter.ProviderException(401, "provider_http_401")))
                .isEqualTo(401);
        assertThat(ChannelModelTestService.httpStatusOf(
                new IllegalStateException("wrapped provider_http_429 upstream")))
                .isEqualTo(429);
        assertThat(ChannelModelTestService.httpStatusOf(new IllegalStateException("boom")))
                .isNull();
        assertThat(ChannelModelTestService.httpStatusOf(null)).isNull();
    }

    @Test
    void picksTheRequestedUpstreamModelOrTheFirstUsableMapping() {
        List<ChannelModelChoice> mappings = List.of(
                choice("video-only", "wanx-video", true, true, false),
                choice("disabled", "qwen-disabled", false, true, true),
                choice("text", "qwen-turbo", true, true, true));

        // 指定上游模型名：命中哪条就测哪条，哪怕它不是「第一条可用的」。
        assertThat(ChannelModelTestService.select(mappings, "qwen-disabled")
                        .map(ChannelModelChoice::modelCode))
                .contains("disabled");
        // 没指定：跳过没有文本能力的、以及被停用的映射。
        assertThat(ChannelModelTestService.select(mappings, null)
                        .map(ChannelModelChoice::upstreamModel))
                .contains("qwen-turbo");
        // 指定的模型名不在这个渠道上：交给调用方回 model_mapping_missing，而不是悄悄改测别的。
        assertThat(ChannelModelTestService.select(mappings, "never-mapped")).isEmpty();
        assertThat(ChannelModelTestService.select(List.of(), null)).isEmpty();
    }

    @Test
    void treatsAnErrorObjectInsideA200BodyAsAFailure() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 一个正常的 OpenAI 兼容应答：没有 error 对象，choices 非空 → 通过。
        assertThat((Object) ProbeResult.upstreamError(mapper.readTree(
                "{\"choices\":[{\"index\":0,\"message\":{\"content\":\"pong\"},"
                        + "\"finish_reason\":\"stop\"}]}")))
                .isNull();
        // 上游用 200 回错误对象是常见形状（余额不足、模型未开通），只看状态码会记成通过。
        assertThat(ProbeResult.upstreamError(mapper.readTree(
                "{\"error\":{\"message\":\"insufficient quota\"}}")))
                .isEqualTo("insufficient quota");
        // choices 是空数组：上游收下了请求却没产出，同样不能算通过。
        assertThat(ProbeResult.upstreamError(mapper.readTree("{\"choices\":[]}")))
                .isEqualTo("invalid_upstream_response");
        assertThat(ProbeResult.upstreamError(mapper.createObjectNode()))
                .isEqualTo("invalid_upstream_response");
        assertThat(ProbeResult.upstreamError(null)).isEqualTo("empty_provider_response");
    }

    private static ProbeResult success() {
        return new ProbeResult(
                true, true, null, 200, 12L, 7L, 1L, "text", "qwen-turbo", null, List.of());
    }

    private static ProbeResult failed(int httpStatus) {
        return failed(httpStatus, ChannelModelTestService.reasonForStatus(httpStatus));
    }

    private static ProbeResult failed(int httpStatus, String reason) {
        return new ProbeResult(
                true, false, reason, httpStatus,
                12L, null, null, "text", "qwen-turbo", "provider_http_" + httpStatus, List.of());
    }

    /** 一个带结构化信封的适配器异常：{@code upstreamCode}/{@code upstreamMessage} 与线上同形。 */
    private static ProviderAdapter.ProviderException envelope(String code, String message) {
        String summary = "provider_http_404"
                + (code == null ? "" : ": " + code)
                + (message == null ? "" : ": " + message);
        return new ProviderAdapter.ProviderException(404, summary, code, message);
    }

    private static ProbeResult timeout() {
        return new ProbeResult(
                true, false, "timeout", null, 30_000L, null, null, "text", "qwen-turbo",
                "provider_attempt_timeout", List.of());
    }

    private static ProbeResult unreachable() {
        return new ProbeResult(
                false, false, "upstream_unreachable", null, null, null, null, "text",
                "qwen-turbo", null, List.of());
    }

    private static ProbeResult bodyError() {
        return new ProbeResult(
                true, false, "upstream_error_in_body", 200, 12L, null, null, "text",
                "qwen-turbo", "insufficient quota", List.of());
    }
}
