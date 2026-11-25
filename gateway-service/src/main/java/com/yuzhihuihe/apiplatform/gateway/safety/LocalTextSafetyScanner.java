package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class LocalTextSafetyScanner {

    private static final int MAX_NESTED_DEPTH = 3;

    private static final List<Trigger> TRIGGERS = List.of(
            new Trigger("PROMPT_INJECTION_001", "PROMPT_INJECTION", "HIGH", "ignore previous instructions"),
            new Trigger("PROMPT_INJECTION_002", "PROMPT_INJECTION", "HIGH", "reveal system prompt"),
            new Trigger("PROMPT_INJECTION_003", "PROMPT_INJECTION", "HIGH", "bypass safety"),
            new Trigger("PROMPT_INJECTION_004", "PROMPT_INJECTION", "HIGH", "developer message"),
            new Trigger("SENSITIVE_WORD_001", "SENSITIVE_CONTENT", "HIGH", "恶意软件"));

    private final UnicodeTextNormalizer normalizer;

    public LocalTextSafetyScanner() {
        this(new UnicodeTextNormalizer());
    }

    public LocalTextSafetyScanner(UnicodeTextNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public SafetyScanResult scan(TextSafetyInput input, PolicySnapshot policySnapshot) {
        if (input == null || input.nestedDepth() > MAX_NESTED_DEPTH) {
            return blocked("INPUT_SHAPE_LIMIT", "POLICY", "HIGH", 0, 0);
        }
        List<SafetyFinding> findings = new ArrayList<>();
        for (TextSafetyInput.Field field : input.fields()) {
            NormalizedText normalized = normalizer.normalize(field.value());
            for (Trigger trigger : TRIGGERS) {
                int start = normalized.value().indexOf(trigger.phrase());
                if (start >= 0) {
                    findings.add(new SafetyFinding(trigger.ruleCode(), trigger.category(), trigger.severity(),
                            start, start + trigger.phrase().length()));
                }
            }
        }
        return findings.isEmpty()
                ? new SafetyScanResult(SafetyScanResult.Result.ALLOW, List.of())
                : new SafetyScanResult(SafetyScanResult.Result.BLOCK, findings);
    }

    private SafetyScanResult blocked(String ruleCode, String category, String severity, int start, int end) {
        return new SafetyScanResult(SafetyScanResult.Result.BLOCK,
                List.of(new SafetyFinding(ruleCode, category, severity, start, end)));
    }

    private record Trigger(String ruleCode, String category, String severity, String phrase) {
        private Trigger {
            phrase = new UnicodeTextNormalizer().normalize(phrase).value();
        }
    }
}
