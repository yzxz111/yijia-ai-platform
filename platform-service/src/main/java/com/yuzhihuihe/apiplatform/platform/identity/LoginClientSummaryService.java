package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.Locale;

import org.springframework.stereotype.Service;

@Service
final class LoginClientSummaryService {

    private final IpLocationResolver locations;

    LoginClientSummaryService(IpLocationResolver locations) {
        this.locations = locations;
    }

    ClientSummary summarize(LoginActivityService.LoginContext context) {
        LoginActivityService.LoginContext safe = context == null
                ? LoginActivityService.LoginContext.empty() : context;
        String region;
        try {
            region = locations.resolve(safe.ipAddress());
        } catch (RuntimeException ignored) {
            region = null;
        }
        if (region == null || region.isBlank() || IpLocationResolver.UNKNOWN.equalsIgnoreCase(region)) {
            region = "未知地区";
        }
        region = bounded(region.trim(), 100);
        return new ClientSummary(region, browser(safe.userAgent()) + " / " + operatingSystem(safe.userAgent()));
    }

    private String browser(String userAgent) {
        String value = normalized(userAgent);
        if (value.contains("edg/")) return "Edge";
        if (value.contains("chrome/") || value.contains("crios/")) return "Chrome";
        if (value.contains("firefox/") || value.contains("fxios/")) return "Firefox";
        if (value.contains("safari/")) return "Safari";
        return "Unknown Browser";
    }

    private String operatingSystem(String userAgent) {
        String value = normalized(userAgent);
        if (value.contains("windows")) return "Windows";
        if (value.contains("android")) return "Android";
        if (value.contains("iphone") || value.contains("ipad")) return "iOS";
        if (value.contains("mac os") || value.contains("macintosh")) return "macOS";
        if (value.contains("linux")) return "Linux";
        return "Unknown OS";
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    record ClientSummary(String region, String userAgentSummary) { }
}
