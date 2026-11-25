package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

class OpenAiErrorMapperTest {

    @ParameterizedTest
    @CsvSource({
            "wallet_insufficient_balance, 422, quota_error",
            "wallet_usage_idempotency_mismatch, 409, invalid_request_error",
            "wallet_billing_request_invalid, 503, server_error",
            "wallet_billing_unavailable, 503, server_error"
    })
    void exposesStableOpenAiCompatibleWalletErrors(
            String code, int expectedStatus, String expectedType) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.valueOf(expectedStatus), code));

        assertThat(descriptor.status().value()).isEqualTo(expectedStatus);
        assertThat(descriptor.code()).isEqualTo(code);
        assertThat(descriptor.type()).isEqualTo(expectedType);
    }

    /**
     * 客户购买的套餐上限与每分钟限流以同一种封装上报，但带有各自的稳定错误码，
     * 这样客户端就能把「你请求得太快了」与「你今天的额度已用尽」区分开。
     */
    @ParameterizedTest
    @CsvSource({
            "daily_request_limit_exceeded, 429, rate_limit_error"
    })
    void exposesTheSoldDailyRequestCapAsAStableRateLimitError(
            String code, int expectedStatus, String expectedType) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.valueOf(expectedStatus), code));

        assertThat(descriptor.status().value()).isEqualTo(expectedStatus);
        assertThat(descriptor.code()).isEqualTo(code);
        assertThat(descriptor.type()).isEqualTo(expectedType);
    }

    /**
     * 视频生成与 {@code /v1} 的其余部分以同一种封装作答。这里把这些错误码固定下来，
     * 是因为按 {@code error.code} 分支的客户端在开始提交视频任务时，
     * 不必再学一套新的词汇。
     */
    @ParameterizedTest
    @CsvSource({
            "invalid_video_request, 422, invalid_request_error",
            "invalid_video_request_id, 422, invalid_request_error",
            "video_task_not_found, 404, invalid_request_error",
            "video_output_not_ready, 409, invalid_request_error",
            "video_request_id_conflict, 409, invalid_request_error",
            "video_requires_second_denominated_package, 422, quota_error",
            "video_channel_unavailable, 503, server_error"
    })
    void exposesStableOpenAiCompatibleVideoErrors(
            String code, int expectedStatus, String expectedType) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.valueOf(expectedStatus), code));

        assertThat(descriptor.status().value()).isEqualTo(expectedStatus);
        assertThat(descriptor.code()).isEqualTo(code);
        assertThat(descriptor.type()).isEqualTo(expectedType);
    }

    /**
     * 上游故障必须区分「超时」与「拒绝」。
     *
     * <p>两者此前都被压成 502 {@code provider_error}，客户端无从选择：对一次超时，
     * 用**同一个幂等键**重试是对的；对一次明确拒绝，重试只会再失败一次。
     */
    @ParameterizedTest
    @CsvSource({
            "504, 504, provider_timeout",
            "502, 502, provider_error",
            "503, 502, provider_error",
            "400, 502, provider_error",
            "429, 429, provider_error"
    })
    void distinguishesAnUpstreamTimeoutFromAnUpstreamRejection(
            int providerStatus, int expectedStatus, String expectedCode) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ProviderAdapter.ProviderException(providerStatus, "upstream"));

        assertThat(descriptor.status().value()).isEqualTo(expectedStatus);
        assertThat(descriptor.code()).isEqualTo(expectedCode);
        assertThat(descriptor.type())
                .isEqualTo(expectedStatus == 429 ? "rate_limit_error" : "server_error");
    }

    /**
     * 只有真正的传输层失败才该被翻译成上游故障。
     *
     * <p>范围故意收窄：适配器自己抛出的协议失败已经在各自的调用点分好类，
     * 而一个 {@code IllegalArgumentException} 是网关自己的编程错误——
     * 把它说成「上游故障」等于替自己的 bug 找借口。
     */
    @Test
    void classifiesOnlyTransportAndTimeoutFailures() {
        assertThat(ProviderAdapter.ProviderException.isTransportFailure(
                new TimeoutException("read timeout"))).isTrue();
        assertThat(ProviderAdapter.ProviderException.isTransportFailure(
                new SocketTimeoutException("connect timeout"))).isTrue();
        assertThat(ProviderAdapter.ProviderException.isTransportFailure(
                new IOException("connection reset"))).isTrue();

        assertThat(ProviderAdapter.ProviderException.isTransportFailure(
                new ProviderAdapter.ProviderException(502, "provider_invalid_response")))
                .isFalse();
        assertThat(ProviderAdapter.ProviderException.isTransportFailure(
                new IllegalArgumentException("programming error"))).isFalse();
        assertThat(ProviderAdapter.ProviderException.isTransportFailure(null)).isFalse();
    }

    @Test
    void translatesATimeoutInto504AndAConnectFailureInto502() {
        ProviderAdapter.ProviderException timedOut =
                ProviderAdapter.ProviderException.ofTransport(new TimeoutException("read timeout"));
        assertThat(timedOut.status()).isEqualTo(504);
        assertThat(timedOut.getMessage()).isEqualTo("provider_attempt_timeout");

        ProviderAdapter.ProviderException unreachable = ProviderAdapter.ProviderException
                .ofTransport(new IOException("connection refused"));
        assertThat(unreachable.status()).isEqualTo(502);
        assertThat(unreachable.getMessage()).isEqualTo("provider_unreachable");
    }

    /** 多参考图上限（8 张）是网关侧的稳定边界，必须有它自己的错误码。 */
    @Test
    void mapsTheMultiReferenceInputCapToItsOwnCode() {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "image_input_count_exceeded"));

        assertThat(descriptor.status().value()).isEqualTo(422);
        assertThat(descriptor.code()).isEqualTo("image_input_count_exceeded");
        assertThat(descriptor.type()).isEqualTo("invalid_request_error");
    }

    /**
     * 「本地上传参考图」这条路被关掉时，客户端必须看到**它自己的**错误码。
     *
     * <p>这条测试是有来历的：{@code image_file_input_disabled} 是 501，而 501 属于
     * 5xx。在把它加进显式映射之前，它会落进 5xx 兜底，被说成
     * {@code upstream_unavailable}「没有可用渠道」——客户端于是会去重试一个
     * **根本没开**的功能，重试一万次也不会变。
     */
    @Test
    void mapsADisabledImageFileInputToItsOwnCodeRatherThanAMisleadingFallback() {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_IMPLEMENTED,
                        "image_file_input_disabled"));

        assertThat(descriptor.status().value()).isEqualTo(501);
        assertThat(descriptor.code()).isEqualTo("image_file_input_disabled");
        assertThat(descriptor.code()).isNotEqualTo("upstream_unavailable");
    }

    /**
     * 上游对生成**内容**的审核拒绝必须是它自己的错误码。
     *
     * <p>它此前与其它上游拒绝一起被压成 502 {@code provider_error}，客户端只能
     * 笼统提示「失败」。现在客户端可以分支到
     * {@code image_content_policy_violation}，提示用户「内容审核未通过，请修改描述」。
     * 2026-10-10 线上首个实锤：{@code OutputImageSensitiveContentDetected.PolicyViolation}。
     */
    @Test
    void mapsAContentPolicyRejectionToItsOwnCode() {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ProviderAdapter.ProviderException(
                        400, "provider_rejected_generation:"
                                + "OutputImageSensitiveContentDetected.PolicyViolation",
                        null, null, true));

        assertThat(descriptor.status().value()).isEqualTo(422);
        assertThat(descriptor.code()).isEqualTo("image_content_policy_violation");
        assertThat(descriptor.type()).isEqualTo("invalid_request_error");
    }

    /** 只有带标记的内容审核拒绝才走新码；普通的上游 4xx 拒绝维持 502 兜底不变。 */
    @Test
    void aPlainUpstreamRejectionStillFallsBackTo502() {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ProviderAdapter.ProviderException(400, "provider_rejected_generation:InvalidApiKey"));

        assertThat(descriptor.status().value()).isEqualTo(502);
        assertThat(descriptor.code()).isEqualTo("provider_error");
    }

    /** 参照物：5xx 兜底对**其它**未识别的服务端错误仍然是「没有可用渠道」。 */
    @Test
    void stillUsesTheUpstreamUnavailableFallbackForOtherServerErrors() {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "something_else"));

        assertThat(descriptor.status().value()).isEqualTo(503);
        assertThat(descriptor.code()).isEqualTo("upstream_unavailable");
    }
}
