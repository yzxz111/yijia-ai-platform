package com.yuzhihuihe.apiplatform.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProviderUsageParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesDeepSeekCacheAndReasoningDetailsWithoutChangingTheOfficialTotal() throws Exception {
        JsonNode body = JSON.readTree("""
                {
                  "id": "req-provider-deepseek",
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 30,
                    "total_tokens": 150,
                    "prompt_cache_hit_tokens": 80,
                    "prompt_cache_miss_tokens": 40,
                    "completion_tokens_details": {"reasoning_tokens": 20}
                  }
                }
                """);

        ProviderAdapter.TokenUsage usage = ProviderUsageParser.parse("DEEPSEEK", body);

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.totalTokens()).isEqualTo(150);
        assertThat(usage.cacheHitTokens()).isEqualTo(80);
        assertThat(usage.cacheMissTokens()).isEqualTo(40);
        assertThat(usage.reasoningTokens()).isEqualTo(20);
        assertThat(usage.providerRequestId()).isEqualTo("req-provider-deepseek");
        assertThat(usage.source()).isEqualTo(ProviderAdapter.UsageSource.PROVIDER_ACTUAL);
        assertThat(usage.rawUsage()).isEqualTo(body.path("usage"));
    }

    @Test
    void parsesBailianOpenAiDetailAliasesAndLeavesMissingValuesNull() throws Exception {
        JsonNode body = JSON.readTree("""
                {
                  "request_id": "req-provider-bailian",
                  "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 25,
                    "total_tokens": 125,
                    "prompt_tokens_details": {
                      "cached_tokens": 60,
                      "cache_write_tokens": 20,
                      "text_tokens": 70,
                      "image_tokens": 30
                    },
                    "completion_tokens_details": {"reasoning_tokens": 10}
                  }
                }
                """);

        ProviderAdapter.TokenUsage usage = ProviderUsageParser.parse("BAILIAN", body);

        assertThat(usage.cacheHitTokens()).isEqualTo(60);
        assertThat(usage.cacheWriteTokens()).isEqualTo(20);
        assertThat(usage.cacheMissTokens()).isNull();
        assertThat(usage.textTokens()).isEqualTo(70);
        assertThat(usage.imageTokens()).isEqualTo(30);
        assertThat(usage.audioTokens()).isNull();
        assertThat(usage.reasoningTokens()).isEqualTo(10);
        assertThat(usage.providerRequestId()).isEqualTo("req-provider-bailian");
    }

    @Test
    void prefersStandardNestedCacheDetailsOverCompatibilityAliases() throws Exception {
        JsonNode body = JSON.readTree("""
                {"usage": {
                  "prompt_tokens": 20,
                  "completion_tokens": 5,
                  "total_tokens": 25,
                  "prompt_cache_hit_tokens": 9,
                  "prompt_tokens_details": {"cached_tokens": 7}
                }}
                """);

        assertThat(ProviderUsageParser.parse("DEEPSEEK", body).cacheHitTokens()).isEqualTo(7);
    }

    @Test
    void returnsNullWhenTheProviderOmitsRequiredOfficialTotals() throws Exception {
        JsonNode body = JSON.readTree("""
                {"usage": {"prompt_tokens": 20, "total_tokens": 25}}
                """);

        assertThat(ProviderUsageParser.parse("DEEPSEEK", body)).isNull();
    }

    @Test
    void rejectsNegativeOrInconsistentDetailedUsage() throws Exception {
        JsonNode negative = JSON.readTree("""
                {"usage": {
                  "prompt_tokens": 20,
                  "completion_tokens": 5,
                  "total_tokens": 25,
                  "prompt_tokens_details": {"cached_tokens": -1}
                }}
                """);
        JsonNode oversizedReasoning = JSON.readTree("""
                {"usage": {
                  "prompt_tokens": 20,
                  "completion_tokens": 5,
                  "total_tokens": 25,
                  "completion_tokens_details": {"reasoning_tokens": 6}
                }}
                """);

        assertThatThrownBy(() -> ProviderUsageParser.parse("DEEPSEEK", negative))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessage("invalid_provider_usage");
        assertThatThrownBy(() -> ProviderUsageParser.parse("DEEPSEEK", oversizedReasoning))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessage("invalid_provider_usage");
    }

    @Test
    void rejectsCacheReadAndWriteTotalsThatExceedInputTokens() throws Exception {
        JsonNode body = JSON.readTree("""
                {"usage": {
                  "prompt_tokens": 100,
                  "completion_tokens": 10,
                  "total_tokens": 110,
                  "prompt_tokens_details": {"cached_tokens": 80, "cache_write_tokens": 30}
                }}
                """);

        assertThatThrownBy(() -> ProviderUsageParser.parse("BAILIAN", body))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessage("invalid_provider_usage");
    }

    @Test
    void rejectsOverflowingCacheCategorySum() throws Exception {
        JsonNode body = JSON.readTree("""
                {"usage": {
                  "prompt_tokens": 9223372036854775807,
                  "completion_tokens": 0,
                  "total_tokens": 9223372036854775807,
                  "prompt_tokens_details": {
                    "cached_tokens": 9223372036854775807,
                    "cache_write_tokens": 9223372036854775807
                  }
                }}
                """);

        assertThatThrownBy(() -> ProviderUsageParser.parse("BAILIAN", body))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessage("invalid_provider_usage");
    }
}
