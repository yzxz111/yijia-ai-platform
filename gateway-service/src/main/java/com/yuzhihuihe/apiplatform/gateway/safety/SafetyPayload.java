package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.List;

public record SafetyPayload(String text, String role, List<String> fileSha256, String direction) {

    public SafetyPayload {
        text = text == null ? "" : text;
        role = role == null ? "unknown" : role;
        fileSha256 = List.copyOf(fileSha256 == null ? List.of() : fileSha256);
        direction = direction == null ? "INPUT" : direction;
    }
}
