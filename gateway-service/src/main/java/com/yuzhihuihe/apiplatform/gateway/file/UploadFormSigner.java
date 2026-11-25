package com.yuzhihuihe.apiplatform.gateway.file;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import reactor.core.publisher.Mono;

public interface UploadFormSigner {

    Mono<SignedUploadForm> sign(String objectKey, String mimeType, long sizeBytes, Instant expiresAt);

    record SignedUploadForm(String method, URI url, Map<String, String> fields, Instant expiresAt) {

        public SignedUploadForm {
            fields = Map.copyOf(fields);
        }
    }
}
