package com.yuzhihuihe.apiplatform.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;

final class ProviderUsageParser {

    private ProviderUsageParser() {
    }

    static ProviderAdapter.TokenUsage parse(String providerType, JsonNode body) {
        JsonNode usage = body == null ? null : body.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        if (!usage.isObject()) {
            throw invalidUsage();
        }

        Long input = requiredTotal(usage, "prompt_tokens", "input_tokens");
        Long output = requiredTotal(usage, "completion_tokens", "output_tokens");
        Long total = requiredTotal(usage, "total_tokens");
        if (input == null || output == null || total == null) {
            return null;
        }

        JsonNode inputDetails = firstObject(usage, "prompt_tokens_details", "input_tokens_details");
        JsonNode outputDetails = firstObject(usage, "completion_tokens_details", "output_tokens_details");
        Long cacheHit = firstOptional(
                inputDetails, new String[]{"cached_tokens", "cache_read_tokens"},
                usage, new String[]{"prompt_cache_hit_tokens", "cache_read_input_tokens", "cached_tokens"});
        Long cacheMiss = firstOptional(
                inputDetails, new String[]{"cache_miss_tokens"},
                usage, new String[]{"prompt_cache_miss_tokens"});
        Long cacheWrite = firstOptional(
                inputDetails, new String[]{"cache_write_tokens", "cached_creation_tokens", "cache_creation_tokens"},
                usage, new String[]{"cache_creation_input_tokens"});
        Long reasoning = firstOptional(
                outputDetails, new String[]{"reasoning_tokens"},
                usage, new String[]{"reasoning_tokens"});
        Long text = optional(inputDetails, "text_tokens");
        Long image = optional(inputDetails, "image_tokens");
        Long audio = optional(inputDetails, "audio_tokens");

        try {
            return new ProviderAdapter.TokenUsage(
                    input, output, total, cacheHit, cacheMiss, cacheWrite, reasoning,
                    text, image, audio, ProviderAdapter.UsageSource.PROVIDER_ACTUAL,
                    providerRequestId(body), usage);
        } catch (IllegalArgumentException invalid) {
            throw invalidUsage();
        }
    }

    private static Long requiredTotal(JsonNode usage, String... names) {
        for (String name : names) {
            JsonNode value = usage.get(name);
            if (value != null && !value.isNull()) {
                return number(value);
            }
        }
        return null;
    }

    private static JsonNode firstObject(JsonNode parent, String... names) {
        for (String name : names) {
            JsonNode value = parent.get(name);
            if (value != null && !value.isNull()) {
                if (!value.isObject()) {
                    throw invalidUsage();
                }
                return value;
            }
        }
        return null;
    }

    private static Long firstOptional(
            JsonNode primary, String[] primaryNames, JsonNode fallback, String[] fallbackNames) {
        Long value = optional(primary, primaryNames);
        return value != null ? value : optional(fallback, fallbackNames);
    }

    private static Long optional(JsonNode parent, String... names) {
        if (parent == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = parent.get(name);
            if (value != null && !value.isNull()) {
                return number(value);
            }
        }
        return null;
    }

    private static long number(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidUsage();
        }
        return value.longValue();
    }

    private static String providerRequestId(JsonNode body) {
        for (String field : new String[]{"request_id", "id"}) {
            JsonNode value = body.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static ProviderAdapter.ProviderException invalidUsage() {
        return new ProviderAdapter.ProviderException(502, "invalid_provider_usage");
    }
}
