package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewBlock;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewTable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** YCPS package validator. Legacy {@code skill.json + /SKILL.md} archives are rejected. */
final class SkillPackageInspector {

    static final String PACKAGE_FORMAT = "YCPS";
    static final String YCPS_VERSION = "1.0.0";
    static final String YCPS_PORTABLE_VERSION = "1.1.0";
    static final String EXPERT_PRESENTATION_PROFILE = "expert-presentation-v1";
    static final String PLUGIN = "plugin.json";
    static final String MCP = "mcp.json";
    static final String RUNTIME = "com.yuzhihuihe.ycps/manifest.json";
    private static final String PLUGIN_SCHEMA =
            "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json";
    private static final String SCHEMA_BASE = "https://schemas.yuzhihuihe.com/ycps/1.0.0/";
    private static final String SCHEMA_BASE_1_1 = "https://schemas.yuzhihuihe.com/ycps/1.1.0/";
    private static final Pattern CODE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern TOOL = Pattern.compile(
            "^[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CREDENTIAL_NAME = Pattern.compile(
            ".*(authorization|credential|password|secret|token|api[-_]?key|cookie).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_ARGUMENT = Pattern.compile(
            "(?:^|\\s)--?(?:authorization|credential|password|secret|token|api[-_]?key)(?:=|\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> KINDS = Set.of("SKILL", "EXPERT", "TEAM");
    private static final Set<String> YCPS_VERSIONS = Set.of(YCPS_VERSION, YCPS_PORTABLE_VERSION);
    private static final Set<String> PLUGIN_FIELDS = Set.of(
            "$schema", "name", "version", "description", "author", "homepage",
            "repository", "license", "keywords", "extensions");
    private static final Set<String> TOOL_CAPABILITIES = Set.of(
            "browser.automate", "calendar.read", "calendar.write", "document.read",
            "document.write", "media.ingest", "pdf.read", "pdf.write",
            "presentation.read", "presentation.write", "process.execute", "skill.manage",
            "skill.validate", "spreadsheet.read", "spreadsheet.write", "web.search");
    private static final Set<String> FILESYSTEM_SCOPES = Set.of(
            "USER_SELECTED:read", "USER_SELECTED:read-write",
            "PLUGIN_DATA:read-write");
    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            ".cjs", ".js", ".mjs", ".ps1", ".py", ".sh", ".ts");
    private static final Set<String> SKILL_FRONTMATTER = Set.of(
            "name", "description", "license", "compatibility", "metadata");
    /**
     * Catalog copy fields a package may declare under {@code metadata} as {@code <field>.<lang>}.
     *
     * <p>Restricted to the copy the console already owns, so an author cannot smuggle operational
     * state (category, tags, sort weight, publish status) in through the package.
     *
     * <p>Only two fields: {@code title} (the card heading) and {@code summary} (the one-line blurb,
     * which the console stores in both its {@code summary} and {@code description} columns). Keeping
     * the blurb to a single key avoids the trap where an English-authored {@code description} would
     * silently land in the Chinese column.
     */
    private static final Set<String> CATALOG_COPY_FIELDS = Set.of("title", "summary");
    /** Language subtag: BCP 47 primary language, optionally with a region (for example {@code en}, {@code zh-Hant}). */
    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$");
    /** Matches the frontmatter {@code description} ceiling so package copy cannot outgrow the console columns. */
    private static final int CATALOG_COPY_MAX_CHARS = 1024;
    private static final int METADATA_TOTAL_VALUE_MAX_BYTES = 32 * 1024;
    private static final int MAX_PRESENTATION_KEYS = 64;
    private static final int MAX_TASK_TYPES = 6;
    private static final int MAX_PRESENTATION_PROMPTS = 8;
    /** Canvas JSON is one opaque presentation value, not a catalog metadata scalar. */
    private static final int MAX_CANVAS_JSON_CHARS = 32 * 1024;
    /** A table is a single compact JSON metadata value, bounded below the aggregate ceiling. */
    private static final int MAX_TABLE_JSON_CHARS = 16 * 1024;
    /** Intro blocks use the same text/tone contract as the API preview block. */
    private static final int MAX_INTRO_JSON_CHARS = 2 * 1024;
    private static final int MAX_PREVIEW_PARAGRAPHS = 8;
    private static final Set<String> ROOT_ENTRIES = Set.of(
            PLUGIN, "skills", MCP, "com.yuzhihuihe.ycps", "LICENSE", "NOTICE");
    private static final int MANIFEST_MAX_BYTES = 64 * 1024;
    private static final int SKILL_MAX_BYTES = 256 * 1024;
    private static final int PORTABLE_ASSET_MAX_BYTES = 640 * 1024;
    private static final int PORTABLE_TOTAL_ASSET_MAX_BYTES = 1_280 * 1024;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_PATH_BYTES = 240;
    private static final int BLOCK = 8192;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final int ZIP64_EXTRA_ID = 0x0001;
    private static final int EOCD_MAX_SCAN = 65_557;
    private static final int UNIX_MADE_BY = 3;
    private static final int MACOS_MADE_BY = 19;
    private static final int REGULAR_MODE = 0x8000;
    private static final int DIRECTORY_MODE = 0x4000;
    private static final int SYMLINK_MODE = 0xA000;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul", "clock$",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final ObjectMapper yaml = new YAMLMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private SkillPackageInspector() {}

    static SkillPackageInspector create() {
        return new SkillPackageInspector();
    }

