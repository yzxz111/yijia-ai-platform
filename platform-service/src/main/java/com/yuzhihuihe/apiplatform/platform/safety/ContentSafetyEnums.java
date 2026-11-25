package com.yuzhihuihe.apiplatform.platform.safety;

public final class ContentSafetyEnums {

    private ContentSafetyEnums() {
    }

    public enum PolicyStatus { DRAFT, PUBLISHED, RETIRED }

    public enum RuleType { SENSITIVE_WORD, REGEX, PROMPT_INJECTION, FILE, SEMANTIC }

    public enum SafetyCategory { SENSITIVE_CONTENT, PROMPT_INJECTION, MALWARE, UNSAFE_FILE, POLICY }

    public enum SafetySeverity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum SafetyAction { ALLOW, REVIEW, BLOCK }

    public enum SafetyDirection { INPUT, OUTPUT, FILE }

    public enum SafetyResult { ALLOW, BLOCK, UNAVAILABLE, TIMEOUT }
}
