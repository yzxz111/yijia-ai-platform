package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.regex.Pattern;

/**
 * Dotted numeric client/skill version comparison ({@code 2026.6.30} > {@code 2026.6.9}) used for the
 * {@code X-Client-Version} versus {@code min_client_version} gate (design R-06/R-30).
 */
final class ClientVersion {

    /** Same shape the admin form validates: {@code ^\d{4}\.\d{1,2}(\.\d{1,2})?$} is a subset. */
    private static final Pattern SHAPE = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");

    private ClientVersion() {}

    static boolean valid(String version) {
        return version != null && SHAPE.matcher(version.strip()).matches();
    }

    /** Negative when {@code left} is older than {@code right}, zero when equal. */
    static int compare(String left, String right) {
        String[] leftParts = left.strip().split("\\.", -1);
        String[] rightParts = right.strip().split("\\.", -1);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            long leftValue = index < leftParts.length ? parse(leftParts[index]) : 0L;
            long rightValue = index < rightParts.length ? parse(rightParts[index]) : 0L;
            if (leftValue != rightValue) {
                return Long.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static long parse(String part) {
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}

