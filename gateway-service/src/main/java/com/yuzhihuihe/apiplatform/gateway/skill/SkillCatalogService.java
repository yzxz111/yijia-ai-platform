package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.MemberView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.CategorySummary;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillDetail;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillFileInfo;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillFileView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillList;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillSummary;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the public client catalog and authenticated skill details ({@code /v1/skills}) plus the
 * admin console mirror ({@code /console/gateway/skills}).
 *
 * <p>The source is selected by {@code gateway.skills.source}: {@code database} (default) reads
 * {@code gateway.skill} filtered by {@code status='PUBLISHED' AND deleted_at IS NULL} (design R-01),
 * {@code filesystem} reads only prebuilt {@code *.ycps.zip} artifacts. Package directories are
 * intentionally not assembled at runtime because a different ZIP implementation would change the
 * artifact digest recorded in exact dependency locks.
 */
@Service
public class SkillCatalogService {

    static final String UTF8_ENCODING = "utf-8";
    static final String BASE64_ENCODING = "base64";

    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final SkillProperties properties;
    private final ObjectMapper objectMapper;
    private final Path root;
    private final SkillRepository repository;
    private final SkillMemberRepository members;
    private final FileObjectStore objectStore;
    private final SkillCategoryRepository categories;
    /** Optional because filesystem rollback tests and deployments may not configure the profile row. */
    private final SkillExpertProfileService expertProfiles;
    private final LinkedHashMap<String, CachedArchive> archiveCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private long archiveCacheBytes;

