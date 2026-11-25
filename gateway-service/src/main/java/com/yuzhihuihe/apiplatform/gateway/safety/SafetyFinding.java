package com.yuzhihuihe.apiplatform.gateway.safety;

public record SafetyFinding(String ruleCode, String category, String severity, int start, int end) {
}
