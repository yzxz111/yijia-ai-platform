package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 只提取可审计的元数据与文本；二进制内容绝不会保留在检查结果中。 */
public final class SafetyInputExtractor {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_FIELDS = 2_048;
    private static final int MAX_FILE_REFERENCES = 256;
    private static final Pattern BASE64_TEXT = Pattern.compile("[A-Za-z0-9+/=_-]+");
    private static final HexFormat HEX = HexFormat.of();

    public SafetyInput extract(JsonNode body) {
        State state = new State();
        walk(body, "request", state, 0);
        return new SafetyInput(state.fields, state.files, state.complete);
    }

    private void walk(JsonNode node, String role, State state, int depth) {
        if (node == null || node.isNull() || !state.complete) {
            return;
        }
        if (depth > MAX_DEPTH) {
            state.complete = false;
            return;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (isBinaryReference(value)) {
                if (state.files.size() >= MAX_FILE_REFERENCES) {
                    state.complete = false;
                } else {
                    state.files.add(sha256(value));
                }
            } else if (!value.isBlank()) {
                if (state.fields.size() >= MAX_FIELDS) {
                    state.complete = false;
                } else {
                    state.fields.add(new TextSafetyInput.Field(role, value));
                }
            }
            return;
        }
        if (node.isArray()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext() && state.complete) {
                walk(children.next(), role, state, depth + 1);
            }
            return;
        }
        if (node.isObject()) {
            String objectRole = messageRole(node, role);
            Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
            while (entries.hasNext() && state.complete) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (entry.getKey().equals("role") || entry.getKey().equals("type")) {
                    continue;
                }
                String childRole = switch (entry.getKey()) {
                    case "content", "input", "text" -> objectRole;
                    default -> entry.getKey();
                };
                walk(entry.getValue(), childRole, state, depth + 1);
            }
        }
    }

    private String messageRole(JsonNode node, String fallback) {
        JsonNode declared = node.get("role");
        return declared != null && declared.isTextual() && !declared.asText().isBlank()
                ? declared.asText().strip()
                : fallback;
    }

    private boolean isBinaryReference(String value) {
        return value.startsWith("data:")
                || value.length() > 4_096 && BASE64_TEXT.matcher(value).matches();
    }

    private String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("content_safety_hash_failed", error);
        }
    }

    private static final class State {
        private final List<TextSafetyInput.Field> fields = new ArrayList<>();
        private final List<String> files = new ArrayList<>();
        private boolean complete = true;
    }
}
