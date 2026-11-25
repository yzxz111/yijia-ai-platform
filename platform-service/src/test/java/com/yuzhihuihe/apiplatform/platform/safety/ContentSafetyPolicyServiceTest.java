package com.yuzhihuihe.apiplatform.platform.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContentSafetyPolicyServiceTest {

    @Test
    void computesStableConfigHashIndependentOfMapIterationOrder() {
        String first = ContentSafetyPolicyService.configSha256(Map.of(
                "enabled", true,
                "rules", Map.of("prompt_injection", "REVIEW", "malware", "BLOCK")));
        String second = ContentSafetyPolicyService.configSha256(Map.of(
                "rules", Map.of("malware", "BLOCK", "prompt_injection", "REVIEW"),
                "enabled", true));

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(second);
    }

    @Test
    void materializesLegacyConfigRulesForGatewaySnapshots() {
        List<ContentSafetyPolicyService.RuleView> rules = ContentSafetyPolicyService.rulesFromConfig(Map.of(
                "rules", Map.of(
                        "prompt_injection", "REVIEW",
                        "malware", "BLOCK")));

        assertThat(rules).containsExactly(
                new ContentSafetyPolicyService.RuleView(
                        "malware", "FILE", "MALWARE", "HIGH", "BLOCK"),
                new ContentSafetyPolicyService.RuleView(
                        "prompt_injection", "PROMPT_INJECTION", "PROMPT_INJECTION", "HIGH", "REVIEW"));
    }
}
