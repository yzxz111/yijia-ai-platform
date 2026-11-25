package com.yuzhihuihe.apiplatform.platform.sms;

import java.util.concurrent.atomic.AtomicBoolean;

public interface SmsChallengeIpRateLimiter {

    Reservation reserve(Scope scope, String requestIpHash);

    void release(Reservation reservation);

    enum Scope {
        REGISTER,
        LOGIN
    }

    final class Reservation {
        private final String key;
        private final AtomicBoolean released = new AtomicBoolean();

        Reservation(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("sms_rate_limit_reservation_required");
            }
            this.key = key;
        }

        String key() {
            return key;
        }

        boolean beginRelease() {
            return released.compareAndSet(false, true);
        }
    }
}
