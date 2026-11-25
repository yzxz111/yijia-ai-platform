package com.yuzhihuihe.apiplatform.gateway.internal;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.quota.PackageRefundService;
import com.yuzhihuihe.apiplatform.gateway.security.InternalResponseSigner;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/refunds")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PackageRefundController {

    private final PackageRefundService service;
    private final InternalResponseSigner responseSigner;
    private final ObjectMapper objectMapper;

    public PackageRefundController(
            PackageRefundService service,
            InternalResponseSigner responseSigner,
            ObjectMapper objectMapper) {
        this.service = service;
        this.responseSigner = responseSigner;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/prepare")
    Mono<InternalResponseSigner.SignedInternalResponse> prepare(
            @RequestHeader("X-Internal-Request-Id") UUID requestId,
            @Valid @RequestBody PackageRefundService.PrepareRefundCommand command) {
        return service.prepare(command).map(payload -> signed(requestId, payload));
    }

    @PostMapping("/release")
    Mono<InternalResponseSigner.SignedInternalResponse> release(
            @RequestHeader("X-Internal-Request-Id") UUID requestId,
            @Valid @RequestBody PackageRefundService.ReleaseRefundCommand command) {
        return service.release(command).map(payload -> signed(requestId, payload));
    }

    private InternalResponseSigner.SignedInternalResponse signed(UUID requestId, Object payload) {
        try {
            return responseSigner.sign(
                    requestId, HttpStatus.OK.value(), objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("internal_response_serialization_failed", exception);
        }
    }
}
