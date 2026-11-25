package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.CreateCategoryCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.UpdateCategoryCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.CreateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.MemberCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.ReplaceMembersCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.SkillListQuery;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminRequests.UpdateSkillCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.DownloadView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.DeletedSkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.MemberView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCodeAvailabilityView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCodeHolderView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCategoryListView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillCategoryView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillDependencyView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillDependentEntry;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillDependentsView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillPackageInspectView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillRelationEdgeView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillRelationsView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionDependenciesView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillCatalogViews.SkillDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Skill administration (design 5.1 A1-A14): input validation, the
 * {@code DRAFT → PUBLISHED → UNPUBLISHED → DELETED} state machine, {@code row_version} optimistic
 * locking, idempotency and the {@code gateway.admin_operation_audit} trail. All business decisions
 * live here; SQL lives in the repositories.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillAdminService {

    static final String ACTION_CREATE = "skill.create";
    static final String ACTION_UPDATE = "skill.update";
    static final String ACTION_PUBLISH = "skill.publish";
    static final String ACTION_UNPUBLISH = "skill.unpublish";
    static final String ACTION_DELETE = "skill.delete";
    static final String ACTION_RESTORE = "skill.restore";
    static final String ACTION_PURGE = "skill.purge";
    static final String ACTION_VERSION_PUBLISH = "skill.version.publish";
    static final String ACTION_VERSION_DELETE = "skill.version.delete";
    static final String ACTION_MEMBERS_REPLACE = "skill.members.replace";
    static final String ACTION_PACKAGE_DOWNLOAD = "skill.package.download";
    static final String ACTION_PACKAGE_BUNDLE_DOWNLOAD = "skill.package.bundle.download";

    private static final Pattern CODE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    /**
     * V39 member keys are wider than skill codes: the console and the desktop roster both allow a
     * dotted or underscored handle such as {@code dave.chen} or {@code Zhang_San}, while still
     * refusing a leading symbol.
     */
    private static final Pattern MEMBER_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern CLIENT_VERSION = Pattern.compile("^\\d{4}\\.\\d{1,2}(\\.\\d{1,2})?$");
    /** Administrative metadata caps kept at the database boundary. */
    private static final int NAME_MAX = 128;
    private static final int SUMMARY_MAX = 512;
    private static final int DESCRIPTION_MAX = 4000;
    static final int SKILL_DESCRIPTION_MAX = 1000;
    private static final int VERSION_MAX = 32;
    private static final int ICON_MAX = 8;
    private static final int CATEGORY_MAX = 32;
    private static final int MAX_TAGS = 16;
    static final int TAG_MAX = 64;
    /** V39 member columns. */
    private static final int MEMBER_KEY_MAX = 64;
    private static final int MEMBER_NAME_MAX = 64;
    private static final int MEMBER_PROFESSION_MAX = 64;
    private static final int MEMBER_SORT_MAX = 100_000;
    private static final int DEPENDENCY_CLOSURE_MAX_PACKAGES = 128;
    private static final int DEPENDENCY_CLOSURE_MAX_DEPTH = 32;
    static final String MEMBER_ROLE_LEAD = "LEAD";
    static final String MEMBER_ROLE_MEMBER = "MEMBER";
    private static final List<String> ICON_TYPES =
            List.of("image/png", "image/jpeg", "image/webp", "image/svg+xml");
    private static final List<String> SKILL_KINDS = List.of("SKILL", "EXPERT", "TEAM");

    /** A23: the derived/stored category code shape, mirroring the database constraint. */
    private static final Pattern CATEGORY_CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final SkillMemberRepository members;
    private final SkillDownloadRepository downloads;
    private final SkillCategoryRepository categories;
    private final SkillPackageIngestService ingest;
    private final FileObjectStore objectStore;
    private final SkillProperties properties;
    private final SkillRetentionCleanupJob retentionCleanup;
    private final TransactionalOperator transactions;

    public SkillAdminService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillMemberRepository members,
            SkillDownloadRepository downloads,
            SkillCategoryRepository categories,
            SkillPackageIngestService ingest,
            FileObjectStore objectStore,
            SkillProperties properties,
            SkillRetentionCleanupJob retentionCleanup,
            TransactionalOperator transactions) {
        this.skills = skills;
        this.versions = versions;
        this.members = members;
        this.downloads = downloads;
        this.categories = categories;
        this.ingest = ingest;
        this.objectStore = objectStore;
        this.properties = properties;
        this.retentionCleanup = retentionCleanup;
        this.transactions = transactions;
    }

    /** A1. */
    public Mono<PageResult<SkillView>> list(SkillListQuery query) {
        return skills.listSkills(query);
    }

    /** A3: detail including the version table and the full roster (D5: lists stay lightweight). */
    public Mono<SkillView> detail(UUID id) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> Mono.zip(
                                versions
                                        .listVersions(id, skill.publishedVersion())
                                        .collectList(),
                                members.listBySkill(id).collectList())
                        .flatMap(both -> skills
                                .skillManifest(id)
                                .map(manifest ->
                                        // D5/E-08: the list projection drops the manifest, so only the
                                        // detail read re-attaches the read-only plugin.json document.
                                        withVersions(
                                                withMembers(skill, both.getT2()),
                                                both.getT1(),
                                                manifest))));
    }

    /** A14: the client DTO for the same skill (design 5.1). */
    public Mono<SkillDetail> preview(UUID id) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> {
                    Mono<SkillVersionView> online = skill.publishedVersion() == null
                            ? Mono.empty()
                            : versions.versionByNumber(id, skill.publishedVersion(), skill.publishedVersion());
                    return Mono.zip(
                                    online.map(version -> detail(skill, version))
                                            .defaultIfEmpty(detail(skill, null)),
                                    members.listBySkill(id).collectList())
                            .map(both -> withMembers(both.getT1(), both.getT2()));
                });
    }

    /** A2. */
    public Mono<SkillView> create(CreateSkillCommand command, UUID actor) {
        String kind = skillKind(command.kind());
        validate(kind, command.code(), command.name(), command.nameEn(), command.category(), command.tags(),
                command.summary(), command.summaryEn(), command.description(), command.descriptionEn(),
                command.icon(), command.minClientVersion(), command.sortWeight());
        Instant now = Instant.now();
        Mono<UUID> inserted = skills
                .codeExists(command.code().strip())
                .flatMap(exists -> exists
                        ? codeConflict(command.code().strip())
                        : skills.insertSkill(command, actor, now))
                .onErrorMap(DuplicateKeyException.class,
                        error -> conflict("skill_code_conflict"));
        return transactions
                .transactional(inserted.flatMap(id -> skills
                        .audit(actor, ACTION_CREATE, id, Map.of(
                                "code", command.code().strip(),
                                "name", command.name().strip(),
                                "kind", kind,
                                "status", "DRAFT"))
                        .thenReturn(id)))
                .flatMap(this::detail);
    }

    /**
     * The code namespace is global across kinds (YCPS authoring standard §4.1: a published code is
     * never reused for another package), so a collision is always fatal. The holder is looked up and
     * reported because "pick another code" alone leaves the operator unable to tell whether the
     * clash is with a skill, an expert or a team.
     */
    private Mono<UUID> codeConflict(String code) {
        return skills
                .skillByCode(code)
                .map(holder -> Mono.<UUID>error(SkillAdminException.conflict(
                        "skill_code_conflict",
                        "该编码已被占用。",
                        Map.of(
                                "code", code,
                                "occupiedBy", Map.of(
                                        "id", holder.id().toString(),
                                        "code", holder.code(),
                                        "name", holder.name() == null ? "" : holder.name(),
                                        "kind", holder.kind() == null ? "SKILL" : holder.kind(),
                                        "status", holder.status() == null ? "" : holder.status())))))
                .defaultIfEmpty(Mono.error(conflict("skill_code_conflict")))
                .flatMap(mono -> mono);
    }

    /** A2 companion: lets the console warn before an upload is attempted. */
    public Mono<SkillCodeAvailabilityView> codeAvailability(String code) {
        String normalized = code == null ? "" : code.strip();
        if (normalized.isEmpty()) {
            throw invalid("编码不能为空。");
        }
        return skills
                .skillByCode(normalized)
                .map(holder -> new SkillCodeAvailabilityView(
                        normalized, false,
                        new SkillCodeHolderView(
                                holder.id(),
                                holder.code(),
                                holder.name() == null ? "" : holder.name(),
                                holder.kind() == null ? "SKILL" : holder.kind(),
                                holder.status() == null ? "" : holder.status())))
                .defaultIfEmpty(new SkillCodeAvailabilityView(normalized, true, null));
    }

    /** A4: optimistic lock on {@code row_version}; {@code kind} is immutable after create (E-01). */
    public Mono<SkillView> update(UUID id, UpdateSkillCommand command, UUID actor) {
        if (command.rowVersion() == null) {
            throw invalid("row_version 必填。");
        }
        Instant now = Instant.now();
        Mono<UUID> updated = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> {
                    requireSameKind(current, command.kind());
                    validate(current.kind(), null, command.name(), command.nameEn(), command.category(), command.tags(),
                            command.summary(), command.summaryEn(), command.description(), command.descriptionEn(),
                            command.icon(), command.minClientVersion(), command.sortWeight());
                    return skills
                            .updateSkill(id, command, actor, now)
                            .filter(count -> count == 1L)
                            .switchIfEmpty(Mono.error(conflict("skill_concurrent_modification")))
                            .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                    "code", current.code(),
                                    "rowVersion", command.rowVersion(),
                                    "fields", changedFields(current, command))));
                })
                .thenReturn(id);
        return transactions.transactional(updated).flatMap(this::detail);
    }

    /** A5: icon image upload; {@code icon} (short text) and the image never overwrite each other. */
    public Mono<SkillView> uploadIcon(UUID id, FilePart file, UUID actor) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> readIcon(file)
                        .flatMap(content -> {
                            String extension = extension(file);
                            String objectKey = "clean/skills/" + skill.id() + "/" + skill.code()
                                    + "/icon/" + UUID.randomUUID() + extension;
                            return objectStore
                                    .put(objectKey, content, file.headers().getContentType() == null
                                            ? "application/octet-stream"
                                            : file.headers().getContentType().toString())
                                    .then(Mono.defer(() -> switchIconPointer(
                                            id, objectKey, actor)))
                                    .onErrorResume(error -> deleteBestEffort(objectKey)
                                            .then(Mono.error(error)))
                                    .flatMap(change -> deleteReplacedObject(change)
                                            .thenReturn(change.id()));
                        }))
                .flatMap(this::detail);
    }

    public Mono<AvatarContent> icon(UUID id) {
        return skills.skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .then(skills.iconObjectKey(id)
                        .switchIfEmpty(Mono.error(iconNotFound()))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(iconNotFound()))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> iconNotFound())
                                .map(content -> new AvatarContent(content, mediaType(objectKey)))));
    }

    /**
     * A5 companion: clears the uploaded icon so the record falls back to its text badge.
     *
     * <p>Idempotent by design — clearing an icon that is already absent still returns the current
     * detail rather than 404. The console shows a 清空 button on every row, so a second click (or a
     * stale list) must not surface an error. The object-storage blob is removed only after the
     * pointer is committed, and best-effort: a storage hiccup must not fail the operator's action.
     */
    public Mono<SkillView> clearIcon(UUID id, UUID actor) {
        Mono<ObjectPointerChange> cleared = transactions.transactional(skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> skills
                        .iconObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(previous -> skills
                                .updateIconObjectKey(id, null, actor, Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_concurrent_modification")))
                                .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                        "code", skill.code(),
                                        "field", "icon_object_key",
                                        "cleared", true)))
                                .thenReturn(new ObjectPointerChange(
                                        id, blankToNull(previous), null)))));
        return cleared
                .flatMap(change -> deleteReplacedObject(change).thenReturn(change.id()))
                .flatMap(this::detail);
    }

    // ------------------------------------------------------------------ A15: expert roster

    /**
     * A15 / V7: whole-roster replacement. The console edits the table and saves once, so the call is
     * idempotent — sending the same body twice leaves the same rows.
     *
     * <p>A member is a reference to another expert package: a newly added {@code memberKey} must name
     * a published {@code EXPERT}, so the team can render that member's portrait from the expert's own
     * icon. Keys already on the roster are grandfathered in — a package seeds its own {@code agents}
     * from {@code plugin.json}, and those ids are the package's own runtime agents, not necessarily
     * separately published packages. Without that exemption, uploading a team would seed rows the
     * console could then never save again.
     */
    public Mono<SkillView> replaceMembers(UUID id, ReplaceMembersCommand command, UUID actor) {
        List<SkillMemberRepository.Member> requested = memberRoster(command);
        Mono<UUID> replaced = transactions.transactional(skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> members
                        .listBySkill(id)
                        .map(SkillMemberRepository.MemberRow::memberKey)
                        .collectList()
                        .flatMap(existing -> requirePublishedExperts(requested, existing))
                        .then(members
                                .replaceAll(id, requested)
                                .then(skills.audit(actor, ACTION_MEMBERS_REPLACE, id, Map.of(
                                        "code", current.code(),
                                        "memberCount", requested.size(),
                                        "members", requested.stream()
                                                .map(SkillMemberRepository.Member::memberKey)
                                                .toList(),
                                        "lead", leadKey(requested))))
                                .thenReturn(current.id()))));
        return replaced.flatMap(this::detail);
    }

    /**
     * Every **newly added** roster entry must name a published expert package (design 3.1: "成员只能
     * 来自已上架的专家"). Entries already on the roster are accepted as-is so a package-seeded roster
     * stays editable; removing and re-adding the same key still has to pass this check.
     */
    private Mono<Void> requirePublishedExperts(
            List<SkillMemberRepository.Member> roster, List<String> alreadyOnRoster) {
        List<String> keys = roster.stream().map(SkillMemberRepository.Member::memberKey).toList();
        List<String> added = keys.stream()
                .filter(key -> !alreadyOnRoster.contains(key))
                .toList();
        if (added.isEmpty()) {
            return Mono.empty();
        }
        return skills
                .publishedExpertCodes(added)
                .collectList()
                .flatMap(published -> {
                    List<String> unknown = added.stream()
                            .filter(key -> !published.contains(key))
                            .toList();
                    if (!unknown.isEmpty()) {
                        return Mono.error(invalidMember(
                                "成员标识必须是已上架的专家，未找到：" + String.join("、", unknown) + "。"));
                    }
                    return Mono.empty();
                });
    }

    /**
     * A19: upload the package introduction illustration (optional, one per package).
     *
     * <p>Same shape as {@link #uploadIcon}: the object key is immutable
     * ({@code clean/skills/{skillId}/{code}/illustration/{uuid}.{ext}}), so a successful object PUT
     * can never silently alter the bytes behind the old database pointer if the later pointer
     * update fails. Including the skill id prevents a skill that reuses the code from colliding
     * with retained objects of the deleted row.
     */
    public Mono<SkillView> uploadIllustration(UUID id, FilePart file, UUID actor) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> readIllustration(file)
                        .flatMap(content -> {
                            String objectKey = illustrationKey(skill.id(), skill.code(), extension(file));
                            return objectStore
                                    .put(objectKey, content, file.headers().getContentType() == null
                                            ? "application/octet-stream"
                                            : file.headers().getContentType().toString())
                                    .then(Mono.defer(() -> switchIllustrationPointer(
                                            id, objectKey, actor)))
                                    .onErrorResume(error -> deleteBestEffort(objectKey)
                                            .then(Mono.error(error)))
                                    .flatMap(change -> deleteReplacedObject(change)
                                            .thenReturn(change.id()));
                        }))
                .flatMap(this::detail);
    }

    /** A19 companion: the illustration bytes for the console preview; 404 when there is none. */
    public Mono<AvatarContent> illustration(UUID id) {
        return skills.skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .then(skills.illustrationObjectKey(id)
                        .switchIfEmpty(Mono.error(illustrationNotFound()))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(illustrationNotFound()))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> illustrationNotFound())
                                .map(content -> new AvatarContent(content, mediaType(objectKey)))));
    }

    /**
     * A19 companion: clears the illustration so the package falls back to shipping no artwork.
     * Idempotent — a package that never had one returns its current view instead of 404.
     */
    public Mono<SkillView> clearIllustration(UUID id, UUID actor) {
        Mono<ObjectPointerChange> cleared = transactions.transactional(skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> skills
                        .illustrationObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(current -> skills
                                .updateIllustrationObjectKey(id, null, actor, Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(
                                        conflict("skill_concurrent_modification")))
                                .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                        "code", skill.code(),
                                        "field", "illustration_object_key",
                                        "cleared", true)))
                                .thenReturn(new ObjectPointerChange(id, current, null)))));
        return cleared
                .flatMap(change -> deleteReplacedObject(change).thenReturn(change.id()))
                .flatMap(this::detail);
    }

    /** Uploads/replaces the optional image that demonstrates an expert's features or output. */
    public Mono<SkillView> uploadExampleImage(UUID id, FilePart file, UUID actor) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> readExampleImage(file)
                        .flatMap(content -> {
                            String objectKey = exampleImageKey(skill.id(), skill.code(), extension(file));
                            return objectStore
                                    .put(objectKey, content, file.headers().getContentType() == null
                                            ? "application/octet-stream"
                                            : file.headers().getContentType().toString())
                                    .then(Mono.defer(() -> switchExampleImagePointer(
                                            id, objectKey, actor)))
                                    .onErrorResume(error -> deleteBestEffort(objectKey)
                                            .then(Mono.error(error)))
                                    .flatMap(change -> deleteReplacedObject(change)
                                            .thenReturn(change.id()));
                        }))
                .flatMap(this::detail);
    }

    /** Reads the optional example image for an authenticated console preview. */
    public Mono<AvatarContent> exampleImage(UUID id) {
        return skills.skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .then(skills.exampleImageObjectKey(id)
                        .switchIfEmpty(Mono.error(exampleImageNotFound()))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(exampleImageNotFound()))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> exampleImageNotFound())
                                .map(content -> new AvatarContent(content, mediaType(objectKey)))));
    }

    /** Clears the example image. The operation is idempotent. */
    public Mono<SkillView> clearExampleImage(UUID id, UUID actor) {
        Mono<ObjectPointerChange> cleared = transactions.transactional(skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> skills
                        .exampleImageObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(current -> skills
                                .updateExampleImageObjectKey(id, null, actor, Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(
                                        conflict("skill_concurrent_modification")))
                                .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                        "code", skill.code(),
                                        "field", "example_image_object_key",
                                        "cleared", true)))
                                .thenReturn(new ObjectPointerChange(id, current, null)))));
        return cleared
                .flatMap(change -> deleteReplacedObject(change).thenReturn(change.id()))
                .flatMap(this::detail);
    }

    /** A6: upload a new version package (design 6.1). */
    public Mono<SkillVersionView> uploadVersion(
            UUID id,
            String version,
            String changelog,
            String minClientVersion,
            FilePart file,
            UUID actor) {
        if (version == null || version.strip().length() > VERSION_MAX
                || !VERSION.matcher(version.strip()).matches()) {
            throw invalid("版本号格式应形如 1.3.0。");
        }
        if (changelog != null && changelog.length() > 2000) {
            throw invalid("更新说明最多 2000 字。");
        }
        if (minClientVersion != null && !minClientVersion.isBlank()
                && !CLIENT_VERSION.matcher(minClientVersion.strip()).matches()) {
            throw invalid("最低客户端版本格式应形如 2026.6.0。");
        }
        return skills.skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> requireYcps(skill)
                        .then(ingest.upload(
                                id, version.strip(), changelog, trim(minClientVersion), file, actor)));
    }

    /**
     * A7: replace the package of an existing version (revision + 1, new object key).
     *
     * <p>When the replaced version is the online one, the new revision is re-promoted immediately.
     * A replacement lands as {@code READY} (the old package keeps serving until the new one passes
     * scanning), so without this the skill would be left with no online version at all — the
     * console's "替换包" action is meant to swap the live package in place, not to take it offline.
     */
    public Mono<SkillVersionView> replacePackage(
            UUID id, UUID versionId, String changelog, String minClientVersion, FilePart file, UUID actor) {
        if (changelog != null && changelog.length() > 2000) {
            throw invalid("更新说明最多 2000 字。");
        }
        if (minClientVersion != null && !minClientVersion.isBlank()
                && !CLIENT_VERSION.matcher(minClientVersion.strip()).matches()) {
            throw invalid("最低客户端版本格式应形如 2026.6.0。");
        }
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(row -> requireYcps(row).then(versions
                        .version(versionId, row.publishedVersion())
                        .filter(existing -> existing.skillId().equals(row.id()))
                        .map(SkillVersionView::online)
                        .defaultIfEmpty(false)
                        .flatMap(wasOnline -> ingest
                                .replace(id, versionId, changelog, trim(minClientVersion), file, actor)
                                .flatMap(replaced -> wasOnline
                                        ? publishVersion(id, versionId, actor).thenReturn(replaced)
                                        : Mono.just(replaced)))));
    }

    /** A8: make one version the online version. */
    public Mono<SkillVersionView> publishVersion(UUID id, UUID versionId, UUID actor) {
        Mono<UUID> published = transactions.transactional(skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> requireYcps(skill).then(versions
                        .version(versionId, skill.publishedVersion())
                        .filter(version -> version.skillId().equals(id))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "skill_version_not_found")))
                        .flatMap(version -> publish(
                                skill, version.version(), ACTION_VERSION_PUBLISH, actor))
                        .thenReturn(id))));
        return published.flatMap(skillId -> skills
                .skill(skillId)
                .flatMap(skill -> versions.version(versionId, skill.publishedVersion())));
    }

    /** A9: publish the skill; a READY version is mandatory (R-11 前置). */
    public Mono<SkillView> publish(UUID id, String version, UUID actor) {
        Mono<UUID> published = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> requireYcps(skill).then(skill.status() != null && "PUBLISHED".equals(skill.status())
                                && (version == null || version.equals(skill.publishedVersion()))
                        // Idempotent: republishing the already online version is a no-op 200 (6.3).
                        ? Mono.just(id)
                        : publish(skill, version, ACTION_PUBLISH, actor).thenReturn(id)));
        return transactions.transactional(published).flatMap(this::detail);
    }

    /** A10: unpublish; idempotent. */
    public Mono<SkillView> unpublish(UUID id, UUID actor) {
        Mono<UUID> unpublished = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> "PUBLISHED".equals(skill.status())
                        ? versions.archiveAll(id)
                                .then(skills.markUnpublished(id, actor, Instant.now()))
                                .then(skills.audit(actor, ACTION_UNPUBLISH, id, Map.of(
                                        "code", skill.code(),
                                        "publishedVersion",
                                        skill.publishedVersion() == null
                                                ? ""
                                                : skill.publishedVersion())))
                                .thenReturn(id)
                        : Mono.just(id));
        return transactions.transactional(unpublished).flatMap(this::detail);
    }

    /** A11: logical delete; a published skill must be unpublished first (R-11). */
    public Mono<Void> delete(UUID id, UUID actor) {
        Mono<UUID> deleted = skills
                .lockSkillIncludingDeleted(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> {
                    if ("PUBLISHED".equals(skill.status())) {
                        return Mono.error(conflict("skill_cannot_delete_published"));
                    }
                 if ("DELETED".equals(skill.status())) {
                     return Mono.just(id);
                 }
                 return versions.hasActiveDependentsForSkill(id)
                         .flatMap(referenced -> Boolean.TRUE.equals(referenced)
                                 ? Mono.error(conflict("skill_has_active_dependents"))
                                 : skills.markDeleted(id, actor, Instant.now()))
                         .then(skills.audit(actor, ACTION_DELETE, id, Map.of(
                                    "code", skill.code(),
                                    "retentionDays", properties.getDeletedRetention().toDays())))
                            .thenReturn(id);
                });
        return transactions.transactional(deleted).then();
    }

    /** Lists recoverable tombstones. Normal admin and client reads continue to exclude them. */
    public Mono<PageResult<DeletedSkillView>> recycleBin(
            int page, int size, String query, String kind) {
        return skills.listDeletedSkills(
                page,
                size,
                query,
                kind,
                properties.getDeletedRetention(),
                Instant.now());
    }

    /**
     * Restores a recycle-bin entry as DRAFT/UNPUBLISHED, never directly as PUBLISHED. This keeps it
     * invisible to desktop clients until an administrator explicitly publishes it again.
     */
    public Mono<SkillView> restore(UUID id, UUID actor) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.getDeletedRetention());
        Mono<UUID> restored = skills.lockDeletedSkill(id, properties.getDeletedRetention())
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(deleted -> {
                    if (!deleted.expiresAt().isAfter(now)) {
                        return Mono.error(conflict("skill_restore_expired"));
                    }
                    return skills.skillByCode(deleted.code())
                            .hasElement()
                            .flatMap(conflicting -> Boolean.TRUE.equals(conflicting)
                                    ? Mono.error(conflict("skill_code_conflict"))
                                    : skills.restoreDeleted(id, actor, now, cutoff)
                                            .filter(updated -> updated == 1L)
                                            .switchIfEmpty(Mono.error(conflict(
                                                    "skill_concurrent_modification"))))
                            .then(skills.audit(actor, ACTION_RESTORE, id, Map.of(
                                    "code", deleted.code(),
                                    "deletedAt", deleted.deletedAt().toString())))
                            .thenReturn(id);
                })
                .onErrorMap(DuplicateKeyException.class, error -> conflict("skill_code_conflict"));
        return transactions.transactional(restored).flatMap(this::detail);
    }

    /** Permanently removes one selected recycle-bin entry without an additional confirmation step. */
    public Mono<Void> purge(UUID id, UUID actor) {
        return skills.lockDeletedSkill(id, properties.getDeletedRetention())
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(deleted -> retentionCleanup.purgeNow(id)
                        .onErrorMap(
                                IllegalStateException.class,
                                error -> conflict("skill_recycle_delete_blocked"))
                        .then(skills.audit(actor, ACTION_PURGE, id, Map.of(
                                "code", deleted.code(),
                                "deletedAt", deleted.deletedAt().toString()))))
                .then();
    }

    /** A12: delete a version that is not the online one. */
    public Mono<Void> deleteVersion(UUID id, UUID versionId, UUID actor) {
        Mono<UUID> deleted = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> versions
                        .version(versionId, skill.publishedVersion())
                        .filter(version -> version.skillId().equals(id))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "skill_version_not_found")))
                        .flatMap(version -> {
                            if (version.online()) {
                                return Mono.error(conflict("skill_version_online"));
                            }
                            Instant now = Instant.now();
                            return versions.hasDependentsForVersion(versionId)
                                    .flatMap(referenced -> Boolean.TRUE.equals(referenced)
                                            ? Mono.error(conflict("skill_version_has_dependents"))
                                            : downloads.hasUnexpiredSessionForVersion(versionId, now))
                                    .flatMap(active -> Boolean.TRUE.equals(active)
                                            ? Mono.error(conflict(
                                                    "skill_version_has_active_downloads"))
                                            : versions.softDelete(versionId)
                                                    .filter(updated -> updated == 1L)
                                                    .switchIfEmpty(Mono.error(conflict(
                                                            "skill_concurrent_modification"))))
                                    .then(Mono.defer(() -> versions.highestReadyVersion(id)
                                            .flatMap(latest -> skills.setLatestVersion(
                                                    id, latest, actor, now))
                                            .switchIfEmpty(skills.setLatestVersion(
                                                    id, null, actor, now))))
                                    .then(skills.audit(actor, ACTION_VERSION_DELETE, id, Map.of(
                                            "code", skill.code(),
                                            "version", version.version())))
                                    .thenReturn(id);
                        }));
        return transactions.transactional(deleted).then();
    }

    /** A13. */
    public Mono<PageResult<DownloadView>> downloads(UUID id, int page, int size) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> downloads.listBySkill(id, page, size));
    }

    private Mono<Void> publish(SkillView skill, String version, String action, UUID actor) {
        Mono<SkillVersionView> candidate = version == null
                ? versions.newestReady(skill.id(), skill.publishedVersion())
                : versions.readyVersionByNumber(skill.id(), version, skill.publishedVersion());
        return candidate
                .switchIfEmpty(Mono.error(conflict("skill_has_no_ready_version")))
                .flatMap(target -> validateDependencyClosure(skill, target)
                        .then(Mono.defer(() -> {
                    Mono<Long> publishVersion;
                    if (target.sealed()) {
                        publishVersion = versions.republishSealed(target.id());
                    } else {
                        publishVersion = versions.sealAndMarkPublished(
                                target.id(), Instant.now());
                    }
                    return versions.archiveOthers(skill.id(), target.id())
                            .then(publishVersion)
                            .filter(updated -> updated == 1L)
                            .switchIfEmpty(Mono.error(conflict("skill_version_not_publishable")))
                            .then(skills.markPublished(
                                    skill.id(), target.version(), actor, Instant.now()))
                            .then(skills.audit(actor, action, skill.id(), Map.of(
                                    "code", skill.code(),
                                    "version", target.version(),
                                    "revision", target.revision(),
                                    "packageFormat", target.packageFormat(),
                                    "previousVersion",
                                    skill.publishedVersion() == null ? "" : skill.publishedVersion())));
                        })))
                .then();
    }

    private Mono<Void> validateDependencyClosure(
            SkillView skill, SkillVersionView target) {
        return versions.dependencyClosure(target.id(), skill.code())
                .collectList()
                .flatMap(edges -> {
                    String error = dependencyClosureError(
                            target.id(), skill.code(), target.packageKind(), target.version(),
                            target.revision(), target.sha256(), edges);
                    return error == null ? Mono.empty() : Mono.error(conflict(error));
                });
    }

    static String dependencyClosureError(
            UUID rootId,
            String rootCode,
            String rootKind,
            String rootVersion,
            int rootRevision,
            String rootSha256,
            List<SkillVersionRepository.DependencyGraphEdge> edges) {
        if (edges.stream().anyMatch(SkillVersionRepository.DependencyGraphEdge::codeCycle)) {
            return "skill_dependency_cycle";
        }
        if (edges.stream().anyMatch(edge -> edge.depth() > DEPENDENCY_CLOSURE_MAX_DEPTH)) {
            return "skill_dependency_depth_exceeded";
        }
        Set<UUID> packages = new HashSet<>();
        packages.add(rootId);
        Map<String, DependencyLock> locks = new HashMap<>();
        locks.put(rootCode, new DependencyLock(
                rootKind, rootVersion, rootRevision, rootSha256));
        for (SkillVersionRepository.DependencyGraphEdge edge : edges) {
            packages.add(edge.targetVersionId());
            DependencyLock lock = new DependencyLock(
                    edge.kind(), edge.version(), edge.revision(), edge.sha256());
            DependencyLock previous = locks.putIfAbsent(edge.code(), lock);
            if (previous != null && !previous.equals(lock)) {
                return "skill_dependency_lock_conflict";
            }
        }
        return packages.size() > DEPENDENCY_CLOSURE_MAX_PACKAGES
                ? "skill_dependency_count_exceeded" : null;
    }

    private record DependencyLock(String kind, String version, int revision, String sha256) {}

    /**
     * A19: the exact dependency edges of one revision. Read-only: the console renders the review
     * dialog from the stored lock plus the live state of each target, and never mutates the graph.
     */
    public Mono<SkillVersionDependenciesView> versionDependencies(UUID id, UUID versionId) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> versions
                        .version(versionId, skill.publishedVersion())
                        .filter(version -> version.skillId().equals(id))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "skill_version_not_found")))
                        .flatMap(version -> versions
                                .dependencies(versionId)
                                .map(row -> new SkillDependencyView(
                                        row.key(),
                                        row.relation(),
                                        row.required(),
                                        row.targetCode(),
                                        row.targetKind(),
                                        row.targetVersion(),
                                        row.targetRevision(),
                                        row.targetSha256(),
                                        row.targetVersionId(),
                                        row.targetSkillId(),
                                        row.targetVersionStatus(),
                                        row.targetOnline()))
                                .collectList()
                                .map(dependencies -> new SkillVersionDependenciesView(
                                        skill.id(),
                                        skill.code(),
                                        skill.kind(),
                                        version.id(),
                                        version.version(),
                                        version.revision(),
                                        version.sealed(),
                                        dependencies))));
    }

    /**
     * A20: every usable revision that locks this code. The list is the operator-facing counterpart
     * of the {@code skill_has_active_dependents} guard, so an unpublish/delete can be planned
     * against concrete owners instead of a bare conflict code.
     */
    public Mono<SkillDependentsView> dependents(UUID id) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> versions
                        .dependents(skill.code())
                        .map(row -> new SkillDependentEntry(
                                row.ownerSkillId(),
                                row.ownerCode(),
                                row.ownerKind(),
                                row.ownerVersionId(),
                                row.ownerVersion(),
                                row.ownerRevision(),
                                row.ownerStatus(),
                                row.ownerOnline(),
                                row.key(),
                                row.relation(),
                                row.required(),
                                row.targetVersion(),
                                row.targetRevision()))
                        .collectList()
                        .map(dependents -> new SkillDependentsView(
                                skill.id(), skill.code(), dependents)));
    }

    /**
     * A21: both directions of one package's relations in a single read.
     *
     * <p>A19 needs a {@code versionId} before it can list dependencies and A20 only answers the
     * reverse direction, so neither can back a "relations" panel on the package itself. This
     * resolves the package's published revision once and reports what it binds plus what binds it,
     * which is also the impact view an operator needs before unpublishing or deleting.
     *
     * <p>A package with no published revision answers with an empty {@code dependsOn} rather than
     * failing: a DRAFT package legitimately has no edges yet, and the console still needs to show
     * who already depends on it.
     */
    public Mono<SkillRelationsView> relations(UUID id) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> versions
                        .onlineVersion(skill.id())
                        .flatMap(version -> versions
                                .dependencies(version.id())
                                .map(row -> new SkillRelationEdgeView(
                                        row.key(),
                                        row.relation(),
                                        row.required(),
                                        row.targetCode(),
                                        row.targetKind(),
                                        row.targetVersion(),
                                        row.targetRevision(),
                                        row.targetVersionStatus(),
                                        row.targetSkillId()))
                                .collectList()
                                .map(edges -> new PublishedRelations(version.version(), version.revision(), edges)))
                        .defaultIfEmpty(new PublishedRelations(null, null, List.of()))
                        .flatMap(published -> versions
                                .dependents(skill.code())
                                .map(row -> new SkillDependentEntry(
                                        row.ownerSkillId(),
                                        row.ownerCode(),
                                        row.ownerKind(),
                                        row.ownerVersionId(),
                                        row.ownerVersion(),
                                        row.ownerRevision(),
                                        row.ownerStatus(),
                                        row.ownerOnline(),
                                        row.key(),
                                        row.relation(),
                                        row.required(),
                                        row.targetVersion(),
                                        row.targetRevision()))
                                .collectList()
                                .map(dependents -> new SkillRelationsView(
                                        skill.id(),
                                        skill.code(),
                                        skill.kind(),
                                        published.version(),
                                        published.revision(),
                                        published.edges(),
                                        dependents))));
    }

    private record PublishedRelations(
            String version, Integer revision, List<SkillRelationEdgeView> edges) {}

    private Mono<byte[]> readIcon(FilePart file) {
        if (file == null) {
            return Mono.error(invalid("请选择图标文件。"));
        }
        String contentType = file.headers().getContentType() == null
                ? ""
                : file.headers().getContentType().toString().toLowerCase(Locale.ROOT);
        if (!ICON_TYPES.contains(contentType)) {
            return Mono.error(invalid("图标只支持 PNG、JPEG、WebP 或 SVG。"));
        }
        long declared = file.headers().getContentLength();
        long maximum = properties.getIconMaxBytes().toBytes();
        if (declared > maximum) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "skill_icon_too_large"));
        }
        // Icons are capped by `gateway.skills.icon-max-bytes` (4 MB by default), so aggregating them
        // into one buffer is safe. The cap also bounds the `DataBufferUtils.join` allocation below.
        return DataBufferUtils.join(file.content(), (int) maximum)
                .map(SkillAdminService::bytes)
                .map(content -> {
                    try {
                        SkillImageValidator.validate(content, contentType);
                        return content;
                    } catch (IllegalArgumentException error) {
                        throw invalid("图标内容与类型不匹配，或 SVG 包含不安全内容。");
                    }
                })
                .switchIfEmpty(Mono.error(invalid("图标内容为空。")))
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE, "skill_icon_too_large"));
    }

    /**
     * The introduction illustration shares the icon rule: PNG/JPEG/WebP/SVG, capped by
     * {@code gateway.skills.icon-max-bytes}, 413 when too large. The wording stays value-free so it
     * does not drift when the configured cap changes.
     */
    private Mono<byte[]> readIllustration(FilePart file) {
        if (file == null) {
            return Mono.error(invalid("请选择介绍插画文件。"));
        }
        String contentType = file.headers().getContentType() == null
                ? ""
                : file.headers().getContentType().toString().toLowerCase(Locale.ROOT);
        if (!ICON_TYPES.contains(contentType)) {
            return Mono.error(invalid("介绍插画只支持 PNG、JPEG、WebP 或 SVG。"));
        }
        long declared = file.headers().getContentLength();
        long maximum = properties.getIconMaxBytes().toBytes();
        if (declared > maximum) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "skill_illustration_too_large"));
        }
        return DataBufferUtils.join(file.content(), (int) maximum)
                .map(SkillAdminService::bytes)
                .map(content -> {
                    try {
                        SkillImageValidator.validate(content, contentType);
                        return content;
                    } catch (IllegalArgumentException error) {
                        throw invalid("介绍插画内容与类型不匹配，或 SVG 包含不安全内容。");
                    }
                })
                .switchIfEmpty(Mono.error(invalid("介绍插画内容为空。")))
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE, "skill_illustration_too_large"));
    }

    private Mono<byte[]> readExampleImage(FilePart file) {
        if (file == null) {
            return Mono.error(invalid("请选择示例图片文件。"));
        }
        String contentType = file.headers().getContentType() == null
                ? ""
                : file.headers().getContentType().toString().toLowerCase(Locale.ROOT);
        if (!ICON_TYPES.contains(contentType)) {
            return Mono.error(invalid("示例图片只支持 PNG、JPEG、WebP 或 SVG。"));
        }
        long declared = file.headers().getContentLength();
        long maximum = properties.getIconMaxBytes().toBytes();
        if (declared > maximum) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "skill_example_image_too_large"));
        }
        return DataBufferUtils.join(file.content(), (int) maximum)
                .map(SkillAdminService::bytes)
                .map(content -> {
                    try {
                        SkillImageValidator.validate(content, contentType);
                        return content;
                    } catch (IllegalArgumentException error) {
                        throw invalid("示例图片内容与类型不匹配，或 SVG 包含不安全内容。");
                    }
                })
                .switchIfEmpty(Mono.error(invalid("示例图片内容为空。")))
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        error -> new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE, "skill_example_image_too_large"));
    }

    /** V65+: {@code clean/skills/{skillId}/{code}/illustration/{uuid}.{ext}}. */
    static String illustrationKey(UUID skillId, String code, String extension) {
        return "clean/skills/" + skillId + "/" + code + "/illustration/"
                + UUID.randomUUID() + extension;
    }

    static String exampleImageKey(UUID skillId, String code, String extension) {
        return "clean/skills/" + skillId + "/" + code + "/example-image/"
                + UUID.randomUUID() + extension;
    }

    private static String mediaType(String objectKey) {
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

    /**
     * E-04 (at most one {@code LEAD}) and E-05 (unique {@code memberKey}) plus the same key regex the
     * database enforces. Returns the validated roster in submission order.
     */
    private static List<SkillMemberRepository.Member> memberRoster(ReplaceMembersCommand command) {
        List<MemberCommand> requested = command == null || command.members() == null
                ? List.of()
                : command.members();
        if (requested.size() > 200) {
            throw invalidMember("成员最多 200 位。");
        }
        Set<String> keys = new HashSet<>();
        List<SkillMemberRepository.Member> roster = new java.util.ArrayList<>(requested.size());
        String lead = null;
        for (MemberCommand entry : requested) {
            if (entry == null) {
                throw invalidMember("成员条目不能为空。");
            }
            String memberKey = trim(entry.memberKey());
            requireMemberKey(memberKey);
            String role = memberRole(entry.role());
            if (MEMBER_ROLE_LEAD.equals(role)) {
                if (lead != null) {
                    throw invalidMember("一个专家最多只能有一位主理人（LEAD）。");
                }
                lead = memberKey;
            }
            if (!keys.add(memberKey)) {
                throw invalidMember("成员标识重复：" + memberKey + "。");
            }
            roster.add(new SkillMemberRepository.Member(
                    memberKey,
                    memberText(entry.name(), MEMBER_NAME_MAX, "成员姓名"),
                    memberText(entry.nameEn(), MEMBER_NAME_MAX, "成员英文姓名"),
                    memberText(entry.profession(), MEMBER_PROFESSION_MAX, "成员职业"),
                    memberText(entry.professionEn(), MEMBER_PROFESSION_MAX, "成员英文职业"),
                    role,
                    memberSortOrder(entry.sortOrder())));
        }
        return List.copyOf(roster);
    }

    private static String memberRole(String role) {
        String normalized = trim(role) == null
                ? MEMBER_ROLE_MEMBER
                : trim(role).toUpperCase(Locale.ROOT);
        if (!MEMBER_ROLE_LEAD.equals(normalized) && !MEMBER_ROLE_MEMBER.equals(normalized)) {
            throw invalidMember("成员角色只能是 LEAD 或 MEMBER。");
        }
        return normalized;
    }

    private static int memberSortOrder(Integer sortOrder) {
        if (sortOrder == null) {
            return 0;
        }
        if (sortOrder < 0 || sortOrder > MEMBER_SORT_MAX) {
            throw invalidMember("成员排序值必须在 0 到 " + MEMBER_SORT_MAX + " 之间。");
        }
        return sortOrder;
    }

    private static String memberText(String value, int maximum, String label) {
        String normalized = trim(value);
        if (normalized != null && normalized.length() > maximum) {
            throw invalidMember(label + "最长 " + maximum + " 个字符。");
        }
        return normalized;
    }

    private static void requireMemberKey(String memberKey) {
        if (memberKey == null || !MEMBER_KEY.matcher(memberKey.strip()).matches()) {
            throw invalidMember(
                    "成员标识只能由字母、数字、点、下划线和连字符组成，最长 " + MEMBER_KEY_MAX
                            + " 位，且不能以符号开头。");
        }
    }

    private static String leadKey(List<SkillMemberRepository.Member> roster) {
        return roster.stream()
                .filter(member -> MEMBER_ROLE_LEAD.equals(member.role()))
                .map(SkillMemberRepository.Member::memberKey)
                .findFirst()
                .orElse("");
    }

    /** Normalises an incoming {@code kind}; {@code null} means {@code SKILL} (D1 default). */
    static String skillKind(String kind) {
        String normalized = trim(kind) == null ? SkillRepository.KIND_SKILL : trim(kind).toUpperCase(Locale.ROOT);
        if (!SKILL_KINDS.contains(normalized)) {
            throw invalidKind("技能类型只能是 SKILL、EXPERT 或 TEAM。");
        }
        return normalized;
    }

    /** E-01: {@code kind} is only settable on create. */
    private static void requireSameKind(SkillView current, String requested) {
        if (trim(requested) == null) {
            return;
        }
        String wanted = skillKind(requested);
        String stored = current.kind() == null
                ? SkillRepository.KIND_SKILL
                : current.kind().toUpperCase(Locale.ROOT);
        if (!wanted.equals(stored)) {
            throw invalidKind("技能类型只能在创建时设定，不能从 " + stored + " 改为 " + wanted + "。");
        }
    }

    private static byte[] bytes(DataBuffer buffer) {
        byte[] content = new byte[buffer.readableByteCount()];
        buffer.read(content);
        DataBufferUtils.release(buffer);
        return content;
    }

    private static String extension(FilePart file) {
        String contentType = file.headers().getContentType() == null
                ? ""
                : file.headers().getContentType().toString().toLowerCase(Locale.ROOT);
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    private static List<String> changedFields(SkillView current, UpdateSkillCommand command) {
        List<String> fields = new java.util.ArrayList<>();
        if (!java.util.Objects.equals(current.name(), command.name())) {
            fields.add("name");
        }
        if (!java.util.Objects.equals(current.nameEn(), trim(command.nameEn()))) {
            fields.add("nameEn");
        }
        if (!java.util.Objects.equals(current.category(), trim(command.category()))) {
            fields.add("category");
        }
        if (!java.util.Objects.equals(current.tags(), command.tags() == null ? List.of() : command.tags())) {
            fields.add("tags");
        }
        if (!java.util.Objects.equals(current.summary(), trim(command.summary()))) {
            fields.add("summary");
        }
        if (!java.util.Objects.equals(current.summaryEn(), trim(command.summaryEn()))) {
            fields.add("summaryEn");
        }
        if (!java.util.Objects.equals(current.description(), trim(command.description()))) {
            fields.add("description");
        }
        if (!java.util.Objects.equals(current.descriptionEn(), trim(command.descriptionEn()))) {
            fields.add("descriptionEn");
        }
        if (!java.util.Objects.equals(current.icon(), trim(command.icon()))) {
            fields.add("icon");
        }
        if (!java.util.Objects.equals(
                current.minClientVersion(), trim(command.minClientVersion()))) {
            fields.add("minClientVersion");
        }
        return fields;
    }

    private static SkillView withVersions(
            SkillView skill, List<SkillVersionView> versions, JsonNode manifest) {
        return new SkillView(
                skill.id(), skill.code(), skill.name(), skill.nameEn(), skill.category(), skill.tags(),
                skill.summary(), skill.summaryEn(), skill.description(), skill.descriptionEn(),
                skill.icon(), skill.iconUrl(), skill.iconUpdatedAt(),
                skill.illustrationUrl(), skill.illustrationUpdatedAt(),
                skill.exampleImageUrl(), skill.exampleImageUpdatedAt(),
                skill.kind(), skill.status(),
                skill.publishedVersion(), skill.latestVersion(), skill.minClientVersion(),
                skill.sizeBytes(), skill.md5(), skill.sha256(), skill.downloadCount(),
                skill.sortWeight(), skill.rowVersion(), skill.createdAt(), skill.updatedAt(),
                skill.publishedAt(), skill.unpublishedAt(), skill.memberCount(),
                skill.dependencyCount(), skill.dependentCount(),
                manifest,
                skill.members(), versions, skill.sourceFormat(), skill.sourceReviewRequired(),
                skill.sourceVersion(), skill.sourceScanStatus());
    }

    private static SkillView withMembers(SkillView skill, List<SkillMemberRepository.MemberRow> rows) {
        List<MemberView> views = SkillMemberViews.admin(rows);
        return new SkillView(
                skill.id(), skill.code(), skill.name(), skill.nameEn(), skill.category(), skill.tags(),
                skill.summary(), skill.summaryEn(), skill.description(), skill.descriptionEn(),
                skill.icon(), skill.iconUrl(), skill.iconUpdatedAt(),
                skill.illustrationUrl(), skill.illustrationUpdatedAt(),
                skill.exampleImageUrl(), skill.exampleImageUpdatedAt(),
                skill.kind(), skill.status(),
                skill.publishedVersion(), skill.latestVersion(), skill.minClientVersion(),
                skill.sizeBytes(), skill.md5(), skill.sha256(), skill.downloadCount(),
                skill.sortWeight(), skill.rowVersion(), skill.createdAt(), skill.updatedAt(),
                skill.publishedAt(), skill.unpublishedAt(), views.size(),
                skill.dependencyCount(), skill.dependentCount(), skill.manifest(),
                views, skill.versions(), skill.sourceFormat(), skill.sourceReviewRequired(),
                skill.sourceVersion(), skill.sourceScanStatus());
    }

    private static SkillDetail detail(SkillView skill, SkillVersionView online) {
        return new SkillDetail(
                SkillDetail.SKILL_OBJECT,
                skill.code(),
                skill.name(),
                skill.description(),
                skill.summary(),
                skill.publishedVersion(),
                skill.category(),
                skill.tags(),
                skill.icon(),
                List.of(),
                skill.status(),
                skill.latestVersion(),
                skill.publishedVersion(),
                skill.minClientVersion(),
                online == null ? null : online.sizeBytes(),
                online == null ? null : online.md5(),
                online == null ? null : online.sha256(),
                online == null ? 0 : online.revision(),
                online == null ? null : online.packageFormat(),
                online == null ? null : online.ycpsVersion(),
                online == null ? null : online.manifestSha256(),
                online == null ? null : online.dependencyLockSha256(),
                "/v1/skills/" + skill.code() + "/downloads",
                skill.iconUrl(),
                skill.updatedAt(),
                skill.illustrationUrl(),
                skill.updatedAt(),
                skill.exampleImageUrl(),
                skill.updatedAt(),
                skill.updatedAt(),
                online == null ? 0 : online.fileCount(),
                online == null || online.sizeBytes() == null ? 0L : online.sizeBytes(),
                skill.kind(),
                skill.nameEn(),
                skill.summaryEn(),
                skill.descriptionEn(),
                List.of(),
                List.of(),
                0,
                List.of(),
                null,
                List.of());
    }

    private static SkillDetail withMembers(SkillDetail detail, List<SkillMemberRepository.MemberRow> rows) {
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

    private void validate(
            String kind,
            String code,
            String name,
            String nameEn,
            String category,
            List<String> tags,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            String icon,
            String minClientVersion,
            Integer sortWeight) {
        if (code != null && !CODE.matcher(code.strip()).matches()) {
            throw invalid("技能标识只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 data-analyst；最长 64 位。");
        }
        if (name == null || name.isBlank()) {
            throw invalid("请填写技能名称。");
        }
        if (name.strip().length() > NAME_MAX) {
            throw invalid("技能名称最多 128 个字符。");
        }
        if (trim(nameEn) != null && trim(nameEn).length() > NAME_MAX) {
            throw invalid("英文名称最多 128 个字符。");
        }
        if (trim(category) != null && trim(category).length() > CATEGORY_MAX) {
            throw invalid("分类最多 32 个字符。");
        }
        if (tags != null) {
            if (tags.size() > MAX_TAGS) {
                throw invalid("最多 16 个标签。");
            }
            for (String tag : tags) {
                if (tag == null || tag.isBlank() || tag.strip().length() > TAG_MAX) {
                    throw invalid("单个标签最多 " + TAG_MAX + " 个字符且不能为空。");
                }
            }
        }
        if (trim(summary) != null && trim(summary).length() > SUMMARY_MAX) {
            throw invalid("技能简介最多 512 个字符。");
        }
        if (trim(summaryEn) != null && trim(summaryEn).length() > SUMMARY_MAX) {
            throw invalid("英文简介最多 512 个字符。");
        }
        int descriptionMax = SkillRepository.KIND_SKILL.equals(skillKind(kind))
                ? SKILL_DESCRIPTION_MAX : DESCRIPTION_MAX;
        if (trim(description) != null && trim(description).length() > descriptionMax) {
            throw invalid("详细说明最多 " + descriptionMax + " 个字符。");
        }
        if (trim(descriptionEn) != null && trim(descriptionEn).length() > descriptionMax) {
            throw invalid("英文详细说明最多 " + descriptionMax + " 个字符。");
        }
        if (trim(icon) != null && trim(icon).length() > ICON_MAX) {
            throw invalid("徽标文本最多 8 个字符。");
        }
        if (trim(minClientVersion) != null
                && !CLIENT_VERSION.matcher(trim(minClientVersion)).matches()) {
            throw invalid("最低客户端版本格式应形如 2026.6.0。");
        }
        if (sortWeight != null && sortWeight < 0) {
            throw invalid("排序权重不能为负数。");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Mono<ObjectPointerChange> switchIconPointer(
            UUID id, String objectKey, UUID actor) {
        Mono<ObjectPointerChange> switched = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> skills.iconObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(previous -> skills.updateIconObjectKey(
                                        id, objectKey, actor, Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_concurrent_modification")))
                                .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                        "code", current.code(),
                                        "field", "icon_object_key")))
                                .thenReturn(new ObjectPointerChange(
                                        id, blankToNull(previous), objectKey))));
        return transactions.transactional(switched);
    }

    private Mono<ObjectPointerChange> switchIllustrationPointer(
            UUID id, String objectKey, UUID actor) {
        Mono<ObjectPointerChange> switched = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> skills.illustrationObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(previous -> skills.updateIllustrationObjectKey(
                                        id, objectKey, actor, Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_concurrent_modification")))
                                .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                        "code", current.code(),
                                        "field", "illustration_object_key")))
                                .thenReturn(new ObjectPointerChange(
                                        id, blankToNull(previous), objectKey))));
        return transactions.transactional(switched);
    }

    private Mono<ObjectPointerChange> switchExampleImagePointer(
            UUID id, String objectKey, UUID actor) {
        Mono<ObjectPointerChange> switched = skills
                .lockSkill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> skills.exampleImageObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(previous -> skills.updateExampleImageObjectKey(
                                        id, objectKey, actor, Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_concurrent_modification")))
                                .then(skills.audit(actor, ACTION_UPDATE, id, Map.of(
                                        "code", current.code(),
                                        "field", "example_image_object_key")))
                                .thenReturn(new ObjectPointerChange(
                                        id, blankToNull(previous), objectKey))));
        return transactions.transactional(switched);
    }

    private Mono<Void> deleteReplacedObject(ObjectPointerChange change) {
        if (change.previousKey() == null || change.previousKey().equals(change.currentKey())) {
            return Mono.empty();
        }
        return deleteBestEffort(change.previousKey());
    }

    private Mono<Void> deleteBestEffort(String objectKey) {
        return objectKey == null
                ? Mono.empty()
                : objectStore.delete(objectKey).onErrorResume(error -> Mono.empty());
    }

    private static SkillAdminException invalid(String message) {
        return SkillAdminException.invalid(message);
    }

    private static SkillAdminException invalidKind(String message) {
        return SkillAdminException.invalid("invalid_skill_kind", message);
    }

    private static SkillAdminException invalidMember(String message) {
        return SkillAdminException.invalid("invalid_skill_member", message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_not_found");
    }

    private static Mono<Void> requireYcps(SkillView skill) {
        return "CLAWHUB_SKILL".equals(skill.sourceFormat())
                ? Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "native_clawhub_ycps_route_forbidden"))
                : Mono.empty();
    }

    // ------------------------------------------------------------------ A23-A24: category dictionary

    /** How many packages a delete refusal names before it switches to "and N more". */
    private static final int CATEGORY_USAGE_SAMPLE = 5;

    /**
     * A23. One kind's category vocabulary with live usage counts, so the console can render the
     * dropdown and disable the delete action on a category that is still in use.
     *
     * <p>{@code kind} is the package kind ({@code SKILL}/{@code EXPERT}/{@code TEAM}); teams share the
     * expert vocabulary because the console groups both under the same business categories.
     */
    public Mono<SkillCategoryListView> listCategories(String kind) {
        String scope = categoryScope(kind);
        return categories
                .list(scope)
                .concatMap(row -> categories
                        .usageCount(scope, row.name())
                        .map(count -> categoryView(row, (int) (long) count)))
                .collectList()
                .map(views -> new SkillCategoryListView(scope, views));
    }

    /**
     * A23. Adds one category to a kind's vocabulary.
     *
     * <p>The name is the value written into {@code gateway.skill.category}, so it is unique inside the
     * kind (case-insensitively). {@code code} is the stable identifier; when omitted it is derived
     * from the name, and a collision on either is a readable 409 rather than a raw constraint error.
     */
    public Mono<SkillCategoryView> createCategory(
            CreateCategoryCommand command, UUID actor) {
        String scope = categoryScope(command == null ? null : command.kind());
        String name = trim(command == null ? null : command.name());
        if (name == null) {
            throw SkillAdminException.invalid("invalid_skill_category", "请填写分类名称。");
        }
        if (name.length() > CATEGORY_MAX) {
            throw SkillAdminException.invalid(
                    "invalid_skill_category", "分类名称最多 " + CATEGORY_MAX + " 个字符。");
        }
        String nameEn = trim(command.nameEn());
        if (nameEn != null && nameEn.length() > CATEGORY_MAX) {
            throw SkillAdminException.invalid(
                    "invalid_skill_category", "英文分类名称最多 " + CATEGORY_MAX + " 个字符。");
        }
        String code = trim(command.code());
        String resolvedCode = code == null ? deriveCategoryCode(name) : code.strip().toLowerCase();
        if (!CATEGORY_CODE.matcher(resolvedCode).matches()) {
            throw SkillAdminException.invalid(
                    "invalid_skill_category",
                    "分类编码只能由小写字母和数字组成，单词之间用连字符（-）分隔。");
        }
        Instant now = Instant.now();
        return categories
                .byName(scope, name)
                .flatMap(existing -> Mono.<SkillCategoryView>error(SkillAdminException.conflict(
                        "skill_category_name_conflict",
                        "分类「" + name + "」已存在，请换一个名称。",
                        Map.of("name", name, "kind", scope))))
                .switchIfEmpty(categories
                        .byCode(scope, resolvedCode)
                        .flatMap(existing -> Mono.<SkillCategoryView>error(SkillAdminException.conflict(
                                "skill_category_code_conflict",
                                "分类编码「" + resolvedCode + "」已被占用，请换一个编码。",
                                Map.of("code", resolvedCode, "kind", scope))))
                        .switchIfEmpty(Mono.defer(() -> categories
                                .nextSortOrder(scope)
                                .flatMap(order -> {
                                    UUID id = UUID.randomUUID();
                                    return categories
                                            .insert(id, scope, resolvedCode, name, nameEn, order,
                                                    actor, now)
                                            .then(categories.category(id));
                                })
                                .map(row -> categoryView(row, 0)))));
    }

    /**
     * A25. Renames one category of a kind's vocabulary.
     *
     * <p>The name is the value written into {@code gateway.skill.category}, so the rename and the
     * package migration must land together: the category row is renamed and every live package still
     * carrying the old name is re-pointed to the new one inside one transaction. Renaming only the
     * dictionary would leave those packages pointing at a label the console no longer offers, so they would
     * silently fall out of every group. {@code code} is deliberately immutable, so a console filter saved
     * against it keeps working after a rename.
     */
    public Mono<SkillCategoryView> updateCategory(
            String kind, String code, UpdateCategoryCommand command, UUID actor) {
        String scope = categoryScope(kind);
        String wanted = trim(code);
        if (wanted == null) {
            throw SkillAdminException.invalid("invalid_skill_category", "请指定要修改的分类。");
        }
        String name = trim(command == null ? null : command.name());
        if (name == null) {
            throw SkillAdminException.invalid("invalid_skill_category", "请填写分类名称。");
        }
        if (name.length() > CATEGORY_MAX) {
            throw SkillAdminException.invalid(
                    "invalid_skill_category", "分类名称最多 " + CATEGORY_MAX + " 个字符。");
        }
        String nameEn = trim(command.nameEn());
        if (nameEn != null && nameEn.length() > CATEGORY_MAX) {
            throw SkillAdminException.invalid(
                    "invalid_skill_category", "英文分类名称最多 " + CATEGORY_MAX + " 个字符。");
        }
        Instant now = Instant.now();
        Mono<SkillCategoryView> renamed = categories
                .byCode(scope, wanted)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_category_not_found")))
                .flatMap(found -> categories.lockCategory(found.id()))
                .flatMap(current -> {
                    if (current.name().equals(name)) {
                        return categories
                                .update(current.id(), name, nameEn, null, false, now)
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_category_concurrent_modification")))
                                .then(categories.category(current.id()))
                                .map(row -> categoryView(row, 0));
                    }
                    // A rename onto a name another live category already owns would make the
                    // grouping ambiguous, so it is a readable 409 instead of a raw constraint error.
                    return categories
                            .byName(scope, name)
                            .flatMap(existing -> Mono.<SkillCategoryView>error(
                                    SkillAdminException.conflict(
                                            "skill_category_name_conflict",
                                            "分类「" + name + "」已存在，请换一个名称。",
                                            Map.of("name", name, "kind", scope))))
                            .switchIfEmpty(Mono.defer(() -> categories
                                    .renamePackages(scope, current.name(), name)
                                    .then(categories.update(
                                            current.id(), name, nameEn, null, false, now))
                                    .filter(updated -> updated == 1L)
                                    .switchIfEmpty(Mono.error(conflict(
                                            "skill_category_concurrent_modification")))
                                    .then(categories.usageCount(scope, name))
                                    .map(count -> categoryView(
                                            new SkillCategoryRepository.CategoryRow(
                                                    current.id(), current.kind(), current.code(),
                                                    name, nameEn, current.sortOrder(),
                                                    current.enabled(), current.rowVersion(),
                                                    current.imageObjectKey(),
                                                    current.imageUpdatedAt()),
                                            (int) (long) count))));
                });
        return transactions.transactional(renamed);
    }

    /**
     * A26. Uploads one category cover.
     *
     * <p>The bytes live in the object store and the row keeps only the object key, mirroring the
     * skill icon: the previous object is deleted only after the pointer moved, so a failed upload never
     * leaves a category without a cover. {@code image_updated_at} is bumped on every upload, so the
     * desktop re-downloads a cover that changed even when the code did not.
     */
    public Mono<SkillCategoryView> uploadCategoryImage(
            String kind, String code, FilePart file, UUID actor) {
        String scope = categoryScope(kind);
        String wanted = trim(code);
        if (wanted == null) {
            throw SkillAdminException.invalid("invalid_skill_category", "请指定要修改的分类。");
        }
        return categories
                .byCode(scope, wanted)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_category_not_found")))
                .flatMap(row -> readIcon(file)
                        .flatMap(content -> {
                            String extension = extension(file);
                            String objectKey = categoryImageKey(row.id(), row.code(), extension);
                            return objectStore
                                    .put(objectKey, content, file.headers().getContentType() == null
                                            ? "application/octet-stream"
                                            : file.headers().getContentType().toString())
                                    .then(Mono.defer(() -> switchCategoryImagePointer(
                                            row.id(), objectKey, actor)))
                                    .onErrorResume(error -> deleteBestEffort(objectKey)
                                            .then(Mono.error(error)))
                                    .flatMap(change -> deleteReplacedObject(change)
                                            .thenReturn(change.id()));
                        })
                        .flatMap(id -> categories
                                .usageCount(scope, row.name())
                                .flatMap(count -> categories.category(id)
                                        .map(updated -> categoryView(
                                                updated, (int) (long) count)))));
    }

    /**
     * The cover bytes of one category, for the console preview and the desktop download.
     * 404 {@code skill_category_image_not_found} when the category has no cover.
     */
    public Mono<AvatarContent> categoryImage(String kind, String code) {
        String scope = categoryScope(kind);
        String wanted = trim(code);
        if (wanted == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "skill_category_image_not_found"));
        }
        return categories
                .byCode(scope, wanted)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_category_not_found")))
                .flatMap(row -> categories
                        .imageObjectKey(row.id())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "skill_category_image_not_found")))
                        .flatMap(objectKey -> objectStore
                                .get(objectKey, properties.getIconMaxBytes().toBytes())
                                .switchIfEmpty(Mono.error(new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "skill_category_image_not_found")))
                                .onErrorMap(
                                        FileObjectMissingException.class,
                                        error -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "skill_category_image_not_found"))
                                .map(content -> new AvatarContent(
                                        content, mediaType(objectKey)))));
    }

    /**
     * A26 companion: clears one category cover, falling back to the placeholder badge.
     *
     * <p>Idempotent: clearing a category that has no cover returns the current view instead of 404,
     * so the console's 清空 button stays safe to click twice. Reuses {@code update(...)} with
     * {@code replaceImage=true} and a null pointer — the repository already writes the cover columns
     * only when that flag is set, so a plain rename still cannot clear a cover by accident.
     */
    public Mono<SkillCategoryView> clearCategoryImage(String kind, String code, UUID actor) {
        String scope = categoryScope(kind);
        String wanted = trim(code);
        if (wanted == null) {
            throw SkillAdminException.invalid("invalid_skill_category", "请指定要修改的分类。");
        }
        Instant now = Instant.now();
        Mono<ObjectPointerChange> cleared = transactions.transactional(categories
                .byCode(scope, wanted)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_category_not_found")))
                .flatMap(found -> categories.lockCategory(found.id()))
                .flatMap(current -> categories
                        .imageObjectKey(current.id())
                        .defaultIfEmpty("")
                        .flatMap(previous -> categories
                                .update(current.id(), current.name(), current.nameEn(), null, true,
                                        now)
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_category_concurrent_modification")))
                                .thenReturn(new ObjectPointerChange(
                                        current.id(), blankToNull(previous), null)))));
        return cleared
                .flatMap(change -> deleteReplacedObject(change).thenReturn(change.id()))
                .flatMap(id -> categories.category(id)
                        .flatMap(row -> categories
                                .usageCount(scope, row.name())
                                .map(count -> categoryView(row, (int) (long) count))));
    }

    /**
     * A24. Removes one category from a kind's vocabulary.
     *
     * <p>A category still carried by any live package is refused with 409 and the offending package
     * codes: deleting it would leave those packages pointing at a label the console no longer offers,
     * so they would fall out of every group. The check is server-side, so a stale console cannot
     * bypass it by hiding the button.
     */
    public Mono<Void> deleteCategory(String kind, String code, UUID actor) {
        String scope = categoryScope(kind);
        String wanted = trim(code);
        if (wanted == null) {
            throw SkillAdminException.invalid("invalid_skill_category", "请指定要删除的分类。");
        }
        return categories
                .byCode(scope, wanted)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_category_not_found")))
                .flatMap(row -> categories
                        .usageCount(scope, row.name())
                        .flatMap(count -> count > 0
                                ? categoryInUse(scope, row.name(), count)
                                : Mono.just(row))
                        .flatMap(ignored -> categories.markDeleted(row.id(), actor, Instant.now()))
                        .then());
    }

    /** Builds the 409 that names the packages still holding the category. */
    private Mono<SkillCategoryRepository.CategoryRow> categoryInUse(
            String scope, String name, long count) {
        return categories
                .usageCodes(scope, name, CATEGORY_USAGE_SAMPLE)
                .collectList()
                .flatMap(codes -> {
                    String sample = String.join("、", codes);
                    String more = count > codes.size() ? " 等 " + count + " 个包" : "";
                    return Mono.error(SkillAdminException.conflict(
                            "skill_category_in_use",
                            "分类「" + name + "」下还有 " + count + " 个包（" + sample + more
                                    + "），请先移除或改归类后再删除该分类。",
                            Map.of(
                                    "kind", scope,
                                    "name", name,
                                    "usageCount", count,
                                    "packages", codes)));
                });
    }

    /**
     * Maps a package kind onto its vocabulary scope. Teams share the expert list, and an absent or
     * unknown kind defaults to {@code SKILL} because the skill page is the unscoped entry point.
     */
    private static String categoryScope(String kind) {
        String value = trim(kind);
        if (value == null) {
            return "SKILL";
        }
        String upper = value.toUpperCase();
        return "EXPERT".equals(upper) || "TEAM".equals(upper) ? "EXPERT" : "SKILL";
    }

    /** One dictionary row plus its live usage count, as the console reads it. */
    private static SkillCategoryView categoryView(
            SkillCategoryRepository.CategoryRow row, int usageCount) {
        return new SkillCategoryView(
                row.id(), row.kind(), row.code(), row.name(), row.nameEn(),
                row.sortOrder(), row.enabled(), usageCount,
                row.imageObjectKey() == null || row.imageObjectKey().isBlank()
                        ? null
                        : "/console/gateway/admin/skills/categories/" + row.code() + "/image",
                row.imageUpdatedAt());
    }

    /** V48+: {@code clean/skill-categories/{categoryId}/{code}/{uuid}.{ext}}. */
    static String categoryImageKey(UUID categoryId, String code, String extension) {
        return "clean/skill-categories/" + categoryId + "/" + code + "/" + UUID.randomUUID()
                + extension;
    }

    /** Moves the cover pointer, then reports the replaced key so the caller can drop the old object. */
    private Mono<ObjectPointerChange> switchCategoryImagePointer(
            UUID id, String objectKey, UUID actor) {
        Mono<ObjectPointerChange> switched = categories
                .lockCategory(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "skill_category_not_found")))
                .flatMap(current -> categories
                        .imageObjectKey(id)
                        .defaultIfEmpty("")
                        .flatMap(previous -> categories
                                .update(id, current.name(), current.nameEn(), objectKey, true,
                                        Instant.now())
                                .filter(updated -> updated == 1L)
                                .switchIfEmpty(Mono.error(conflict(
                                        "skill_category_concurrent_modification")))
                                .thenReturn(new ObjectPointerChange(
                                        id, blankToNull(previous), objectKey))));
        return transactions.transactional(switched);
    }

    /** Derives a stable kebab code from a Chinese or Latin name when the operator omits one. */
    private static String deriveCategoryCode(String name) {
        StringBuilder slug = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char current = Character.toLowerCase(name.charAt(index));
            if (Character.isLetterOrDigit(current) && current < 128) {
                slug.append(current);
            } else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        // A name with no ASCII alphanumerics (all Chinese, for example) yields nothing usable, so
        // fall back to a generated handle rather than rejecting an otherwise valid category name.
        return slug.length() == 0 ? "category-" + UUID.randomUUID().toString().substring(0, 8)
                : slug.toString();
    }

    /**
     * A25: resolves the stored package one console download must stream.
     *
     * <p>A25 is the admin console's own browser download. The client path (C5) deliberately goes
     * through a download session with device fingerprinting and quota accounting, which is the wrong
     * shape for an operator who just wants the artefact they uploaded; this path therefore reads the
     * sealed object directly and records the act in {@code admin_operation_audit} instead.
     *
     * <p>{@code versionId} is optional: omitted (or null) means "whatever is online right now", so the
     * console's default button needs no version. Nothing here buffers the package — only the object
     * key and metadata are resolved, and the caller streams the body.
     */
    public Mono<PackageDownload> packageForDownload(UUID id, UUID versionId, UUID actor) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> {
                    Mono<SkillVersionRepository.PackageRef> resolved = versionId == null
                            ? versions.onlinePackage(id)
                            : versions.packageByVersionId(id, versionId);
                    return resolved
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "skill_package_not_available")))
                            .flatMap(ref -> skills
                                    .audit(actor, ACTION_PACKAGE_DOWNLOAD, id, Map.of(
                                            "code", skill.code() == null ? "" : skill.code(),
                                            "version", ref.version() == null ? "" : ref.version(),
                                            "revision", ref.revision(),
                                            "sizeBytes", ref.sizeBytes(),
                                            "sha256", ref.sha256() == null ? "" : ref.sha256()))
                                    .thenReturn(packageDownload(skill, ref)));
                });
    }

    /**
     * A26: the same package, wrapped in a folder-shaped ZIP that also carries the curated images.
     *
     * <p>The console's "download the complete package" action has to hand the operator something they
     * can archive, re-import or send on with the branding intact. The YCPS archive itself cannot grow
     * those images — a client verifies the downloaded bytes against the session hashes and the
     * installer re-checks {@code artifactSha256}, so injecting files at download time would fail
     * verification. Wrapping is therefore the only shape that keeps the inner package byte-identical
     * while still shipping the artwork.
     *
     * <p>The wrapper is <em>not</em> itself a YCPS package and must not be uploaded as one; it exists
     * so a human can unzip one folder and find everything. The inner {@code .ycps.zip} stays exactly
     * as stored, so it can be re-uploaded unchanged.
     */
    public Mono<PackageBundle> packageBundleForDownload(UUID id, UUID versionId, UUID actor) {
        return skills
                .skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(skill -> {
                    Mono<SkillVersionRepository.PackageRef> resolved = versionId == null
                            ? versions.onlinePackage(id)
                            : versions.packageByVersionId(id, versionId);
                    return resolved
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "skill_package_not_available")))
                            .flatMap(ref -> readBrandingAssets(skill)
                                    .flatMap(assets -> skills
                                            .audit(actor, ACTION_PACKAGE_BUNDLE_DOWNLOAD, id, Map.of(
                                                    "code", skill.code() == null ? "" : skill.code(),
                                                    "version", ref.version() == null ? "" : ref.version(),
                                                    "revision", ref.revision(),
                                                    "assetCount", assets.size()))
                                            .thenReturn(new PackageBundle(
                                                    packageDownload(skill, ref), assets))));
                });
    }

    /**
     * The curated images that belong beside the package, as {@code (file name, bytes)} pairs.
     *
     * <p>All are optional and independently absent, and none is allowed to fail the download: an
     * operator asking for the complete package of a record that never had artwork still gets the
     * package. A missing object behind a live pointer is treated the same as no pointer, which is
     * what the console's own preview does.
     */
    private Mono<Map<String, byte[]>> readBrandingAssets(SkillView skill) {
        if (skill.code() == null || skill.code().isBlank()) {
            return Mono.just(Map.of());
        }
        String code = skill.code();
        Mono<Map.Entry<String, byte[]>> icon = skills
                .iconObjectKey(skill.id())
                .flatMap(objectKey -> readAsset(objectKey)
                        .map(bytes -> Map.entry(code + "-icon" + extensionOf(objectKey), bytes)))
                .onErrorResume(error -> Mono.empty());
        Mono<Map.Entry<String, byte[]>> illustration = skills
                .illustrationObjectKey(skill.id())
                .flatMap(objectKey -> readAsset(objectKey)
                        .map(bytes -> Map.entry(
                                code + "-illustration" + extensionOf(objectKey), bytes)))
                .onErrorResume(error -> Mono.empty());
        Mono<Map.Entry<String, byte[]>> exampleImage = skills
                .exampleImageObjectKey(skill.id())
                .flatMap(objectKey -> readAsset(objectKey)
                        .map(bytes -> Map.entry(
                                code + "-example" + extensionOf(objectKey), bytes)))
                .onErrorResume(error -> Mono.empty());
        return Mono.zip(icon.map(Optional::of).defaultIfEmpty(Optional.empty()),
                        illustration.map(Optional::of).defaultIfEmpty(Optional.empty()),
                        exampleImage.map(Optional::of).defaultIfEmpty(Optional.empty()))
                .map(tuple -> {
                    Map<String, byte[]> assets = new LinkedHashMap<>();
                    tuple.getT1().ifPresent(entry -> assets.put(entry.getKey(), entry.getValue()));
                    tuple.getT2().ifPresent(entry -> assets.put(entry.getKey(), entry.getValue()));
                    tuple.getT3().ifPresent(entry -> assets.put(entry.getKey(), entry.getValue()));
                    return assets;
                });
    }

    private Mono<byte[]> readAsset(String objectKey) {
        return objectStore
                .get(objectKey, properties.getIconMaxBytes().toBytes())
                .onErrorResume(FileObjectMissingException.class, error -> Mono.empty());
    }

    /** Keeps the stored extension (the three image routes carry none in their own path). */
    private static String extensionOf(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        int slash = objectKey.lastIndexOf('/');
        return dot > slash ? objectKey.substring(dot) : "";
    }

    private static PackageDownload packageDownload(
            SkillView skill, SkillVersionRepository.PackageRef ref) {
        String code = skill.code() == null || skill.code().isBlank() ? "skill" : skill.code();
        String version = ref.version() == null || ref.version().isBlank() ? "0" : ref.version();
        return new PackageDownload(
                ref.objectKey(),
                code + "-" + version + ".ycps.zip",
                ref.sizeBytes(),
                ref.md5(),
                ref.sha256(),
                code,
                version);
    }

    private static ResponseStatusException memberNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_member_not_found");
    }

    private static ResponseStatusException illustrationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "skill_illustration_not_found");
    }

    private static ResponseStatusException exampleImageNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "skill_example_image_not_found");
    }

    /**
     * A27: what a YCPS archive declares, for prefilling the create form.
     *
     * <p>Read-only by construction — it delegates to {@link SkillPackageIngestService#inspectOnly}
     * (no row, no object, no audit) and adds the one thing the archive cannot answer about itself:
     * whether its code is still free. That check rides along in the same round trip because the
     * operator is about to commit to this code, and a clash is fatal
     * ({@link #codeConflict}: codes are never reused across kinds).
     *
     * <p>Field mapping mirrors what ingest would seed, including the summary/description fan-out
     * ({@code SkillPackageIngestService.seedCatalogCopy} writes the one-line blurb into both
     * columns). Keeping the duplication here means the console copies values verbatim instead of
     * encoding the rule a second time.
     */
    public Mono<SkillPackageInspectView> inspectPackage(FilePart file) {
        return ingest
                .inspectOnly(file)
                .flatMap(inspected -> {
                    SkillPackageInspector.Report report = inspected.report();
                    SkillPackageIngestService.Digest digest = inspected.digest();
                    SkillPackageInspector.CatalogCopy copy = report.catalogCopy();
                    Map<String, String> defaults =
                            copy == null ? Map.of() : copy.defaultCopy();
                    Map<String, String> english =
                            copy == null ? Map.of() : copy.forLanguage("en");
                    String summary = defaults.get("summary");
                    String summaryEn = english.get("summary");
                    return skills
                            .skillByCode(report.code())
                            .map(holder -> new SkillPackageInspectView(
                                    report.code(),
                                    report.version(),
                                    report.kind(),
                                    report.minClientVersion(),
                                    defaults.get("title"),
                                    english.get("title"),
                                    summary,
                                    summaryEn,
                                    summary,
                                    summaryEn,
                                    keywords(report.pluginManifest()),
                                    report.fileCount(),
                                    report.extractedBytes(),
                                    digest.sizeBytes(),
                                    digest.sha256(),
                                    digest.md5(),
                                    report.manifestSha256(),
                                    false,
                                    new SkillCodeHolderView(
                                            holder.id(),
                                            holder.code(),
                                            holder.name() == null ? "" : holder.name(),
                                            holder.kind() == null ? "SKILL" : holder.kind(),
                                            holder.status() == null ? "" : holder.status())))
                            .defaultIfEmpty(new SkillPackageInspectView(
                                    report.code(),
                                    report.version(),
                                    report.kind(),
                                    report.minClientVersion(),
                                    defaults.get("title"),
                                    english.get("title"),
                                    summary,
                                    summaryEn,
                                    summary,
                                    summaryEn,
                                    keywords(report.pluginManifest()),
                                    report.fileCount(),
                                    report.extractedBytes(),
                                    digest.sizeBytes(),
                                    digest.sha256(),
                                    digest.md5(),
                                    report.manifestSha256(),
                                    true,
                                    null));
                });
    }

    /**
     * The package's own keywords, offered to the console as prefilled tags.
     *
     * <p>YCPS caps a keyword at whatever {@code plugin.json} allows, while the console column caps a
     * tag at {@link #TAG_MAX}; over-long entries are dropped rather than truncated, because a
     * silently shortened tag is worse than an absent one. The list is also capped at
     * {@link #MAX_TAGS} so a package can never produce a form the operator cannot submit.
     */
    private static List<String> keywords(JsonNode plugin) {
        if (plugin == null) {
            return List.of();
        }
        JsonNode array = plugin.get("keywords");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> keywords = new java.util.ArrayList<>();
        for (JsonNode entry : array) {
            if (!entry.isTextual()) {
                continue;
            }
            String keyword = entry.asText().strip();
            if (keyword.isEmpty() || keyword.length() > TAG_MAX) {
                continue;
            }
            keywords.add(keyword);
            if (keywords.size() >= MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(keywords);
    }
    private static ResponseStatusException iconNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_icon_not_found");
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    /** Avatar bytes plus the media type derived from the stored object key extension. */
    public record AvatarContent(byte[] content, String mediaType) {}

    /**
     * A25: the sealed package one console download streams, plus the headers the response needs.
     *
     * <p>Carries {@code objectKey} rather than the bytes on purpose: skill packages reach 200 MB, so
     * materialising one in heap to hand it to the response would be the same defect the client path
     * avoids. The controller streams from the key.
     */
    public record PackageDownload(
            String objectKey,
            String fileName,
            long sizeBytes,
            String md5,
            String sha256,
            String code,
            String version) {}

    /**
     * A25 plus the curated images, for the console's "complete package" download.
     *
     * <p>{@code archive} is streamed from the object store exactly as {@link PackageDownload} does;
     * {@code assets} are small (capped by {@code gateway.skills.icon-max-bytes}) and already in heap,
     * so the controller can wrap both without buffering the archive.
     */
    public record PackageBundle(PackageDownload archive, Map<String, byte[]> assets) {
        /** The folder the wrapper creates, named after the code so the inner zip sits one level in. */
        public String folderName() {
            return archive.code() + "-" + archive.version();
        }

        public String fileName() {
            return archive.code() + "-" + archive.version() + ".bundle.zip";
        }
    }

    private record ObjectPointerChange(UUID id, String previousKey, String currentKey) {}
}
