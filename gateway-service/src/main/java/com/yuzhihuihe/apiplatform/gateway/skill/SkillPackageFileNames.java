package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.regex.Pattern;

import org.springframework.http.codec.multipart.FilePart;

/** Canonicalises harmless browser duplicate-download suffixes without changing package bytes. */
final class SkillPackageFileNames {

    private static final Pattern DOWNLOAD_DUPLICATE_SUFFIX = Pattern.compile(
            "(?:\\s*\\(\\d+\\))+(?=(?:\\.ycps)?\\.zip$)",
            Pattern.CASE_INSENSITIVE);

    private SkillPackageFileNames() { }

    static String safeFileName(FilePart file, String fallback) {
        return safeFileName(file == null ? null : file.filename(), fallback);
    }

    static String safeFileName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String base = value.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        for (int attempt = 0; attempt < 4; attempt++) {
            String normalized = DOWNLOAD_DUPLICATE_SUFFIX.matcher(base).replaceAll("");
            if (normalized.equals(base)) break;
            base = normalized;
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
