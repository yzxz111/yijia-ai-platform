package com.yuzhihuihe.apiplatform.platform.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/platform/content-safety")
public class ContentSafetyInternalController {

    private static final String PATH = "/internal/platform/content-safety/events";
    private final ContentSafetyCaseService caseService;
    private final InternalRequestSigner signer;
    private final ObjectMapper objectMapper;

    public ContentSafetyInternalController(
            ContentSafetyCaseService caseService, InternalRequestSigner signer, ObjectMapper objectMapper) {
        this.caseService = caseService;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/events")
    ContentSafetyCaseDto event(
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Request-Id") String requestId,
            @RequestHeader("X-Internal-Content-Sha256") String contentSha256,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestBody byte[] body) {
        UUID parsedRequestId;
        try {
            parsedRequestId = UUID.fromString(requestId);
            long parsedTimestamp = Long.parseLong(timestamp);
            if (!signer.verify(parsedTimestamp, parsedRequestId, "POST", PATH, body, contentSha256, signature)) {
                throw new IllegalArgumentException("invalid_signature");
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_internal_signature");
        }
        try {
            return caseService.ingest(objectMapper.readValue(body, ContentSafetyCaseService.SafetyEventPayload.class));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_content_safety_event");
        }
    }
}
