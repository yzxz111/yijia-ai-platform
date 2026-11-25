package com.yuzhihuihe.apiplatform.gateway.file;

import java.time.Instant;

public record StoredObjectMetadata(
        String objectKey,
        long sizeBytes,
        String contentType,
        String eTag,
        Instant lastModified) {
}
