package com.yuzhihuihe.apiplatform.platform.identity;

interface PasswordLoginRateLimiter {

    LoginAttempt inspect(String normalizedIdentifier, String clientIp);

    void recordFailure(LoginAttempt attempt);

    void recordSuccess(LoginAttempt attempt);

    record LoginAttempt(String accountFingerprint, String ipFingerprint) { }
}
