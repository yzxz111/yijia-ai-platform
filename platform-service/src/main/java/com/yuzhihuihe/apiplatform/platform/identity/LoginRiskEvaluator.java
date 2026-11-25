package com.yuzhihuihe.apiplatform.platform.identity;

import org.springframework.stereotype.Component;

@Component
public class LoginRiskEvaluator {

    public RiskLevel evaluate(
            boolean knownDevice,
            boolean knownNetwork,
            String previousRegion,
            String currentRegion) {
        if (!knownDevice && knownRegion(previousRegion) && knownRegion(currentRegion)
                && !previousRegion.equalsIgnoreCase(currentRegion)) {
            return RiskLevel.HIGH;
        }
        if (!knownDevice || !knownNetwork) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private boolean knownRegion(String region) {
        return region != null && !region.isBlank() && !IpLocationResolver.UNKNOWN.equalsIgnoreCase(region);
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }
}
