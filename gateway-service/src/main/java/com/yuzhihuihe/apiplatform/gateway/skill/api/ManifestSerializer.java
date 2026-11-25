package com.yuzhihuihe.apiplatform.gateway.skill.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * Writes the skill package manifest exactly as the contract requires: the parsed {@code plugin.json}
 * document when the package carried one, and an explicit JSON {@code null} when it did not (V4).
 *
 * <p>The gateway's configured {@code ObjectMapper} suppresses null-ish and empty values while writing,
 * which silently removed a {@code NullNode} manifest from the admin detail response. An explicit
 * {@code @JsonSerialize(using = …)} on the component bypasses that inclusion rule entirely, so the
 * key is always present on {@code A3}/{@code A14} whether or not the package had a manifest.
 */
final class ManifestSerializer extends JsonSerializer<JsonNode> {

    static final ManifestSerializer INSTANCE = new ManifestSerializer();

    @Override
    public void serialize(JsonNode value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {
        if (value == null || value.isNull() || value.isMissingNode()) {
            generator.writeNull();
            return;
        }
        generator.writeTree(value);
    }
}

