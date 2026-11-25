package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.ByteBuffer;
import java.util.List;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/** Public metadata catalog plus authenticated package downloads for published native ClawHub ZIPs. */
@RestController
@RequestMapping("/v1/native-clawhub/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class NativeClawhubSkillsController {

    private static final String IMAGE_CSP =
            "default-src 'none'; style-src 'none'; script-src 'none'; sandbox";

    private final NativeClawhubSkillRepository skills;
    private final ClawhubSkillImportService sourceArchives;
    private final FileObjectStore objectStore;
    private final SkillProperties properties;

    NativeClawhubSkillsController(
            NativeClawhubSkillRepository skills,
            ClawhubSkillImportService sourceArchives,
            FileObjectStore objectStore,
            SkillProperties properties) {
        this.skills = skills;
        this.sourceArchives = sourceArchives;
        this.objectStore = objectStore;
        this.properties = properties;
    }

    @GetMapping
    Mono<Catalog> list(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        return skills.catalogSkills().collectList()
                .map(items -> new Catalog(items, "list"));
    }

    /** Public artwork is limited by the same visibility gate as the native catalog. */
    @GetMapping("/{code}/icon")
    Mono<ResponseEntity<?>> icon(@PathVariable String code, ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return skills.catalogIconObjectKey(code)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "native_clawhub_icon_not_found")))
                .flatMap(objectKey -> objectStore.get(objectKey, properties.getIconMaxBytes().toBytes())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "native_clawhub_icon_not_found")))
                        .onErrorMap(FileObjectMissingException.class, error ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "native_clawhub_icon_not_found"))
                        .map(content -> ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType(
                                        SkillCatalogService.memberAvatarMediaType(objectKey)))
                                .header("x-request-id", requestId)
                                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                .header("X-Content-Type-Options", "nosniff")
                                .header("Content-Security-Policy", IMAGE_CSP)
                                .body(content)))
                .<ResponseEntity<?>>map(response -> response)
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    @GetMapping("/{code}/package")
    Mono<Void> packageArchive(
            @PathVariable String code,
            ServerWebExchange exchange) {
        return SkillAccessIdentity.resolve(exchange)
                .then(skills.publishedPackage(code)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "native_clawhub_package_not_found"))))
                .flatMap(file -> sourceArchives.publishedSourceArchive(file.skillId())
                        .flatMap(archive -> {
                            if (archive.sizeBytes() != file.sizeBytes()
                                    || !archive.sha256().equals(file.sha256())
                                    || !archive.code().equals(file.code())) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT, "native_clawhub_release_conflict"));
                            }
                            return streamPackage(exchange, file, archive.objectKey());
                        }));
    }

    private Mono<Void> streamPackage(
            ServerWebExchange exchange,
            NativeClawhubSkillRepository.PackageFile file,
            String verifiedObjectKey) {
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentLength(file.sizeBytes());
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.code() + "-" + file.version() + ".zip\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Skill-Package-Format", file.packageFormat());
        headers.set("X-Skill-Version", file.version());
        headers.set("X-Skill-Sha256", file.sha256());
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return response.writeWith(objectStore.getRange(verifiedObjectKey, 0L, file.sizeBytes())
                .map(NativeClawhubSkillsController::copyChunk)
                .map(response.bufferFactory()::wrap));
    }

    private static byte[] copyChunk(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    public record Catalog(List<NativeClawhubSkillRepository.CatalogSkill> items, String object) {
        public Catalog {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }
}
