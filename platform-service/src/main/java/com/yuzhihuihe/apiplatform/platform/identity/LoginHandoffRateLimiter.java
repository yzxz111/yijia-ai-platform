package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;

interface LoginHandoffRateLimiter {
    void acquire(UUID userId);
}
