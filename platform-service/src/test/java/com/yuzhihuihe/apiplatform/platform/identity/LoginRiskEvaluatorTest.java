package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginRiskEvaluatorTest {

    private final LoginRiskEvaluator evaluator = new LoginRiskEvaluator();

    @Test
    void knownDeviceAndKnownNetworkAreLowRisk() {
        assertThat(evaluator.evaluate(true, true, "Zhejiang/Hangzhou", "Zhejiang/Hangzhou"))
                .isEqualTo(LoginRiskEvaluator.RiskLevel.LOW);
    }

    @Test
    void eitherNewDeviceOrNewNetworkIsMediumRisk() {
        assertThat(evaluator.evaluate(false, true, "Zhejiang/Hangzhou", "Zhejiang/Hangzhou"))
                .isEqualTo(LoginRiskEvaluator.RiskLevel.MEDIUM);
        assertThat(evaluator.evaluate(true, false, "Zhejiang/Hangzhou", "Zhejiang/Hangzhou"))
                .isEqualTo(LoginRiskEvaluator.RiskLevel.MEDIUM);
    }

    @Test
    void newDeviceAndChangedKnownRegionAreHighRisk() {
        assertThat(evaluator.evaluate(false, true, "Zhejiang/Hangzhou", "Shanghai/Shanghai"))
                .isEqualTo(LoginRiskEvaluator.RiskLevel.HIGH);
    }

    @Test
    void unavailableLocationNeverElevatesDeviceAndNetworkRisk() {
        assertThat(evaluator.evaluate(false, false, "Zhejiang/Hangzhou", IpLocationResolver.UNKNOWN))
                .isEqualTo(LoginRiskEvaluator.RiskLevel.MEDIUM);
        assertThat(evaluator.evaluate(false, false, IpLocationResolver.UNKNOWN, "Shanghai/Shanghai"))
                .isEqualTo(LoginRiskEvaluator.RiskLevel.MEDIUM);
    }
}
