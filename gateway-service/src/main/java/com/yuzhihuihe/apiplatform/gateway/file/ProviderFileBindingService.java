package com.yuzhihuihe.apiplatform.gateway.file;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ProviderFileBindingService {

    private static final int MAX_WAIT_ATTEMPTS = 100;
    private static final Duration WAIT_INTERVAL = Duration.ofMillis(100);

    private final ProviderFileBindingRepository repository;
    private final FileObjectStore objectStore;

    public ProviderFileBindingService(
            ProviderFileBindingRepository repository, FileObjectStore objectStore) {
        this.repository = repository;
        this.objectStore = objectStore;
    }

    public Mono<String> bind(
            FileObjectRepository.FileObject file,
            ProviderAdapter adapter,
            ProviderAdapter.Channel channel) {
        if (file == null || adapter == null || channel == null
                || !"READY".equals(file.status())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "file_not_usable"));
        }
        return repository.findReady(file.id(), channel.id())
                .map(ProviderFileBindingRepository.Binding::upstreamFileId)
                .switchIfEmpty(Mono.defer(() -> repository.claim(file.id(), channel.id())
                        .flatMap(binding -> upload(file, adapter, channel, binding))
                        .switchIfEmpty(Mono.defer(() -> waitForBinding(file, adapter, channel, 0)))));
    }

    private Mono<String> upload(
            FileObjectRepository.FileObject file,
            ProviderAdapter adapter,
            ProviderAdapter.Channel channel,
            ProviderFileBindingRepository.Binding binding) {
        return objectStore.get(file.objectKey(), file.sizeBytes())
                .flatMap(content -> {
                    if (content.length != file.sizeBytes() || !sha256(content).equals(file.sha256())) {
                        return Mono.error(new IllegalStateException("stored_file_integrity_mismatch"));
                    }
                    ProviderAdapter.FileObject providerFile = new ProviderAdapter.FileObject(
                            file.id(), file.objectKey(), file.mimeType(), file.sizeBytes(), content);
                    return adapter.bindFile(providerFile, channel);
                })
                .flatMap(reference -> repository.markReady(
                                binding.id(), binding.claimToken(), reference.upstreamFileId())
                        .thenReturn(reference.upstreamFileId()))
                .onErrorResume(error -> repository.markFailed(binding.id(), binding.claimToken())
                        .onErrorResume(ignored -> Mono.empty())
                        .then(Mono.error(error)));
    }

    private Mono<String> waitForBinding(
            FileObjectRepository.FileObject file,
            ProviderAdapter adapter,
            ProviderAdapter.Channel channel,
            int attempt) {
        if (attempt >= MAX_WAIT_ATTEMPTS) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "provider_file_binding_timeout"));
        }
        return Mono.delay(WAIT_INTERVAL)
                .then(repository.find(file.id(), channel.id()))
                .flatMap(binding -> {
                    if ("READY".equals(binding.status())
                            && binding.upstreamFileId() != null
                            && !binding.upstreamFileId().isBlank()) {
                        return Mono.just(binding.upstreamFileId());
                    }
                    if ("FAILED".equals(binding.status()) || "PENDING".equals(binding.status())) {
                        return repository.claim(file.id(), channel.id())
                                .flatMap(claimed -> upload(file, adapter, channel, claimed))
                                .switchIfEmpty(Mono.defer(() -> waitForBinding(
                                        file, adapter, channel, attempt + 1)));
                    }
                    return Mono.defer(() -> waitForBinding(file, adapter, channel, attempt + 1));
                })
                .switchIfEmpty(Mono.defer(() -> waitForBinding(
                        file, adapter, channel, attempt + 1)));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }
}