    public SkillCatalogService(SkillProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, (SkillRepository) null, null, null, null, null);
    }

    @Autowired
    public SkillCatalogService(
            SkillProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<SkillRepository> repository,
            ObjectProvider<SkillMemberRepository> members,
            ObjectProvider<FileObjectStore> objectStore,
            ObjectProvider<SkillCategoryRepository> categories,
            ObjectProvider<SkillExpertProfileService> expertProfiles) {
        this(properties, objectMapper, repository.getIfAvailable(),
                members.getIfAvailable(), objectStore.getIfAvailable(),
                categories.getIfAvailable(), expertProfiles.getIfAvailable());
    }

    SkillCatalogService(
            SkillProperties properties, ObjectMapper objectMapper, SkillRepository repository) {
        this(properties, objectMapper, repository, null, null, null, null);
    }

    SkillCatalogService(
            SkillProperties properties,
            ObjectMapper objectMapper,
            SkillRepository repository,
            SkillMemberRepository members,
            FileObjectStore objectStore) {
        this(properties, objectMapper, repository, members, objectStore, null, null);
    }

    SkillCatalogService(
            SkillProperties properties,
            ObjectMapper objectMapper,
            SkillRepository repository,
            SkillMemberRepository members,
            FileObjectStore objectStore,
            SkillCategoryRepository categories) {
        this(properties, objectMapper, repository, members, objectStore, categories, null);
    }

    SkillCatalogService(
            SkillProperties properties,
            ObjectMapper objectMapper,
            SkillRepository repository,
            SkillMemberRepository members,
            FileObjectStore objectStore,
            SkillCategoryRepository categories,
            SkillExpertProfileService expertProfiles) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.members = members;
        this.objectStore = objectStore;
        this.categories = categories;
        this.expertProfiles = expertProfiles;
        this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
    }

    /**
     * Published catalog with optional category/kind filters and the OpenAI-style cursor window.
     * Filtering happens before cursor pagination so a kind-specific client never receives a short
     * or unstable page merely because other presentation kinds occupied the preceding rows.
     */
    public Mono<SkillList> list(
            String category, String kind, String clientVersion, Integer limit, String after) {
        return list(category, kind, clientVersion, limit, after, false);
    }

    /**
     * Desktop clients may retain unpublished entries in the catalogue so an unavailable card can
     * be rendered. Package metadata stays absent and all detail/download paths remain
     * published-only.
     */
    public Mono<SkillList> list(
            String category,
            String kind,
            String clientVersion,
            Integer limit,
            String after,
            boolean includeUnpublished) {
        return Mono.defer(() -> {
            int pageSize = window(limit);
            Set<String> kinds = kinds(kind);
            return source(includeUnpublished)
                    .flatMap(skills -> enrich(skills)
                            .map(enriched -> window(
                                    filter(enriched, category, kinds, clientVersion),
                                    pageSize,
                                    after)));
        });
    }

    public Mono<List<SkillSummary>> listSkills() {
        return source(false).flatMap(this::enrich);
    }

    public Mono<SkillDetail> loadSkill(String name) {
        return loadSkill(name, null);
    }

    /** R-06: a directly requested skill still answers 426 when the client is too old. */
    public Mono<SkillDetail> loadSkill(String name, String clientVersion) {
        return sourceDetail(name)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(detail -> tooOld(detail.minClientVersion(), clientVersion)
                        ? Mono.error(new ResponseStatusException(
                                HttpStatus.UPGRADE_REQUIRED, "client_version_too_low"))
                        : Mono.just(detail));
    }

    /** Loads an already-built YCPS artifact without repackaging or changing its digest. */
    Mono<FilesystemArchive> filesystemArchive(String name) {
        if (properties.databaseSource()) {
            return Mono.error(storageUnavailable());
        }
        if (name == null || !SKILL_NAME.matcher(name).matches()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_skill_name"));
        }
        return Mono.fromCallable(() -> rawArchive(name))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Cheap current visibility probe for an already snapshotted filesystem download session. */
    Mono<Boolean> filesystemPublished(String name) {
        if (properties.databaseSource() || name == null || !SKILL_NAME.matcher(name).matches()) {
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> {
                    try {
                        rawArchive(name);
                        return true;
                    } catch (ResponseStatusException exception) {
                        return false;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * C2 companion: the raw bytes of one catalog package's icon.
     *
     * <p>A roster entry is a reference to another expert package, so the member portrait a client
     * renders is that package's icon — {@link #icon(String)} with the member key as the code. The
     * dedicated per-member avatar endpoint is gone; there is nothing extra to serve.
     */
    public Mono<AvatarContent> icon(String code) {
        return icon(code, false);
    }

    Mono<AvatarContent> icon(String code, boolean publicCatalogOnly) {
        if (code == null || !SKILL_NAME.matcher(code).matches()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_skill_name"));
        }
        if (!properties.databaseSource() || repository == null || objectStore == null) {
            return Mono.error(iconNotFound());
        }
        // Anonymous storefront reads follow the visible C1 catalog: online packages plus
        // unpublished packages that the catalog can render as unavailable cards. Drafts and
        // deleted packages remain private. ClawHub packages have their own catalog and no public
        // artwork route here.
        Mono<UUID> skillId = publicCatalogOnly
                ? repository.publishedSkill(code)
                        .map(SkillRepository.PublishedSkill::id)
                        .switchIfEmpty(repository.skillByCode(code)
                                .filter(skill -> "UNPUBLISHED".equals(skill.status())
                                        && !"CLAWHUB_SKILL".equals(skill.sourceFormat()))
                                .map(SkillView::id))
                        .switchIfEmpty(Mono.error(notFound()))
                : repository.skillByCode(code)
                        .map(SkillView::id)
                        .switchIfEmpty(Mono.error(notFound()));
        return skillId
                .flatMap(id -> repository.iconObjectKey(id)
                        .switchIfEmpty(Mono.error(iconNotFound()))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(iconNotFound()))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> iconNotFound())
                                .map(content -> new AvatarContent(
                                        content, memberAvatarMediaType(objectKey)))));
    }

    /** The optional introduction illustration of a visible package; 404 when none was uploaded. */
    public Mono<AvatarContent> illustration(String code) {
        if (code == null || !SKILL_NAME.matcher(code).matches()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_skill_name"));
        }
        if (!properties.databaseSource() || repository == null || objectStore == null) {
            return Mono.error(illustrationNotFound());
        }
        return publicArtworkOwner(code)
                .flatMap(id -> repository.illustrationObjectKey(id)
                        .switchIfEmpty(Mono.error(illustrationNotFound()))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(illustrationNotFound()))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> illustrationNotFound())
                                .map(content -> new AvatarContent(
                                        content, memberAvatarMediaType(objectKey)))));
    }

    /** The optional feature/output example image of a visible package. */
    public Mono<AvatarContent> exampleImage(String code) {
        if (code == null || !SKILL_NAME.matcher(code).matches()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_skill_name"));
        }
        if (!properties.databaseSource() || repository == null || objectStore == null) {
            return Mono.error(exampleImageNotFound());
        }
        return publicArtworkOwner(code)
                .flatMap(id -> repository.exampleImageObjectKey(id)
                        .switchIfEmpty(Mono.error(exampleImageNotFound()))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(exampleImageNotFound()))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> exampleImageNotFound())
                                .map(content -> new AvatarContent(
                                        content, memberAvatarMediaType(objectKey)))));
    }

    /** Unpublished Experts retain presentation assets, not downloadable package access. */
    private Mono<UUID> publicArtworkOwner(String code) {
        return repository.publishedSkill(code)
                .map(SkillRepository.PublishedSkill::id)
                .switchIfEmpty(repository.skillByCode(code)
                        .filter(skill -> "UNPUBLISHED".equals(skill.status())
                                && "EXPERT".equalsIgnoreCase(skill.kind())
                                && !"CLAWHUB_SKILL".equals(skill.sourceFormat()))
                        .map(SkillView::id))
                .switchIfEmpty(Mono.error(notFound()));
    }

    boolean databaseSource() {
        return properties.databaseSource();
    }

    /**
     * The published category vocabulary of one package kind, for the desktop featured-scene cards.
     *
     * <p>Only categories that still have at least one published package are returned: a card without
     * an expert behind it would render as an empty entry, so the client never receives one. A category
     * with no uploaded cover reports {@code imageUrl=null} and the desktop falls back to the image it
     * ships with for the codes it knows. The list is database-only like the roster read, so the
     * filesystem rollback source never touches the database here.
     */
    public Mono<List<CategorySummary>> listCategories(String kind) {
        String scope = categoryScope(kind);
        if (!properties.databaseSource() || repository == null) {
            return Mono.just(List.of());
        }
        return categories
                .list(scope)
                .concatMap(row -> categories
                        .publishedUsageCount(scope, row.name())
                        .filter(count -> count > 0)
                        .map(count -> new CategorySummary(
                                row.code(), row.name(), row.nameEn(), row.sortOrder(),
                                row.imageObjectKey() == null || row.imageObjectKey().isBlank()
                                        ? null
                                        : "/v1/skill-categories/" + row.code() + "/image",
                                row.imageUpdatedAt())))
                .collectList();
    }

    /**
     * The cover bytes of one published category. 404 {@code skill_category_image_not_found} when the
     * category has no cover, so the desktop displays its neutral card background.
     */
    public Mono<AvatarContent> categoryImage(String kind, String code) {
        String scope = categoryScope(kind);
        String wanted = code == null ? null : code.trim();
        if (wanted == null || !SKILL_NAME.matcher(wanted).matches()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_skill_category"));
        }
        if (!properties.databaseSource() || categories == null || objectStore == null) {
            return Mono.error(categoryImageNotFound());
        }
        return categories
                .byCode(scope, wanted)
                .switchIfEmpty(Mono.error(categoryImageNotFound()))
                .flatMap(row -> categories
                        .publishedUsageCount(scope, row.name())
                        .filter(count -> count > 0)
                        .switchIfEmpty(Mono.error(categoryImageNotFound()))
                        .flatMap(count -> categories
                                .imageObjectKey(row.id())
                                .switchIfEmpty(Mono.error(categoryImageNotFound()))
                                .flatMap(objectKey -> objectStore
                                        .get(objectKey, properties.getIconMaxBytes().toBytes())
                                        .switchIfEmpty(Mono.error(categoryImageNotFound()))
                                        .onErrorMap(
                                                FileObjectMissingException.class,
                                                error -> categoryImageNotFound())
                                        .map(content -> new AvatarContent(
                                                content, categoryImageMediaType(objectKey))))));
    }

    private static ResponseStatusException categoryImageNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "skill_category_image_not_found");
    }

    /** Same extension rule as the skill icon, applied to the category cover key. */
    private static String categoryImageMediaType(String objectKey) {
        String key = objectKey.toLowerCase(Locale.ROOT);
        if (key.endsWith(".jpg") || key.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (key.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (key.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    /** Maps a package kind onto its vocabulary scope, mirroring the admin dictionary rule. */
    private static String categoryScope(String kind) {
        String value = kind == null ? null : kind.trim();
        if (value == null || value.isBlank()) {
            return "SKILL";
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return "EXPERT".equals(upper) || "TEAM".equals(upper) ? "EXPERT" : "SKILL";
    }

    /**
     * D5/E-08: the list reports {@code memberCount} but never the roster. The counts of one page are
     * read in a single grouped query through {@link SkillMemberRepository#countBySkillBatch}.
     *
     * <p>The enrichment is database-only: under {@code gateway.skills.source=filesystem} the rollback
     * source must not touch the database at all, otherwise a deployment that intentionally runs
     * without R2DBC (or with it stubbed) turns every console list request into a 500.
     */
    private Mono<List<SkillSummary>> enrich(List<SkillSummary> skills) {
        Mono<List<SkillSummary>> withMembers;
        if (!properties.databaseSource() || skills.isEmpty() || members == null || repository == null) {
            withMembers = Mono.just(skills);
        } else {
            withMembers = repository
                    .idsByCodes(skills.stream().map(SkillSummary::name).toList())
                    .flatMap(ids -> members
                            .countBySkillBatch(ids.values())
                            .map(counts -> {
                                Map<UUID, Long> byId = counts.stream().collect(
                                        java.util.stream.Collectors.toMap(
                                                SkillMemberRepository.MemberCount::skillId,
                                                SkillMemberRepository.MemberCount::count,
                                                (first, second) -> first));
                                return skills.stream()
                                        .map(skill -> withMemberCount(
                                                skill,
                                                ids.containsKey(skill.name())
                                                        ? (int) (long) byId.getOrDefault(
                                                                ids.get(skill.name()), 0L)
                                                        : 0))
                                        .toList();
                            }));
        }
        return withMembers.flatMap(this::enrichExpertPresentations);
    }

    private static SkillSummary withMemberCount(SkillSummary skill, int memberCount) {
        return new SkillSummary(
                skill.name(), skill.title(), skill.description(), skill.summary(), skill.version(),
                skill.category(), skill.tags(), skill.icon(), skill.highlights(), skill.status(),
                skill.latestVersion(), skill.publishedVersion(), skill.minClientVersion(),
                skill.sizeBytes(), skill.md5(), skill.sha256(), skill.revision(),
                skill.packageFormat(), skill.ycpsVersion(), skill.manifestSha256(),
                skill.dependencyLockSha256(), skill.downloadUrl(), skill.iconUrl(),
                skill.iconUpdatedAt(), skill.illustrationUrl(), skill.illustrationUpdatedAt(),
                skill.exampleImageUrl(), skill.exampleImageUpdatedAt(),
                skill.updatedAt(), skill.downloadCount(), skill.publishedAt(),
                skill.fileCount(), skill.totalBytes(), skill.kind(),
                skill.titleEn(), skill.summaryEn(), skill.descriptionEn(),
                skill.taskTypes(), skill.taskTypesEn(), memberCount, skill.files());
    }

    /** Adds only compact capability labels to C1; previews remain detail-only. */
    private Mono<List<SkillSummary>> enrichExpertPresentations(List<SkillSummary> skills) {
        if (!properties.databaseSource() || expertProfiles == null || skills.isEmpty()) {
            return Mono.just(skills);
        }
        return Flux.fromIterable(skills)
                .concatMap(skill -> {
                    if (!"EXPERT".equalsIgnoreCase(skill.kind())
                            || !"PUBLISHED".equalsIgnoreCase(skill.status())) {
                        return Mono.just(skill);
                    }
                    return expertProfiles.publicByCode(skill.name())
                            .map(value -> withPresentation(
                                    skill, value.presentation().taskTypes(), value.presentation().taskTypesEn()))
                            .onErrorResume(error -> Mono.just(skill));
                })
                .collectList();
    }

    private static SkillSummary withPresentation(
            SkillSummary skill, List<String> taskTypes, List<String> taskTypesEn) {
        return new SkillSummary(
                skill.name(), skill.title(), skill.description(), skill.summary(), skill.version(),
                skill.category(), skill.tags(), skill.icon(), skill.highlights(), skill.status(),
                skill.latestVersion(), skill.publishedVersion(), skill.minClientVersion(),
                skill.sizeBytes(), skill.md5(), skill.sha256(), skill.revision(),
                skill.packageFormat(), skill.ycpsVersion(), skill.manifestSha256(),
                skill.dependencyLockSha256(), skill.downloadUrl(), skill.iconUrl(),
                skill.iconUpdatedAt(), skill.illustrationUrl(), skill.illustrationUpdatedAt(),
                skill.exampleImageUrl(), skill.exampleImageUpdatedAt(), skill.updatedAt(),
                skill.downloadCount(), skill.publishedAt(), skill.fileCount(), skill.totalBytes(),
                skill.kind(), skill.titleEn(), skill.summaryEn(),
                skill.descriptionEn(), taskTypes, taskTypesEn, skill.memberCount(), skill.files());
    }

    private Mono<SkillDetail> enrich(SkillDetail detail) {
        // Same rule as the list enrichment: the filesystem rollback source never reads the roster.
        Mono<SkillDetail> withMembers;
        if (!properties.databaseSource() || members == null || repository == null) {
            withMembers = Mono.just(detail);
        } else {
            withMembers = repository
                    .skillByCode(detail.name())
                    .flatMap(skill -> members
                            .listBySkill(skill.id())
                            .collectList()
                            .map(rows -> withMembers(detail, rows))
                            .defaultIfEmpty(detail))
                    .defaultIfEmpty(detail);
        }
        return withMembers.flatMap(this::enrichExpertPresentation);
    }

    /** C2 carries the full profile only for Expert details. */
    private Mono<SkillDetail> enrichExpertPresentation(SkillDetail detail) {
        if (!properties.databaseSource() || expertProfiles == null
                || !"EXPERT".equalsIgnoreCase(detail.kind())
                || !"PUBLISHED".equalsIgnoreCase(detail.status())) {
            return Mono.just(detail);
        }
        return expertProfiles.publicByCode(detail.name())
                // A published Expert can predate the structured profile row.  The profile service
                // intentionally returns a scalar/catalog shell for that case; do not serialize
                // that incomplete shell as C2's full presentation, otherwise the desktop would
                // reject the response and lose its package/SQLite fallback chain.
                .map(value -> value.presentation())
                .filter(SkillCatalogService::isCompletePresentation)
                .map(presentation -> withPresentation(detail, presentation))
                .defaultIfEmpty(detail)
                .onErrorResume(error -> Mono.just(detail));
    }

    private static boolean isCompletePresentation(ExpertPresentation presentation) {
        return presentation != null
                && presentation.title() != null
                && presentation.summary() != null
                && presentation.description() != null
                && presentation.usageGuide() != null
                && !presentation.taskTypes().isEmpty()
                && !presentation.examplePrompts().isEmpty()
                && presentation.preview() != null
                && !presentation.preview().outputParagraphs().isEmpty();
    }

    private static SkillDetail withPresentation(SkillDetail detail, ExpertPresentation presentation) {
        ExpertPresentation complete = completePresentationAssets(presentation);
        return new SkillDetail(
                detail.object(), detail.name(), detail.title(), detail.description(), detail.summary(),
                detail.version(), detail.category(), detail.tags(), detail.icon(), detail.highlights(),
                detail.status(), detail.latestVersion(), detail.publishedVersion(), detail.minClientVersion(),
                detail.sizeBytes(), detail.md5(), detail.sha256(), detail.revision(), detail.packageFormat(),
                detail.ycpsVersion(), detail.manifestSha256(), detail.dependencyLockSha256(),
                detail.downloadUrl(), detail.iconUrl(), detail.iconUpdatedAt(),
                detail.illustrationUrl(), detail.illustrationUpdatedAt(),
                detail.exampleImageUrl(), detail.exampleImageUpdatedAt(),
                detail.updatedAt(), detail.fileCount(), detail.totalBytes(), detail.kind(), detail.titleEn(),
                detail.summaryEn(), detail.descriptionEn(), detail.taskTypes(), detail.taskTypesEn(),
                detail.memberCount(), detail.members(), complete, detail.files());
    }

    /** C2 keeps all three asset roles presence-visible so an empty role clears package artwork. */
    private static ExpertPresentation completePresentationAssets(ExpertPresentation presentation) {
        Map<String, String> assets = new LinkedHashMap<>(presentation.assets());
        assets.putIfAbsent("icon", "");
        assets.putIfAbsent("illustration", "");
        assets.putIfAbsent("exampleImage", "");
        return new ExpertPresentation(
                presentation.title(), presentation.titleEn(), presentation.summary(),
                presentation.summaryEn(), presentation.description(), presentation.descriptionEn(),
                presentation.taskTypes(), presentation.taskTypesEn(), presentation.usageGuide(),
                presentation.usageGuideEn(), presentation.examplePrompts(), presentation.examplePromptsEn(),
                presentation.preview(), presentation.previewEn(), Map.copyOf(assets),
                presentation.portableRevision(), presentation.rowVersion(), presentation.updatedAt(),
                presentation.canvas(), presentation.exampleImageLocale());
    }

    private static SkillDetail withMembers(
            SkillDetail detail, List<SkillMemberRepository.MemberRow> rows) {
        List<MemberView> views = SkillMemberViews.client(rows);
        return new SkillDetail(
                detail.object(), detail.name(), detail.title(), detail.description(), detail.summary(),
                detail.version(), detail.category(), detail.tags(), detail.icon(), detail.highlights(),
                detail.status(), detail.latestVersion(), detail.publishedVersion(),
                detail.minClientVersion(), detail.sizeBytes(), detail.md5(), detail.sha256(),
                detail.revision(), detail.packageFormat(), detail.ycpsVersion(),
                detail.manifestSha256(), detail.dependencyLockSha256(),
                detail.downloadUrl(), detail.iconUrl(), detail.iconUpdatedAt(),
                detail.illustrationUrl(), detail.illustrationUpdatedAt(),
                detail.exampleImageUrl(), detail.exampleImageUpdatedAt(),
                detail.updatedAt(), detail.fileCount(),
                detail.totalBytes(), detail.kind(), detail.titleEn(), detail.summaryEn(),
                detail.descriptionEn(), detail.taskTypes(), detail.taskTypesEn(),
                views.size(), views, detail.expertPresentation(), detail.files());
    }

    private static ResponseStatusException iconNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_icon_not_found");
    }

    private static ResponseStatusException illustrationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "skill_illustration_not_found");
    }

    private static ResponseStatusException exampleImageNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "skill_example_image_not_found");
    }

    static String memberAvatarMediaType(String objectKey) {
        String key = objectKey.toLowerCase(java.util.Locale.ROOT);
        if (key.endsWith(".jpg") || key.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (key.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (key.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private Mono<List<SkillSummary>> source(boolean includeUnpublished) {
        if (!properties.databaseSource()) {
            return Mono.fromCallable(this::scan).subscribeOn(Schedulers.boundedElastic());
        }
        if (repository == null) {
            return Mono.error(storageUnavailable());
        }
        return repository.listCatalog(includeUnpublished)
                .map(SkillRepository.PublishedSkill::summary)
                .collectList();
    }

    private Mono<SkillDetail> sourceDetail(String name) {
        if (name == null || !SKILL_NAME.matcher(name).matches()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_skill_name"));
        }
        if (!properties.databaseSource()) {
            return Mono.fromCallable(() -> read(name))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(this::enrich);
        }
        if (repository == null) {
            return Mono.error(storageUnavailable());
        }
        // C2 returns the roster; the filesystem rollback source simply has none (design 11.2).
        return repository.publishedSkill(name)
                .map(skill -> detail(skill.summary()))
                .flatMap(this::enrich);
    }

    private List<SkillSummary> filter(
            List<SkillSummary> skills,
            String category,
            Set<String> kinds,
            String clientVersion) {
        if (category != null && !category.isBlank()) {
            String wanted = category.strip();
            skills = skills.stream()
                    .filter(skill -> wanted.equals(skill.category()))
                    .toList();
        }
        if (!kinds.isEmpty()) {
            skills = skills.stream()
                    .filter(skill -> kinds.contains(normalizedKind(skill.kind())))
                    .toList();
        }
        if (clientVersion == null || !ClientVersion.valid(clientVersion)) {
            // R-07: an unknown client version is the most permissive case and only fails on download.
            return skills;
        }
        return skills.stream()
                .filter(skill -> "UNPUBLISHED".equals(skill.status())
                        || !tooOld(skill.minClientVersion(), clientVersion))
                .toList();
    }

    /** Accepts the same case-insensitive single/comma-separated values as the admin catalog. */
    private static Set<String> kinds(String kind) {
        if (kind == null || kind.isBlank()) {
            return Set.of();
        }
        Set<String> values = java.util.Arrays.stream(kind.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (values.isEmpty() || !SkillRepository.KINDS.containsAll(values)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_skill_kind");
        }
        return values;
    }

    private static String normalizedKind(String kind) {
        return kind == null || kind.isBlank()
                ? SkillRepository.KIND_SKILL
                : kind.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean tooOld(String minimum, String clientVersion) {
        if (minimum == null || minimum.isBlank() || minimum.equals("0.0.0")) {
            return false;
        }
        if (clientVersion == null || !ClientVersion.valid(clientVersion)) {
            return true;
        }
        return ClientVersion.compare(clientVersion, minimum) < 0;
    }

    private static SkillList window(List<SkillSummary> skills, int limit, String after) {
        int start = 0;
        if (after != null && !after.isBlank()) {
            String cursor = after.strip();
            int index = 0;
            while (index < skills.size() && !skills.get(index).name().equals(cursor)) {
                index++;
            }
            start = index >= skills.size() ? skills.size() : index + 1;
        }
        List<SkillSummary> window = skills.subList(start, skills.size());
        boolean hasMore = window.size() > limit;
        List<SkillSummary> page = hasMore ? window.subList(0, limit) : window;
        String next = hasMore ? page.get(page.size() - 1).name() : null;
        return new SkillList("list", List.copyOf(page), hasMore, next);
    }

    private static int window(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination");
        }
        return limit;
    }

    private static SkillDetail detail(SkillSummary summary) {
        return new SkillDetail(
                SkillDetail.SKILL_OBJECT,
                summary.name(),
                summary.title(),
                summary.description(),
                summary.summary(),
                summary.version(),
                summary.category(),
                summary.tags(),
                summary.icon(),
                summary.highlights(),
                summary.status(),
                summary.latestVersion(),
                summary.publishedVersion(),
                summary.minClientVersion(),
                summary.sizeBytes(),
                summary.md5(),
                summary.sha256(),
                summary.revision(),
                summary.packageFormat(),
                summary.ycpsVersion(),
                summary.manifestSha256(),
                summary.dependencyLockSha256(),
                summary.downloadUrl(),
                summary.iconUrl(),
                summary.iconUpdatedAt(),
                summary.illustrationUrl(),
                summary.illustrationUpdatedAt(),
                summary.exampleImageUrl(),
                summary.exampleImageUpdatedAt(),
                summary.updatedAt(),
                summary.fileCount(),
                summary.totalBytes(),
                summary.kind(),
                summary.titleEn(),
                summary.summaryEn(),
                summary.descriptionEn(),
                summary.taskTypes(),
                summary.taskTypesEn(),
                0,
                List.of(),
                null,
                List.of());
    }

    private List<SkillSummary> scan() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Map<String, SkillSummary> skills = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (!Files.isSymbolicLink(entry) && Files.isRegularFile(entry)
                        && name.endsWith(".ycps.zip")) {
                    FilesystemArchive archive = rawArchive(entry, null);
                    skills.putIfAbsent(archive.name(), summary(
                            archive, List.of(), lastModified(entry)));
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            throw storageUnavailable();
        }
        return List.copyOf(skills.values());
    }

    private SkillDetail read(String name) {
        FilesystemArchive archive = rawArchive(name);
        return detail(summary(archive, List.of(), System.currentTimeMillis()), List.of());
    }

    private SkillDetail detail(SkillSummary summary, List<MaterializedFile> files) {
        List<SkillFileView> views = files.stream().map(SkillCatalogService::view).toList();
        return new SkillDetail(
                SkillDetail.SKILL_OBJECT,
                summary.name(),
                summary.title(),
                summary.description(),
                summary.summary(),
                summary.version(),
                summary.category(),
                summary.tags(),
                summary.icon(),
                summary.highlights(),
                summary.status(),
                summary.latestVersion(),
                summary.publishedVersion(),
                summary.minClientVersion(),
                summary.sizeBytes(),
                summary.md5(),
                summary.sha256(),
                summary.revision(),
                summary.packageFormat(),
                summary.ycpsVersion(),
                summary.manifestSha256(),
                summary.dependencyLockSha256(),
                summary.downloadUrl(),
                summary.iconUrl(),
                summary.iconUpdatedAt(),
                summary.illustrationUrl(),
                summary.illustrationUpdatedAt(),
                summary.exampleImageUrl(),
                summary.exampleImageUpdatedAt(),
                summary.updatedAt(),
                summary.fileCount(),
                summary.totalBytes(),
                summary.kind(),
                summary.titleEn(),
                summary.summaryEn(),
                summary.descriptionEn(),
                summary.taskTypes(),
                summary.taskTypesEn(),
                0,
                List.of(),
                null,
                views);
    }

    private List<MaterializedFile> materialize(Path directory) {
        List<Path> paths = enumerate(directory);
        long maxFileBytes = properties.getMaxFileBytes().toBytes();
        long maxTotalBytes = properties.getMaxTotalBytes().toBytes();
        List<MaterializedFile> files = new ArrayList<>(paths.size());
        long totalBytes = 0;
        for (Path path : paths) {
            byte[] content;
            try {
                content = Files.readAllBytes(path);
            } catch (IOException exception) {
                throw storageUnavailable();
            }
            if (content.length > maxFileBytes) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "skill_file_too_large");
            }
            totalBytes += content.length;
            if (totalBytes > maxTotalBytes) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "skill_too_large");
            }
            files.add(new MaterializedFile(
                    relative(directory, path), content, sha256(content), lastModified(path)));
        }
        return List.copyOf(files);
    }

    private List<Path> enumerate(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> paths = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isHidden(directory.relativize(path)))
                .sorted(Comparator.comparing(path -> relative(directory, path)))
                    .toList();
            if (paths.size() > properties.getMaxFilesPerSkill()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "skill_too_many_files");
            }
            return paths;
        } catch (IOException | UncheckedIOException exception) {
            throw storageUnavailable();
        }
    }

    private SkillSummary summary(String name, Path directory, List<MaterializedFile> files) {
        FilesystemArchive archive = archive(name, directory, files);
        return summary(archive, files, updatedAt(directory, files));
    }

    private SkillSummary summary(
            FilesystemArchive archive, List<MaterializedFile> files, long updatedAt) {
        long totalBytes = files.stream().mapToLong(file -> file.content().length).sum();
        List<SkillFileInfo> infos = files.stream()
                .map(file -> new SkillFileInfo(file.path(), file.content().length, file.sha256()))
                .toList();
        return new SkillSummary(
                archive.name(),
                archive.name(),
                archive.description(),
                archive.description(),
                archive.version(),
                null,
                List.of(),
                null,
                List.of(),
                "PUBLISHED",
                archive.version(),
                archive.version(),
                archive.minClientVersion(),
                archive.sizeBytes(),
                archive.md5(),
                archive.sha256(),
                archive.revision(),
                archive.packageFormat(),
                archive.ycpsVersion(),
                archive.manifestSha256(),
                archive.dependencyLockSha256(),
                "/v1/skills/" + archive.name() + "/downloads",
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.ofEpochMilli(updatedAt),
                0L,
                Instant.ofEpochMilli(updatedAt),
                infos.size(),
                totalBytes,
                archive.packageKind(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                0,
                infos);
    }

    private static long updatedAt(Path directory, List<MaterializedFile> files) {
        long latest = files.stream().mapToLong(MaterializedFile::lastModified).max()
                .orElseGet(() -> lastModified(directory));
        return Math.max(latest, lastModified(directory));
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return System.currentTimeMillis();
        }
    }

    private static SkillFileView view(MaterializedFile file) {
        boolean text = isUtf8Text(file.content());
        String content = text
                ? new String(file.content(), StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(file.content());
        return new SkillFileView(
                file.path(),
                text ? UTF8_ENCODING : BASE64_ENCODING,
                file.content().length,
                file.sha256(),
                content);
    }

    private static boolean isUtf8Text(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static boolean isHidden(Path relativePath) {
        for (Path segment : relativePath) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path directory, Path path) {
        return directory.relativize(path).toString().replace('\\', '/');
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha_256_unavailable", exception);
        }
    }

    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("md5_unavailable", exception);
        }
    }

    private FilesystemArchive archive(
            String name,
            Path directory,
            List<MaterializedFile> visibleFiles) {
        List<ArchiveFile> entries = new ArrayList<>(visibleFiles.size());
        for (MaterializedFile file : visibleFiles) {
            entries.add(new ArchiveFile(file.path(), file.content()));
        }
        entries.sort(Comparator.comparing(ArchiveFile::path));
        String fingerprint = archiveFingerprint(entries);
        synchronized (archiveCache) {
            CachedArchive cached = archiveCache.get(name);
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                return cached.archive();
            }
        }
        byte[] content;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (ArchiveFile file : entries) {
                ZipEntry entry = new ZipEntry(file.path());
                // A fixed timestamp makes repeated packaging byte-for-byte stable across edits to
                // filesystem metadata and across operating systems.
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.content());
                zip.closeEntry();
            }
            zip.finish();
            content = output.toByteArray();
        } catch (IOException exception) {
            throw storageUnavailable();
        }
        if (content.length > properties.getMaxPackageBytes().toBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "skill_package_too_large");
        }
        FilesystemArchive created = validatedArchive(content, name);
        cache(name, fingerprint, created);
        return created;
    }

    private String archiveFingerprint(List<ArchiveFile> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ArchiveFile entry : entries) {
                byte[] path = entry.path().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(entry.content().length).array());
                digest.update(entry.content());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha_256_unavailable", exception);
        }
    }

    private FilesystemArchive rawArchive(String code) {
        if (!Files.isDirectory(root)) {
            throw notFound();
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                if (!Files.isSymbolicLink(entry) && Files.isRegularFile(entry)
                        && entry.getFileName().toString().endsWith(".ycps.zip")) {
                    FilesystemArchive archive = rawArchive(entry, code);
                    if (archive != null) {
                        return archive;
                    }
                }
            }
        } catch (IOException exception) {
            throw storageUnavailable();
        }
        throw notFound();
    }

    private FilesystemArchive rawArchive(Path file, String wantedCode) {
        try {
            if (Files.size(file) > properties.getMaxPackageBytes().toBytes()) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "ycps_package_too_large");
            }
            byte[] content = Files.readAllBytes(file);
            FilesystemArchive archive = validatedArchive(content, null);
            return wantedCode == null || wantedCode.equals(archive.name()) ? archive : null;
        } catch (IOException exception) {
            throw storageUnavailable();
        }
    }

    private FilesystemArchive validatedArchive(byte[] content, String expectedCode) {
        Path temp = null;
        try {
            temp = Files.createTempFile("ycps-filesystem-", ".ycps.zip");
            Files.write(temp, content);
            SkillPackageInspector.Report report = SkillPackageInspector.create()
                    .inspect(temp, properties, expectedCode);
            String artifactSha256 = sha256(content);
            return new FilesystemArchive(
                    report.code(),
                    report.version(),
                    report.minClientVersion(),
                    report.pluginManifest().path("description").asText(),
                    content,
                    content.length,
                    md5(content),
                    artifactSha256,
                    1,
                    SkillPackageInspector.PACKAGE_FORMAT,
                    report.ycpsVersion(),
                    report.kind(),
                    report.manifestSha256(),
                    report.dependencyLockSha256(),
                    report.runtimeManifest().path("dependencies").deepCopy());
        } catch (IOException exception) {
            throw storageUnavailable();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // OS temp cleanup is the fallback.
                }
            }
        }
    }

    private void cache(String name, String fingerprint, FilesystemArchive archive) {
        long maximumBytes = properties.getFilesystemArchiveCacheMaxBytes().toBytes();
        if (archive.sizeBytes() > maximumBytes) {
            return;
        }
        synchronized (archiveCache) {
            CachedArchive previous = archiveCache.remove(name);
            if (previous != null) {
                archiveCacheBytes -= previous.archive().sizeBytes();
            }
            int maximumEntries = properties.getFilesystemArchiveCacheMaxEntries();
            Iterator<Map.Entry<String, CachedArchive>> iterator = archiveCache.entrySet().iterator();
            while (iterator.hasNext()
                    && (archiveCache.size() >= maximumEntries
                            || archiveCacheBytes + archive.sizeBytes() > maximumBytes)) {
                CachedArchive evicted = iterator.next().getValue();
                archiveCacheBytes -= evicted.archive().sizeBytes();
                iterator.remove();
            }
            archiveCache.put(name, new CachedArchive(fingerprint, archive));
            archiveCacheBytes += archive.sizeBytes();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_not_found");
    }

    private static ResponseStatusException storageUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable");
    }

    /** Avatar bytes plus the media type derived from the stored object key extension. */
    public record AvatarContent(byte[] content, String mediaType) {}

    /** Immutable, prebuilt YCPS ZIP returned by the filesystem backend. */
    record FilesystemArchive(
            String name,
            String version,
            String minClientVersion,
            String description,
            byte[] content,
            long sizeBytes,
            String md5,
            String sha256,
            int revision,
            String packageFormat,
            String ycpsVersion,
            String packageKind,
            String manifestSha256,
            String dependencyLockSha256,
            com.fasterxml.jackson.databind.JsonNode dependencies) {}

    private record MaterializedFile(String path, byte[] content, String sha256, long lastModified) { }

    private record ArchiveFile(String path, byte[] content) { }

    private record CachedArchive(String fingerprint, FilesystemArchive archive) { }

}
