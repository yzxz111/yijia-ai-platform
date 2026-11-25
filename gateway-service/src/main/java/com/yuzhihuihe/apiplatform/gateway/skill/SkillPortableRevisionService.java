package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Compiles the current cloud-side Expert presentation into an immutable YCPS 1.1 successor.
 *
 * <p>The compiler owns only the presentation overlay. Runtime files are copied from the explicitly
 * selected base revision, while old presentation metadata/assets are removed before the current
 * profile and media pointers are written. The resulting file enters
 * {@link SkillPackageIngestService#ingestGenerated}, so it receives the ordinary Inspector,
 * dependency-resolution, object promotion and malware-scan treatment.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillPortableRevisionService {

    /** First desktop client that understands the complete YCPS 1.1 Expert presentation profile. */
    static final String DEFAULT_MIN_CLIENT_VERSION = "2026.6.30";
    private static final String EXPERT_SCHEMA =
            "https://schemas.yuzhihuihe.com/ycps/1.1.0/expert-manifest.schema.json";
    private static final String ASSET_ROOT = "/assets/";
    private static final String SKILL_FILE_SUFFIX = "/SKILL.md";
    private static final int ICON_MAX_BYTES = 128 * 1024;
    private static final int ILLUSTRATION_MAX_BYTES = 640 * 1024;
    private static final int EXAMPLE_MAX_BYTES = 512 * 1024;
    // 1980-01-02T00:00:00Z stays inside the DOS timestamp range in every practical
    // timezone. Using epoch zero makes ZipOutputStream add an extended-time extra field,
    // which violates the portable artifact's no-extra-fields determinism contract.
    private static final long DETERMINISTIC_ZIP_TIME_MILLIS = 315_619_200_000L;

    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final SkillExpertProfileRepository profiles;
    private final SkillPackageIngestService ingest;
    private final FileObjectStore objectStore;
    private final SkillProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final YAMLMapper yaml = yamlMapper();

    public SkillPortableRevisionService(
            SkillRepository skills,
            SkillVersionRepository versions,
            SkillExpertProfileRepository profiles,
            SkillPackageIngestService ingest,
            FileObjectStore objectStore,
            SkillProperties properties) {
        this.skills = skills;
        this.versions = versions;
        this.profiles = profiles;
        this.ingest = ingest;
        this.objectStore = objectStore;
        this.properties = properties;
    }

    private static YAMLMapper yamlMapper() {
        YAMLMapper mapper = new YAMLMapper();
        mapper.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        return mapper;
    }

    /** Generates (or idempotently returns) the portable successor of one exact base revision. */
    public Mono<SkillVersionView> generate(UUID skillId, UUID versionId, UUID actor) {
        return skills
                .skill(skillId)
                .switchIfEmpty(Mono.error(notFound("skill_not_found")))
                .flatMap(skill -> requireExpert(skill)
                        .then(versions.packageByVersionId(skillId, versionId)
                                .filter(ref -> "EXPERT".equals(ref.packageKind()))
                                .switchIfEmpty(Mono.error(notFound("skill_version_not_found")))
                                .flatMap(base -> profiles.findBySkillId(skillId)
                                        .switchIfEmpty(Mono.error(incompleteProfile()))
                                        .flatMap(profile -> {
                                            Presentation presentation = presentation(skill, profile);
                                            validatePresentation(presentation);
                                            return loadAssets(skill)
                                                    .flatMap(assets -> compile(skill, base, profile,
                                                            presentation, assets, actor));
                                        }))));
    }

    private Mono<SkillVersionView> compile(
            SkillView skill,
            SkillVersionRepository.PackageRef base,
            SkillExpertProfileRepository.StoredProfile profile,
            Presentation presentation,
            Map<String, PortableAsset> assets,
            UUID actor) {
        return Mono.fromCallable(() -> Files.createTempFile("skill-portable-base-", ".ycps.zip"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(baseFile -> Mono.usingWhen(
                        Mono.just(baseFile),
                        path -> download(base, path)
                                .then(Mono.fromCallable(() -> digest(path)))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(baseDigest -> {
                                    if (base.sha256() == null
                                            || !base.sha256().equals(baseDigest)) {
                                        return Mono.error(new ResponseStatusException(
                                                HttpStatus.UNPROCESSABLE_ENTITY,
                                                "skill_package_mismatch"));
                                    }
                                    String canonicalHash = presentationHash(
                                            skill, presentation, assets, selectedMinimum(skill, base));
                                    return currentPortableRevision(
                                                    skill, base, profile, baseDigest, canonicalHash)
                                            .switchIfEmpty(Mono.defer(() -> versions
                                                    .portableRevisionByInputs(
                                                            skill.id(), base.version(), baseDigest,
                                                            canonicalHash)
                                                    .flatMap(existing -> reuseExisting(
                                                            skill, profile, assets, baseDigest,
                                                            canonicalHash, existing))))
                                            .switchIfEmpty(Mono.defer(() -> buildAndIngest(
                                                    skill, base, profile, presentation, assets, path,
                                                    baseDigest, canonicalHash, actor)
                                                    .onErrorResume(error -> isVersionConflict(error)
                                                            ? versions.portableRevisionByInputs(
                                                                    skill.id(), base.version(), baseDigest,
                                                                    canonicalHash)
                                                                    .flatMap(existing -> reuseExisting(
                                                                            skill, profile, assets,
                                                                            baseDigest, canonicalHash,
                                                                            existing))
                                                                    .switchIfEmpty(Mono.error(error))
                                                            : Mono.error(error))));
                                }),
                        path -> cleanup(path),
                        (path, error) -> cleanup(path),
                        path -> cleanup(path)));
    }

    /**
     * A generated YCPS 1.1 package may itself be selected as the base by a retry or by an
     * operator opening the version action.  If that exact artifact is still the profile's current
     * portable artifact, return the stored revision instead of wrapping it again and creating an
     * unnecessary successor.  A changed profile or asset set has a different canonical hash and
     * deliberately falls through to the normal successor path.
     */
    private Mono<SkillVersionView> currentPortableRevision(
            SkillView skill,
            SkillVersionRepository.PackageRef base,
            SkillExpertProfileRepository.StoredProfile profile,
            String baseDigest,
            String canonicalHash) {
        if (!SkillPackageInspector.YCPS_PORTABLE_VERSION.equals(base.ycpsVersion())
                || !currentPortableInputsMatch(profile, base, baseDigest, canonicalHash)) {
            return Mono.empty();
        }
        return versions.version(base.versionId(), skill.publishedVersion());
    }

    private boolean currentPortableInputsMatch(
            SkillExpertProfileRepository.StoredProfile profile,
            SkillVersionRepository.PackageRef base,
            String baseDigest,
            String canonicalHash) {
        JsonNode manifest = profile.assetManifest();
        return manifest != null
                && baseDigest.equals(manifest.path("portableArtifactSha256").asText(null))
                && canonicalHash.equals(manifest.path("presentationCanonicalSha256").asText(null))
                && base.version().equals(manifest.path("version").asText(null))
                && base.revision() == manifest.path("revision").asInt(-1);
    }

    private Mono<SkillVersionView> buildAndIngest(
            SkillView skill,
            SkillVersionRepository.PackageRef base,
            SkillExpertProfileRepository.StoredProfile profile,
            Presentation presentation,
            Map<String, PortableAsset> assets,
            Path baseFile,
            String baseDigest,
            String canonicalHash,
            UUID actor) {
        return Mono.fromCallable(() -> Files.createTempFile("skill-portable-output-", ".ycps.zip"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(output -> Mono.usingWhen(
                        Mono.just(output),
                        target -> Mono.fromRunnable(() -> rewrite(
                                        baseFile, target, skill, presentation, assets,
                                        selectedMinimum(skill, base)))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(ingest.ingestGenerated(
                                        skill.id(),
                                        base.version(),
                                        selectedMinimum(skill, base),
                                        target,
                                        skill.code() + "-" + base.version() + ".ycps.zip",
                                        actor,
                                        Map.of(
                                                "portableBaseArtifactSha256", baseDigest,
                                                "portablePresentationCanonicalSha256", canonicalHash)))
                                .flatMap(generated -> updateProfileProvenance(
                                        skill,
                                        profile,
                                        assets,
                                        baseDigest,
                                        canonicalHash,
                                        generated)
                                                .thenReturn(generated)),
                        path -> cleanup(path),
                        (path, error) -> cleanup(path),
                        path -> cleanup(path)));
    }

    private Mono<Void> updateProfileProvenance(
            SkillView skill,
            SkillExpertProfileRepository.StoredProfile profile,
            Map<String, PortableAsset> assets,
            String baseDigest,
            String canonicalHash,
            SkillVersionView generated) {
        return updateProfileProvenanceIfNeeded(
                skill, profile, assets, baseDigest, canonicalHash, generated);
    }

    private Mono<SkillVersionView> reuseExisting(
            SkillView skill,
            SkillExpertProfileRepository.StoredProfile profile,
            Map<String, PortableAsset> assets,
            String baseDigest,
            String canonicalHash,
            SkillVersionView existing) {
        return updateProfileProvenanceIfNeeded(
                skill, profile, assets, baseDigest, canonicalHash, existing)
                .thenReturn(existing);
    }

    private Mono<Void> updateProfileProvenanceIfNeeded(
            SkillView skill,
            SkillExpertProfileRepository.StoredProfile profile,
            Map<String, PortableAsset> assets,
            String baseDigest,
            String canonicalHash,
            SkillVersionView generated) {
        if (provenanceMatches(profile, baseDigest, canonicalHash, generated)) {
            return Mono.empty();
        }
        ObjectNode manifest = json.createObjectNode();
        manifest.put("baseArtifactSha256", baseDigest);
        manifest.put("presentationCanonicalSha256", canonicalHash);
        manifest.put("portableArtifactSha256", generated.sha256());
        manifest.put("version", generated.version());
        manifest.put("revision", generated.revision());
        ObjectNode assetManifest = manifest.putObject("assets");
        assets.values().stream()
                .sorted(Comparator.comparing(PortableAsset::role))
                .forEach(asset -> {
                    ObjectNode value = assetManifest.putObject(asset.role());
                    value.put("path", asset.packagePath());
                    value.put("sha256", asset.sha256());
                    value.put("sizeBytes", asset.content().length);
                });
        return profiles.updatePortableMetadata(
                        skill.id(), manifest, profile.rowVersion(), profile.portableRevision() + 1)
                .filter(updated -> updated == 1L)
                // A concurrent identical request may have won the guarded write just before us.
                // Re-read once so that race converges to the already generated revision instead
                // of returning a false conflict.  A different profile snapshot still fails closed.
                .switchIfEmpty(profiles.findBySkillId(skill.id())
                        .filter(current -> provenanceMatches(current, baseDigest, canonicalHash, generated))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "skill_expert_profile_concurrent_modification")))
                        .thenReturn(1L))
                .then();
    }

    private boolean provenanceMatches(
            SkillExpertProfileRepository.StoredProfile profile,
            String baseDigest,
            String canonicalHash,
            SkillVersionView generated) {
        JsonNode manifest = profile.assetManifest();
        return manifest != null
                && baseDigest.equals(manifest.path("baseArtifactSha256").asText(null))
                && canonicalHash.equals(manifest.path("presentationCanonicalSha256").asText(null))
                && generated.sha256() != null
                && generated.sha256().equals(manifest.path("portableArtifactSha256").asText(null))
                && generated.version().equals(manifest.path("version").asText(null))
                && generated.revision() == manifest.path("revision").asInt(-1);
    }

    private Mono<Void> download(SkillVersionRepository.PackageRef base, Path target) {
        return Mono.fromRunnable(() -> {
            try (OutputStream output = Files.newOutputStream(target)) {
                for (ByteBuffer chunk : objectStore
                        .getRange(base.objectKey(), 0L, base.sizeBytes())
                        .toIterable()) {
                    ByteBuffer copy = chunk.duplicate();
                    byte[] bytes = new byte[copy.remaining()];
                    copy.get(bytes);
                    output.write(bytes);
                }
            } catch (IOException error) {
                throw new IllegalStateException("skill_storage_unavailable", error);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void rewrite(
            Path baseFile,
            Path output,
            SkillView skill,
            Presentation presentation,
            Map<String, PortableAsset> assets,
            String minClientVersion) {
        try (ZipFile zip = new ZipFile(baseFile.toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipPayload> entries = new LinkedHashMap<>();
            String skillPath = "skills/" + skill.code() + SKILL_FILE_SUFFIX;
            byte[] plugin = null;
            byte[] runtime = null;
            for (var enumeration = zip.entries(); enumeration.hasMoreElements();) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                // YCPS 1.1 closes the entry-skill assets directory: only the three declared
                // presentation paths may remain.  Drop the whole old directory payload before
                // adding the current cloud objects so stale/unknown assets cannot make Inspector
                // reject the rebuilt package (or survive a profile change).
                if (isPresentationAssetPath(name, skill.code())) {
                    continue;
                }
                byte[] bytes = entry.isDirectory() ? new byte[0] : read(zip, entry);
                if (SkillPackageInspector.PLUGIN.equals(name)) {
                    plugin = bytes;
                } else if (SkillPackageInspector.RUNTIME.equals(name)) {
                    runtime = bytes;
                } else if (skillPath.equals(name)) {
                    bytes = rewriteSkill(bytes, skill, presentation, assets);
                }
                entries.put(name, new ZipPayload(name, entry.isDirectory(), bytes));
            }
            if (plugin == null || runtime == null || !entries.containsKey(skillPath)) {
                throw invalid("ycps_package_invalid");
            }
            entries.put(SkillPackageInspector.PLUGIN,
                    new ZipPayload(SkillPackageInspector.PLUGIN, false,
                            rewritePlugin(plugin, skill.code())));
            entries.put(SkillPackageInspector.RUNTIME,
                    new ZipPayload(SkillPackageInspector.RUNTIME, false,
                            rewriteRuntime(runtime, minClientVersion)));
            for (PortableAsset asset : assets.values()) {
                entries.put(asset.packagePath(), new ZipPayload(asset.packagePath(), false,
                        asset.content()));
            }
            writeZip(output, entries);
        } catch (IOException error) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "ycps_package_invalid", error);
        }
    }

    private byte[] rewriteSkill(
            byte[] original,
            SkillView skill,
            Presentation presentation,
            Map<String, PortableAsset> assets) throws IOException {
        String document = new String(original, StandardCharsets.UTF_8);
        int firstBreak = document.indexOf('\n');
        int close = document.indexOf("\n---", firstBreak + 1);
        if (firstBreak < 0 || close < 0) {
            throw invalid("ycps_package_invalid");
        }
        int bodyStart = close + 4;
        if (bodyStart < document.length() && document.charAt(bodyStart) == '\r') {
            bodyStart++;
        }
        if (bodyStart < document.length() && document.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        String frontmatterText = document.substring(firstBreak + 1, close + 1);
        JsonNode parsed = yaml.readTree(frontmatterText);
        if (!(parsed instanceof ObjectNode frontmatter)) {
            throw invalid("ycps_package_invalid");
        }
        JsonNode existingMetadata = frontmatter.get("metadata");
        ObjectNode metadata = json.createObjectNode();
        if (existingMetadata != null && existingMetadata.isObject()) {
            List<String> keys = new ArrayList<>();
            existingMetadata.fieldNames().forEachRemaining(keys::add);
            keys.stream().filter(key -> !isPresentationMetadata(key)).sorted()
                    .forEach(key -> metadata.set(key, existingMetadata.get(key)));
        }
        writePresentationMetadata(metadata, skill, presentation, assets);
        frontmatter.set("metadata", metadata);
        String rendered = yaml.writeValueAsString(frontmatter).stripTrailing();
        // Some Jackson YAML versions still emit a document-start marker even when the
        // generator feature is disabled.  We own the Markdown frontmatter delimiters,
        // so strip that serializer marker explicitly to avoid producing `---\n---`.
        if (rendered.startsWith("---\r\n")) {
            rendered = rendered.substring(5);
        } else if (rendered.startsWith("---\n")) {
            rendered = rendered.substring(4);
        }
        String body = document.substring(bodyStart);
        return ("---\n" + rendered + "\n---\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] rewritePlugin(byte[] original, String code) throws IOException {
        JsonNode parsed = json.readTree(original);
        if (!(parsed instanceof ObjectNode plugin)) {
            throw invalid("ycps_package_invalid");
        }
        JsonNode extensions = plugin.get("extensions");
        if (!(extensions instanceof ObjectNode extensionMap)
                || !(extensionMap.get("com.yuzhihuihe.ycps") instanceof ObjectNode ycps)) {
            throw invalid("ycps_package_invalid");
        }
        ycps.put("ycpsVersion", SkillPackageInspector.YCPS_PORTABLE_VERSION);
        ycps.put("kind", "EXPERT");
        ycps.put("entrySkill", code);
        ycps.put("manifest", "./" + SkillPackageInspector.RUNTIME);
        ArrayNode features = ycps.putArray("requiredFeatures");
        features.add(SkillPackageInspector.EXPERT_PRESENTATION_PROFILE);
        return json.writeValueAsBytes(plugin);
    }

    private byte[] rewriteRuntime(byte[] original, String minClientVersion) throws IOException {
        JsonNode parsed = json.readTree(original);
        if (!(parsed instanceof ObjectNode runtime)) {
            throw invalid("ycps_package_invalid");
        }
        runtime.put("$schema", EXPERT_SCHEMA);
        JsonNode requirements = runtime.get("hostRequirements");
        ObjectNode host = requirements instanceof ObjectNode object
                ? object : runtime.putObject("hostRequirements");
        // The generated package is a 1.1 presentation package, so it must never claim support
        // below the first client that understands that profile.  The selected value already folds
        // the base row and skill-level minimums into the floor.
        host.put("minClientVersion", minClientVersion);
        return json.writeValueAsBytes(runtime);
    }

    private void writePresentationMetadata(
            ObjectNode metadata,
            SkillView skill,
            Presentation presentation,
            Map<String, PortableAsset> assets) {
        put(metadata, "title.zh", required(skill.name()));
        optional(metadata, "title.en", presentation.titleEn());
        put(metadata, "summary.zh", required(skill.summary()));
        optional(metadata, "summary.en", presentation.summaryEn());
        put(metadata, "description.zh", required(skill.description()));
        optional(metadata, "description.en", presentation.descriptionEn());
        indexed(metadata, "expert.taskType", presentation.taskTypes(), presentation.taskTypesEn(), 64);
        put(metadata, "expert.usageGuide.zh", presentation.usageGuide());
        optional(metadata, "expert.usageGuide.en", presentation.usageGuideEn());
        indexed(metadata, "expert.examplePrompt", presentation.examplePrompts(),
                presentation.examplePromptsEn(), 300);
        preview(metadata, "zh", presentation.preview());
        if (presentation.previewEn() != null) {
            preview(metadata, "en", presentation.previewEn());
        }
        canvas(metadata, presentation.canvas());
        if (assets.containsKey("exampleImage")) {
            optional(metadata, "expert.asset.exampleImage.locale", presentation.exampleImageLocale());
        }
        assets.values().stream().sorted(Comparator.comparing(PortableAsset::role)).forEach(asset ->
                put(metadata, "expert.asset." + asset.role() + ".path",
                        asset.packagePath().substring(("skills/" + skill.code()).length() + 1)));
    }

    private void indexed(
            ObjectNode metadata,
            String prefix,
            List<String> zh,
            List<String> en,
            int maxChars) {
        for (int i = 0; i < zh.size(); i++) {
            put(metadata, prefix + "." + String.format(Locale.ROOT, "%02d", i + 1) + ".zh",
                    zh.get(i));
        }
        for (int i = 0; i < en.size(); i++) {
            put(metadata, prefix + "." + String.format(Locale.ROOT, "%02d", i + 1) + ".en",
                    en.get(i));
        }
    }

    private void preview(ObjectNode metadata, String language, ExpertPreviewContent preview) {
        ExpertPreviewContent value = preview;
        put(metadata, "expert.preview.heading." + language, value.heading());
        optional(metadata, "expert.preview.scene." + language, value.scene());
        put(metadata, "expert.preview.request." + language, value.request());
        optional(metadata, "expert.preview.material." + language, value.material());
        put(metadata, "expert.preview.outputTitle." + language, value.outputTitle());
        for (int i = 0; i < value.outputParagraphs().size(); i++) {
            put(metadata, "expert.preview.outputParagraph."
                    + String.format(Locale.ROOT, "%02d", i + 1) + "." + language,
                    value.outputParagraphs().get(i));
        }
        // Keep a table's lead-in as one semantic block.  Older profiles encoded the same
        // sentence as a pipe-delimited output paragraph; the v1 compact value lets the editor
        // migrate that paragraph without losing its tone or placing it after the table on export.
        if (value.intro() != null) {
            try {
                put(metadata, "expert.preview.intro.v1." + language,
                        json.writeValueAsString(withoutNulls(sorted(json.valueToTree(value.intro())))));
            } catch (IOException error) {
                throw new IllegalStateException("expert_presentation_intro_serialization_failed", error);
            }
        }
        // Structured tables are kept as one deterministic compact JSON value.  Splitting cells
        // into metadata keys would make row/column shape ambiguous and would lose numeric values
        // on a reverse import.  The inspector applies the dedicated table bound and validates
        // the decoded object against the same API model used by the public detail response.
        if (value.table() != null) {
            try {
                put(metadata, "expert.preview.table.v1." + language,
                        json.writeValueAsString(withoutNulls(sorted(json.valueToTree(value.table())))));
            } catch (IOException error) {
                throw new IllegalStateException("expert_presentation_table_serialization_failed", error);
            }
        }
    }

    /**
     * Desktop YCPS readers treat explicit nulls in the table style object as invalid optional
     * fields.  Keep this cleanup local to the table metadata export so nulls in the other
     * presentation metadata retain their existing meaning.
     */
    private JsonNode withoutNulls(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                JsonNode value = withoutNulls(entry.getValue());
                if (value != null) {
                    result.set(entry.getKey(), value);
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(value -> {
                JsonNode sanitized = withoutNulls(value);
                if (sanitized != null) {
                    result.add(sanitized);
                }
            });
            return result;
        }
        return node;
    }

    /** Store the complete layout as one canonical compact JSON value per locale. */
    private void canvas(ObjectNode metadata, String language, JsonNode localized) {
        if (localized == null || localized.isNull()) return;
        JsonNode layout = localized.has("version") ? ("zh".equals(language) ? localized : null) : localized.get(language);
        if (layout == null || layout.isNull()) return;
        try {
            put(metadata, "expert.canvas.v1." + language, json.writeValueAsString(sorted(layout)));
        } catch (IOException error) {
            throw new IllegalStateException("expert_canvas_serialization_failed", error);
        }
    }

    /** Canvas layouts are opaque JSON values; unlike scalar metadata they may be up to 32 KiB. */
    private void canvas(ObjectNode metadata, JsonNode value) {
        if (value == null || value.isNull()) return;
        for (String language : List.of("zh", "en")) {
            JsonNode layout = value.has("version") && "zh".equals(language)
                    ? value : value.get(language);
            if (layout == null || layout.isNull()) continue;
            try {
                put(metadata, "expert.canvas.v1." + language, json.writeValueAsString(sorted(layout)));
            } catch (IOException error) {
                throw new IllegalStateException("expert_presentation_canvas_invalid", error);
            }
        }
    }

    private Mono<Map<String, PortableAsset>> loadAssets(SkillView skill) {
        return Flux.just("icon", "illustration", "exampleImage")
                .concatMap(role -> asset(skill, role))
                .collectMap(PortableAsset::role, asset -> asset, LinkedHashMap::new);
    }

    private Mono<PortableAsset> asset(SkillView skill, String role) {
        Mono<String> key = switch (role) {
            case "icon" -> skills.iconObjectKey(skill.id());
            case "illustration" -> skills.illustrationObjectKey(skill.id());
            case "exampleImage" -> skills.exampleImageObjectKey(skill.id());
            default -> Mono.empty();
        };
        int limit = switch (role) {
            case "icon" -> ICON_MAX_BYTES;
            case "illustration" -> ILLUSTRATION_MAX_BYTES;
            default -> EXAMPLE_MAX_BYTES;
        };
        return key.flatMap(objectKey -> {
            String extension = extension(objectKey);
            String packagePath = "skills/" + skill.code() + ASSET_ROOT
                    + assetFileName(role) + extension;
            return objectStore.get(objectKey, limit)
                    .switchIfEmpty(Mono.error(assetInvalid()))
                    .flatMap(bytes -> Mono.fromCallable(() -> {
                        try {
                            // Existing object pointers may predate the aggregate upload endpoint.
                            // Re-run the portable role-specific checks here so a legacy oversized,
                            // wrong-ratio, or extension/magic-mismatched image can never be sealed
                            // into a YCPS 1.1 artifact.
                            ExpertImageValidator.validate(bytes, role, mediaType(extension));
                        } catch (IllegalArgumentException error) {
                            throw assetInvalid();
                        }
                        return new PortableAsset(role, packagePath, bytes,
                                sha256(bytes));
                    }));
        });
    }

    private static Presentation presentation(
            SkillView skill,
            SkillExpertProfileRepository.StoredProfile profile) {
        return new Presentation(
                required(skill.name()),
                optional(skill.nameEn()),
                required(skill.summary()),
                optional(skill.summaryEn()),
                required(skill.description()),
                optional(skill.descriptionEn()),
                List.copyOf(profile.taskTypes()),
                List.copyOf(profile.taskTypesEn()),
                profile.usageGuide(),
                profile.usageGuideEn(),
                List.copyOf(profile.examplePrompts()),
                List.copyOf(profile.examplePromptsEn()),
                profile.preview(),
                profile.previewEn(),
                profile.canvas(),
                profile.exampleImageLocale());
    }

    private static void validatePresentation(Presentation presentation) {
        if (presentation.title().isBlank()
                || presentation.summary().isBlank()
                || presentation.description().isBlank()
                || presentation.taskTypes().isEmpty() || presentation.usageGuide() == null
                || presentation.usageGuide().isBlank() || presentation.examplePrompts().isEmpty()
                || presentation.preview() == null || presentation.preview().outputParagraphs().isEmpty()) {
            throw incompleteProfile();
        }
        // Profiles written before the canvas column/validator was introduced may still be
        // present.  Revalidate at the package boundary so a generated YCPS can never carry an
        // oversized or malformed opaque layout even if it was inserted out-of-band.
        ExpertPresentationValidator.canvas(presentation.canvas());
        if (presentation.previewEn() == null) {
            // English is optional; clients apply the Chinese fallback at read time.
        }
    }

    private String presentationHash(
            SkillView skill,
            Presentation presentation,
            Map<String, PortableAsset> assets,
            String minClientVersion) {
        ObjectNode value = json.createObjectNode();
        value.put("code", skill.code());
        value.put("title", presentation.title());
        value.put("titleEn", presentation.titleEn());
        value.put("summary", presentation.summary());
        value.put("summaryEn", presentation.summaryEn());
        value.put("description", presentation.description());
        value.put("descriptionEn", presentation.descriptionEn());
        value.put("usageGuide", presentation.usageGuide());
        value.put("usageGuideEn", optional(presentation.usageGuideEn()));
        value.set("taskTypes", json.valueToTree(presentation.taskTypes()));
        value.set("taskTypesEn", json.valueToTree(presentation.taskTypesEn()));
        value.set("examplePrompts", json.valueToTree(presentation.examplePrompts()));
        value.set("examplePromptsEn", json.valueToTree(presentation.examplePromptsEn()));
        value.set("preview", json.valueToTree(presentation.preview()));
        value.set("previewEn", json.valueToTree(presentation.previewEn()));
        value.set("canvas", json.valueToTree(presentation.canvas()));
        value.put("exampleImageLocale", assets.containsKey("exampleImage")
                ? presentation.exampleImageLocale() : null);
        value.put("minClientVersion", minClientVersion);
        ObjectNode media = value.putObject("assets");
        assets.values().stream().sorted(Comparator.comparing(PortableAsset::role)).forEach(asset -> {
            ObjectNode item = media.putObject(asset.role());
            item.put("path", asset.packagePath());
            item.put("sha256", asset.sha256());
            item.put("sizeBytes", asset.content().length);
        });
        try {
            return sha256(json.writeValueAsBytes(sorted(value)));
        } catch (IOException error) {
            throw new IllegalStateException("json_serialization_failed", error);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node == null || node.isValueNode() || node.isNull()) {
            return node;
        }
        ObjectMapper mapper = new ObjectMapper();
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            fields.forEach(field -> result.set(field, sorted(node.get(field))));
            return result;
        }
        ArrayNode result = mapper.createArrayNode();
        node.forEach(child -> result.add(sorted(child)));
        return result;
    }

    private void writeZip(Path output, Map<String, ZipPayload> entries) throws IOException {
        List<ZipPayload> ordered = entries.values().stream()
                .sorted((left, right) -> compareUtf8(left.name(), right.name()))
                .toList();
        try (OutputStream stream = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            for (ZipPayload payload : ordered) {
                ZipEntry entry = new ZipEntry(payload.name());
                entry.setTime(DETERMINISTIC_ZIP_TIME_MILLIS);
                entry.setComment(null);
                entry.setExtra(null);
                entry.setMethod(ZipEntry.DEFLATED);
                zip.putNextEntry(entry);
                zip.write(payload.content());
                zip.closeEntry();
            }
        }
    }

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int i = 0; i < length; i++) {
            int compared = Byte.toUnsignedInt(leftBytes[i]) - Byte.toUnsignedInt(rightBytes[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private static boolean isPresentationAssetPath(String name, String code) {
        String root = "skills/" + code + ASSET_ROOT;
        // Keep the explicit assets directory entry itself, when the source archive has one.  All
        // descendants are rebuilt from the cloud profile and must not be copied byte-for-byte.
        return name.startsWith(root) && !name.equals(root);
    }

    private static boolean isPresentationMetadata(String key) {
        return key.startsWith("expert.")
                || key.matches("(?:title|summary|description)\\.(?:zh|en)");
    }

    private static String assetFileName(String role) {
        return "exampleImage".equals(role) ? "example-image" : role;
    }

    private static String extension(String key) {
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        if (dot <= slash || dot == key.length() - 1) {
            throw assetInvalid();
        }
        String extension = key.substring(dot).toLowerCase(Locale.ROOT);
        if (!List.of(".png", ".jpg", ".jpeg", ".webp").contains(extension)) {
            throw assetInvalid();
        }
        return extension;
    }

    private static String mediaType(String extension) {
        return ".jpg".equals(extension) || ".jpeg".equals(extension)
                ? "image/jpeg" : ".webp".equals(extension) ? "image/webp" : "image/png";
    }

    private static String selectedMinimum(
            SkillView skill, SkillVersionRepository.PackageRef base) {
        String selected = DEFAULT_MIN_CLIENT_VERSION;
        String[] configured = {base.minClientVersion(), skill.minClientVersion()};
        for (String candidate : configured) {
            if (candidate != null && !candidate.isBlank()
                    && SkillVersionRepository.compareVersion(candidate.strip(), selected) > 0) {
                selected = candidate.strip();
            }
        }
        return selected;
    }

    private static String required(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    private static void optional(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    private static Mono<SkillView> requireExpert(SkillView skill) {
        return "EXPERT".equalsIgnoreCase(skill.kind())
                ? Mono.just(skill)
                : Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "skill_expert_profile_kind_required"));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("digest_unavailable", error);
        }
    }

    private static String digest(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new IllegalStateException("skill_storage_unavailable", error);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The temporary-file cleaner is the fallback and must not mask a pipeline result.
        }
    }

    private static Mono<Void> cleanup(Path path) {
        return Mono.fromRunnable(() -> deleteQuietly(path));
    }

    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    private static ResponseStatusException incompleteProfile() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "skill_expert_profile_required");
    }

    private static ResponseStatusException assetInvalid() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "expert_presentation_asset_invalid");
    }

    private static ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }

    private static boolean isVersionConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResponseStatusException status
                    && "skill_version_conflict".equals(status.getReason())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record ZipPayload(String name, boolean directory, byte[] content) {}

    private record PortableAsset(String role, String packagePath, byte[] content, String sha256) {}

    private record Presentation(
            String title,
            String titleEn,
            String summary,
            String summaryEn,
            String description,
            String descriptionEn,
            List<String> taskTypes,
            List<String> taskTypesEn,
            String usageGuide,
            String usageGuideEn,
            List<String> examplePrompts,
            List<String> examplePromptsEn,
            ExpertPreviewContent preview,
            ExpertPreviewContent previewEn,
            JsonNode canvas,
            String exampleImageLocale) {}
}