    Report inspect(Path packageFile, SkillProperties properties, String expectedCode) {
        try (ZipFile zip = new ZipFile(packageFile.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() > Math.min(MAX_ENTRIES, properties.getMaxPackageEntries())) {
                throw unsafe("too_many_entries");
            }
            if (hasSymlinkEntry(packageFile)) {
                throw unsafe("symlink_or_unsupported_zip");
            }
            long extractedBytes = 0;
            int fileCount = 0;
            byte[] pluginBytes = null;
            byte[] runtimeBytes = null;
            byte[] mcpBytes = null;
            byte[] skillBytes = null;
            String skillPath = null;
            Map<String, byte[]> embeddedAgents = new HashMap<>();
            Map<String, byte[]> presentationAssets = new LinkedHashMap<>();
            Set<String> allPaths = new HashSet<>();
            Set<String> normalizedNames = new HashSet<>();
            Set<String> directSkillDirectories = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                validateRootShapeAndCase(name, entry.isDirectory());
                if ("skill.json".equalsIgnoreCase(name) || "SKILL.md".equalsIgnoreCase(name)) {
                    throw invalid("legacy_package_entry");
                }
                if (isUnsafeName(name)
                        || name.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                    throw unsafe("unsafe_entry_name");
                }
                String collisionName = name.endsWith("/")
                        ? name.substring(0, name.length() - 1)
                        : name;
                if (!normalizedNames.add(collisionName.toLowerCase(Locale.ROOT))) {
                    throw unsafe("duplicate_entry_name");
                }
                allPaths.add(collisionName);
                collectDirectSkillDirectory(collisionName, directSkillDirectories);
                if (entry.isDirectory()) {
                    continue;
                }
                fileCount++;
                long actual;
                if (PLUGIN.equals(name)) {
                    pluginBytes = capture(zip, entry, MANIFEST_MAX_BYTES, "plugin_too_large");
                    actual = pluginBytes.length;
                } else if (MCP.equals(name)) {
                    mcpBytes = capture(zip, entry, MANIFEST_MAX_BYTES, "mcp_too_large");
                    actual = mcpBytes.length;
                } else if (RUNTIME.equals(name)) {
                    runtimeBytes = capture(zip, entry, MANIFEST_MAX_BYTES, "manifest_too_large");
                    actual = runtimeBytes.length;
                } else if (name.endsWith("/SKILL.md")) {
                    if (skillBytes != null) {
                        throw invalid("multiple_skill_documents");
                    }
                    skillBytes = capture(zip, entry, SKILL_MAX_BYTES, "skill_document_too_large");
                    skillPath = name;
                    actual = skillBytes.length;
                } else if (name.startsWith("com.yuzhihuihe.ycps/agents/")
                        && name.endsWith(".md")) {
                    byte[] agentBytes = capture(
                            zip, entry, SKILL_MAX_BYTES, "embedded_agent_too_large");
                    embeddedAgents.put(name, agentBytes);
                    actual = agentBytes.length;
                } else if (isPresentationAssetCandidate(name)) {
                    byte[] assetBytes = capture(
                            zip, entry, PORTABLE_ASSET_MAX_BYTES, "expert_asset_too_large");
                    presentationAssets.put(name, assetBytes);
                    actual = assetBytes.length;
                } else {
                    long perEntryLimit = Math.min(
                            25L * 1024 * 1024,
                            Math.max(1, properties.getMaxExtractedBytes().toBytes()));
                    actual = measure(zip, entry, perEntryLimit,
                            properties.getMaxExtractedBytes().toBytes() - extractedBytes);
                }
                extractedBytes += actual;
                if (extractedBytes > properties.getMaxExtractedBytes().toBytes()) {
                    throw unsafe("extracted_too_large");
                }
                checkRatio(entry, actual, Math.min(100, properties.getMinCompressionRatio()));
            }
            if (pluginBytes == null || runtimeBytes == null || skillBytes == null) {
                throw invalid("missing_required_ycps_entry");
            }
            if (directSkillDirectories.size() != 1) {
                throw invalid("entry_skill_directory_count");
            }

            JsonNode plugin = json(pluginBytes, "plugin_invalid");
            PluginIdentity identity = validatePlugin(plugin);
            requireIdentity(identity, expectedCode);
            String expectedSkillPath = "skills/" + identity.code() + "/SKILL.md";
            if (!expectedSkillPath.equals(skillPath)
                    || !directSkillDirectories.equals(Set.of(identity.code()))) {
                throw identityMismatch("entry skill path does not match package name");
            }
            CatalogCopy catalogCopy = validateSkill(skillBytes, identity.code());
            ExpertPresentation expertPresentation = identity.ycpsVersion().equals(YCPS_PORTABLE_VERSION)
                    ? validateExpertPresentation(
                            skillBytes,
                            identity,
                            allPaths,
                            presentationAssets)
                    : ExpertPresentation.EMPTY;
            JsonNode runtime = json(runtimeBytes, "runtime_manifest_invalid");
            validateRuntime(runtime, identity, allPaths, embeddedAgents);
            validateExecutablePermissions(allPaths, identity.code(), runtime);
            if (mcpBytes != null) {
                validateMcp(json(mcpBytes, "mcp_json_invalid"), runtime);
            }
            enforceProfileLimits(packageFile, identity.kind(), extractedBytes);

            String manifestSha256 = sha256(canonicalPair(plugin, runtime));
            String dependencyLockSha256 = sha256(canonicalDependencies(runtime.get("dependencies")));
            return new Report(
                    fileCount,
                    extractedBytes,
                    identity.code(),
                    identity.version(),
                    identity.kind(),
                    identity.ycpsVersion(),
                    hostMinimum(runtime),
                    plugin,
                    runtime,
                    json.writeValueAsString(plugin),
                    json.writeValueAsString(runtime),
                    manifestSha256,
                    dependencyLockSha256,
                    catalogCopy,
                    expertPresentation);
        } catch (ZipException exception) {
            throw invalid("not_a_zip");
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "skill_storage_unavailable", exception);
        }
    }

    private static void collectDirectSkillDirectory(String path, Set<String> directories) {
        if (!path.startsWith("skills/")) {
            return;
        }
        String rest = path.substring("skills/".length());
        int slash = rest.indexOf('/');
        if (slash > 0) {
            directories.add(rest.substring(0, slash));
        }
    }

    private static void validateRootShapeAndCase(String name, boolean directory) {
        String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (path.isEmpty()) {
            throw unsafe("unsafe_entry_name");
        }
        int separator = path.indexOf('/');
        String rootName = separator < 0 ? path : path.substring(0, separator);
        if (!ROOT_ENTRIES.contains(rootName)) {
            if (ROOT_ENTRIES.stream().anyMatch(rootName::equalsIgnoreCase)) {
                throw invalid("fixed_entry_case_invalid");
            }
            throw invalid("root_entry_not_allowed");
        }
        boolean container = "skills".equals(rootName)
                || "com.yuzhihuihe.ycps".equals(rootName);
        if (separator >= 0 && !container || separator < 0 && container != directory) {
            throw invalid("root_entry_not_allowed");
        }
        if (path.equalsIgnoreCase(PLUGIN) && !path.equals(PLUGIN)
                || path.equalsIgnoreCase(RUNTIME) && !path.equals(RUNTIME)) {
            throw invalid("fixed_entry_case_invalid");
        }
        String basename = path.substring(path.lastIndexOf('/') + 1);
        if (basename.equalsIgnoreCase("SKILL.md") && !basename.equals("SKILL.md")) {
            throw invalid("fixed_entry_case_invalid");
        }
    }

    private PluginIdentity validatePlugin(JsonNode plugin) {
        requireObject(plugin, "plugin_invalid");
        requireOnly(plugin, PLUGIN_FIELDS, "plugin_closed");
        String pluginSchema = requiredText(plugin, "$schema", 160, "plugin_schema_invalid");
        if (!PLUGIN_SCHEMA.equals(pluginSchema)) {
            throw invalid("plugin_schema_invalid");
        }
        String code = requiredText(plugin, "name", 64, "plugin_name_invalid");
        if (!CODE.matcher(code).matches()) {
            throw invalid("plugin_name_invalid");
        }
        String version = requiredText(plugin, "version", 32, "plugin_version_invalid");
        if (!SEMVER.matcher(version).matches()) {
            throw invalid("plugin_version_invalid");
        }
        requiredText(plugin, "description", "plugin_description_invalid");
        JsonNode author = plugin.get("author");
        requireObject(author, "plugin_author_invalid");
        requireOnly(author, Set.of("name", "email", "url"), "plugin_author_invalid");
        requiredText(author, "name", "plugin_author_invalid");
        optionalText(author, "email", "plugin_author_invalid");
        optionalText(author, "url", "plugin_author_invalid");
        optionalText(plugin, "homepage", "plugin_homepage_invalid");
        optionalText(plugin, "repository", "plugin_repository_invalid");
        requiredText(plugin, "license", "plugin_license_invalid");
        for (String forbidden : List.of("kind", "members", "entrySkill", "expertType")) {
            if (plugin.has(forbidden)) {
                throw invalid("plugin_private_field");
            }
        }
        JsonNode extensions = plugin.get("extensions");
        requireObject(extensions, "plugin_extension_invalid");
        extensions.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isObject()) {
                throw invalid("plugin_extension_invalid");
            }
        });
        JsonNode keywords = plugin.get("keywords");
        if (keywords != null) {
            if (!keywords.isArray()) {
                throw invalid("plugin_keywords_invalid");
            }
            for (JsonNode keyword : keywords) {
                if (!keyword.isTextual()) {
                    throw invalid("plugin_keywords_invalid");
                }
            }
        }
        JsonNode extension = extensions.get("com.yuzhihuihe.ycps");
        requireObject(extension, "plugin_extension_invalid");
        requireOnly(extension, Set.of(
                "ycpsVersion", "kind", "entrySkill", "manifest", "requiredFeatures"),
                "plugin_extension_invalid");
        String ycpsVersion = requiredText(extension, "ycpsVersion", 16, "ycps_version_unsupported");
        if (!YCPS_VERSIONS.contains(ycpsVersion)) {
            throw invalid("ycps_version_unsupported");
        }
        String kind = requiredText(extension, "kind", 16, "package_kind_invalid");
        if (!KINDS.contains(kind)) {
            throw invalid("package_kind_invalid");
        }
        if (YCPS_PORTABLE_VERSION.equals(ycpsVersion)) {
            if (!"EXPERT".equals(kind)
                    || !PLUGIN_SCHEMA.equals(pluginSchema)) {
                throw invalid("expert_presentation_profile_invalid");
            }
        }
        requireTextEquals(extension, "entrySkill", code, "package_identity_mismatch");
        requireTextEquals(extension, "manifest", "./" + RUNTIME, "plugin_extension_invalid");
        JsonNode features = extension.get("requiredFeatures");
        if (features == null || !features.isArray()) {
            throw invalid("required_features_unsupported");
        }
        if (YCPS_PORTABLE_VERSION.equals(ycpsVersion)) {
            if (features.size() != 1
                    || !features.get(0).isTextual()
                    || !EXPERT_PRESENTATION_PROFILE.equals(features.get(0).asText())) {
                throw invalid("expert_presentation_profile_invalid");
            }
        } else if (!features.isEmpty()) {
            throw invalid("required_features_unsupported");
        }
        return new PluginIdentity(code, version, kind, ycpsVersion);
    }

    private CatalogCopy validateSkill(byte[] bytes, String code) {
        String document = utf8(bytes, "skill_document_invalid");
        if (!document.startsWith("---\n") && !document.startsWith("---\r\n")) {
            throw invalid("skill_frontmatter_missing");
        }
        int firstBreak = document.indexOf('\n');
        int close = document.indexOf("\n---", firstBreak + 1);
        if (close < 0) {
            throw invalid("skill_frontmatter_invalid");
        }
        int bodyStart = close + 4;
        if (bodyStart < document.length() && document.charAt(bodyStart) == '\r') {
            bodyStart++;
        }
        if (bodyStart < document.length() && document.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        JsonNode frontmatter;
        try {
            frontmatter = yaml.readTree(document.substring(firstBreak + 1, close + 1));
        } catch (IOException exception) {
            throw invalid("skill_frontmatter_invalid");
        }
        requireObject(frontmatter, "skill_frontmatter_invalid");
        requireOnly(frontmatter, SKILL_FRONTMATTER, "skill_frontmatter_field_invalid");
        requireTextEquals(frontmatter, "name", code, "package_identity_mismatch");
        requiredText(frontmatter, "description", 1024, "skill_description_invalid");
        JsonNode compatibility = frontmatter.get("compatibility");
        if (compatibility != null
                && (!compatibility.isTextual() || compatibility.asText().length() > 500)) {
            throw invalid("skill_compatibility_invalid");
        }
        JsonNode metadata = frontmatter.get("metadata");
        if (metadata != null) {
            requireObject(metadata, "skill_metadata_invalid");
            metadata.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw invalid("skill_metadata_invalid");
                }
            });
        }
        if (document.substring(bodyStart).isBlank()) {
            throw invalid("skill_document_empty");
        }
        return catalogCopy(frontmatter, metadata);
    }

    /**
     * Collects the catalog copy a package declares, so ingest can seed {@code gateway.skill}.
     *
     * <p>The default language takes the bare {@code description} field plus any {@code <field>.zh}
     * keys; every other language takes its {@code <field>.<lang>} keys. A package that declares only
     * the default copy yields a {@link CatalogCopy} whose {@code localized} map is empty — the
     * console then keeps its own copy for the other languages, which is the pre-existing behaviour.
     *
     * <p>Copy is deliberately capped at the same 1024 characters the frontmatter {@code description}
     * already observes, and the console's own column limits are enforced on write.
     */
    private static CatalogCopy catalogCopy(JsonNode frontmatter, JsonNode metadata) {
        Map<String, Map<String, String>> localized = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();

        JsonNode description = frontmatter.get("description");
        if (description != null && description.isTextual() && !description.asText().isBlank()) {
            defaults.put("summary", description.asText());
        }

        if (metadata == null) {
            return new CatalogCopy(Map.copyOf(defaults), Map.of());
        }
        metadata.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            int dot = key.lastIndexOf('.');
            if (dot <= 0 || dot == key.length() - 1) {
                return;
            }
            String field = key.substring(0, dot);
            String language = key.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!CATALOG_COPY_FIELDS.contains(field) || !LANGUAGE_TAG.matcher(language).matches()) {
                return;
            }
            String value = entry.getValue().asText();
            if (value.isBlank() || value.length() > CATALOG_COPY_MAX_CHARS) {
                return;
            }
            localized.computeIfAbsent(language, ignored -> new LinkedHashMap<>()).put(field, value);
            if (CatalogCopy.DEFAULT_LANGUAGE.equals(language)) {
                defaults.put(field, value);
            }
        });
        Map<String, Map<String, String>> frozen = new LinkedHashMap<>();
        localized.forEach((language, copy) -> frozen.put(language, Map.copyOf(copy)));
        return new CatalogCopy(Map.copyOf(defaults), Map.copyOf(frozen));
    }

    private static boolean isPresentationAssetCandidate(String name) {
        return name.startsWith("skills/") && name.contains("/assets/")
                && name.matches("^skills/[^/]+/assets/(?:icon|illustration|example-image)\\.(?:png|jpe?g|webp)\\z");
    }

    /** Validates the opt-in YCPS 1.1 Expert presentation profile and its package-local assets. */
    private ExpertPresentation validateExpertPresentation(
            byte[] skillBytes,
            PluginIdentity identity,
            Set<String> allPaths,
            Map<String, byte[]> assets) {
        JsonNode frontmatter = skillFrontmatter(skillBytes);
        JsonNode metadata = frontmatter.get("metadata");
        requireObject(metadata, "expert_presentation_metadata_required");

        Map<String, String> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int totalValueBytes = 0;
        int expertKeyCount = 0;
        Iterator<Map.Entry<String, JsonNode>> metadataEntries = metadata.fields();
        while (metadataEntries.hasNext()) {
            Map.Entry<String, JsonNode> entry = metadataEntries.next();
            String key = entry.getKey();
            boolean expertKey = key.startsWith("expert.");
            boolean presentationCopy = isPresentationCopyKey(key);
            boolean canvasKey = key.matches("expert\\.canvas\\.v1\\.(?:zh|en)");
            boolean tableKey = key.matches("expert\\.preview\\.table\\.v1\\.(?:zh|en)");
            boolean introKey = key.matches("expert\\.preview\\.intro\\.v1\\.(?:zh|en)");
            if (!expertKey && !presentationCopy) {
                // YCPS metadata remains extensible for authors. Only the presentation namespace
                // is interpreted and constrained by this profile. Preserve compatibility, but
                // surface the ignored author key to an operator and still apply the 1.1 value
                // ceiling to every metadata value.
                if (!entry.getValue().isTextual()
                        || entry.getValue().asText().length() > CATALOG_COPY_MAX_CHARS) {
                    throw invalid("skill_metadata_invalid");
                }
                warnings.add("author_metadata_ignored:" + key);
                continue;
            }
            if (expertKey && ++expertKeyCount > MAX_PRESENTATION_KEYS) {
                throw invalid("expert_presentation_metadata_too_many_keys");
            }
            if (!entry.getValue().isTextual()
                    || entry.getValue().asText().isBlank()
                    || entry.getValue().asText().length()
                            > (canvasKey ? MAX_CANVAS_JSON_CHARS
                                    : tableKey ? MAX_TABLE_JSON_CHARS
                                            : introKey ? MAX_INTRO_JSON_CHARS : CATALOG_COPY_MAX_CHARS)
                    || !knownPresentationKey(key)) {
                    throw invalid("expert_presentation_metadata_invalid");
            }
            if (expertKey) {
                totalValueBytes += entry.getValue().asText().getBytes(StandardCharsets.UTF_8).length;
                if (totalValueBytes > METADATA_TOTAL_VALUE_MAX_BYTES) {
                    throw invalid("expert_presentation_metadata_too_large");
                }
            }
            values.put(key, entry.getValue().asText());
        }

        String title = value(values, "title.zh");
        String titleEn = value(values, "title.en");
        String summary = value(values, "summary.zh");
        String summaryEn = value(values, "summary.en");
        String description = value(values, "description.zh");
        String descriptionEn = value(values, "description.en");
        if (title == null || summary == null || description == null) {
            throw invalid("expert_presentation_catalog_copy_required");
        }
        // The portable metadata ceiling is 1024 for every value, but the values copied into the
        // gateway aggregate must also satisfy the existing catalog column/editor limits.  Enforce
        // those limits here so a valid-looking package cannot reach commit and fail as a SQL 500.
        requireLengths(List.of(title), 128, "expert_presentation_catalog_copy_invalid");
        if (titleEn != null) requireLengths(List.of(titleEn), 128,
                "expert_presentation_catalog_copy_invalid");
        requireLengths(List.of(summary), 512, "expert_presentation_catalog_copy_invalid");
        if (summaryEn != null) requireLengths(List.of(summaryEn), 512,
                "expert_presentation_catalog_copy_invalid");
        requireLengths(List.of(description), 1024, "expert_presentation_catalog_copy_invalid");
        if (descriptionEn != null) requireLengths(List.of(descriptionEn), 1024,
                "expert_presentation_catalog_copy_invalid");
        String usageGuide = value(values, "expert.usageGuide.zh");
        if (usageGuide == null) {
            throw invalid("expert_presentation_usage_guide_required");
        }
        String usageGuideEn = value(values, "expert.usageGuide.en");
        List<String> taskTypes = indexed(values, "expert.taskType", "zh", MAX_TASK_TYPES,
                "expert_presentation_task_types_invalid");
        List<String> taskTypesEn = indexed(values, "expert.taskType", "en", MAX_TASK_TYPES,
                "expert_presentation_task_types_invalid");
        if (taskTypes.isEmpty()) {
            throw invalid("expert_presentation_task_types_required");
        }
        requireLengths(taskTypes, 64, "expert_presentation_task_types_invalid");
        requireLengths(taskTypesEn, 64, "expert_presentation_task_types_invalid");
        requireLengths(List.of(usageGuide), CATALOG_COPY_MAX_CHARS,
                "expert_presentation_usage_guide_invalid");
        if (usageGuideEn != null) {
            requireLengths(List.of(usageGuideEn), CATALOG_COPY_MAX_CHARS,
                    "expert_presentation_usage_guide_invalid");
        }
        List<String> prompts = indexed(values, "expert.examplePrompt", "zh", MAX_PRESENTATION_PROMPTS,
                "expert_presentation_prompts_invalid");
        List<String> promptsEn = indexed(values, "expert.examplePrompt", "en", MAX_PRESENTATION_PROMPTS,
                "expert_presentation_prompts_invalid");
        if (prompts.isEmpty()) {
            throw invalid("expert_presentation_prompts_required");
        }
        requireLengths(prompts, 300, "expert_presentation_prompts_invalid");
        requireLengths(promptsEn, 300, "expert_presentation_prompts_invalid");
        ExpertPreviewContent preview = preview(values, "zh");
        // An optional English table/intro is allowed to travel with a Chinese-only preview;
        // only the English prose fields declare an English preview.  This mirrors the desktop
        // parser and avoids rejecting packages that carry a localized semantic block alone.
        boolean hasPreviewEn = values.keySet().stream()
                .anyMatch(key -> key.matches(
                        "expert\\.preview\\.(?:heading|scene|request|material|outputTitle|outputParagraph\\.\\d{2})\\.en"));
        ExpertPreviewContent previewEn = hasPreviewEn ? preview(values, "en") : null;
        if (hasPreviewEn && previewEn == null) {
            throw invalid("expert_presentation_preview_invalid");
        }
        if (preview == null) {
            throw invalid("expert_presentation_preview_required");
        }
        JsonNode canvas = parseCanvas(values);
        String exampleImageLocale = exampleImageLocale(values);

        Map<String, PortableAsset> validatedAssets = validatePresentationAssets(
                identity.code(), allPaths, values, assets);
        return new ExpertPresentation(
                title,
                titleEn,
                summary,
                summaryEn,
                description,
                descriptionEn,
                taskTypes,
                taskTypesEn,
                usageGuide,
                usageGuideEn,
                prompts,
                promptsEn,
                preview,
                previewEn,
                canvas,
                exampleImageLocale,
                validatedAssets,
                warnings);
    }

    private static boolean isPresentationCopyKey(String key) {
        return key.matches("(?:title|summary|description)\\.(?:zh|en)");
    }

    private static boolean knownPresentationKey(String key) {
        return isPresentationCopyKey(key)
                || key.matches("expert\\.usageGuide\\.(?:zh|en)")
                || key.matches("expert\\.asset\\.(?:icon|illustration|exampleImage)\\.path")
                || key.matches("expert\\.(?:taskType|examplePrompt)\\.\\d{2}\\.(?:zh|en)")
                || key.matches("expert\\.preview\\.(?:heading|scene|request|material|outputTitle)\\.(?:zh|en)")
                || key.matches("expert\\.preview\\.outputParagraph\\.\\d{2}\\.(?:zh|en)")
                || key.matches("expert\\.preview\\.intro\\.v1\\.(?:zh|en)")
                || key.matches("expert\\.preview\\.table\\.v1\\.(?:zh|en)")
                || key.matches("expert\\.canvas\\.v1\\.(?:zh|en)")
                || key.equals("expert.asset.exampleImage.locale");
    }

    private static String exampleImageLocale(Map<String, String> values) {
        String raw = value(values, "expert.asset.exampleImage.locale");
        if (raw == null) return null;
        try {
            return ExpertImageLocale.normalize(raw);
        } catch (IllegalArgumentException error) {
            throw invalid("expert_presentation_example_image_locale_invalid");
        }
    }

    /** Parses one optional JSON layout per locale and exposes the same {zh,en} shape as the API. */
    private JsonNode parseCanvas(Map<String, String> values) {
        ObjectNode result = json.createObjectNode();
        for (String language : List.of("zh", "en")) {
            String raw = values.get("expert.canvas.v1." + language);
            if (raw == null || raw.isBlank()) continue;
            try {
                JsonNode parsed = json.readTree(raw);
                // Each metadata key carries exactly one locale's bare layout.  Do not accept a
                // nested {zh,en} object here: that would make the key language ambiguous and
                // would break deterministic export/import.  The API uses {zh,en}; YCPS splits it
                // into these two metadata values.
                if (parsed == null || !parsed.isObject() || !parsed.has("version")) {
                    throw invalid("expert_presentation_canvas_invalid");
                }
                JsonNode normalized = ExpertPresentationValidator.canvas(parsed);
                if (normalized == null || !normalized.isObject()
                        || normalized.get("zh") == null || normalized.get("en") != null) {
                    throw invalid("expert_presentation_canvas_invalid");
                }
                result.set(language, normalized.get("zh"));
            } catch (ResponseStatusException error) {
                throw invalid("expert_presentation_canvas_invalid");
            } catch (IOException | RuntimeException error) {
                throw invalid("expert_presentation_canvas_invalid");
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static String value(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> indexed(
            Map<String, String> values,
            String prefix,
            String language,
            int maximum,
            String reason) {
        Pattern pattern = Pattern.compile(
                Pattern.quote(prefix) + "\\.(\\d{2})\\." + Pattern.quote(language));
        Map<Integer, String> indexed = new java.util.TreeMap<>();
        values.forEach((key, value) -> {
            java.util.regex.Matcher matcher = pattern.matcher(key);
            if (matcher.matches()) {
                int index;
                try {
                    index = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException error) {
                    throw invalid(reason);
                }
                if (index < 1 || index > maximum || indexed.put(index, value) != null) {
                    throw invalid(reason);
                }
            }
        });
        if (!indexed.isEmpty()) {
            int expected = 1;
            for (Integer index : indexed.keySet()) {
                if (index != expected++) {
                    throw invalid(reason);
                }
            }
        }
        return List.copyOf(indexed.values());
    }

    private ExpertPreviewContent preview(Map<String, String> values, String language) {
        String prefix = "expert.preview.";
        String heading = value(values, prefix + "heading." + language);
        String request = value(values, prefix + "request." + language);
        String outputTitle = value(values, prefix + "outputTitle." + language);
        if (heading == null || request == null || outputTitle == null) {
            return null;
        }
        List<String> paragraphs = indexed(values, prefix + "outputParagraph", language,
                MAX_PREVIEW_PARAGRAPHS, "expert_presentation_preview_invalid");
        requireLengths(paragraphs, CATALOG_COPY_MAX_CHARS,
                "expert_presentation_preview_invalid");
        if (paragraphs.isEmpty()) {
            return null;
        }
        return new ExpertPreviewContent(
                heading,
                null,
                value(values, prefix + "scene." + language),
                request,
                value(values, prefix + "material." + language),
                outputTitle,
                paragraphs,
                intro(values, language),
                table(values, language),
                null,
                null);
    }

    private ExpertPreviewBlock intro(Map<String, String> values, String language) {
        String raw = value(values, "expert.preview.intro.v1." + language);
        if (raw == null) return null;
        if (raw.length() > MAX_INTRO_JSON_CHARS) {
            throw invalid("expert_presentation_preview_invalid");
        }
        try {
            JsonNode parsed = json.readTree(raw);
            if (parsed == null || !parsed.isObject()) {
                throw invalid("expert_presentation_preview_invalid");
            }
            ExpertPreviewBlock block = json.treeToValue(parsed, ExpertPreviewBlock.class);
            // Reuse the common preview validator so tone defaults and text bounds are identical
            // for API writes, package generation and reverse import.
            ExpertPreviewContent probe = new ExpertPreviewContent(
                    "预览", null, null, "请求", null, "输出", List.of("内容"),
                    block, null, null, null);
            return ExpertPresentationValidator.preview(probe, true).intro();
        } catch (ResponseStatusException error) {
            throw invalid("expert_presentation_preview_invalid");
        } catch (IOException | RuntimeException error) {
            throw invalid("expert_presentation_preview_invalid");
        }
    }

    private ExpertPreviewTable table(Map<String, String> values, String language) {
        String raw = value(values, "expert.preview.table.v1." + language);
        if (raw == null) return null;
        try {
            JsonNode parsed = json.readTree(raw);
            if (parsed == null || !parsed.isObject()) {
                throw invalid("expert_presentation_preview_invalid");
            }
            ExpertPreviewTable table = json.treeToValue(parsed, ExpertPreviewTable.class);
            // Reuse the API validator for column-count/width, scalar cells and style bounds.
            ExpertPreviewContent probe = new ExpertPreviewContent(
                    "预览", null, null, "请求", null, "输出", List.of("内容"),
                    null, table, null, null);
            return ExpertPresentationValidator.preview(probe, true).table();
        } catch (ResponseStatusException error) {
            throw invalid("expert_presentation_preview_invalid");
        } catch (IOException | RuntimeException error) {
            throw invalid("expert_presentation_preview_invalid");
        }
    }

    private static void requireLengths(List<String> values, int maximum, String reason) {
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > maximum) {
                throw invalid(reason);
            }
        }
    }

    private Map<String, PortableAsset> validatePresentationAssets(
            String code,
            Set<String> allPaths,
            Map<String, String> values,
            Map<String, byte[]> assets) {
        String root = "skills/" + code + "/assets";
        Map<String, PortableAsset> result = new LinkedHashMap<>();
        Map<String, String> roleKeys = Map.of(
                "icon", "expert.asset.icon.path",
                "illustration", "expert.asset.illustration.path",
                "exampleImage", "expert.asset.exampleImage.path");
        Set<String> declaredPaths = new HashSet<>();
        long total = 0;
        for (Map.Entry<String, String> entry : roleKeys.entrySet()) {
            String role = entry.getKey();
            String relative = values.get(entry.getValue());
            if (relative == null || relative.isBlank()) {
                continue;
            }
            String expectedAssetName = switch (role) {
                case "icon" -> "icon";
                case "illustration" -> "illustration";
                default -> "example-image";
            };
            // \z 而不是 $：Java 的 $ 允许末尾有一个行终止符，于是 "assets/icon.png\n"
            // 能通过校验并把带换行的路径写进清单/asset_keys。
            if (!relative.matches("^assets/" + expectedAssetName
                    + "\\.(?:png|jpe?g|webp)\\z")) {
                throw invalid("expert_presentation_asset_path_invalid");
            }
            String path = "skills/" + code + "/" + relative;
            if (!declaredPaths.add(path)) {
                throw invalid("expert_presentation_asset_duplicate");
            }
            byte[] bytes = assets.get(path);
            if (bytes == null || !allPaths.contains(path)) {
                throw invalid("expert_presentation_asset_missing");
            }
            int roleLimit = switch (role) {
                case "icon" -> 128 * 1024;
                case "illustration" -> 640 * 1024;
                default -> 512 * 1024;
            };
            if (bytes.length > roleLimit) {
                throw invalid("expert_presentation_asset_too_large");
            }
            String mediaType = mediaType(path);
            try {
                // Use the same role-aware byte parser as the aggregate upload and portable
                // compiler.  Besides magic and dimensions it validates the WebP RIFF length, so
                // a hand-crafted header cannot pass inspection and later fail to render.
                ExpertImageValidator.validate(bytes, role, mediaType);
            } catch (IllegalArgumentException error) {
                if (error.getMessage() != null && error.getMessage().endsWith("_dimensions")) {
                    throw invalid("expert_presentation_asset_dimensions_invalid");
                }
                throw invalid("expert_presentation_asset_invalid");
            }
            total += bytes.length;
            result.put(role, new PortableAsset(
                    role, path, mediaType, bytes.length, sha256(bytes), bytes.clone()));
        }
        if (total > PORTABLE_TOTAL_ASSET_MAX_BYTES) {
            throw invalid("expert_presentation_assets_too_large");
        }
        for (String path : allPaths) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                if (!path.equals(root) && !declaredPaths.contains(path)) {
                    throw invalid("expert_presentation_asset_path_invalid");
                }
            }
        }
        return Map.copyOf(result);
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private JsonNode skillFrontmatter(byte[] bytes) {
        String document = utf8(bytes, "skill_frontmatter_invalid");
        int firstBreak = document.indexOf('\n');
        int close = document.indexOf("\n---", firstBreak + 1);
        if (firstBreak < 0 || close < 0) {
            throw invalid("skill_frontmatter_invalid");
        }
        try {
            JsonNode frontmatter = yaml.readTree(document.substring(firstBreak + 1, close + 1));
            requireObject(frontmatter, "skill_frontmatter_invalid");
            return frontmatter;
        } catch (IOException error) {
            throw invalid("skill_frontmatter_invalid");
        }
    }

    private void validateRuntime(
            JsonNode runtime,
            PluginIdentity identity,
            Set<String> paths,
            Map<String, byte[]> embeddedAgents) {
        requireObject(runtime, "runtime_manifest_invalid");
        Set<String> allowed = new HashSet<>(Set.of(
                "$schema", "dependencies", "permissionRequests", "hostRequirements"));
        if ("EXPERT".equals(identity.kind())) {
            allowed.addAll(Set.of("entryAgent", "agents"));
        } else if ("TEAM".equals(identity.kind())) {
            allowed.addAll(Set.of("entryAgent", "agents", "orchestration"));
        }
        requireOnly(runtime, allowed, "runtime_manifest_closed");
        String schemaName = identity.kind().toLowerCase(Locale.ROOT) + "-manifest.schema.json";
        String schemaBase = YCPS_PORTABLE_VERSION.equals(identity.ycpsVersion())
                ? SCHEMA_BASE_1_1 : SCHEMA_BASE;
        requireTextEquals(runtime, "$schema", schemaBase + schemaName, "runtime_schema_invalid");
        Map<String, Dependency> dependencies = validateDependencies(runtime.get("dependencies"), identity);
        validatePermissions(runtime.get("permissionRequests"));
        validateHostRequirements(runtime.get("hostRequirements"));
        if (!"SKILL".equals(identity.kind())) {
            validateAgents(runtime, identity.kind(), dependencies, paths, embeddedAgents);
        }
    }

    private void validateMcp(JsonNode mcp, JsonNode runtime) {
        requireObject(mcp, "mcp_schema_invalid");
        requireOnly(mcp, Set.of("$schema", "mcpServers"), "mcp_schema_invalid");
        requireTextEquals(mcp, "$schema",
                "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                "mcp_schema_invalid");
        JsonNode servers = mcp.get("mcpServers");
        requireObject(servers, "mcp_schema_invalid");
        Set<String> tools = stringSet(runtime.path("permissionRequests").path("tools"));
        Set<String> network = stringSet(runtime.path("permissionRequests").path("network"));
        servers.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank()) {
                throw invalid("mcp_schema_invalid");
            }
            JsonNode server = entry.getValue();
            requireObject(server, "mcp_schema_invalid");
            String type = requiredText(server, "type", 32, "mcp_schema_invalid");
            if ("stdio".equals(type)) {
                validateStdioMcp(server, tools);
            } else if ("streamable-http".equals(type) || "sse".equals(type)) {
                validateRemoteMcp(server, network);
            } else {
                throw invalid("mcp_schema_invalid");
            }
        });
    }

    private static void validateExecutablePermissions(
            Set<String> paths, String code, JsonNode runtime) {
        String prefix = "skills/" + code + "/";
        boolean containsExecutable = paths.stream()
                .filter(path -> path.startsWith(prefix))
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(path -> EXECUTABLE_EXTENSIONS.stream().anyMatch(path::endsWith));
        if (containsExecutable && !stringSet(
                runtime.path("permissionRequests").path("tools")).contains("process.execute")) {
            throw invalid("process_permission_missing");
        }
    }

    private static void validateStdioMcp(JsonNode server, Set<String> tools) {
        requireOnly(server, Set.of("type", "command", "args", "env", "cwd"),
                "mcp_schema_invalid");
        String command = requiredText(server, "command", 4096, "mcp_schema_invalid");
        if (!command.equals(command.strip()) || command.indexOf('\0') >= 0
                || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
            throw invalid("mcp_command_invalid");
        }
        if (!tools.contains("process.execute")) {
            throw invalid("mcp_permission_missing");
        }
        JsonNode args = server.get("args");
        if (args != null) {
            if (!args.isArray()) {
                throw invalid("mcp_schema_invalid");
            }
            for (JsonNode argument : args) {
                if (!argument.isTextual()) {
                    throw invalid("mcp_schema_invalid");
                }
                if (UNSAFE_ARGUMENT.matcher(argument.asText()).find()) {
                    throw invalid("mcp_embedded_credential");
                }
            }
        }
        JsonNode env = server.get("env");
        if (env != null) {
            requireObject(env, "mcp_schema_invalid");
            env.fields().forEachRemaining(entry -> {
                if ("PLUGIN_ROOT".equals(entry.getKey()) || "PLUGIN_DATA".equals(entry.getKey())
                        || !entry.getValue().isTextual()) {
                    throw invalid("mcp_schema_invalid");
                }
                if (CREDENTIAL_NAME.matcher(entry.getKey()).matches()) {
                    throw invalid("mcp_embedded_credential");
                }
            });
        }
        JsonNode cwd = server.get("cwd");
        if (cwd != null) {
            if (!cwd.isTextual() || !safeMcpCwd(cwd.asText())) {
                throw invalid("mcp_cwd_unsafe");
            }
        }
    }

    private static void validateRemoteMcp(JsonNode server, Set<String> network) {
        requireOnly(server, Set.of("type", "url", "headers"), "mcp_schema_invalid");
        String url = requiredText(server, "url", 4096, "mcp_schema_invalid");
        URI endpoint;
        try {
            endpoint = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw invalid("mcp_url_invalid");
        }
        String host = endpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host);
        boolean schemeAllowed = "https".equalsIgnoreCase(endpoint.getScheme())
                || loopback && "http".equalsIgnoreCase(endpoint.getScheme());
        if (!endpoint.isAbsolute() || host == null || !schemeAllowed
                || endpoint.getRawUserInfo() != null || hasCredentialQuery(endpoint.getRawQuery())) {
            throw invalid(endpoint.getRawUserInfo() != null || hasCredentialQuery(endpoint.getRawQuery())
                    ? "mcp_embedded_credential" : "mcp_url_invalid");
        }
        String origin = origin(endpoint);
        if (!network.contains(origin)) {
            throw invalid("mcp_permission_missing");
        }
        JsonNode headers = server.get("headers");
        if (headers != null) {
            requireObject(headers, "mcp_schema_invalid");
            headers.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw invalid("mcp_schema_invalid");
                }
                if (CREDENTIAL_NAME.matcher(entry.getKey()).matches()) {
                    throw invalid("mcp_embedded_credential");
                }
            });
        }
    }

    private static boolean safeMcpCwd(String value) {
        if (value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            return false;
        }
        String suffix;
        if (value.startsWith("./")) {
            suffix = value.substring(2);
        } else if (value.equals("${PLUGIN_ROOT}") || value.equals("${PLUGIN_DATA}")) {
            suffix = "";
        } else if (value.startsWith("${PLUGIN_ROOT}/")) {
            suffix = value.substring("${PLUGIN_ROOT}/".length());
        } else if (value.startsWith("${PLUGIN_DATA}/")) {
            suffix = value.substring("${PLUGIN_DATA}/".length());
        } else {
            return false;
        }
        for (String segment : suffix.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCredentialQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            String name = pair.split("=", 2)[0];
            if (CREDENTIAL_NAME.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port < 0 || "https".equals(scheme) && port == 443
                || "http".equals(scheme) && port == 80;
        String renderedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return scheme + "://" + renderedHost + (defaultPort ? "" : ":" + port);
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> {
                if (value.isTextual()) {
                    result.add(value.asText());
                }
            });
        }
        return result;
    }

    private Map<String, Dependency> validateDependencies(JsonNode value, PluginIdentity owner) {
        if (value == null || !value.isArray() || value.size() > 32) {
            throw invalid("dependencies_invalid");
        }
        Map<String, Dependency> result = new HashMap<>();
        for (JsonNode dependency : value) {
            requireObject(dependency, "dependency_invalid");
            requireOnly(dependency, Set.of("key", "relation", "required", "package"),
                    "dependency_invalid");
            String key = requiredCode(dependency, "key", "dependency_invalid");
            String relation = requiredText(dependency, "relation", 32, "dependency_invalid");
            if (!Set.of("SKILL_BINDING", "TEAM_MEMBER").contains(relation)
                    || !dependency.path("required").isBoolean()) {
                throw invalid("dependency_invalid");
            }
            JsonNode target = dependency.get("package");
            requireObject(target, "dependency_invalid");
            requireOnly(target, Set.of("code", "kind", "version", "revision", "sha256"),
                    "dependency_invalid");
            String code = requiredCode(target, "code", "dependency_invalid");
            String kind = requiredText(target, "kind", 16, "dependency_invalid");
            String version = requiredText(target, "version", 32, "dependency_invalid");
            String hash = requiredText(target, "sha256", 64, "dependency_invalid");
            int revision = target.path("revision").canConvertToInt()
                    ? target.path("revision").intValue() : 0;
            if (!KINDS.contains(kind) || !SEMVER.matcher(version).matches()
                    || revision < 1 || !SHA256.matcher(hash).matches()
                    || code.equals(owner.code())) {
                throw invalid("dependency_invalid");
            }
            if ("SKILL_BINDING".equals(relation) && !"SKILL".equals(kind)
                    || "TEAM_MEMBER".equals(relation)
                            && (!"TEAM".equals(owner.kind()) || !"EXPERT".equals(kind)
                                    || !dependency.path("required").booleanValue())) {
                throw invalid("dependency_relation_invalid");
            }
            Dependency parsed = new Dependency(key, relation, code, kind, version, revision, hash);
            if (result.putIfAbsent(key, parsed) != null) {
                throw invalid("dependency_duplicate");
            }
        }
        return result;
    }

    private void validatePermissions(JsonNode permissions) {
        requireObject(permissions, "permission_requests_invalid");
        requireOnly(permissions, Set.of("tools", "network", "filesystem"),
                "permission_requests_invalid");
        validateSortedStrings(permissions.get("tools"), 256,
                value -> TOOL.matcher(value).matches() && TOOL_CAPABILITIES.contains(value),
                "permission_tools_invalid");
        validateSortedStrings(permissions.get("filesystem"), 32, FILESYSTEM_SCOPES::contains,
                "permission_filesystem_invalid");
        validateSortedStrings(permissions.get("network"), 128, SkillPackageInspector::validOrigin,
                "permission_network_invalid");
    }

    private void validateHostRequirements(JsonNode host) {
        requireObject(host, "host_requirements_invalid");
        requireOnly(host, Set.of("minClientVersion", "platforms", "architectures"),
                "host_requirements_invalid");
        JsonNode minimum = host.get("minClientVersion");
        if (minimum != null && (!minimum.isTextual() || minimum.asText().length() > 32
                || !SEMVER.matcher(minimum.asText()).matches())) {
            throw invalid("host_requirements_invalid");
        }
        if (host.has("platforms")) {
            validateSortedStrings(host.get("platforms"), 3,
                    Set.of("darwin", "linux", "win32")::contains, "host_requirements_invalid");
            if (host.get("platforms").isEmpty()) {
                throw invalid("host_requirements_invalid");
            }
        }
        if (host.has("architectures")) {
            validateSortedStrings(host.get("architectures"), 2,
                    Set.of("arm64", "x64")::contains, "host_requirements_invalid");
            if (host.get("architectures").isEmpty()) {
                throw invalid("host_requirements_invalid");
            }
        }
    }

    private void validateAgents(
            JsonNode runtime,
            String kind,
            Map<String, Dependency> dependencies,
            Set<String> paths,
            Map<String, byte[]> embeddedAgents) {
        String entryAgent = requiredCode(runtime, "entryAgent", "agents_invalid");
        JsonNode agents = runtime.get("agents");
        int minimum = "EXPERT".equals(kind) ? 1 : 2;
        int maximum = "EXPERT".equals(kind) ? 1 : 16;
        if (agents == null || !agents.isArray() || agents.size() < minimum || agents.size() > maximum) {
            throw invalid("agents_invalid");
        }
        Set<String> ids = new HashSet<>();
        Set<String> usedBindings = new HashSet<>();
        int leadCount = 0;
        String leadId = null;
        Set<String> packageMembers = new HashSet<>();
        for (JsonNode agent : agents) {
            requireObject(agent, "agent_invalid");
            Set<String> allowed = "EXPERT".equals(kind)
                    ? Set.of("id", "role", "instructions", "bindings")
                    : Set.of("id", "role", "source", "bindings");
            requireOnly(agent, allowed, "agent_invalid");
            String id = requiredCode(agent, "id", "agent_invalid");
            if (!ids.add(id)) {
                throw invalid("agent_duplicate");
            }
            String role = requiredText(agent, "role", 16, "agent_invalid");
            if ("LEAD".equals(role)) {
                leadCount++;
                leadId = id;
            } else if (!"TEAM".equals(kind) || !"MEMBER".equals(role)) {
                throw invalid("agent_role_invalid");
            }
            validateBindings(agent.get("bindings"), dependencies, usedBindings);
            if ("EXPERT".equals(kind)) {
                validateInstructions(agent.get("instructions"), id, paths, embeddedAgents);
            } else {
                validateSource(agent.get("source"), id, dependencies, paths,
                        embeddedAgents, packageMembers);
            }
        }
        if (leadCount != 1 || !entryAgent.equals(leadId)) {
            throw invalid("entry_agent_invalid");
        }
        for (Dependency dependency : dependencies.values()) {
            if ("SKILL_BINDING".equals(dependency.relation())
                    && !usedBindings.contains(dependency.key())) {
                throw invalid("unused_skill_binding");
            }
            if ("TEAM_MEMBER".equals(dependency.relation())
                    && !packageMembers.contains(dependency.key())) {
                throw invalid("unused_team_member");
            }
        }
        if ("TEAM".equals(kind)) {
            validateOrchestration(runtime.get("orchestration"));
        }
    }

    private void validateBindings(
            JsonNode bindings, Map<String, Dependency> dependencies, Set<String> used) {
        Set<String> local = new HashSet<>();
        if (bindings == null || !bindings.isArray() || bindings.size() > 32) {
            throw invalid("bindings_invalid");
        }
        for (JsonNode binding : bindings) {
            if (!binding.isTextual() || !local.add(binding.asText())) {
                throw invalid("bindings_invalid");
            }
            Dependency dependency = dependencies.get(binding.asText());
            if (dependency == null || !"SKILL_BINDING".equals(dependency.relation())) {
                throw invalid("binding_unresolved");
            }
            used.add(binding.asText());
        }
    }

    private void validateInstructions(
            JsonNode instructions,
            String id,
            Set<String> paths,
            Map<String, byte[]> embeddedAgents) {
        requireObject(instructions, "agent_instructions_invalid");
        String type = requiredText(instructions, "type", 16, "agent_instructions_invalid");
        if ("ENTRY_SKILL".equals(type)) {
            requireOnly(instructions, Set.of("type"), "agent_instructions_invalid");
        } else if ("EMBEDDED".equals(type)) {
            requireOnly(instructions, Set.of("type", "path"), "agent_instructions_invalid");
            validateEmbeddedPath(requiredText(instructions, "path", MAX_PATH_BYTES,
                    "agent_instructions_invalid"), id, paths, embeddedAgents);
        } else {
            throw invalid("agent_instructions_invalid");
        }
    }

    private void validateSource(
            JsonNode source,
            String id,
            Map<String, Dependency> dependencies,
            Set<String> paths,
            Map<String, byte[]> embeddedAgents,
            Set<String> packageMembers) {
        requireObject(source, "agent_source_invalid");
        String type = requiredText(source, "type", 16, "agent_source_invalid");
        if ("PACKAGE".equals(type)) {
            requireOnly(source, Set.of("type", "dependency"), "agent_source_invalid");
            String key = requiredCode(source, "dependency", "agent_source_invalid");
            Dependency dependency = dependencies.get(key);
            if (dependency == null || !"TEAM_MEMBER".equals(dependency.relation())
                    || !packageMembers.add(key)) {
                throw invalid("agent_source_invalid");
            }
        } else if ("EMBEDDED".equals(type)) {
            requireOnly(source, Set.of("type", "path"), "agent_source_invalid");
            validateEmbeddedPath(requiredText(source, "path", MAX_PATH_BYTES,
                    "agent_source_invalid"), id, paths, embeddedAgents);
        } else {
            throw invalid("agent_source_invalid");
        }
    }

    private void validateEmbeddedPath(
            String declared,
            String id,
            Set<String> paths,
            Map<String, byte[]> embeddedAgents) {
        String expected = "./com.yuzhihuihe.ycps/agents/" + id + ".md";
        String archivePath = declared.startsWith("./") ? declared.substring(2) : declared;
        byte[] bytes = embeddedAgents.get(archivePath);
        if (!expected.equals(declared) || !paths.contains(archivePath) || bytes == null) {
            throw invalid("embedded_agent_missing");
        }
        validateEmbeddedAgent(bytes, id);
    }

    private void validateEmbeddedAgent(byte[] bytes, String id) {
        String document = utf8(bytes, "embedded_agent_invalid");
        if (document.startsWith("\uFEFF")
                || !document.startsWith("---\n") && !document.startsWith("---\r\n")) {
            throw invalid("embedded_agent_invalid");
        }
        int firstBreak = document.indexOf('\n');
        int close = document.indexOf("\n---", firstBreak + 1);
        if (close < 0) {
            throw invalid("embedded_agent_invalid");
        }
        int delimiterEnd = close + 4;
        if (delimiterEnd < document.length() && document.charAt(delimiterEnd) == '\r') {
            delimiterEnd++;
        }
        if (delimiterEnd < document.length() && document.charAt(delimiterEnd) == '\n') {
            delimiterEnd++;
        } else if (delimiterEnd != document.length()) {
            throw invalid("embedded_agent_invalid");
        }
        JsonNode frontmatter;
        try {
            frontmatter = yaml.readTree(document.substring(firstBreak + 1, close + 1));
        } catch (IOException exception) {
            throw invalid("embedded_agent_invalid");
        }
        requireObject(frontmatter, "embedded_agent_invalid");
        requireOnly(frontmatter, Set.of("id", "description"), "embedded_agent_invalid");
        requireTextEquals(frontmatter, "id", id, "embedded_agent_invalid");
        requiredText(frontmatter, "description", "embedded_agent_invalid");
        if (document.substring(delimiterEnd).isBlank()) {
            throw invalid("embedded_agent_invalid");
        }
    }

    private static void validateOrchestration(JsonNode orchestration) {
        requireObject(orchestration, "orchestration_invalid");
        requireOnly(orchestration,
                Set.of("mode", "maxRounds", "timeoutSeconds", "failurePolicy"),
                "orchestration_invalid");
        String mode = requiredText(orchestration, "mode", 16, "orchestration_invalid");
        String failure = requiredText(orchestration, "failurePolicy", 16, "orchestration_invalid");
        int rounds = orchestration.path("maxRounds").canConvertToInt()
                ? orchestration.path("maxRounds").intValue() : 0;
        int timeout = orchestration.path("timeoutSeconds").canConvertToInt()
                ? orchestration.path("timeoutSeconds").intValue() : 0;
        if (!Set.of("SUPERVISOR", "SEQUENTIAL", "PARALLEL").contains(mode)
                || !Set.of("FAIL_FAST", "BEST_EFFORT").contains(failure)
                || rounds < 1 || rounds > 32 || timeout < 1 || timeout > 3600) {
            throw invalid("orchestration_invalid");
        }
    }

    private void enforceProfileLimits(Path packageFile, String kind, long extracted) throws IOException {
        long compressedLimit = switch (kind) {
            case "EXPERT" -> 2L * 1024 * 1024;
            case "TEAM" -> 5L * 1024 * 1024;
            default -> 10L * 1024 * 1024;
        };
        long expandedLimit = switch (kind) {
            case "EXPERT" -> 5L * 1024 * 1024;
            case "TEAM" -> 10L * 1024 * 1024;
            default -> 25L * 1024 * 1024;
        };
        if (Files.size(packageFile) > compressedLimit) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "ycps_package_too_large");
        }
        if (extracted > expandedLimit) {
            throw unsafe("extracted_too_large");
        }
    }

    private static boolean validOrigin(String value) {
        try {
            URI uri = URI.create(value);
            boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
            boolean scheme = "https".equalsIgnoreCase(uri.getScheme())
                    || loopback && "http".equalsIgnoreCase(uri.getScheme());
            return scheme && uri.isAbsolute() && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void validateSortedStrings(
            JsonNode value, int maximum, java.util.function.Predicate<String> predicate, String reason) {
        if (value == null || !value.isArray() || value.size() > maximum) {
            throw invalid(reason);
        }
        String previous = null;
        for (JsonNode entry : value) {
            if (!entry.isTextual() || !predicate.test(entry.asText())
                    || previous != null && previous.compareTo(entry.asText()) >= 0) {
                throw invalid(reason);
            }
            previous = entry.asText();
        }
    }

    private static void requireIdentity(PluginIdentity identity, String expectedCode) {
        if (expectedCode != null && !identity.code().equals(expectedCode)) {
            throw identityMismatch("upload target code does not match plugin.name");
        }
    }

    static void requireUploadIdentity(
            Report report, String expectedCode, String expectedVersion, String expectedKind) {
        if (!report.code().equals(expectedCode)
                || !report.version().equals(expectedVersion)
                || !report.kind().equals(expectedKind)) {
            throw identityMismatch("upload target code/version/kind does not match package");
        }
    }

    private JsonNode json(byte[] bytes, String reason) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef
                && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            throw invalid(reason);
        }
        try {
            JsonNode node = json.readTree(utf8(bytes, reason));
            requireObject(node, reason);
            return node;
        } catch (IOException exception) {
            throw invalid(reason);
        }
    }

    private byte[] canonicalPair(JsonNode plugin, JsonNode runtime) throws IOException {
        ObjectNode pair = json.createObjectNode();
        pair.set("plugin", sorted(plugin));
        pair.set("runtime", sorted(runtime));
        return json.writeValueAsBytes(pair);
    }

    private byte[] canonicalDependencies(JsonNode dependencies) throws IOException {
        List<JsonNode> sortedDependencies = new ArrayList<>();
        dependencies.forEach(sortedDependencies::add);
        sortedDependencies.sort(Comparator.comparing(node -> node.path("key").asText()));
        ArrayNode array = json.createArrayNode();
        sortedDependencies.forEach(node -> array.add(sorted(node)));
        return json.writeValueAsBytes(array);
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = json.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            fields.forEach(field -> object.set(field, sorted(node.get(field))));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = json.createArrayNode();
            node.forEach(value -> array.add(sorted(value)));
            return array;
        }
        return node;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha_256_unavailable", exception);
        }
    }

    private long measure(ZipFile zip, ZipEntry entry, long perEntryLimit, long remainingBudget)
            throws IOException {
        long limit = Math.min(perEntryLimit, Math.max(0, remainingBudget));
        if (limit <= 0) {
            throw unsafe("extracted_too_large");
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[BLOCK];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw unsafe("entry_too_large");
                }
            }
            return total;
        }
    }

    private static byte[] capture(ZipFile zip, ZipEntry entry, int maximum, String reason)
            throws IOException {
        try (InputStream input = zip.getInputStream(entry);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BLOCK];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum) {
                    throw invalid(reason);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void checkRatio(ZipEntry entry, long actualBytes, int maximumRatio) {
        long compressed = entry.getCompressedSize();
        if (compressed > 0 && actualBytes > compressed * maximumRatio) {
            throw unsafe("compression_ratio");
        }
    }

    private static boolean isUnsafeName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.contains("\\")) {
            return true;
        }
        if (name.startsWith("/") || name.startsWith("//")
                || name.length() > 1 && name.charAt(1) == ':') {
            return true;
        }
        String checked = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String segment : checked.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.indexOf(':') >= 0 || segment.endsWith(".") || segment.endsWith(" ")) {
                return true;
            }
            String lower = segment.toLowerCase(Locale.ROOT);
            int extension = lower.indexOf('.');
            String stem = extension < 0 ? lower : lower.substring(0, extension);
            if (WINDOWS_RESERVED.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    private static String utf8(byte[] bytes, String reason) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException exception) {
            throw invalid(reason);
        }
    }

    private static void requireObject(JsonNode node, String reason) {
        if (node == null || !node.isObject()) {
            throw invalid(reason);
        }
    }

    private static void requireOnly(JsonNode node, Set<String> allowed, String reason) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw invalid(reason);
            }
        }
    }

    private static String requiredText(JsonNode node, String field, int maximum, String reason) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximum) {
            throw invalid(reason);
        }
        return value.asText();
    }

    private static String requiredText(JsonNode node, String field, String reason) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(reason);
        }
        return value.asText();
    }

    private static void optionalText(JsonNode node, String field, String reason) {
        JsonNode value = node.get(field);
        if (value != null && !value.isTextual()) {
            throw invalid(reason);
        }
    }

    private static String optionalTextValue(JsonNode node, String field, String reason) {
        optionalText(node, field, reason);
        JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }

    private static String requiredCode(JsonNode node, String field, String reason) {
        String value = requiredText(node, field, 64, reason);
        if (!CODE.matcher(value).matches()) {
            throw invalid(reason);
        }
        return value;
    }

    private static void requireTextEquals(
            JsonNode node, String field, String expected, String reason) {
        if (!expected.equals(requiredText(node, field, Math.max(256, expected.length()), reason))) {
            throw invalid(reason);
        }
    }

    private static String hostMinimum(JsonNode runtime) {
        JsonNode value = runtime.path("hostRequirements").get("minClientVersion");
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** Reads the central directory because ZipEntry does not expose Unix special-file modes. */
    static boolean hasSymlinkEntry(Path packageFile) throws IOException {
        long size = Files.size(packageFile);
        if (size < 22) {
            return false;
        }
        int tailLength = (int) Math.min(size, EOCD_MAX_SCAN);
        byte[] tail = new byte[tailLength];
        try (RandomAccessFile file = new RandomAccessFile(packageFile.toFile(), "r")) {
            file.seek(size - tailLength);
            file.readFully(tail);
        }
        int eocd = -1;
        for (int index = tail.length - 22; index >= 0; index--) {
            if (readInt(tail, index) == EOCD_SIGNATURE) {
                eocd = index;
                break;
            }
        }
        if (eocd < 0) {
            return true;
        }
        int commentLength = readShort(tail, eocd + 20);
        long eocdOffset = size - tailLength + eocd;
        if (eocdOffset + 22L + commentLength != size) {
            return true;
        }
        long disk = readShort(tail, eocd + 4);
        long centralDisk = readShort(tail, eocd + 6);
        long entriesOnDisk = readShort(tail, eocd + 8);
        long entries = readShort(tail, eocd + 10);
        long centralSize = readInt(tail, eocd + 12);
        long centralOffset = readInt(tail, eocd + 16);
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != entries
                || entries == 0xFFFFL || centralSize == 0xFFFFFFFFL
                || centralOffset == 0xFFFFFFFFL) {
            return true;
        }
        long centralEnd = centralOffset + centralSize;
        if (centralEnd < centralOffset || centralEnd != eocdOffset) {
            return true;
        }
        byte[] header = new byte[46];
        try (RandomAccessFile file = new RandomAccessFile(packageFile.toFile(), "r")) {
            long cursor = centralOffset;
            for (long index = 0; index < entries; index++) {
                if (cursor + header.length > centralEnd) {
                    return true;
                }
                file.seek(cursor);
                file.readFully(header);
                if (readInt(header, 0) != CENTRAL_SIGNATURE) {
                    return true;
                }
                int flags = readShort(header, 8);
                int method = readShort(header, 10);
                long compressedSize = readInt(header, 20);
                long uncompressedSize = readInt(header, 24);
                int nameLength = readShort(header, 28);
                int extraLength = readShort(header, 30);
                int entryCommentLength = readShort(header, 32);
                int diskStart = readShort(header, 34);
                long localOffset = readInt(header, 42);
                if ((flags & 1) != 0 || method != 0 && method != 8 || diskStart != 0
                        || compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL
                        || localOffset == 0xFFFFFFFFL) {
                    return true;
                }
                int platform = (readShort(header, 4) >> 8) & 0xFF;
                int mode = (int) ((readInt(header, 38) >> 16) & 0xFFFF);
                if (platform == UNIX_MADE_BY || platform == MACOS_MADE_BY) {
                    int type = mode & 0xF000;
                    if (type == SYMLINK_MODE
                            || type != 0 && type != REGULAR_MODE && type != DIRECTORY_MODE) {
                        return true;
                    }
                }
                long variableLength = (long) nameLength + extraLength + entryCommentLength;
                long next = cursor + header.length + variableLength;
                if (next < cursor || next > centralEnd) {
                    return true;
                }
                byte[] name = new byte[nameLength];
                byte[] extra = new byte[extraLength];
                file.seek(cursor + header.length);
                file.readFully(name);
                file.readFully(extra);
                if (containsZip64Extra(extra) || localOffset + 30L > centralOffset) {
                    return true;
                }
                byte[] local = new byte[30];
                file.seek(localOffset);
                file.readFully(local);
                if (readInt(local, 0) != LOCAL_SIGNATURE
                        || readShort(local, 8) != method
                        || (readShort(local, 6) & 1) != 0) {
                    return true;
                }
                int localNameLength = readShort(local, 26);
                int localExtraLength = readShort(local, 28);
                long dataOffset = localOffset + local.length
                        + (long) localNameLength + localExtraLength;
                long dataEnd = dataOffset + compressedSize;
                if (dataOffset < localOffset || dataEnd < dataOffset || dataEnd > centralOffset) {
                    return true;
                }
                byte[] localName = new byte[localNameLength];
                byte[] localExtra = new byte[localExtraLength];
                file.seek(localOffset + local.length);
                file.readFully(localName);
                file.readFully(localExtra);
                if (!java.util.Arrays.equals(name, localName) || containsZip64Extra(localExtra)) {
                    return true;
                }
                cursor = next;
            }
            return cursor != centralEnd;
        }
    }

    private static boolean containsZip64Extra(byte[] extra) {
        int offset = 0;
        while (offset < extra.length) {
            if (offset + 4 > extra.length) {
                return true;
            }
            int id = readShort(extra, offset);
            int length = readShort(extra, offset + 2);
            offset += 4;
            if (offset + length > extra.length) {
                return true;
            }
            if (id == ZIP64_EXTRA_ID) {
                return true;
            }
            offset += length;
        }
        return false;
    }

    private static int readShort(byte[] source, int offset) {
        if (offset + 2 > source.length) {
            return 0;
        }
        return (source[offset] & 0xFF) | ((source[offset + 1] & 0xFF) << 8);
    }

    private static long readInt(byte[] source, int offset) {
        if (offset + 4 > source.length) {
            return -1;
        }
        return (source[offset] & 0xFFL) | ((source[offset + 1] & 0xFFL) << 8)
                | ((source[offset + 2] & 0xFFL) << 16)
                | ((source[offset + 3] & 0xFFL) << 24);
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ycps_package_invalid", new IllegalStateException(detail));
    }

    private static ResponseStatusException unsafe(String detail) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ycps_package_unsafe_entry", new IllegalStateException(detail));
    }

    private static ResponseStatusException identityMismatch(String detail) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "package_identity_mismatch", new IllegalStateException(detail));
    }

    record PluginIdentity(String code, String version, String kind, String ycpsVersion) {}

    record Dependency(
            String key, String relation, String code, String kind, String version,
            int revision, String sha256) {}

    /**
     * Catalog copy the package itself declares, keyed by language.
     *
     * <p>YCPS §16 keeps the <em>authoritative</em> catalog copy in {@code gateway.skill} so operators
     * can fix wording without cutting a release. That left a gap for automation: a newly uploaded
     * package carried no copy at all, so every new package needed someone to retype its title and
     * summary in the console. The Entry Skill frontmatter already has a free-form {@code metadata}
     * map, so authors can now ship the copy inside the package and the ingest path backfills the
     * catalog from it. The console still wins when an operator edits a field.
     *
     * <p>Keys use {@code <field>.<lang>} (for example {@code title.zh}, {@code summary.en}). The
     * bare {@code description} frontmatter field is the default-language copy, so it is reported as
     * {@link #DEFAULT_LANGUAGE}. Only the languages the package actually declares are present.
     */
    record CatalogCopy(Map<String, String> defaultCopy, Map<String, Map<String, String>> localized) {

        /** Language key used for the frontmatter fields that carry no explicit language suffix. */
        static final String DEFAULT_LANGUAGE = "zh";

        static final CatalogCopy EMPTY = new CatalogCopy(Map.of(), Map.of());

        /** The copy for one language, falling back to nothing when the package omitted it. */
        Map<String, String> forLanguage(String language) {
            return localized.getOrDefault(language, Map.of());
        }
    }

    /** Parsed, validated Expert presentation carried by YCPS 1.1. */
    record ExpertPresentation(
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
            String exampleImageLocale,
            Map<String, PortableAsset> assets,
            List<String> warnings) {
        static final ExpertPresentation EMPTY = new ExpertPresentation(
                null, null, null, null, null, null, List.of(), List.of(), null, null,
                List.of(), List.of(), null, null, null, null, Map.of(), List.of());

        ExpertPresentation {
            taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
            taskTypesEn = taskTypesEn == null ? List.of() : List.copyOf(taskTypesEn);
            examplePrompts = examplePrompts == null ? List.of() : List.copyOf(examplePrompts);
            examplePromptsEn = examplePromptsEn == null ? List.of() : List.copyOf(examplePromptsEn);
            assets = assets == null ? Map.of() : Map.copyOf(assets);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    record PortableAsset(
            String role,
            String packagePath,
            String mediaType,
            long sizeBytes,
            String sha256,
            byte[] content) {}

    record Report(
            int fileCount,
            long extractedBytes,
            String code,
            String version,
            String kind,
            String ycpsVersion,
            String minClientVersion,
            JsonNode pluginManifest,
            JsonNode runtimeManifest,
            String pluginJson,
            String runtimeJson,
            String manifestSha256,
            String dependencyLockSha256,
            CatalogCopy catalogCopy,
            ExpertPresentation expertPresentation) {}
}
