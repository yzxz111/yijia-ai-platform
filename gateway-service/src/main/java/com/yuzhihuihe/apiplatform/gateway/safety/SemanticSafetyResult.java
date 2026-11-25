package com.yuzhihuihe.apiplatform.gateway.safety;

public record SemanticSafetyResult(
        Status status, String category, String severity, String providerCode, double confidence) {

    public enum Status { ALLOW, BLOCK, UNAVAILABLE }

    public static SemanticSafetyResult allow(String providerCode) {
        return new SemanticSafetyResult(Status.ALLOW, null, null, providerCode, 0.0);
    }

    public static SemanticSafetyResult block(String category, String severity, String providerCode) {
        return new SemanticSafetyResult(Status.BLOCK, category, severity, providerCode, 1.0);
    }

    public static SemanticSafetyResult unavailable(String providerCode) {
        return new SemanticSafetyResult(Status.UNAVAILABLE, null, null, providerCode, 0.0);
    }
}
