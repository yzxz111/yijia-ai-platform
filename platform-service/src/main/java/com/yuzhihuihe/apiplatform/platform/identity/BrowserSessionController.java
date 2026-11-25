package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BrowserSessionController {
    private final BrowserSessionService sessions;
    BrowserSessionController(BrowserSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/console/platform/auth/browser-close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@CookieValue(name = IdentityController.REFRESH_COOKIE, required = false) String refresh,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "X-Device-Id", required = false) String deviceId,
            @RequestHeader("X-Browser-Page") UUID pageId,
            @RequestHeader("X-Browser-Sequence") long sequence) {
        sessions.close(refresh, csrf, deviceId, pageId, sequence);
    }
}
