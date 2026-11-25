package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NormalizedAttachment(
        String externalFileId,
        String filename,
        Kind kind,
        String normalizedText,
        List<String> imageDataUris,
        Map<String, Object> metadata) {

    public NormalizedAttachment {
        externalFileId = Objects.requireNonNull(externalFileId, "externalFileId");
        filename = Objects.requireNonNull(filename, "filename");
        kind = Objects.requireNonNull(kind, "kind");
        normalizedText = normalizedText == null ? "" : normalizedText;
        imageDataUris = imageDataUris == null ? List.of() : List.copyOf(imageDataUris);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum Kind {
        TEXT,
        IMAGE,
        TEXT_AND_IMAGE
    }
}
