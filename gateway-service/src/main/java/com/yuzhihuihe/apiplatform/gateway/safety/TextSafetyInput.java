package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.List;
import java.util.Objects;

public record TextSafetyInput(List<Field> fields, int nestedDepth) {

    public TextSafetyInput {
        fields = List.copyOf(fields == null ? List.of() : fields);
    }

    public TextSafetyInput(List<Field> fields) {
        this(fields, 0);
    }

    public static TextSafetyInput of(List<Field> fields) {
        return new TextSafetyInput(fields);
    }

    public record Field(String role, String value) {
        public Field {
            role = Objects.requireNonNullElse(role, "unknown");
            value = Objects.requireNonNullElse(value, "");
        }
    }
}
