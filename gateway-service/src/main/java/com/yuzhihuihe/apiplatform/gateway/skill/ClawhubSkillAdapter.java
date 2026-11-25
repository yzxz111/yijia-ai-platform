package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Inspects a ClawHub Skill ZIP without rewriting or executing any source files. */
final class ClawhubSkillAdapter {

    static final String SOURCE_FORMAT = "CLAWHUB_SKILL";
    static final String ADAPTER_VERSION = "2.1.0";
    static final String IMPORTABLE = "IMPORTABLE";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    static final String BLOCKED = "BLOCKED";

    static final long MAX_SOURCE_ZIP_BYTES = 50L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 250L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ORIGIN_BYTES = 256 * 1024;
    private static final long MAX_COMPRESSION_RATIO = 100L;
    private static final Pattern OWNER = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern TECHNICAL_NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern MARKDOWN_H1 = Pattern.compile("^ {0,3}#\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern SCRIPT = Pattern.compile(
            ".*\\.(?:bat|cjs|cmd|com|dll|dylib|exe|jar|js|jsx|lua|mjs|php|pl|ps1|py|rb|sh|so|ts|tsx|wasm)$",
            Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    record Report(
            String sourceFormat,
            String adapterVersion,
            String sourceSha256,
            String code,
            String version,
            String status,
            List<String> decisions,
            List<String> blockingReasons,
            String name,
            String owner,
            String slug,
            String description) {
        Report {
            decisions = List.copyOf(decisions == null ? List.of() : decisions);
            blockingReasons = List.copyOf(blockingReasons == null ? List.of() : blockingReasons);
        }

        boolean reviewRequired() {
            return REVIEW_REQUIRED.equals(status);
        }
    }

    Report inspect(Path archive, String requestedOwner, String requestedSlug, String requestedVersion)
            throws IOException {
        return inspect(archive, requestedOwner, requestedSlug, requestedVersion, null);
    }

    Report inspect(
            Path archive,
            String requestedOwner,
            String requestedSlug,
            String requestedVersion,
            String requestedDisplayName) throws IOException {
        String sourceSha256 = sha256(archive);
        List<String> decisions = new ArrayList<>();
        List<String> review = new ArrayList<>();

        if (requestedOwner != null && !requestedOwner.isBlank() && normalizeOwner(requestedOwner) == null) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "ClawHub owner contains unsupported characters");
        }
        if (requestedSlug != null && !requestedSlug.isBlank() && normalizeSlug(requestedSlug) == null) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "ClawHub slug contains unsupported characters");
        }
        if (requestedDisplayName != null && !requestedDisplayName.isBlank()
                && !isValidDisplayName(requestedDisplayName)) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "ClawHub display name must be 1 to 128 characters without control characters");
        }

        if (!Files.isRegularFile(archive) || Files.size(archive) == 0
                || Files.size(archive) > MAX_SOURCE_ZIP_BYTES) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "Archive is empty or exceeds the 50 MiB source package limit");
        }
        try {
            if (SkillPackageInspector.hasSymlinkEntry(archive)) {
                return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                        "Archive contains a symlink, special file, encrypted entry or unsupported ZIP structure");
            }
        } catch (IOException invalidArchive) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "Archive central directory is invalid");
        }

        Archive contents;
        try {
            contents = readArchive(archive);
        } catch (UnsafeArchive invalidArchive) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    invalidArchive.getMessage());
        }

        List<ArchiveEntry> documents = contents.entries().stream()
                .filter(entry -> !entry.directory() && isSkillDocument(entry.name()))
                .toList();
        if (documents.isEmpty()) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "Archive does not contain SKILL.md, skill.md or skills.md");
        }
        if (documents.size() != 1) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "Archive contains more than one Skill document and may contain nested executable Skills");
        }

        ArchiveEntry documentEntry = documents.getFirst();
        byte[] documentBytes = contents.retained().get(documentEntry.name());
        if (documentBytes == null || documentBytes.length > MAX_DOCUMENT_BYTES) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "Skill document exceeds the 2 MiB inspection limit");
        }
        String document;
        try {
            document = decodeUtf8(documentBytes).replace("\r\n", "\n");
        } catch (CharacterCodingException invalidUtf8) {
            return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                    "Skill document is not valid UTF-8");
        }
        if (!document.isEmpty() && document.charAt(0) == '\uFEFF') {
            document = document.substring(1);
        }

        JsonNode frontmatter = null;
        if (document.startsWith("---\n") || document.startsWith("---\r\n")) {
            String normalized = document.replace("\r\n", "\n");
            int end = frontmatterEnd(normalized);
            if (end < 0) {
                return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                        "Skill document starts frontmatter but has no closing delimiter");
            }
            try {
                frontmatter = YAML.readTree(normalized.substring(4, end + 1));
            } catch (IOException malformed) {
                return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                        "Skill document frontmatter is malformed YAML");
            }
            if (frontmatter == null || !frontmatter.isObject()) {
                return blocked(sourceSha256, null, requestedVersion, requestedOwner, requestedSlug,
                        "Skill document frontmatter must be a YAML object");
            }
        } else {
            review.add("SKILL.md has no optional frontmatter; catalog metadata needs operator review");
        }

        String manifestOwner = null;
        String manifestSlug = text(frontmatter, "slug");
        String sourceName = text(frontmatter, "name");
        String declaredDisplayName = firstNonBlank(
                text(frontmatter, "displayName"), text(frontmatter, "title"));
        String description = text(frontmatter, "description");
        String manifestVersion = text(frontmatter, "version");
        String documentDirectory = directory(documentEntry.name());
        byte[] originBytes = contents.retained().get(documentDirectory + ".clawhub/origin.json");
        if (originBytes == null) {
            originBytes = contents.retained().get(".clawhub/origin.json");
        }
        JsonNode origin = parseOrigin(originBytes);
        if (originBytes != null && origin == null) {
            review.add("ClawHub origin metadata is malformed and must be checked manually");
        }
        if (origin != null) {
            manifestOwner = text(origin, "ownerHandle");
            declaredDisplayName = firstNonBlank(declaredDisplayName, text(origin, "displayName"));
            String originSlug = text(origin, "slug");
            if (originSlug != null && originSlug.startsWith("@") && originSlug.contains("/")) {
                String[] qualified = originSlug.substring(1).split("/", 2);
                if (manifestOwner == null) manifestOwner = qualified[0];
                originSlug = qualified[1];
            }
            if (manifestSlug == null) manifestSlug = originSlug;
            if (origin.has("installedVersion") && !origin.get("installedVersion").isTextual()) {
                review.add("ClawHub installedVersion metadata is not a string");
            } else if (text(origin, "installedVersion") != null) {
                manifestVersion = text(origin, "installedVersion");
            }
            decisions.add("ClawHub origin fields are declarations from the uploaded archive");
        }

        String owner = normalizeOwner(firstNonBlank(requestedOwner, manifestOwner));
        String slug = normalizeSlug(firstNonBlank(requestedSlug, manifestSlug,
                basename(documentDirectory)));
        if (owner == null) {
            review.add("Publisher owner is missing; enter the ClawHub owner before commit");
        }
        if (slug == null) {
            review.add("Skill slug is missing; enter the ClawHub slug before commit");
        }
        if (requestedOwner != null && !requestedOwner.isBlank() && manifestOwner != null
                && !normalizeOwner(requestedOwner).equals(normalizeOwner(manifestOwner))) {
            review.add("Entered publisher owner differs from archive origin metadata");
        }
        if (requestedSlug != null && !requestedSlug.isBlank() && manifestSlug != null
                && !normalizeSlug(requestedSlug).equals(normalizeSlug(manifestSlug))) {
            review.add("Entered slug differs from archive origin metadata");
        }

        String declaredVersion = firstNonBlank(manifestVersion, null);
        String version = firstNonBlank(requestedVersion, declaredVersion);
        if (version == null) {
            review.add("Release version is missing; this archive can be saved as a draft and completed later");
        } else if (!SEMVER.matcher(version).matches() || version.length() > 32) {
            review.add("Release version is invalid; enter a valid SemVer before publishing");
            version = null;
        }
        if (requestedVersion != null && declaredVersion != null
                && !requestedVersion.strip().equals(declaredVersion)) {
            review.add("Entered release version differs from the version declared in the archive");
        }

        String name = firstNonBlank(
                requestedDisplayName,
                declaredDisplayName,
                nonTechnicalName(sourceName),
                firstMarkdownHeading(document),
                titleFromSlug(slug),
                sourceName);
        if (name == null || name.isBlank()) name = "ClawHub Skill";
        if (name.length() > 128) {
            review.add("Skill name exceeds the console title limit; the source file remains unchanged");
            name = name.substring(0, 128);
        }
        if (description != null && description.length() > SkillAdminService.SKILL_DESCRIPTION_MAX) {
            review.add("Description exceeds the console detail limit; the source file remains unchanged");
            description = null;
        }
        if (frontmatter != null) {
            JsonNode metadata = frontmatter.get("metadata");
            if (metadata != null && !metadata.isNull() && !metadata.isEmpty()) {
                review.add("OpenClaw metadata needs runtime and permission review before use");
            }
            if (frontmatter.has("metadata") && !frontmatter.get("metadata").isObject()
                    && !frontmatter.get("metadata").isNull()) {
                review.add("OpenClaw metadata has an unsupported shape and needs review");
            }
        }
        for (ArchiveEntry entry : contents.entries()) {
            if (entry.directory() || isPlatformMetadata(entry.name())) continue;
            if (SCRIPT.matcher(entry.name()).matches()) {
                review.add("Executable support files need static review; the importer never executes them");
                break;
            }
        }
        if (origin != null && owner != null && slug != null) {
            String originOwner = normalizeOwner(manifestOwner);
            String originSlug = normalizeSlug(manifestSlug);
            if (originOwner != null && !owner.equals(originOwner)
                    || originSlug != null && !slug.equals(originSlug)) {
                review.add("Archive origin does not match the selected owner and slug");
            }
        }
        if (review.isEmpty()) {
            decisions.add("Original ZIP is retained byte-for-byte as a native ClawHub Skill package");
            decisions.add("No source files or executable code are run during inspection");
        } else {
            decisions.addAll(review);
        }
        String code = owner == null || slug == null ? null : nativeCode(owner, slug);
        return new Report(
                SOURCE_FORMAT,
                ADAPTER_VERSION,
                sourceSha256,
                code,
                version,
                review.isEmpty() ? IMPORTABLE : REVIEW_REQUIRED,
                decisions,
                List.of(),
                name,
                owner,
                slug,
                description);
    }

    static String normalizeOwner(String owner) {
        if (owner == null) return null;
        String normalized = owner.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        return OWNER.matcher(normalized).matches() ? normalized : null;
    }

    static String normalizeSlug(String slug) {
        if (slug == null) return null;
        String normalized = slug.strip().toLowerCase(Locale.ROOT);
        return SLUG.matcher(normalized).matches() && normalized.length() <= 64 ? normalized : null;
    }

    static String nativeCode(String owner, String slug) {
        String normalizedOwner = normalizeOwner(owner);
        String normalizedSlug = normalizeSlug(slug);
        if (normalizedOwner == null || normalizedSlug == null) return null;
        String base = (normalizedOwner + "-" + normalizedSlug)
                .replaceAll("[._]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        String suffix = sha256((normalizedOwner + "/" + normalizedSlug)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        int prefixLength = Math.min(base.length(), 64 - suffix.length() - 1);
        String prefix = base.substring(0, prefixLength).replaceAll("-$", "");
        return prefix + "-" + suffix;
    }

    static boolean isPublishableVersion(String version) {
        return version != null && version.length() <= 32 && SEMVER.matcher(version).matches();
    }

    static boolean isValidDisplayName(String name) {
        if (name == null) return false;
        String normalized = name.strip();
        return !normalized.isEmpty() && normalized.length() <= 128
                && normalized.codePoints().noneMatch(Character::isISOControl);
    }

    static Report blocked(
            String sha256,
            String code,
            String version,
            String owner,
            String slug,
            String reason) {
        return new Report(SOURCE_FORMAT, ADAPTER_VERSION, sha256, code, version, BLOCKED,
                List.of(), List.of(reason), null, owner, slug, null);
    }

    private static Archive readArchive(Path archive) {
        Map<String, PathEntry> pathKinds = new HashMap<>();
        List<ArchiveEntry> entries = new ArrayList<>();
        Map<String, byte[]> retained = new LinkedHashMap<>();
        long totalExpanded = 0L;
        int skillDocuments = 0;
        int originFiles = 0;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() == 0 || zip.size() > MAX_ENTRIES) {
                throw unsafe("Archive entry count is outside the supported range");
            }
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String originalName = entry.getName();
                boolean directory = entry.isDirectory() || originalName.endsWith("/");
                String name = directory ? originalName.substring(0, originalName.length() - 1)
                        : originalName;
                if (!safeName(name)) throw unsafe("Archive contains an unsafe path");
                if (!directory && isSkillDocument(name) && ++skillDocuments > 1) {
                    throw unsafe("Archive contains more than one Skill document and may contain nested executable Skills");
                }
                if (!directory && isOriginFile(name) && ++originFiles > 1) {
                    throw unsafe("Archive contains multiple ClawHub origin records");
                }
                registerPath(pathKinds, name, directory);
                long uncompressedSize = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (uncompressedSize < 0 || compressedSize < 0) {
                    throw unsafe("Archive contains an entry with unknown size");
                }
                if (!directory && uncompressedSize > 0
                        && (compressedSize == 0
                            || uncompressedSize > compressedSize * MAX_COMPRESSION_RATIO)) {
                    throw unsafe("Archive exceeds the supported compression ratio");
                }
                if (uncompressedSize > MAX_EXPANDED_BYTES - totalExpanded) {
                    throw unsafe("Archive expands beyond the 250 MiB safety limit");
                }
                totalExpanded += uncompressedSize;

                byte[] bytes = null;
                boolean keep = !directory && (isSkillDocument(name) || isOriginFile(name));
                long retainLimit = isOriginFile(name) ? MAX_ORIGIN_BYTES : MAX_DOCUMENT_BYTES;
                if (keep && uncompressedSize > retainLimit) {
                    if (isSkillDocument(name)) {
                        throw unsafe("Skill document exceeds the 2 MiB inspection limit");
                    }
                    keep = false;
                }
                if (!directory) {
                    long actualSize = 0L;
                    ByteArrayOutputStream buffer = keep
                            ? new ByteArrayOutputStream((int) Math.min(uncompressedSize, 8192L)) : null;
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] chunk = new byte[8192];
                        int count;
                        while ((count = input.read(chunk)) != -1) {
                            actualSize += count;
                            if (actualSize > uncompressedSize || actualSize > MAX_EXPANDED_BYTES) {
                                throw unsafe("Archive entry size does not match its central directory");
                            }
                            if (buffer != null) buffer.write(chunk, 0, count);
                        }
                    }
                    if (actualSize != uncompressedSize) {
                        throw unsafe("Archive entry size does not match its central directory");
                    }
                    if (buffer != null) bytes = buffer.toByteArray();
                }
                entries.add(new ArchiveEntry(name, directory, uncompressedSize, compressedSize));
                if (bytes != null) retained.put(name, bytes);
            }
        } catch (UnsafeArchive unsafe) {
            throw unsafe;
        } catch (IOException | RuntimeException invalidZip) {
            throw unsafe("Archive ZIP data is corrupt or unreadable");
        }
        return new Archive(List.copyOf(entries), Map.copyOf(retained));
    }

    private static void registerPath(Map<String, PathEntry> nodes, String path, boolean directory) {
        String[] parts = pathKey(path).split("/");
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < parts.length - 1; index++) {
            if (!current.isEmpty()) current.append('/');
            current.append(parts[index]);
            PathEntry parent = nodes.get(current.toString());
            if (parent == PathEntry.FILE) throw unsafe("Archive has a file/directory path conflict");
            nodes.putIfAbsent(current.toString(), PathEntry.IMPLICIT_DIRECTORY);
        }
        String key = pathKey(path);
        PathEntry previous = nodes.get(key);
        if (directory) {
            if (previous == PathEntry.FILE || previous == PathEntry.EXPLICIT_DIRECTORY) {
                throw unsafe("Archive contains duplicate or conflicting paths");
            }
            nodes.put(key, PathEntry.EXPLICIT_DIRECTORY);
        } else {
            if (previous != null) throw unsafe("Archive contains duplicate or conflicting paths");
            nodes.put(key, PathEntry.FILE);
        }
    }

    private static boolean safeName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.getBytes(StandardCharsets.UTF_8).length > 240
                || name.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            return false;
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".")
                    || part.endsWith(" ") || part.contains(":")
                    || part.chars().anyMatch(character -> "<>?*|\"".indexOf(character) >= 0)) {
                return false;
            }
            String stem = part.contains(".") ? part.substring(0, part.indexOf('.')) : part;
            String upperStem = stem.toUpperCase(Locale.ROOT);
            if (Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
                    "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3",
                    "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(upperStem)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSkillDocument(String name) {
        String fileName = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return fileName.equals("skill.md") || fileName.equals("skills.md");
    }

    private static String pathKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT)
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isOriginFile(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith("/.clawhub/origin.json")
                || name.equalsIgnoreCase(".clawhub/origin.json");
    }

    private static boolean isPlatformMetadata(String name) {
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return name.startsWith("__MACOSX/") || fileName.equals(".DS_Store")
                || fileName.equals("Thumbs.db") || fileName.equals("desktop.ini")
                || fileName.startsWith("._");
    }

    private static JsonNode parseOrigin(byte[] bytes) {
        if (bytes == null) return null;
        try {
            JsonNode origin = JSON.readTree(bytes);
            return origin != null && origin.isObject() ? origin : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private static String basename(String directory) {
        String normalized = directory.endsWith("/")
                ? directory.substring(0, directory.length() - 1) : directory;
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String titleFromSlug(String slug) {
        if (slug == null) return null;
        String[] words = slug.replace('-', ' ').split("\\s+");
        StringBuilder title = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return title.toString();
    }

    private static String nonTechnicalName(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.strip();
        return TECHNICAL_NAME.matcher(normalized).matches() ? null : normalized;
    }

    private static String firstMarkdownHeading(String document) {
        String normalized = document.replace("\r\n", "\n");
        int bodyStart = 0;
        if (normalized.startsWith("---\n")) {
            int end = frontmatterEnd(normalized);
            if (end >= 0) {
                bodyStart = end + 4;
                if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
                    bodyStart++;
                }
            }
        }
        boolean inFence = false;
        char fenceCharacter = 0;
        int fenceLength = 0;
        for (String line : normalized.substring(Math.min(bodyStart, normalized.length())).split("\n")) {
            var fenceMatcher = MARKDOWN_FENCE.matcher(line);
            if (fenceMatcher.matches()) {
                String fence = fenceMatcher.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceCharacter = fence.charAt(0);
                    fenceLength = fence.length();
                } else if (fence.charAt(0) == fenceCharacter && fence.length() >= fenceLength) {
                    inFence = false;
                }
                continue;
            }
            if (inFence) continue;
            var headingMatcher = MARKDOWN_H1.matcher(line);
            if (headingMatcher.matches()) {
                String heading = headingMatcher.group(1).strip().replaceAll("\\s+#+$", "").strip();
                if (!heading.isBlank()) return heading;
            }
        }
        return null;
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int frontmatterEnd(String document) {
        int from = 4;
        while (true) {
            int delimiter = document.indexOf("\n---", from);
            if (delimiter < 0) return -1;
            int after = delimiter + 4;
            if (after == document.length() || document.charAt(after) == '\n') return delimiter;
            from = after;
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static UnsafeArchive unsafe(String reason) {
        return new UnsafeArchive(reason);
    }

    private record Archive(List<ArchiveEntry> entries, Map<String, byte[]> retained) {}
    private record ArchiveEntry(String name, boolean directory, long sizeBytes, long compressedBytes) {}
    private enum PathEntry { FILE, EXPLICIT_DIRECTORY, IMPLICIT_DIRECTORY }

    private static final class UnsafeArchive extends RuntimeException {
        UnsafeArchive(String message) { super(message); }
    }
}
