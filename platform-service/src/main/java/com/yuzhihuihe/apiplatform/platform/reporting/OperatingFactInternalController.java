package com.yuzhihuihe.apiplatform.platform.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/platform/operating-facts")
public class OperatingFactInternalController {

    private final OperatingFactIngestionService ingestion;

    public OperatingFactInternalController(OperatingFactIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/usage-events")
    public ResponseEntity<Void> ingest(
            @RequestBody byte[] body,
            @RequestHeader("X-Internal-Content-Sha256") String payloadHash) {
        return switch (ingestion.ingest(body, payloadHash)) {
            case ACCEPTED, REPLAYED -> ResponseEntity.noContent().build();
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case INVALID -> ResponseEntity.unprocessableEntity().build();
        };
    }
}
