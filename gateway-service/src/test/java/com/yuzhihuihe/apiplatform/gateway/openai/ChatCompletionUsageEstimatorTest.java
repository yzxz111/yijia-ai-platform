package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.TokenEstimator;
import org.junit.jupiter.api.Test;

class ChatCompletionUsageEstimatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatCompletionUsageEstimator estimator =
            new ChatCompletionUsageEstimator(new TokenEstimator(3, 1.25), JSON);

    @Test
    void normalizesPreferredOutputFieldWithoutMutatingRequest() {
        ObjectNode request = request();
        request.put("max_tokens", 300);
        request.put("max_completion_tokens", 500);

        JsonNode normalized = estimator.normalizeOutputLimit(request, 400);

        assertThat(normalized.path("max_completion_tokens").asLong()).isEqualTo(400);
        assertThat(normalized.has("max_tokens")).isFalse();
        assertThat(request.path("max_tokens").asLong()).isEqualTo(300);
        assertThat(request.path("max_completion_tokens").asLong()).isEqualTo(500);
    }

    @Test
    void usesBoundedDefaultOutputWhenTheClientOmitsALimit() {
        ChatCompletionUsageEstimator.Estimates estimates = estimator.estimates(request(), 10_000);

        assertThat(estimates.inputEstimate()).isEqualTo(1);
        assertThat(estimates.reservationEstimate()).isEqualTo(8_193);
    }

    @Test
    void readsTheCompletionCountFromNWithOneAsTheDefault() {
        ObjectNode request = request();
        request.put("max_tokens", 100);

        assertThat(estimator.requestedCompletions(request)).isEqualTo(1);
        request.put("n", 1);
        assertThat(estimator.requestedCompletions(request)).isEqualTo(1);
        request.put("n", ProviderAdapter.MAX_COMPLETIONS);
        assertThat(estimator.requestedCompletions(request)).isEqualTo(ProviderAdapter.MAX_COMPLETIONS);
        request.putNull("n");
        assertThat(estimator.requestedCompletions(request)).isEqualTo(1);
    }

    @Test
    void refusesCompletionCountsOutsideTheSupportedBoundInsteadOfReservingLess() {
        ObjectNode request = request();

        request.put("n", ProviderAdapter.MAX_COMPLETIONS + 1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.requestedCompletions(request))
                .withMessage("invalid_n");
        request.put("n", 0);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.requestedCompletions(request))
                .withMessage("invalid_n");
        request.put("n", -1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.requestedCompletions(request))
                .withMessage("invalid_n");
        request.put("n", 1.5);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> estimator.requestedCompletions(request))
                .withMessage("invalid_n");
    }

    @Test
    void oneCompletionReservesExactlyWhatItReservedBeforeNWasAccountedFor() {
        ObjectNode request = request();
        request.put("max_tokens", 64);

        ChatCompletionUsageEstimator.Estimates absent = estimator.estimates(request, 8_192);
        ChatCompletionUsageEstimator.Estimates explicit = estimator.estimates(request, 8_192, 1);

        assertThat(explicit).isEqualTo(absent);
        assertThat(absent.inputEstimate()).isEqualTo(1);
        assertThat(absent.reservationEstimate()).isEqualTo(65);
    }

    @Test
    void aThousandCompletionsReserveAThousandTimesTheOutputOfOne() {
        // 被报告的漏洞，就发生在管钱的这一层：n=1000 过去预留的 token 与 n=1 一样都是
        // 65 537。这里的上限取系统上限，因此提示词——它由供应商发送并只计费一次，
        // 所以绝不能被乘以 n——相对于输出只是舍入误差，从而使整个预留量也约为 1000 倍。
        ObjectNode request = request();
        request.put("max_tokens", 65_536);

        ChatCompletionUsageEstimator.Estimates one = estimator.estimates(request, 65_536);
        ChatCompletionUsageEstimator.Estimates thousand = estimator.estimates(request, 65_536, 1_000);

        assertThat(one.reservationEstimate() - one.inputEstimate()).isEqualTo(65_536);
        assertThat(thousand.inputEstimate()).isEqualTo(one.inputEstimate());
        assertThat(thousand.reservationEstimate() - thousand.inputEstimate())
                .isEqualTo(1_000L * (one.reservationEstimate() - one.inputEstimate()));
        assertThat(thousand.reservationEstimate())
                .isStrictlyBetween(990L * one.reservationEstimate(), 1_010L * one.reservationEstimate());
    }

    @Test
    void imageEstimationDoesNotChargeRawUrlOrBase64Length() {
        ObjectNode shortImage = imageRequest("https://example.test/image.png");
        ObjectNode largeImage = imageRequest("data:image/png;base64," + "A".repeat(20_000));
        ObjectNode textOnly = request();
        textOnly.put("max_tokens", 0);
        textOnly.withArray("messages")
                .addObject()
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "describe");

        long shortEstimate = estimator.estimates(shortImage, 8_192).reservationEstimate();
        long largeEstimate = estimator.estimates(largeImage, 8_192).reservationEstimate();
        long textEstimate = estimator.estimates(textOnly, 8_192).reservationEstimate();

        assertThat(shortEstimate).isEqualTo(largeEstimate);
        assertThat(shortEstimate).isGreaterThan(textEstimate + 1_000);
        assertThat(largeImage.toString()).contains("A".repeat(1_000));
    }

    @Test
    void completionEstimateCountsContentReasoningAndToolArgumentsOnly() {
        ObjectNode response = JSON.createObjectNode();
        ObjectNode message = response.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("message");
        message.put("role", "assistant");
        message.put("content", "abc");
        message.put("reasoning_content", "def");
        message.putArray("tool_calls")
                .addObject()
                .put("id", "ignored-id")
                .put("type", "function")
                .putObject("function")
                .put("arguments", "ghi");

        ProviderAdapter.TokenUsage usage =
                estimator.estimatedCompletionUsage(12, 100, response);

        assertThat(usage).isEqualTo(new ProviderAdapter.TokenUsage(12, 3, 15));
    }

    @Test
    void streamEstimateSaturatesAtCapacityAndResetsBetweenAttempts() {
        ChatCompletionUsageEstimator.StreamUsageEstimate stream =
                estimator.streamUsageEstimate(12, 14);
        ObjectNode chunk = JSON.createObjectNode();
        ObjectNode delta = chunk.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta");
        delta.put("reasoning_content", "abc");
        delta.putArray("tool_calls")
                .addObject()
                .put("index", 0)
                .putObject("function")
                .put("arguments", "def");

        stream.received(chunk);

        assertThat(stream.usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 2, 14));

        stream.reset();

        assertThat(stream.usage()).isEqualTo(new ProviderAdapter.TokenUsage(12, 0, 12));
    }

    private static ObjectNode request() {
        ObjectNode request = JSON.createObjectNode();
        request.putArray("messages");
        return request;
    }

    private static ObjectNode imageRequest(String url) {
        ObjectNode request = request();
        request.put("max_tokens", 0);
        request.withArray("messages")
                .addObject()
                .putArray("content")
                .addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", url);
        return request;
    }
}
