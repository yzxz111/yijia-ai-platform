package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class FileUploadMetadataValidator {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.ofEntries(
            Map.entry(".png", Set.of("image/png")),
            Map.entry(".jpg", Set.of("image/jpeg")),
            Map.entry(".jpeg", Set.of("image/jpeg")),
            Map.entry(".gif", Set.of("image/gif")),
            Map.entry(".webp", Set.of("image/webp")),
            Map.entry(".pdf", Set.of("application/pdf")),
            Map.entry(".doc", Set.of("application/msword")),
            Map.entry(".docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry(".xls", Set.of("application/vnd.ms-excel")),
            Map.entry(".xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            Map.entry(".csv", Set.of("text/csv", "application/csv")),
            Map.entry(".txt", Set.of("text/plain")),
            Map.entry(".md", Set.of("text/markdown", "text/x-markdown")),
            Map.entry(".markdown", Set.of("text/markdown", "text/x-markdown")));

    public ValidatedUpload validate(DeclaredUpload upload, long maximumBytes) {
        if (upload == null || upload.bytes() <= 0 || maximumBytes <= 0) {
            throw invalidMetadata();
        }
        if (upload.bytes() > maximumBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large");
        }
        String filename = sanitizeFilename(upload.filename());
        String mimeType = normalizeMimeType(upload.mimeType());
        Set<String> allowedMimeTypes = ALLOWED_TYPES.get(extension(filename));
        if (allowedMimeTypes == null || !allowedMimeTypes.contains(mimeType)
                || upload.sha256() == null || !SHA256.matcher(upload.sha256()).matches()) {
            throw invalidMetadata();
        }
        return new ValidatedUpload(filename, upload.bytes(), mimeType, upload.sha256());
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw invalidMetadata();
        }
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String basename = filename.substring(separator + 1);
        StringBuilder sanitized = new StringBuilder(basename.length());
        basename.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(sanitized::appendCodePoint);
        String result = sanitized.toString().strip();
        if (result.isEmpty() || result.length() > 255
                || result.indexOf('/') >= 0 || result.indexOf('\\') >= 0) {
            throw invalidMetadata();
        }
        return result;
    }

    private static String extension(String filename) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? "" : normalized.substring(dot);
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int parameters = mimeType.indexOf(';');
        return (parameters < 0 ? mimeType : mimeType.substring(0, parameters))
                .strip().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException invalidMetadata() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_file_metadata");
    }

    public record DeclaredUpload(
            String filename, long bytes, String mimeType, String sha256, String purpose) { }

    public record ValidatedUpload(
            String sanitizedFilename, long bytes, String mimeType, String sha256) { }
}
