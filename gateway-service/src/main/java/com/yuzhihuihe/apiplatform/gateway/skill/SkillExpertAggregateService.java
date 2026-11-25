package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectMissingException;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ImageAction;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.AdminExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertAggregateResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Atomic Expert authoring service.  It owns the aggregate boundary so the old Skill/Team image
 * endpoints and the legacy JSON profile endpoint can remain backwards compatible.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillExpertAggregateService {

    static final String ROLE_ICON = "icon";
    static final String ROLE_ILLUSTRATION = "illustration";
    static final String ROLE_EXAMPLE = "exampleImage";
    private static final Pattern CODE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern CLIENT_VERSION = Pattern.compile("^\\d{4}\\.\\d{1,2}(\\.\\d{1,2})?$");
    private static final int NAME_MAX = 128;
    private static final int SUMMARY_MAX = 512;
    private static final int DESCRIPTION_MAX = 1024;
    private static final int CATEGORY_MAX = 32;
    private static final int TAG_MAX = SkillAdminService.TAG_MAX;
    private static final int MAX_TAGS = 16;
    private static final int MAX_TEXT = 1024;
    private static final int MAX_TASK_TYPES = 6;
    private static final int MAX_PROMPTS = 8;

    private final SkillRepository skills;
    private final SkillExpertAggregateRepository aggregates;
    private final SkillExpertProfileService profiles;
    private final SkillAdminService admin;
    private final FileObjectStore objectStore;
    private final TransactionalOperator transactions;

    public SkillExpertAggregateService(
            SkillRepository skills,
            SkillExpertAggregateRepository aggregates,
            SkillExpertProfileService profiles,
            SkillAdminService admin,
            FileObjectStore objectStore,
            TransactionalOperator transactions) {
        this.skills = skills;
        this.aggregates = aggregates;
        this.profiles = profiles;
        this.admin = admin;
        this.objectStore = objectStore;
        this.transactions = transactions;
    }

    public Mono<ExpertAggregateResponse> create(
            ExpertAggregateCommand request,
            FilePart icon,
            FilePart illustration,
            FilePart exampleImage,
            UUID actor) {
        ExpertAggregateCommand command = normalize(request, null, true);
        UUID id = UUID.randomUUID();
        List<String> staged = new ArrayList<>();
        return prepareImages(id, command.code(), command, null, icon, illustration, exampleImage, staged)
                .flatMap(images -> writeCreate(id, command, images, actor)
                        // Once the database transaction has completed the clean pointers are
                        // committed.  Keep only quarantine keys in the failure ledger from this
                        // point on: a projection/response failure must never delete an object
                        // that the committed row now references.
                        .then(Mono.fromRunnable(() -> discardCommittedCleanKeys(images, staged)))
                        .then(cleanupAfterSuccess(images, List.of()))
                        .thenReturn(id))
                .onErrorResume(error -> cleanup(staged).then(Mono.error(mapError(error))))
                .flatMap(this::response)
                .onErrorMap(DuplicateKeyException.class, error -> conflict("skill_code_conflict"));
    }

    public Mono<ExpertAggregateResponse> update(
            UUID id,
            ExpertAggregateCommand request,
            FilePart icon,
            FilePart illustration,
            FilePart exampleImage,
            UUID actor) {
        return skills.skill(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> {
                    if (!"EXPERT".equalsIgnoreCase(current.kind())) {
                        return Mono.error(invalid("skill_expert_profile_kind_required"));
                    }
                    ExpertAggregateCommand command = normalize(request, current, false);
                    if (command.expectedRowVersion() == null) {
                        return Mono.error(invalid("expected_row_version_required"));
                    }
                    if (command.expectedRowVersion() < 1) {
                        return Mono.error(invalid("expected_row_version_invalid"));
                    }
                    List<String> staged = new ArrayList<>();
                    return currentObjectKeys(id)
                            .flatMap(previous -> prepareImages(
                                    id, current.code(), command, previous, icon, illustration,
                                    exampleImage, staged)
                                    .flatMap(images -> writeUpdate(
                                            id, command, command.expectedRowVersion(), images, actor)
                                            .then(Mono.fromRunnable(() -> discardCommittedCleanKeys(
                                                    images, staged)))
                                            .then(cleanupAfterSuccess(images, previous.values()))
                                            .thenReturn(id)))
                            .onErrorResume(error -> cleanup(staged).then(Mono.error(mapError(error))))
                            .flatMap(this::response)
                            .onErrorMap(DuplicateKeyException.class, error -> conflict(
                                    "skill_code_conflict"));
                });
    }

    private Mono<Void> writeCreate(
            UUID id, ExpertAggregateCommand command, PreparedImages images, UUID actor) {
        Mono<Void> action = aggregates
                .insert(id, command, images.icon().pointer(), images.illustration().pointer(),
                        images.exampleImage().pointer(), actor, Instant.now())
                .then();
        return transactions == null ? action : transactions.transactional(action);
    }

    private Mono<Void> writeUpdate(
            UUID id,
            ExpertAggregateCommand command,
            int expectedRowVersion,
            PreparedImages images,
            UUID actor) {
        Mono<Void> action = aggregates
                .update(id, command, expectedRowVersion, images.icon().pointer(),
                        images.illustration().pointer(), images.exampleImage().pointer(), actor,
                        Instant.now())
                .then();
        return transactions == null ? action : transactions.transactional(action);
    }

    private Mono<ExpertAggregateResponse> response(UUID id) {
        return Mono.zip(admin.detail(id), profiles.admin(id))
                .map(both -> {
                    SkillView skill = both.getT1();
                    AdminExpertPresentation profile = both.getT2();
                    return new ExpertAggregateResponse(id, skill, profile.presentation());
                });
    }

    private Mono<Map<String, String>> currentObjectKeys(UUID id) {
        return Mono.zip(
                        skills.iconObjectKey(id).defaultIfEmpty(""),
                        skills.illustrationObjectKey(id).defaultIfEmpty(""),
                        skills.exampleImageObjectKey(id).defaultIfEmpty(""))
                .map(tuple -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put(ROLE_ICON, blankToNull(tuple.getT1()));
                    values.put(ROLE_ILLUSTRATION, blankToNull(tuple.getT2()));
                    values.put(ROLE_EXAMPLE, blankToNull(tuple.getT3()));
                    return values;
                });
    }

    private Mono<PreparedImages> prepareImages(
            UUID id,
            String code,
            ExpertAggregateCommand command,
            Map<String, String> previous,
            FilePart icon,
            FilePart illustration,
            FilePart exampleImage,
            List<String> staged) {
        boolean creating = previous == null;
        // Stage roles sequentially so the cleanup ledger is complete before any later role can
        // fail.  A concurrent zip can cancel sibling uploads while they are adding keys, leaving
        // a just-copied object outside the error-path snapshot.
        return Mono.defer(() -> prepareImage(
                        id, code, ROLE_ICON, action(command, ROLE_ICON, creating),
                        previous == null ? null : previous.get(ROLE_ICON), icon, staged)
                .flatMap(iconValue -> prepareImage(
                        id, code, ROLE_ILLUSTRATION,
                        action(command, ROLE_ILLUSTRATION, creating),
                        previous == null ? null : previous.get(ROLE_ILLUSTRATION),
                        illustration, staged)
                        .flatMap(illustrationValue -> prepareImage(
                                id, code, ROLE_EXAMPLE,
                                action(command, ROLE_EXAMPLE, creating),
                                previous == null ? null : previous.get(ROLE_EXAMPLE),
                                exampleImage, staged)
                                .map(exampleValue -> new PreparedImages(
                                        iconValue, illustrationValue, exampleValue)))))
                .flatMap(images -> {
                    long total = images.icon().sizeBytes()
                            + images.illustration().sizeBytes()
                            + images.exampleImage().sizeBytes();
                    if (total > ExpertImageValidator.TOTAL_MAX_BYTES) {
                        return Mono.error(invalid("expert_images_total_too_large"));
                    }
                    return Mono.just(images);
                });
    }

    private Mono<PreparedImage> prepareImage(
            UUID id,
            String code,
            String role,
            ImageAction action,
            String previous,
            FilePart file,
            List<String> staged) {
        return switch (action) {
            case CLEAR -> Mono.just(new PreparedImage(role, null, null, 0));
            case REPLACE -> {
                if (file == null) {
                    yield Mono.error(invalid("expert_image_file_required_" + role));
                }
                yield readAndValidate(file, role)
                        .flatMap(content -> {
                            String extension = extension(content.info().mediaType());
                            String nonce = UUID.randomUUID().toString();
                            String quarantine = "quarantine/skill-experts/" + id + "/" + role
                                    + "/" + nonce + extension;
                            String clean = "clean/skills/" + id + "/" + safeCode(code)
                                    + "/" + role + "/" + nonce + extension;
                            staged.add(quarantine);
                            staged.add(clean);
                            return objectStore.put(quarantine, content.bytes(), content.info().mediaType())
                                    // FileObjectStore starts the async SDK request when copy() is
                                    // called. Defer constructing that publisher until PUT has
                                    // completed, otherwise COPY can race the quarantine upload.
                                    .then(Mono.defer(() -> objectStore.copy(quarantine, clean)))
                                    .thenReturn(new PreparedImage(
                                            role, clean, quarantine, content.bytes().length));
                        });
            }
            case KEEP -> keep(previous, role);
        };
    }

    private Mono<PreparedImage> keep(String previous, String role) {
        if (previous == null || previous.isBlank()) {
            return Mono.just(new PreparedImage(role, null, null, 0));
        }
        return objectStore.head(previous)
                .switchIfEmpty(Mono.error(invalid("expert_image_missing_" + role)))
                .flatMap(metadata -> {
                    // Existing catalog rows may still point at an SVG accepted by the legacy
                    // image endpoints. Keeping that unchanged must not prevent an administrator
                    // from saving unrelated WYSIWYG text. SVG is deliberately excluded from the
                    // aggregate byte budget here because it is not a portable presentation asset;
                    // the YCPS 1.1 generator still rejects it and asks for a raster replacement.
                    if (legacySvg(previous, metadata.contentType())) {
                        return Mono.just(0L);
                    }
                    // Assets uploaded by the legacy image endpoints predate the portable Expert
                    // limits.  A text-only aggregate save with KEEP must not fail merely because
                    // one of those existing objects is larger than today's role budget.  HEAD is
                    // enough to make that decision and avoids asking S3 to stream the object.
                    if (metadata.sizeBytes() > ExpertImageValidator.maxBytes(role)) {
                        return Mono.just(0L);
                    }
                    return objectStore.get(
                                previous, ExpertImageValidator.maxBytes(role) + 1)
                        .switchIfEmpty(Mono.error(invalid("expert_image_missing_" + role)))
                        .map(bytes -> {
                            if (bytes.length > ExpertImageValidator.maxBytes(role)) {
                                throw payloadTooLarge("expert_image_too_large_" + role);
                            }
                            try {
                                ExpertImageValidator.validate(bytes, role, metadata.contentType());
                            } catch (IllegalArgumentException error) {
                                throw invalid(error.getMessage());
                            }
                            return (long) bytes.length;
                        })
                        // A provider can discover a larger body after HEAD (or expose a stale
                        // content length).  Treat that as the same legacy oversized KEEP case;
                        // REPLACE never enters this branch and therefore never reads the old key.
                        .onErrorResume(error -> isObjectTooLarge(error)
                                ? Mono.just(0L)
                                : Mono.error(error));
                })
                .onErrorMap(FileObjectMissingException.class,
                        error -> invalid("expert_image_missing_" + role))
                .map(size -> new PreparedImage(role, previous, null, size));
    }

    private static boolean legacySvg(String objectKey, String contentType) {
        String key = objectKey == null ? "" : objectKey.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return key.endsWith(".svg") || type.startsWith("image/svg+xml");
    }

    private static boolean isObjectTooLarge(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if ("file_object_too_large".equals(current.getMessage())) return true;
            current = current.getCause();
        }
        return false;
    }

    private Mono<ValidatedContent> readAndValidate(FilePart file, String role) {
        String contentType = file.headers().getContentType() == null
                ? ""
                : file.headers().getContentType().toString().toLowerCase(Locale.ROOT);
        long max = ExpertImageValidator.maxBytes(role);
        long declared = file.headers().getContentLength();
        if (declared > max) {
            return Mono.error(payloadTooLarge("expert_image_too_large_" + role));
        }
        return DataBufferUtils.join(file.content(), Math.toIntExact(max + 1))
                .map(this::bytes)
                .switchIfEmpty(Mono.error(invalid("expert_image_empty_" + role)))
                .map(bytes -> {
                    if (bytes.length > max) {
                        throw payloadTooLarge("expert_image_too_large_" + role);
                    }
                    try {
                        return new ValidatedContent(
                                bytes, ExpertImageValidator.validate(bytes, role, contentType));
                    } catch (IllegalArgumentException error) {
                        throw invalid(error.getMessage());
                    }
                })
                .onErrorMap(DataBufferLimitException.class,
                        error -> payloadTooLarge("expert_image_too_large_" + role));
    }

    private byte[] bytes(DataBuffer buffer) {
        try {
            byte[] value = new byte[buffer.readableByteCount()];
            buffer.read(value);
            return value;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private Mono<Void> cleanupAfterSuccess(PreparedImages images, Collection<String> oldKeys) {
        List<String> deletions = new ArrayList<>();
        images.all().forEach(image -> {
            if (image.quarantineKey() != null) deletions.add(image.quarantineKey());
        });
        oldKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .filter(key -> images.all().stream().noneMatch(image -> key.equals(image.pointer())))
                .distinct()
                .forEach(deletions::add);
        return cleanup(deletions);
    }

    private static void discardCommittedCleanKeys(PreparedImages images, List<String> staged) {
        if (staged == null || staged.isEmpty()) return;
        synchronized (staged) {
            staged.removeIf(key -> images.all().stream()
                    .anyMatch(image -> key.equals(image.pointer())));
        }
    }

    private Mono<Void> cleanup(List<String> keys) {
        if (keys == null || keys.isEmpty()) return Mono.empty();
        List<String> snapshot;
        synchronized (keys) {
            snapshot = List.copyOf(keys);
        }
        return reactor.core.publisher.Flux.fromIterable(snapshot)
                .concatMap(key -> objectStore.delete(key).onErrorResume(error -> Mono.empty()))
                .then();
    }

    private static ImageAction action(
            ExpertAggregateCommand command, String role, boolean creating) {
        try {
            return command.imageAction(role, creating);
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage());
        }
    }

    private static ExpertAggregateCommand normalize(
            ExpertAggregateCommand request, SkillView current, boolean creating) {
        if (request == null) throw invalid("expert_aggregate_required");
        String code = request.code();
        // Expert aggregate saves are full saves.  A missing name is therefore an invalid
        // request rather than an instruction to resurrect the value from the stored row.
        String name = request.name();
        if (creating && (code == null || code.isBlank())) throw invalid("skill_code_required");
        if (code == null || code.strip().length() > 64
                || !CODE.matcher(code.strip()).matches()) throw invalid("invalid_skill_code");
        if (current != null && !code.strip().equals(current.code())) {
            throw invalid("skill_code_immutable");
        }
        if (name == null || name.isBlank()) throw invalid("skill_name_required");
        requireLength(name, NAME_MAX, "skill_name_invalid");
        // This is a full-save contract: null/empty nullable management fields are explicit
        // clears.  Do not merge them from the stored row (that would make a WYSIWYG clear
        // impossible and would resurrect stale operational metadata).
        String nameEn = request.nameEn();
        String category = request.category();
        String summary = request.summary();
        String summaryEn = request.summaryEn();
        String description = request.description();
        String descriptionEn = request.descriptionEn();
        if (nameEn != null) requireLength(nameEn, NAME_MAX, "skill_name_en_invalid");
        if (category != null) requireLength(category, CATEGORY_MAX, "skill_category_invalid");
        if (summary != null) requireLength(summary, SUMMARY_MAX, "skill_summary_invalid");
        if (summaryEn != null) requireLength(summaryEn, SUMMARY_MAX, "skill_summary_en_invalid");
        if (description != null) requireLength(description, DESCRIPTION_MAX, "skill_description_invalid");
        if (descriptionEn != null) {
            requireLength(descriptionEn, DESCRIPTION_MAX, "skill_description_en_invalid");
        }
        List<String> tags = normalizedStrings(request.tags(), MAX_TAGS, TAG_MAX, "skill_tags_invalid");
        String icon = request.icon();
        String minClientVersion = request.minClientVersion();
        if (summary == null || summary.isBlank()) throw invalid("skill_summary_required");
        if (description == null || description.isBlank()) throw invalid("skill_description_required");
        if (icon != null && icon.strip().length() > 8) throw invalid("skill_icon_invalid");
        if (minClientVersion != null && !CLIENT_VERSION.matcher(minClientVersion.strip()).matches()) {
            throw invalid("skill_min_client_version_invalid");
        }
        String exampleImageLocale;
        try {
            exampleImageLocale = ExpertImageLocale.normalize(request.exampleImageLocale());
        } catch (IllegalArgumentException error) {
            throw invalid("skill_expert_profile_example_image_locale_invalid");
        }
        if (action(request, ROLE_EXAMPLE, creating) == ImageAction.CLEAR) {
            exampleImageLocale = null;
        }
        int sortWeight = request.sortWeight() == null
                ? current == null ? 0 : current.sortWeight()
                : request.sortWeight();
        if (sortWeight < 0) throw invalid("skill_sort_weight_invalid");
        ExpertPreviewContent preview = validatePreview(request.preview(), true, "preview");
        ExpertPreviewContent previewEn = validatePreview(request.previewEn(), false, "previewEn");
        ExpertAggregateCommand normalized = new ExpertAggregateCommand(
                request.expectedRowVersion(), code.strip(), name.strip(), trim(nameEn), trim(category),
                tags, trim(summary), trim(summaryEn), trim(description), trim(descriptionEn),
                trim(icon), trim(minClientVersion), sortWeight, request.status(),
                normalizedStrings(request.taskTypes(), MAX_TASK_TYPES, 64,
                        "skill_expert_profile_task_types_invalid"),
                inField("taskTypesEn", () -> normalizedStrings(request.taskTypesEn(),
                        MAX_TASK_TYPES, 64, "skill_expert_profile_task_types_invalid")),
                optionalText(request.usageGuide(), MAX_TEXT, "skill_expert_profile_usage_guide_invalid"),
                inField("usageGuideEn", () -> optionalText(request.usageGuideEn(), MAX_TEXT,
                        "skill_expert_profile_usage_guide_invalid")),
                normalizedStrings(request.examplePrompts(), MAX_PROMPTS, 300,
                        "skill_expert_profile_prompts_invalid"),
                inField("examplePromptsEn", () -> normalizedStrings(request.examplePromptsEn(),
                        MAX_PROMPTS, 300, "skill_expert_profile_prompts_invalid")),
                preview, previewEn,
                request.images(), request.imageActions(), request.iconAction(),
                request.illustrationAction(), request.exampleImageAction(),
                ExpertPresentationValidator.canvas(request.canvas(), preview, previewEn),
                exampleImageLocale);
        validatePresentation(normalized);
        return normalized;
    }

    private static void validatePresentation(ExpertAggregateCommand request) {
        if (request.taskTypes().isEmpty()) throw invalid("skill_expert_profile_task_types_required");
        if (request.usageGuide() == null) throw invalid("skill_expert_profile_usage_guide_required");
        if (request.examplePrompts().isEmpty()) throw invalid("skill_expert_profile_prompts_required");
        if (request.preview() == null || request.preview().outputParagraphs().isEmpty()) {
            throw invalid("skill_expert_profile_preview_required");
        }
    }

    private static ExpertPreviewContent validatePreview(
            ExpertPreviewContent preview, boolean required, String field) {
        return ExpertPresentationValidator.preview(preview, required, field);
    }

    private static List<String> normalizedStrings(
            List<String> values, int maxItems, int maxChars, String reason) {
        if (values == null) return List.of();
        if (values.size() > maxItems) throw invalid(reason);
        List<String> result = values.stream()
                .map(value -> requiredText(value, maxChars, reason))
                .toList();
        if (result.stream().distinct().count() != result.size()) throw invalid(reason);
        return List.copyOf(result);
    }

    private static List<String> normalizedStringsAllowingDuplicates(
            List<String> values, int maxItems, int maxChars, String reason) {
        if (values == null) return List.of();
        if (values.size() > maxItems) throw invalid(reason);
        return List.copyOf(values.stream()
                .map(value -> requiredText(value, maxChars, reason))
                .toList());
    }

    private static String optionalText(String value, int max, String reason) {
        if (value == null || value.isBlank()) return null;
        return requiredText(value, max, reason);
    }

    private static String requiredText(String value, int max, String reason) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw invalid(reason);
        return value.strip();
    }

    private static void requireLength(String value, int max, String reason) {
        if (value != null && value.strip().length() > max) throw invalid(reason);
    }

    private static <T> T inField(String field, Supplier<T> validation) {
        try {
            return validation.get();
        } catch (ResponseStatusException error) {
            throw new ExpertFieldValidationException(error.getReason(), field);
        }
    }

    private static String value(String requested, String fallback) {
        return requested == null ? fallback : requested;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safeCode(String code) {
        return code == null ? "expert" : code.replaceAll("[^a-z0-9-]", "-");
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    private static RuntimeException mapError(Throwable error) {
        if (error instanceof ResponseStatusException) return (RuntimeException) error;
        if (error instanceof SkillExpertAggregateRepository.ConcurrentAggregateException) {
            return conflict("skill_concurrent_modification");
        }
        return error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "skill_not_found");
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException payloadTooLarge(String reason) {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, reason);
    }

    private record ValidatedContent(byte[] bytes, ExpertImageValidator.ImageInfo info) {}

    private record PreparedImage(String role, String pointer, String quarantineKey, long sizeBytes) {}

    private record PreparedImages(
            PreparedImage icon, PreparedImage illustration, PreparedImage exampleImage) {
        List<PreparedImage> all() {
            return List.of(icon, illustration, exampleImage);
        }
    }
}
