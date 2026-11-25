package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.List;

public record SafetyScanResult(Result result, List<SafetyFinding> findings) {

    public SafetyScanResult {
        findings = List.copyOf(findings == null ? List.of() : findings);
    }

    public enum Result { ALLOW, BLOCK, REVIEW }
}
