package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

class FileUploadMetadataValidatorTest {

    private static final String SHA256 = "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";
    private final FileUploadMetadataValidator validator = new FileUploadMetadataValidator();

    @ParameterizedTest
    @MethodSource("supportedUploads")
    void acceptsSupportedExtensionAndMimeCombinations(String filename, String mimeType) {
        var result = validator.validate(upload(filename, 1, mimeType, SHA256), 20);

        assertThat(result).isEqualTo(new FileUploadMetadataValidator.ValidatedUpload(
                filename, 1, mimeType, SHA256));
    }

    @Test
    void normalizesMimeTypeAndRemovesPathAndControlCharactersFromDisplayName() {
        var result = validator.validate(
                upload("../unsafe\\folder/re\u0000port.PDF", 3, " Application/PDF ; charset=binary ", SHA256),
                3);

        assertThat(result.sanitizedFilename()).isEqualTo("report.PDF");
        assertThat(result.mimeType()).isEqualTo("application/pdf");
    }

    @ParameterizedTest
    @MethodSource("invalidUploads")
    void rejectsInvalidDeclaredMetadata(FileUploadMetadataValidator.DeclaredUpload upload, long maximumBytes) {
        assertThatThrownBy(() -> validator.validate(upload, maximumBytes))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static Stream<Arguments> supportedUploads() {
        return Stream.of(
                Arguments.of("a.png", "image/png"),
                Arguments.of("a.jpg", "image/jpeg"),
                Arguments.of("a.jpeg", "image/jpeg"),
                Arguments.of("a.gif", "image/gif"),
                Arguments.of("a.webp", "image/webp"),
                Arguments.of("a.pdf", "application/pdf"),
                Arguments.of("a.doc", "application/msword"),
                Arguments.of("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                Arguments.of("a.xls", "application/vnd.ms-excel"),
                Arguments.of("a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Arguments.of("a.csv", "text/csv"),
                Arguments.of("a.csv", "application/csv"),
                Arguments.of("a.txt", "text/plain"),
                Arguments.of("a.md", "text/markdown"),
                Arguments.of("a.md", "text/x-markdown"),
                Arguments.of("a.markdown", "text/markdown"));
    }

    private static Stream<Arguments> invalidUploads() {
        return Stream.of(
                Arguments.of(upload("a.txt", 0, "text/plain", SHA256), 10),
                Arguments.of(upload("a.txt", 11, "text/plain", SHA256), 10),
                Arguments.of(upload("a.txt", 1, "text/plain", SHA256.toUpperCase()), 10),
                Arguments.of(upload("a.txt", 1, "text/plain", SHA256.substring(1)), 10),
                Arguments.of(upload("a.pdf", 1, "text/plain", SHA256), 10),
                Arguments.of(upload("a.exe", 1, "application/octet-stream", SHA256), 10),
                Arguments.of(upload("\u0000/\u0001", 1, "text/plain", SHA256), 10),
                Arguments.of(upload("a".repeat(252) + ".txt", 1, "text/plain", SHA256), 10));
    }

    private static FileUploadMetadataValidator.DeclaredUpload upload(
            String filename, long bytes, String mimeType, String sha256) {
        return new FileUploadMetadataValidator.DeclaredUpload(
                filename, bytes, mimeType, sha256, "assistants");
    }
}
