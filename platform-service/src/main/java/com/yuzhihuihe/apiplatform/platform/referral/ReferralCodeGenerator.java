package com.yuzhihuihe.apiplatform.platform.referral;

import java.security.SecureRandom;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
final class ReferralCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{32}");

    private final SecureRandom random = new SecureRandom();

    String generate() {
        StringBuilder code = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    boolean valid(String code) {
        return code != null && CODE.matcher(code).matches();
    }
}
