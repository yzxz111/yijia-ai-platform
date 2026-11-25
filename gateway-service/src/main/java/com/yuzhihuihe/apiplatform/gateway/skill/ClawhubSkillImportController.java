package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Admin-only preview, draft import and explicit publish routes for native ClawHub Skill ZIPs. */
@RestController
@RequestMapping("/console/gateway/admin/skill-source-imports")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ClawhubSkillImportController {

    private final ClawhubSkillImportService imports;
    private final SkillCatalogEventService catalogEvents;
    private final FileObjectStore objectStore;
    private final RemoteSkillFetchService remoteFetch;

    public ClawhubSkillImportController(
            ClawhubSkillImportService imports,
            SkillCatalogEventService catalogEvents,
            FileObjectStore objectStore,
            RemoteSkillFetchService remoteFetch) {
        this.imports = imports;
        this.catalogEvents = catalogEvents;
        this.objectStore = objectStore;
        this.remoteFetch = remoteFetch;
    }

    /** Fetches one immutable remote response for the existing preview and SHA-pinned commit flow. */
    @PostMapping(value = "/clawhub/remote/fetch", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<byte[]>> fetchRemote(@RequestBody RemoteSkillFetchService.FetchRequest request) {
        return remoteFetch.fetch(request == null ? null : request.url())
                .map(result -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/zip"))
                        .contentLength(result.archive().length)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("X-Skill-Sha256", result.sha256())
                        .header("X-Skill-Remote-Metadata", URLEncoder.encode(
                                result.metadataJson(), StandardCharsets.UTF_8).replace("+", "%20"))
                        .body(result.archive()));
    }

    @PostMapping(value = "/clawhub/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<ClawhubSkillImportService.Preview> preview(
            @RequestPart("file") FilePart file,
            @RequestPart(name = "owner", required = false) String owner,
            @RequestPart(name = "slug", required = false) String slug,
            @RequestPart(name = "version", required = false) String version,
            @RequestPart(name = "displayName", required = false) String displayName) {
        return imports.preview(file, owner, slug, version, displayName);
    }

    @PostMapping(value = "/clawhub/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ClawhubSkillImportService.Commit> commit(
            @RequestPart("file") FilePart file,
            @RequestPart(name = "owner", required = false) String owner,
            @RequestPart(name = "slug", required = false) String slug,
            @RequestPart(name = "version", required = false) String version,
            @RequestPart(name = "displayName", required = false) String displayName,
            @RequestPart(name = "expectedSha256", required = false) String expectedSha256,
            @RequestPart(name = "metadata", required = false) String metadata,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.commit(file, owner, slug, version, displayName, expectedSha256,
                metadata, actor(jwt));
    }

    @PostMapping("/clawhub/{skillId}/publish")
    Mono<SkillView> publish(
            @PathVariable UUID skillId,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(imports.publish(skillId, actor(jwt)));
    }

    @PutMapping("/clawhub/{skillId}/version")
    Mono<ClawhubSkillImportService.Commit> addMissingVersion(
            @PathVariable UUID skillId,
            @RequestBody VersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.addMissingVersion(skillId, request == null ? null : request.version(), actor(jwt));
    }

    @PostMapping("/clawhub/{skillId}/rescan")
    Mono<ClawhubSkillImportService.Commit> rescan(
            @PathVariable UUID skillId,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.rescan(skillId, actor(jwt));
    }

    @GetMapping("/clawhub/{skillId}/package")
    Mono<Void> downloadSourcePackage(
            @PathVariable UUID skillId,
            ServerWebExchange exchange) {
        return imports.sourceArchive(skillId)
                .flatMap(archive -> streamArchive(exchange, archive));
    }

    @GetMapping("/clawhub/{skillId}/versions")
    Mono<ClawhubSkillImportService.VersionList> versions(@PathVariable UUID skillId) {
        return imports.versions(skillId);
    }

    @PostMapping(value = "/clawhub/{skillId}/versions",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ClawhubSkillImportService.VersionItem> uploadVersion(
            @PathVariable UUID skillId,
            @RequestPart("file") FilePart file,
            @RequestPart(name = "version", required = false) String version,
            @RequestPart(name = "expectedSha256", required = false) String expectedSha256,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.uploadVersion(skillId, file, version, expectedSha256, actor(jwt));
    }

    @GetMapping("/clawhub/{skillId}/versions/{versionId}/download")
    Mono<Void> downloadVersion(
            @PathVariable UUID skillId,
            @PathVariable UUID versionId,
            ServerWebExchange exchange) {
        return imports.sourceArchive(skillId, versionId)
                .flatMap(source -> streamVersionArchive(exchange, source));
    }

    @PostMapping("/clawhub/{skillId}/versions/{versionId}/publish")
    Mono<SkillView> publishVersion(
            @PathVariable UUID skillId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(imports.publishVersion(skillId, versionId, actor(jwt)));
    }

    @PostMapping("/clawhub/{skillId}/versions/{versionId}/rescan")
    Mono<ClawhubSkillImportService.VersionItem> rescanVersion(
            @PathVariable UUID skillId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.rescanVersion(skillId, versionId, actor(jwt));
    }

    @DeleteMapping("/clawhub/{skillId}/versions/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> deleteVersion(
            @PathVariable UUID skillId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.deleteVersion(skillId, versionId, actor(jwt));
    }

    public record VersionRequest(String version) {}

    /**
     * 取操作人 id。
     *
     * <p>JWT 的 sub 不是 UUID（或 principal 不是 Jwt）时，直接 {@code UUID.fromString}
     * 会抛 IllegalArgumentException/NPE 变成 500 —— 但那本质是"调用方身份有问题"，
     * 应当与 SkillAccessIdentity 的做法一致地回 401，而不是制造告警噪音。
     */
    private static UUID actor(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        try {
            return UUID.fromString(subject);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "authentication_required", invalid);
        }
    }

    private Mono<Void> streamArchive(
            ServerWebExchange exchange, ClawhubSkillImportService.SourceArchive archive) {
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentLength(archive.sizeBytes());
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"clawhub-" + archive.code() + ".zip\"");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Skill-Package-Format", ClawhubSkillAdapter.SOURCE_FORMAT);
        headers.set("X-Skill-Sha256", archive.sha256());
        return response.writeWith(objectStore.getRange(
                        archive.objectKey(), 0L, archive.sizeBytes())
                .map(ClawhubSkillImportController::copyChunk)
                .map(response.bufferFactory()::wrap));
    }

    private Mono<Void> streamVersionArchive(
            ServerWebExchange exchange, NativeClawhubSkillRepository.Source source) {
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentLength(source.sizeBytes());
        String version = source.version() == null ? "source" : source.version();
        String filename = SkillPackageFileNames.safeFileName(
                source.slug() + "-" + version + ".zip", "clawhub-source.zip");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Skill-Package-Format", ClawhubSkillAdapter.SOURCE_FORMAT);
        if (source.version() != null) headers.set("X-Skill-Version", source.version());
        headers.set("X-Skill-Sha256", source.sha256());
        return response.writeWith(objectStore.getRange(source.objectKey(), 0L, source.sizeBytes())
                .map(ClawhubSkillImportController::copyChunk)
                .map(response.bufferFactory()::wrap));
    }

    private static byte[] copyChunk(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
