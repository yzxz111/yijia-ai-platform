package com.yuzhihuihe.apiplatform.gateway.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ContentSafetyCheckService {

    private final ContentSafetyCheckRepository repository;
    private final ObjectMapper objectMapper;

    public ContentSafetyCheckService(
            ContentSafetyCheckRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> record(
            String requestId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            String direction,
            String result,
            long policyVersion,
            Map<String, Object> localResult,
            Map<String, Object> semanticResult,
            Map<String, Object> fileResult,
            long durationMs,
            String safetyFingerprint) {
        return repository.insert(
                new ContentSafetyCheckRepository.CheckRecord(
                        UUID.randomUUID(),
                        requestId,
                        tenantId,
                        userId,
                        apiKeyId,
                        direction,
                        result,
                        policyVersion,
                        json(localResult),
                        json(semanticResult),
                        json(fileResult),
                        Math.max(0, durationMs),
                        safetyFingerprint));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("content_safety_check_invalid", exception);
        }
    }
}
