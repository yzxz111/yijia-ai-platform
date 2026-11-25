package com.yuzhihuihe.apiplatform.gateway.safety;

public record FileSafetyResult(Result result, String reasonCode, long inspectedBytes) {

    public enum Result { ALLOW, BLOCK, UNAVAILABLE }

    public static FileSafetyResult allow(long bytes) {
        return new FileSafetyResult(Result.ALLOW, "allowed", bytes);
    }

    public static FileSafetyResult block(String reason, long bytes) {
        return new FileSafetyResult(Result.BLOCK, reason, bytes);
    }
}
