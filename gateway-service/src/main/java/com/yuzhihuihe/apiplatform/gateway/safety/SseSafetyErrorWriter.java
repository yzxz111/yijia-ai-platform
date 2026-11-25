package com.yuzhihuihe.apiplatform.gateway.safety;

public final class SseSafetyErrorWriter {
    private SseSafetyErrorWriter() { }
    public static String error(String code) {
        return "{\"error\":{\"type\":\"content_safety\",\"code\":\""
                + (code == null ? "content_safety_blocked" : code)
                + "\",\"message\":\"The response was stopped by content safety policy.\"}}";
    }
}
