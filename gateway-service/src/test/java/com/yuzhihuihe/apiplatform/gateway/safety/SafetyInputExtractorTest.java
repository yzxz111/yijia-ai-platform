package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafetyInputExtractorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SafetyInputExtractor extractor = new SafetyInputExtractor();

    @Test
    void preservesMessageRolesWithoutTreatingRoleNamesAsContent() throws Exception {
        SafetyInput input = extractor.extract(JSON.readTree("""
                {"messages":[
                  {"role":"user","content":"customer text"},
                  {"role":"assistant","content":"assistant text"}
                ]}
                """));

        assertThat(input.complete()).isTrue();
        assertThat(input.fields()).containsExactly(
                new TextSafetyInput.Field("user", "customer text"),
                new TextSafetyInput.Field("assistant", "assistant text"));
    }

    @Test
    void fingerprintsInlineBinaryReferencesWithSha256() {
        String dataUri = "data:image/png;base64,AAAA";
        ObjectNode body = JSON.createObjectNode().put("image", dataUri);

        SafetyInput input = extractor.extract(body);

        assertThat(input.fields()).isEmpty();
        assertThat(input.fileReferences()).singleElement()
                .asString().matches("[0-9a-f]{64}")
                .isNotEqualTo(Integer.toHexString(dataUri.hashCode()));
    }

    @Test
    void marksOverlyDeepPayloadsIncompleteInsteadOfSilentlyIgnoringThem() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode current = root;
        for (int depth = 0; depth < 10; depth++) {
            current = current.putObject("nested");
        }
        current.put("content", "hidden text");

        SafetyInput input = extractor.extract(root);

        assertThat(input.complete()).isFalse();
        assertThat(input.fields()).isEqualTo(List.of());
    }
}
