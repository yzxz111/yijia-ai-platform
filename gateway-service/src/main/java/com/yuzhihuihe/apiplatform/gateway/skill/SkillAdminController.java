package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.annotation.JsonView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.CreateCategoryCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.UpdateCategoryCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.CreateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.PublishSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.ReplaceMembersCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.SkillListQuery;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.UpdateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.DownloadView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.DeletedSkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCodeAvailabilityView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCategoryListView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCategoryView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillDependentsView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillPackageInspectView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillRelationsView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionDependenciesView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Skill administration endpoints (design 5.1 A1-A14). The {@code /console/gateway/admin/**} prefix
 * is already guarded by {@code hasRole("ADMIN")} in {@code SecurityConfiguration}, so this controller
 * only parses HTTP and delegates to {@link SkillAdminService}.
 */
@RestController
@RequestMapping("/console/gateway/admin/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillAdminController {

    private static final String IMAGE_CSP =
            "default-src 'none'; style-src 'none'; script-src 'none'; sandbox";

    /** Same media type the client download path serves (design 5.2 C5). */
    private static final String PACKAGE_CONTENT_TYPE = "application/vnd.yuzhi.ycps+zip";

    /**
     * The A26 wrapper. A distinct type because it is a plain folder-shaped ZIP, not a YCPS package:
     * it must never be mistaken for something uploadable.
     */
    private static final String PACKAGE_BUNDLE_CONTENT_TYPE = "application/zip";

    /** Response read size for the assembled bundle; large enough to keep syscalls down. */
    private static final int BUFFER_BYTES = 256 * 1024;

    private final SkillAdminService admin;
    private final FileObjectStore objectStore;
    private final SkillCatalogEventService catalogEvents;

    public SkillAdminController(
            SkillAdminService admin,
            FileObjectStore objectStore,
            SkillCatalogEventService catalogEvents) {
        this.admin = admin;
        this.objectStore = objectStore;
        this.catalogEvents = catalogEvents;
    }

    /**
     * A1. Serialised without {@code @JsonView}, so the detail-only {@code manifest} component of
     * {@link SkillView} is not part of the list payload (D5/E-08).
     */
    @GetMapping
    Mono<PageResult<SkillView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return admin.list(new SkillListQuery(page, size, query, status, category, kind, sort, direction));
    }

    /** Lists recoverable tombstones that have not reached the configured retention deadline. */
    @GetMapping("/trash")
    Mono<PageResult<DeletedSkillView>> recycleBin(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String kind) {
        return admin.recycleBin(page, size, query, kind);
    }

    /** A2. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<SkillView> create(
            @RequestBody CreateSkillCommand command, @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.create(command, actor(jwt)));
    }

    /** A3. */
    @GetMapping("/{id}")
    Mono<SkillView> detail(@PathVariable UUID id) {
        return admin.detail(id);
    }

    /** A4. */
    @PutMapping("/{id}")
    Mono<SkillView> update(
            @PathVariable UUID id,
            @RequestBody UpdateSkillCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.update(id, command, actor(jwt)));
    }

    /** A5. */
    @PostMapping("/{id}/icon")
    Mono<SkillView> uploadIcon(
            @PathVariable UUID id,
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.uploadIcon(id, file, actor(jwt)));
    }

    @GetMapping("/{id}/icon")
    Mono<ResponseEntity<byte[]>> icon(@PathVariable UUID id) {
        return admin.icon(id)
                .map(icon -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(icon.mediaType()))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(icon.content()));
    }

    /** A5 companion: clears the uploaded icon so the record falls back to its text badge. */
    @DeleteMapping("/{id}/icon")
    Mono<SkillView> clearIcon(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.clearIcon(id, actor(jwt)));
    }

    /** A6. */
    @PostMapping("/{id}/versions")
    Mono<SkillVersionView> uploadVersion(
            @PathVariable UUID id,
            @RequestPart("file") FilePart file,
            // WebFlux binds multipart form fields through @RequestPart, not @RequestParam.
            @RequestPart("version") String version,
            @RequestPart(name = "changelog", required = false) String changelog,
            @RequestPart(name = "minClientVersion", required = false) String minClientVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.uploadVersion(id, version, changelog, minClientVersion, file, actor(jwt)));
    }

    /** A7. */
    @PutMapping("/{id}/versions/{versionId}/package")
    Mono<SkillVersionView> replacePackage(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @RequestPart("file") FilePart file,
            @RequestPart(name = "changelog", required = false) String changelog,
            @RequestPart(name = "minClientVersion", required = false) String minClientVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.replacePackage(id, versionId, changelog, minClientVersion, file, actor(jwt)));
    }

    /** A8. */
    @PostMapping("/{id}/versions/{versionId}/publish")
    Mono<SkillVersionView> publishVersion(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.publishVersion(id, versionId, actor(jwt)));
    }

    /** A9. */
    @PostMapping("/{id}/publish")
    Mono<SkillView> publish(
            @PathVariable UUID id,
            @RequestBody(required = false) PublishSkillCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.publish(id, command == null ? null : command.version(), actor(jwt)));
    }

    /** A10. */
    @PostMapping("/{id}/unpublish")
    Mono<SkillView> unpublish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.unpublish(id, actor(jwt)));
    }

    /** A11. */
    @DeleteMapping("/{id}")
    Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.delete(id, actor(jwt)));
    }

    /** Restores a tombstone as draft/unpublished; it is never made client-visible implicitly. */
    @PostMapping("/{id}/restore")
    Mono<SkillView> restore(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.restore(id, actor(jwt)));
    }

    /** Permanently removes a tombstone and its package/image objects immediately. */
    @DeleteMapping("/{id}/purge")
    Mono<Void> purge(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.purge(id, actor(jwt)));
    }

    /** A12. */
    @DeleteMapping("/{id}/versions/{versionId}")
    Mono<Void> deleteVersion(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.deleteVersion(id, versionId, actor(jwt)));
    }

    /** A13. */
    @GetMapping("/{id}/downloads")
    Mono<PageResult<DownloadView>> downloads(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin.downloads(id, page, size);
    }

    /** A14. */
    @GetMapping("/{id}/preview")
    Mono<SkillDetail> preview(@PathVariable UUID id) {
        return admin.preview(id);
    }

    /**
     * A25: streams the sealed package of one revision to the console (browser download).
     *
     * <p>{@code versionId} is optional; omitting it downloads the version that is online right now,
     * which is what the default button does. The body is streamed from the object store rather than
     * buffered, because packages reach 200 MB.
     */
    @GetMapping("/{id}/versions/{versionId}/package")
    Mono<Void> downloadPackage(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        return admin.packageForDownload(id, versionId, actor(jwt))
                .flatMap(download -> streamPackage(exchange, download));
    }

    /** A25 default: the online version, without the caller having to name it. */
    @GetMapping("/{id}/package")
    Mono<Void> downloadOnlinePackage(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        return admin.packageForDownload(id, null, actor(jwt))
                .flatMap(download -> streamPackage(exchange, download));
    }

    /**
     * A26: the online package wrapped in a folder-shaped ZIP together with the curated images.
     *
     * <p>Streamed, not buffered: the inner archive is copied straight from the object store into the
     * wrapper's deflate stream, so a 200 MB package never lands in heap.
     */
    @GetMapping("/{id}/package/bundle")
    Mono<Void> downloadOnlinePackageBundle(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        return admin.packageBundleForDownload(id, null, actor(jwt))
                .flatMap(bundle -> streamPackageBundle(exchange, bundle));
    }

    /** A26 for one named revision, mirroring the version-scoped A25 route. */
    @GetMapping("/{id}/versions/{versionId}/package/bundle")
    Mono<Void> downloadPackageBundle(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        return admin.packageBundleForDownload(id, versionId, actor(jwt))
                .flatMap(bundle -> streamPackageBundle(exchange, bundle));
    }

    /**
     * Writes the wrapper ZIP: one folder holding the untouched inner package plus the curated images.
     *
     * <p>Assembled into a temp file rather than streamed straight out, because a ZIP's central
     * directory can only be written once every entry's size and CRC are known — there is no way to
     * emit it incrementally. The inner archive is copied through the object store's ranged reader, so
     * a 200 MB package still never lands in heap; only the small images are buffered. The temp file is
     * deleted on both the success and the error path.
     */
    private Mono<Void> streamPackageBundle(
            ServerWebExchange exchange, SkillAdminService.PackageBundle bundle) {
        SkillAdminService.PackageDownload download = bundle.archive();
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.parseMediaType(PACKAGE_BUNDLE_CONTENT_TYPE));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + bundle.fileName() + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Skill-Code", download.code());
        headers.set("X-Skill-Version", download.version());
        headers.set("X-Skill-Bundle-Assets", Integer.toString(bundle.assets().size()));
        if (download.sha256() != null) {
            headers.set("X-Skill-Sha256", download.sha256());
        }
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");

        Mono<Path> tempFile = Mono.fromCallable(() -> Files.createTempFile("skill-bundle-", ".zip"))
                .subscribeOn(Schedulers.boundedElastic());
        return Mono.usingWhen(
                tempFile,
                temp -> Mono.fromRunnable(() -> writeBundleUnchecked(temp, bundle))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then(Mono.defer(() -> response.writeWith(
                                DataBufferUtils.read(temp, response.bufferFactory(), BUFFER_BYTES)))),
                temp -> Mono.fromRunnable(() -> deleteQuietly(temp))
                        .subscribeOn(Schedulers.boundedElastic()),
                (temp, error) -> Mono.fromRunnable(() -> deleteQuietly(temp))
                        .subscribeOn(Schedulers.boundedElastic()),
                temp -> Mono.fromRunnable(() -> deleteQuietly(temp))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** Bridges {@link #writeBundle} into a {@code Runnable}, where checked exceptions cannot surface. */
    private void writeBundleUnchecked(Path temp, SkillAdminService.PackageBundle bundle) {
        try {
            writeBundle(temp, bundle);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Blocking by design; the caller pins it to a bounded-elastic thread. */
    private void writeBundle(Path temp, SkillAdminService.PackageBundle bundle) throws IOException {
        SkillAdminService.PackageDownload download = bundle.archive();
        String folder = bundle.folderName() + "/";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            zip.putNextEntry(new ZipEntry(folder + download.fileName()));
            // Ranged reads are the only streaming access the store exposes, so the inner archive is
            // pulled chunk by chunk and handed straight to the deflater.
            for (ByteBuffer chunk : objectStore
                    .getRange(download.objectKey(), 0L, download.sizeBytes())
                    .toIterable()) {
                byte[] bytes = new byte[chunk.remaining()];
                chunk.duplicate().get(bytes);
                zip.write(bytes);
            }
            zip.closeEntry();
            for (Map.Entry<String, byte[]> asset : bundle.assets().entrySet()) {
                zip.putNextEntry(new ZipEntry(folder + asset.getKey()));
                zip.write(asset.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // A leftover temp file is not worth failing a download the caller already received.
        }
    }

    private Mono<Void> streamPackage(
            ServerWebExchange exchange, SkillAdminService.PackageDownload download) {
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.parseMediaType(PACKAGE_CONTENT_TYPE));
        headers.setContentLength(download.sizeBytes());
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + download.fileName() + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Skill-Code", download.code());
        headers.set("X-Skill-Version", download.version());
        if (download.md5() != null) {
            headers.set("X-Skill-Md5", download.md5());
        }
        if (download.sha256() != null) {
            headers.set("X-Skill-Sha256", download.sha256());
        }
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return response.writeWith(objectStore
                .getRange(download.objectKey(), 0L, download.sizeBytes())
                .map(SkillAdminController::copyChunk)
                .map(chunk -> response.bufferFactory().wrap(chunk)));
    }

    /** Copies each chunk because the transport may recycle the publisher's buffers. */
    private static byte[] copyChunk(java.nio.ByteBuffer source) {
        java.nio.ByteBuffer buffer = source.duplicate();
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        return chunk;
    }

    // ------------------------------------------------------------------ A15-A18: expert roster

    /** A15: whole-roster replacement; the body is {@code {"members":[…]}} and the response is A3. */
    @PutMapping("/{id}/members")
    Mono<SkillView> replaceMembers(
            @PathVariable UUID id,
            @RequestBody ReplaceMembersCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.replaceMembers(id, command, actor(jwt)));
    }

    /**
     * Upload the optional package introduction illustration (multipart field {@code file},
     * PNG/JPEG/WebP/SVG; size capped by {@code gateway.skills.icon-max-bytes}).
     */
    @PostMapping("/{id}/illustration")
    Mono<SkillView> uploadIllustration(
            @PathVariable UUID id,
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.uploadIllustration(id, file, actor(jwt)));
    }

    /** Companion to the upload: clears the illustration; the detail comes back with {@code illustrationUrl=null}. */
    @DeleteMapping("/{id}/illustration")
    Mono<SkillView> clearIllustration(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.clearIllustration(id, actor(jwt)));
    }

    /** The illustration bytes for the console preview (ADR-3.1: authenticated image stream). */
    @GetMapping("/{id}/illustration")
    Mono<ResponseEntity<byte[]>> illustration(@PathVariable UUID id) {
        return admin.illustration(id)
                .map(illustration -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(illustration.mediaType()))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(illustration.content()));
    }

    /** Uploads/replaces the optional expert feature example image. */
    @PostMapping("/{id}/example-image")
    Mono<SkillView> uploadExampleImage(
            @PathVariable UUID id,
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.uploadExampleImage(id, file, actor(jwt)));
    }

    /** Clears the example image; the operation is idempotent. */
    @DeleteMapping("/{id}/example-image")
    Mono<SkillView> clearExampleImage(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.clearExampleImage(id, actor(jwt)));
    }

    /** Reads example-image bytes for the authenticated console preview. */
    @GetMapping("/{id}/example-image")
    Mono<ResponseEntity<byte[]>> exampleImage(@PathVariable UUID id) {
        return admin.exampleImage(id)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mediaType()))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(image.content()));
    }

    // ------------------------------------------------------------------ A19-A20: review surface

    /** A19: the exact YCPS dependency edges of one revision (read-only review dialog). */
    @GetMapping("/{id}/versions/{versionId}/dependencies")
    Mono<SkillVersionDependenciesView> versionDependencies(
            @PathVariable UUID id, @PathVariable UUID versionId) {
        return admin.versionDependencies(id, versionId);
    }

    /** A20: every usable revision that locks this skill, for the unpublish/delete impact check. */
    @GetMapping("/{id}/dependents")
    Mono<SkillDependentsView> dependents(@PathVariable UUID id) {
        return admin.dependents(id);
    }

    /** A21: both directions of one package's relations, so the console needs no versionId first. */
    @GetMapping("/{id}/relations")
    Mono<SkillRelationsView> relations(@PathVariable UUID id) {
        return admin.relations(id);
    }

    /**
     * A22. Pre-flight check for a code the operator is about to use. Codes are unique across kinds,
     * so the console asks before rendering the upload step instead of failing mid-form.
     */
    @GetMapping("/code-availability")
    Mono<SkillCodeAvailabilityView> codeAvailability(@RequestParam String code) {
        return admin.codeAvailability(code);
    }

    /**
     * A27. Reads a YCPS archive the operator is about to create, so the create form can be filled
     * from the package instead of retyped.
     *
     * <p>Strictly a read: nothing is created, stored or audited, and the response answers the A22
     * code question in the same round trip. Errors use the same codes the upload path raises
     * ({@code ycps_package_invalid}, {@code ycps_package_unsafe_entry}, …), so the console shows the
     * operator the real reason a package will not be accepted before they have typed anything.
     */
    @PostMapping("/inspect-package")
    Mono<SkillPackageInspectView> inspectPackage(@RequestPart("file") FilePart file) {
        return admin.inspectPackage(file);
    }

    // ------------------------------------------------------------------ A23-A24: category dictionary

    /**
     * A23. One kind's category vocabulary with usage counts. {@code kind} is the package kind
     * ({@code SKILL}/{@code EXPERT}/{@code TEAM}); teams share the expert vocabulary.
     */
    @GetMapping("/categories")
    Mono<SkillCategoryListView> categories(@RequestParam(required = false) String kind) {
        return admin.listCategories(kind);
    }

    /** A23: add one category to a kind's vocabulary. */
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<SkillCategoryView> createCategory(
            @RequestBody CreateCategoryCommand command, @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.createCategory(command, actor(jwt)));
    }

    /**
     * A24. Remove one category. Refused with 409 {@code skill_category_in_use} while any live
     * package still carries it, and the refusal names those packages.
     */
    @DeleteMapping("/categories/{code}")
    Mono<Void> deleteCategory(
            @PathVariable String code,
            @RequestParam(required = false) String kind,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.deleteCategory(kind, code, actor(jwt)));
    }

    /**
     * A25. Rename one category. The gateway re-points every live package still carrying the old
     * name in the same transaction, so a renamed category never drops its packages into "未分类".
     */
    @PutMapping("/categories/{code}")
    Mono<SkillCategoryView> updateCategory(
            @PathVariable String code,
            @RequestParam(required = false) String kind,
            @RequestBody UpdateCategoryCommand command,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.updateCategory(kind, code, command, actor(jwt)));
    }

    /** A26: upload one category cover (multipart field {@code file}, PNG/JPEG/WebP/SVG; size capped by {@code gateway.skills.icon-max-bytes}). */
    @PostMapping("/categories/{code}/image")
    Mono<SkillCategoryView> uploadCategoryImage(
            @PathVariable String code,
            @RequestParam(required = false) String kind,
            @RequestPart("file") FilePart file,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.uploadCategoryImage(kind, code, file, actor(jwt)));
    }

    /** A26 companion: the cover bytes for the console preview (authenticated image stream). */
    @GetMapping("/categories/{code}/image")
    Mono<ResponseEntity<byte[]>> categoryImage(
            @PathVariable String code,
            @RequestParam(required = false) String kind) {
        return admin.categoryImage(kind, code)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mediaType()))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", IMAGE_CSP)
                        .body(image.content()));
    }

    /** A26 companion: clears one category cover so it falls back to the placeholder badge. */
    @DeleteMapping("/categories/{code}/image")
    Mono<SkillCategoryView> clearCategoryImage(
            @PathVariable String code,
            @RequestParam(required = false) String kind,
            @AuthenticationPrincipal Jwt jwt) {
        return changed(admin.clearCategoryImage(kind, code, actor(jwt)));
    }

    private <T> Mono<T> changed(Mono<T> operation) {
        return catalogEvents.publishAfter(operation);
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
