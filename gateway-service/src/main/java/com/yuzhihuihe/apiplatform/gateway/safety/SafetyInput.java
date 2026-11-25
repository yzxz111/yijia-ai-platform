package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.List;

public record SafetyInput(
        List<TextSafetyInput.Field> fields,
        List<String> fileReferences,
        boolean complete) {

    public SafetyInput(List<TextSafetyInput.Field> fields, List<String> fileReferences) {
        this(fields, fileReferences, true);
    }

    public SafetyInput {
        fields = List.copyOf(fields == null ? List.of() : fields);
        fileReferences = List.copyOf(fileReferences == null ? List.of() : fileReferences);
    }
}
