package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.text.Collator;
import com.ibm.icu.util.ULocale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Downloads a bounded public source once, then hands its exact bytes to preview and commit. */
@Service
final class RemoteSkillFetchService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int METADATA_LIMIT = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_EXPANDED_BYTES = 250L * 1024 * 1024;
    private static final Duration API_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration ARCHIVE_TIMEOUT = Duration.ofMinutes(3);
    private static final Pattern GITHUB_SEGMENT = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final Pattern SHA40 = Pattern.compile("^[a-fA-F0-9]{40}$");
    private static final Pattern SHA64 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$");
    private static final Set<String> FETCH_HOSTS = Set.of(
            "clawhub.ai", "www.clawhub.ai", "api.github.com", "codeload.github.com",
            "raw.githubusercontent.com");

    record FetchRequest(String url) {}

    record Result(byte[] archive, String sha256, String metadataJson) {}

    record Metadata(String sourceKind, String owner, String slug, String version,
                    String displayName, String sourceUrl, String fileName,
                    String sourceRevision) {}

    record Reference(String sourceKind, String owner, String slug, String version,
                     String repo, List<String> treeParts, boolean documentLink,
                     String sourceUrl) {}

    record GitHubHandoff(String repo, String path, String commit, String contentHash) {}

    record ManifestFile(String path, long size, String sha256) {}

    record TreeBlob(String path, long size, String sha) {}

    record Archive(byte[] bytes, String revision) {}

    interface Fetcher {
        byte[] get(URI uri, int limit, Duration timeout) throws IOException, InterruptedException;
    }

    private final Fetcher fetcher;
    private final ClawhubSkillAdapter adapter = new ClawhubSkillAdapter();

    RemoteSkillFetchService() {
        this(new JdkFetcher());
    }

    RemoteSkillFetchService(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    Mono<Result> fetch(String url) {
        return Mono.fromCallable(() -> fetchBlocking(url)).subscribeOn(Schedulers.boundedElastic());
    }

    Result fetchBlocking(String url) {
        Reference ref = parseReference(url);
        try {
            return "CLAWHUB".equals(ref.sourceKind()) ? fetchClawHub(ref) : fetchGitHub(ref);
        } catch (ResponseStatusException status) {
            throw status;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "remote_skill_fetch_failed", failure);
        }
    }

    private Result fetchClawHub(Reference ref) throws IOException, InterruptedException {
        String base = "https://clawhub.ai/api/v1/skills/" + ref.slug();
        JsonNode detail = getJson(URI.create(base + "?ownerHandle=" + ref.owner()));
        if (!ref.slug().equalsIgnoreCase(text(detail.path("skill"), "slug"))
                || !ref.owner().equalsIgnoreCase(text(detail.path("owner"), "handle"))) {
            throw invalid("clawhub_owner_mismatch");
        }
        String displayName = ClawhubRegistryDisplayNameClient.selectDetailDisplayName(
                detail, ref.owner(), ref.slug());
        JsonNode install = getJson(URI.create(base + "/install?ownerHandle=" + ref.owner()));
        if (install.path("ok").isBoolean() && !install.path("ok").asBoolean()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "remote_skill_blocked");
        }
        if (!install.path("ok").asBoolean(false)
                || !ref.slug().equalsIgnoreCase(text(install, "slug"))) {
            throw invalid("clawhub_install_response_invalid");
        }

        String selectedVersion = ref.version();
        GitHubHandoff handoff = null;
        URI archiveUrl = null;
        if (selectedVersion != null) {
            archiveUrl = clawhubDownload(ref.owner(), ref.slug(), selectedVersion);
        } else if ("github".equals(text(install, "installKind"))) {
            handoff = parseHandoff(install.path("github"));
            archiveUrl = githubZipball(handoff.repo(), handoff.commit());
        } else if ("archive".equals(text(install, "installKind"))) {
            JsonNode archive = install.path("archive");
            if (selectedVersion == null) selectedVersion = text(archive, "version");
            if (!validVersion(selectedVersion)) throw invalid("clawhub_version_invalid");
            archiveUrl = clawhubDownload(ref.owner(), ref.slug(), selectedVersion);
            String advertised = text(archive, "downloadUrl");
            if (advertised != null) validateAdvertisedDownload(advertised, ref, selectedVersion);
        } else {
            throw invalid("clawhub_install_response_invalid");
        }
        List<ManifestFile> manifest = handoff == null
                ? versionManifest(ref, selectedVersion) : List.of();
        byte[] downloaded;
        try {
            downloaded = fetcher.get(archiveUrl,
                    (int) ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES, ARCHIVE_TIMEOUT);
        } catch (ResponseStatusException tooLarge) {
            if (handoff == null || !shouldUseGitHubTree(tooLarge)) throw tooLarge;
            Archive fallback = fetchGitHubTree(handoff.repo(), handoff.commit(),
                    handoff.path(), handoff.contentHash());
            return result(fallback.bytes(), new Metadata("CLAWHUB", ref.owner(), ref.slug(),
                    ClawhubSkillAdapter.isPublishableVersion(selectedVersion) ? selectedVersion : null,
                    displayName, ref.sourceUrl(), ref.slug() + ".zip", handoff.commit()));
        } catch (IOException unreadable) {
            if (handoff == null) throw unreadable;
            Archive fallback = fetchGitHubTree(handoff.repo(), handoff.commit(),
                    handoff.path(), handoff.contentHash());
            return result(fallback.bytes(), new Metadata("CLAWHUB", ref.owner(), ref.slug(),
                    ClawhubSkillAdapter.isPublishableVersion(selectedVersion) ? selectedVersion : null,
                    displayName, ref.sourceUrl(), ref.slug() + ".zip", handoff.commit()));
        }
        if (handoff == null && !looksLikeZip(downloaded)) {
            handoff = parseHandoff(parseJson(downloaded));
        }
        Archive archive;
        if (handoff == null) {
            verifyHostedManifest(downloaded, manifest, ref.slug(), selectedVersion);
            archive = new Archive(downloaded, null);
        } else {
            try {
                byte[] repository = archiveUrl.equals(githubZipball(handoff.repo(), handoff.commit()))
                        ? downloaded
                        : fetcher.get(githubZipball(handoff.repo(), handoff.commit()),
                                (int) ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES, ARCHIVE_TIMEOUT);
                archive = selectGitHubSkill(repository, handoff.path(), handoff.contentHash());
            } catch (ResponseStatusException tooLarge) {
                if (!shouldUseGitHubTree(tooLarge)) throw tooLarge;
                archive = fetchGitHubTree(handoff.repo(), handoff.commit(),
                        handoff.path(), handoff.contentHash());
            } catch (IOException unreadable) {
                archive = fetchGitHubTree(handoff.repo(), handoff.commit(),
                        handoff.path(), handoff.contentHash());
            }
        }
        String publishVersion = ClawhubSkillAdapter.isPublishableVersion(selectedVersion)
                ? selectedVersion : null;
        return result(archive.bytes(), new Metadata("CLAWHUB", ref.owner(), ref.slug(),
                publishVersion, displayName, ref.sourceUrl(), ref.slug() + ".zip",
                handoff == null ? null : handoff.commit()));
    }

    private Result fetchGitHub(Reference ref) throws IOException, InterruptedException {
        String base = "https://api.github.com/repos/" + ref.repo();
        JsonNode repository = getJson(URI.create(base));
        String branch = text(repository, "default_branch");
        if (branch == null || branch.length() > 200) throw invalid("github_repository_invalid");
        List<String> parts = ref.treeParts();
        String sourcePath = "";
        String resolvedRef = branch;
        if (!parts.isEmpty()) {
            resolvedRef = null;
            // GitHub refs can contain slashes. Resolve the longest valid commit prefix.
            for (int count = parts.size(); count >= 1; count--) {
                String candidate = String.join("/", parts.subList(0, count));
                try {
                    JsonNode commit = getJson(URI.create(base + "/commits/" + encodePath(candidate)));
                    String sha = text(commit, "sha");
                    if (sha != null && SHA40.matcher(sha).matches()) {
                        resolvedRef = sha;
                        sourcePath = String.join("/", parts.subList(count, parts.size()));
                        break;
                    }
                } catch (ResponseStatusException status) {
                    if (status.getStatusCode().value() != 404) throw status;
                }
            }
            if (resolvedRef == null) throw invalid("github_tree_path_invalid");
            if (ref.documentLink()) {
                if (!isSkillDocument(sourcePath)) throw invalid("github_document_link_invalid");
                sourcePath = directory(sourcePath);
                if (sourcePath.endsWith("/")) sourcePath = sourcePath.substring(0, sourcePath.length() - 1);
            }
        }
        JsonNode commit = getJson(URI.create(base + "/commits/" + encodePath(resolvedRef)));
        String sha = text(commit, "sha");
        if (sha == null || !SHA40.matcher(sha).matches()) throw invalid("github_commit_invalid");
        Archive archive;
        try {
            byte[] repositoryZip = fetcher.get(githubZipball(ref.repo(), sha),
                    (int) ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES, ARCHIVE_TIMEOUT);
            archive = selectGitHubSkill(repositoryZip, sourcePath, null);
        } catch (ResponseStatusException tooLarge) {
            if (!shouldUseGitHubTree(tooLarge)) throw tooLarge;
            archive = fetchGitHubTree(ref.repo(), sha, sourcePath, null);
        } catch (IOException unreadable) {
            archive = fetchGitHubTree(ref.repo(), sha, sourcePath, null);
        }
        String owner = "github." + ref.owner();
        String slug = githubSlug(ref.slug(), sourcePath);
        return result(archive.bytes(), new Metadata("GITHUB", owner, slug, null,
                null, ref.sourceUrl(), slug + ".zip", sha));
    }

    private Result result(byte[] archive, Metadata metadata) throws IOException {
        if (archive.length == 0 || archive.length > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "remote_skill_archive_too_large");
        }
        Path temp = Files.createTempFile("remote-skill-inspect-", ".zip");
        try {
            Files.write(temp, archive);
            ClawhubSkillAdapter.Report report = adapter.inspect(temp,
                    metadata.owner(), metadata.slug(), metadata.version(), metadata.displayName());
            if (ClawhubSkillAdapter.BLOCKED.equals(report.status())) {
                throw invalid("remote_skill_archive_unsafe");
            }
            String name = metadata.displayName() == null ? report.name() : metadata.displayName();
            Metadata resolved = new Metadata(metadata.sourceKind(), metadata.owner(), metadata.slug(),
                    metadata.version() == null ? report.version() : metadata.version(),
                    name, metadata.sourceUrl(), metadata.fileName(), metadata.sourceRevision());
            return new Result(archive, report.sourceSha256(), JSON.writeValueAsString(resolved));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private List<ManifestFile> versionManifest(Reference ref, String version)
            throws IOException, InterruptedException {
        JsonNode detail = getJson(URI.create("https://clawhub.ai/api/v1/skills/" + ref.slug()
                + "/versions/" + encodePath(version) + "?ownerHandle=" + ref.owner()));
        if (!ref.slug().equalsIgnoreCase(text(detail.path("skill"), "slug"))
                || !version.equals(text(detail.path("version"), "version"))) {
            throw invalid("clawhub_version_manifest_invalid");
        }
        JsonNode files = detail.path("version").path("files");
        if (!files.isArray() || files.isEmpty() || files.size() > MAX_ENTRIES) {
            throw invalid("clawhub_version_manifest_invalid");
        }
        List<ManifestFile> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (JsonNode file : files) {
            String path = text(file, "path");
            String hash = text(file, "sha256");
            JsonNode size = file.path("size");
            if (!safeRelativePath(path) || hash == null || !SHA64.matcher(hash).matches()
                    || !size.isIntegralNumber() || size.longValue() < 0
                    || !seen.add(path.toLowerCase(Locale.ROOT))) {
                throw invalid("clawhub_version_manifest_invalid");
            }
            total += size.longValue();
            if (total > MAX_EXPANDED_BYTES) throw invalid("clawhub_version_manifest_invalid");
            result.add(new ManifestFile(path, size.longValue(), hash.toLowerCase(Locale.ROOT)));
        }
        return result;
    }

    void verifyHostedManifest(byte[] bytes, List<ManifestFile> manifest,
                              String slug, String version) throws IOException {
        if (!looksLikeZip(bytes)) throw invalid("clawhub_download_not_zip");
        Path temp = Files.createTempFile("clawhub-hosted-", ".zip");
        try {
            Files.write(temp, bytes);
            if (SkillPackageInspector.hasSymlinkEntry(temp)) throw invalid("remote_skill_archive_unsafe");
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                List<String> names = safeNames(zip);
                String document = soleSkillDocument(names, "");
                String root = directory(document);
                Map<String, ManifestFile> expected = new HashMap<>();
                for (ManifestFile file : manifest) expected.put(file.path(), file);
                Set<String> found = new HashSet<>();
                for (String name : names) {
                    if (!name.startsWith(root)) throw invalid("clawhub_version_manifest_mismatch");
                    String relative = name.substring(root.length());
                    if (relative.equals("_meta.json")) {
                        // ClawHub regenerates this transport file at download time. It can also
                        // appear in the published files[] manifest with a different byte hash.
                        // Verify its identity, then count it if the manifest declared it.
                        verifyTransportMetadata(zip, zip.getEntry(name), slug, version);
                        if (expected.containsKey(relative)) found.add(relative);
                        continue;
                    }
                    ManifestFile file = expected.get(relative);
                    if (file == null) throw invalid("clawhub_version_manifest_mismatch");
                    ZipEntry entry = zip.getEntry(name);
                    if (entry.getSize() != file.size()
                            || !hashEntry(zip, entry).equals(file.sha256())) {
                        throw invalid("clawhub_version_manifest_mismatch");
                    }
                    found.add(relative);
                }
                if (found.size() != expected.size()) throw invalid("clawhub_version_manifest_mismatch");
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void verifyTransportMetadata(ZipFile zip, ZipEntry entry,
                                                String slug, String version) throws IOException {
        if (entry.getSize() <= 0 || entry.getSize() > 16_384) {
            throw invalid("clawhub_version_manifest_mismatch");
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = in.readNBytes(16_385);
        }
        if (bytes.length != entry.getSize()) throw invalid("clawhub_version_manifest_mismatch");
        JsonNode transport = parseJson(bytes);
        String ownerId = text(transport, "ownerId");
        JsonNode publishedAt = transport.path("publishedAt");
        if (ownerId == null || ownerId.length() > 128
                || !slug.equals(text(transport, "slug"))
                || !version.equals(text(transport, "version"))
                || !publishedAt.isIntegralNumber() || publishedAt.longValue() <= 0) {
            throw invalid("clawhub_version_manifest_mismatch");
        }
    }

    private Archive selectGitHubSkill(byte[] repositoryZip, String requestedPath,
                                      String expectedContentHash) throws IOException {
        if (!looksLikeZip(repositoryZip)) throw invalid("github_archive_not_zip");
        Path temp = Files.createTempFile("github-skill-", ".zip");
        try {
            Files.write(temp, repositoryZip);
            if (SkillPackageInspector.hasSymlinkEntry(temp)) throw invalid("remote_skill_archive_unsafe");
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                List<String> names = requestedPath.isEmpty()
                        ? safeNames(zip) : safeNames(zip, 100_000, Long.MAX_VALUE);
                if (names.isEmpty()) throw invalid("github_archive_empty");
                String wrapper = firstComponent(names.getFirst());
                if (names.stream().anyMatch(name -> !name.startsWith(wrapper + "/"))) {
                    throw invalid("github_archive_root_invalid");
                }
                String subtree = requestedPath.isEmpty() ? "" : requestedPath + "/";
                String base = wrapper + "/" + subtree;
                String document = soleSkillDocument(names, base);
                String selectedRoot = requestedPath.isEmpty() || !document.equals(base + "SKILL.md")
                        && !document.equalsIgnoreCase(base + "skill.md")
                        && !document.equalsIgnoreCase(base + "skills.md")
                        ? directory(document) : base;
                // A selected directory's nested single Skill is the actual package root.
                Map<String, String> selected = new LinkedHashMap<>();
                for (String name : names) {
                    if (name.startsWith(selectedRoot)) {
                        String relative = name.substring(selectedRoot.length());
                        if (!relative.isEmpty()) selected.put(relative, name);
                    }
                }
                if (selected.isEmpty()) throw invalid("github_skill_path_empty");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                List<String> contentLines = new ArrayList<>();
                long expanded = 0;
                try (ZipOutputStream out = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                    for (Map.Entry<String, String> file : selected.entrySet()) {
                        ZipEntry entry = zip.getEntry(file.getValue());
                        ZipEntry target = new ZipEntry(file.getKey());
                        target.setTime(0L);
                        out.putNextEntry(target);
                        MessageDigest digest = sha256Digest();
                        long size = 0;
                        try (InputStream in = zip.getInputStream(entry)) {
                            byte[] chunk = new byte[8192];
                            for (int read; (read = in.read(chunk)) >= 0;) {
                                size += read;
                                expanded += read;
                                if (expanded > MAX_EXPANDED_BYTES) throw invalid("github_archive_expanded_too_large");
                                digest.update(chunk, 0, read);
                                out.write(chunk, 0, read);
                                if (output.size() > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
                                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                            "remote_skill_archive_too_large");
                                }
                            }
                        }
                        out.closeEntry();
                        contentLines.add(file.getKey() + "\0" + size + "\0"
                                + HexFormat.of().formatHex(digest.digest()));
                    }
                }
                verifyContentHash(contentLines, expectedContentHash);
                return new Archive(output.toByteArray(), null);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Bounded fallback for a small Skill inside a repository whose whole zipball is too large. */
    private Archive fetchGitHubTree(String repo, String commit, String requestedPath,
                                    String expectedContentHash)
            throws IOException, InterruptedException {
        JsonNode commitDetail = getJson(URI.create("https://api.github.com/repos/" + repo
                + "/commits/" + commit));
        String treeSha = text(commitDetail.path("commit").path("tree"), "sha");
        if (treeSha == null || !SHA40.matcher(treeSha).matches()) {
            throw invalid("github_tree_response_invalid");
        }
        String api = "https://api.github.com/repos/" + repo + "/git/trees/";
        if (!requestedPath.isEmpty()) {
            for (String segment : requestedPath.split("/")) {
                JsonNode level = getJson(URI.create(api + treeSha));
                JsonNode entries = level.path("tree");
                if (!entries.isArray()) throw invalid("github_tree_response_invalid");
                String next = null;
                for (JsonNode entry : entries) {
                    if (segment.equals(text(entry, "path"))
                            && "tree".equals(text(entry, "type"))) {
                        next = text(entry, "sha");
                        break;
                    }
                }
                if (next == null || !SHA40.matcher(next).matches()) {
                    throw invalid("github_tree_path_invalid");
                }
                treeSha = next;
            }
        }
        JsonNode tree = getJson(URI.create(api + treeSha + "?recursive=1"));
        if (tree.path("truncated").asBoolean(false) || !tree.path("tree").isArray()) {
            throw invalid("github_tree_response_invalid");
        }
        List<TreeBlob> allFiles = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        for (JsonNode entry : tree.path("tree")) {
            String path = text(entry, "path");
            String type = text(entry, "type");
            String mode = text(entry, "mode");
            if (path == null || !safeRelativePath(path) || path.endsWith("/")) {
                throw invalid("github_tree_response_invalid");
            }
            if ("tree".equals(type)) continue;
            if (!"blob".equals(type) || !("100644".equals(mode) || "100755".equals(mode))) {
                throw invalid("github_tree_entry_unsafe");
            }
            String blobSha = text(entry, "sha");
            JsonNode sizeNode = entry.path("size");
            if (blobSha == null || !SHA40.matcher(blobSha).matches()
                    || !sizeNode.isIntegralNumber() || sizeNode.longValue() < 0) {
                throw invalid("github_tree_response_invalid");
            }
            allFiles.add(new TreeBlob(path, sizeNode.longValue(), blobSha));
            if (isSkillDocument(path)) documents.add(path);
        }
        if (documents.size() != 1) throw invalid("remote_skill_document_ambiguous");
        String selectedRoot = directory(documents.getFirst());
        List<TreeBlob> files = allFiles.stream()
                .filter(file -> file.path().startsWith(selectedRoot)).toList();
        if (files.isEmpty() || files.size() > MAX_ENTRIES) throw invalid("github_tree_entry_limit");
        long expanded = 0;
        Set<String> seen = new HashSet<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<String> contentLines = new ArrayList<>();
        try (ZipOutputStream out = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (TreeBlob file : files) {
                String relative = file.path().substring(selectedRoot.length());
                if (!seen.add(relative.toLowerCase(Locale.ROOT))) {
                    throw invalid("github_tree_entry_unsafe");
                }
                expanded += file.size();
                if (expanded > MAX_EXPANDED_BYTES || file.size() > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "remote_skill_archive_too_large");
                }
                URI raw = URI.create("https://raw.githubusercontent.com/" + repo + "/" + commit
                        + "/" + encodeRelativePath(requestedPath)
                        + (requestedPath.isEmpty() ? "" : "/")
                        + encodeRelativePath(file.path()));
                byte[] bytes = fetcher.get(raw, (int) ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES,
                        ARCHIVE_TIMEOUT);
                if (bytes.length != file.size() || !gitBlobSha(bytes).equalsIgnoreCase(file.sha())) {
                    throw invalid("github_tree_blob_mismatch");
                }
                ZipEntry target = new ZipEntry(relative);
                target.setTime(0L);
                out.putNextEntry(target);
                out.write(bytes);
                out.closeEntry();
                if (output.size() > ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "remote_skill_archive_too_large");
                }
                contentLines.add(relative + "\0" + bytes.length + "\0" + sha256(bytes));
            }
        }
        verifyContentHash(contentLines, expectedContentHash);
        return new Archive(output.toByteArray(), commit);
    }

    private static boolean shouldUseGitHubTree(ResponseStatusException failure) {
        return failure.getStatusCode().value() == 413
                || "github_repository_archive_too_many_entries".equals(failure.getReason())
                || "remote_skill_archive_unsafe".equals(failure.getReason());
    }

    private static String encodeRelativePath(String path) {
        if (path.isEmpty()) return "";
        String[] parts = path.split("/");
        List<String> encoded = new ArrayList<>(parts.length);
        for (String part : parts) encoded.add(encodePath(part));
        return String.join("/", encoded);
    }

    private static String gitBlobSha(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void verifyContentHash(List<String> lines, String expected) {
        if (expected == null) return;
        // Match JavaScript's ICU localeCompare order used by ClawHub's directory hash.
        Collator collator = Collator.getInstance(ULocale.ENGLISH);
        lines.sort((left, right) -> collator.compare(
                left.substring(0, left.indexOf('\0')),
                right.substring(0, right.indexOf('\0'))));
        String actual = sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        if (!actual.equalsIgnoreCase(expected)) {
            throw invalid("clawhub_github_content_hash_mismatch");
        }
    }

    private static List<String> safeNames(ZipFile zip) {
        return safeNames(zip, MAX_ENTRIES, MAX_EXPANDED_BYTES);
    }

    private static List<String> safeNames(ZipFile zip, int maxEntries, long maxExpanded) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        long expanded = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > maxEntries) {
                throw invalid("github_repository_archive_too_many_entries");
            }
            if (!safeRelativePath(entry.getName())) {
                throw invalid("remote_skill_archive_unsafe");
            }
            if (entry.isDirectory()) continue;
            if (entry.getSize() < 0 || !seen.add(entry.getName().toLowerCase(Locale.ROOT))) {
                throw invalid("remote_skill_archive_unsafe");
            }
            expanded += entry.getSize();
            if (expanded > maxExpanded) throw invalid("remote_skill_archive_unsafe");
            names.add(entry.getName());
        }
        return names;
    }

    private static String soleSkillDocument(List<String> names, String base) {
        List<String> documents = names.stream()
                .filter(name -> name.startsWith(base) && isSkillDocument(name))
                .toList();
        if (documents.size() != 1) throw invalid("remote_skill_document_ambiguous");
        return documents.getFirst();
    }

    private static boolean isSkillDocument(String path) {
        String leaf = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return leaf.equals("skill.md") || leaf.equals("skills.md");
    }

    private static String directory(String path) {
        return path.substring(0, path.lastIndexOf('/') + 1);
    }

    private static String firstComponent(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private static boolean safeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 1024 || path.startsWith("/")
                || path.indexOf('\\') >= 0) return false;
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                if (i == parts.length - 1) continue;
                return false;
            }
            if (part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.codePoints().anyMatch(ch -> ch < 32 || "<>:\"|?*".indexOf(ch) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static String hashEntry(ZipFile zip, ZipEntry entry) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] chunk = new byte[8192];
            for (int read; (read = in.read(chunk)) >= 0;) digest.update(chunk, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private JsonNode getJson(URI uri) throws IOException, InterruptedException {
        return parseJson(fetcher.get(uri, METADATA_LIMIT, API_TIMEOUT));
    }

    private static JsonNode parseJson(byte[] bytes) {
        try {
            JsonNode value = JSON.readTree(bytes);
            if (value == null || !value.isObject()) throw invalid("remote_skill_json_invalid");
            return value;
        } catch (IOException malformed) {
            throw invalid("remote_skill_json_invalid");
        }
    }

    private static String text(JsonNode parent, String key) {
        JsonNode value = parent.path(key);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    private static GitHubHandoff parseHandoff(JsonNode value) {
        if (!"public-github".equals(text(value, "sourceRef"))
                && value.has("sourceRef")) throw invalid("clawhub_handoff_invalid");
        String repo = text(value, "repo");
        String path = text(value, "path");
        String commit = text(value, "commit");
        String hash = text(value, "contentHash");
        if (!validRepo(repo) || !safeRelativePath(path) || path.endsWith("/")
                || commit == null || !SHA40.matcher(commit).matches()
                || hash == null || !SHA64.matcher(hash).matches()) {
            throw invalid("clawhub_handoff_invalid");
        }
        String advertised = text(value, "archiveUrl");
        if (advertised != null) {
            try {
                if (!githubZipball(repo, commit).equals(URI.create(advertised))) {
                    throw invalid("clawhub_handoff_invalid");
                }
            } catch (IllegalArgumentException malformed) {
                throw invalid("clawhub_handoff_invalid");
            }
        }
        return new GitHubHandoff(repo, path, commit.toLowerCase(Locale.ROOT),
                hash.toLowerCase(Locale.ROOT));
    }

    private static URI githubZipball(String repo, String sha) {
        if (!validRepo(repo) || !SHA40.matcher(sha).matches()) throw invalid("github_reference_invalid");
        return URI.create("https://api.github.com/repos/" + repo + "/zipball/" + sha);
    }

    private static URI clawhubDownload(String owner, String slug, String version) {
        return URI.create("https://clawhub.ai/api/v1/download?slug=" + slug
                + "&ownerHandle=" + owner + "&version=" + encodeQuery(version));
    }

    private static void validateAdvertisedDownload(String raw, Reference ref, String version) {
        try {
            URI advertised = URI.create(raw);
            if (!advertised.isAbsolute()) advertised = URI.create("https://clawhub.ai").resolve(advertised);
            if (!"https".equalsIgnoreCase(advertised.getScheme())
                    || !"clawhub.ai".equalsIgnoreCase(advertised.getHost())
                    || advertised.getPort() != -1 || advertised.getUserInfo() != null
                    || advertised.getFragment() != null
                    || !"/api/v1/download".equals(advertised.getPath())) {
                throw invalid("clawhub_download_url_invalid");
            }
            Map<String, String> query = query(advertised.getRawQuery(),
                    Set.of("ownerHandle", "slug", "version"));
            if (!ref.slug().equals(query.get("slug"))
                    || query.get("ownerHandle") != null
                            && !ref.owner().equalsIgnoreCase(query.get("ownerHandle"))
                    || query.get("version") != null
                            && !version.equals(query.get("version"))) {
                throw invalid("clawhub_download_url_invalid");
            }
        } catch (IllegalArgumentException malformed) {
            throw invalid("clawhub_download_url_invalid");
        }
    }

    static Reference parseReference(String raw) {
        URI url;
        try {
            if (raw == null || raw.length() > 2048) throw invalid("remote_skill_url_invalid");
            url = URI.create(raw.strip());
        } catch (IllegalArgumentException malformed) {
            throw invalid("remote_skill_url_invalid");
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getPort() != -1 || url.getUserInfo() != null || url.getFragment() != null) {
            throw invalid("remote_skill_url_invalid");
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String[] parts = url.getPath().split("/");
        if (host.equals("clawhub.ai") || host.equals("www.clawhub.ai")) {
            String owner;
            String slug;
            String version = null;
            if (url.getPath().equals("/api/v1/download")) {
                Map<String, String> query = query(url.getRawQuery(), Set.of("ownerHandle", "slug", "version"));
                owner = query.get("ownerHandle");
                slug = query.get("slug");
                version = query.get("version");
            } else {
                Map<String, String> query = query(url.getRawQuery(), Set.of("version"));
                version = query.get("version");
                if (parts.length == 4 && parts[2].equals("skills")) {
                    owner = parts[1]; slug = parts[3];
                } else if (parts.length == 4 && parts[1].equals("skills")) {
                    owner = parts[2]; slug = parts[3];
                } else if (parts.length == 3) {
                    owner = parts[1]; slug = parts[2];
                } else if (parts.length == 6 && parts[2].equals("skills")
                        && parts[4].equals("versions")) {
                    owner = parts[1]; slug = parts[3]; version = parts[5];
                } else {
                    throw invalid("remote_skill_url_invalid");
                }
            }
            if (owner == null || slug == null || !owner.equals(ClawhubSkillAdapter.normalizeOwner(owner))
                    || !slug.equals(ClawhubSkillAdapter.normalizeSlug(slug))
                    || version != null && !validVersion(version)) {
                throw invalid("remote_skill_url_invalid");
            }
            return new Reference("CLAWHUB", owner, slug, version, null, List.of(), false,
                    "https://clawhub.ai/" + owner + "/skills/" + slug);
        }
        if (!host.equals("github.com") && !host.equals("www.github.com")) {
            throw invalid("remote_skill_url_invalid");
        }
        if (url.getRawQuery() != null || parts.length < 3) throw invalid("remote_skill_url_invalid");
        String owner = parts[1];
        String repo = parts[2].replaceFirst("(?i)\\.git$", "");
        if (!validGithubSegment(owner) || !validGithubSegment(repo)) {
            throw invalid("remote_skill_url_invalid");
        }
        List<String> treeParts = List.of();
        boolean documentLink = false;
        if (parts.length > 3) {
            if (parts.length < 5 || !(parts[3].equals("tree") || parts[3].equals("blob"))
                    || parts.length > 20) {
                throw invalid("remote_skill_url_invalid");
            }
            documentLink = parts[3].equals("blob");
            treeParts = List.of(parts).subList(4, parts.length);
            if (treeParts.stream().anyMatch(part -> !validGithubPathSegment(part))) {
                throw invalid("remote_skill_url_invalid");
            }
        }
        return new Reference("GITHUB", owner.toLowerCase(Locale.ROOT), repo.toLowerCase(Locale.ROOT),
                null, owner + "/" + repo, treeParts, documentLink, raw.strip());
    }

    private static Map<String, String> query(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> parsed = new HashMap<>();
        for (String pair : raw.split("&", -1)) {
            String[] halves = pair.split("=", 2);
            String key = URLDecoder.decode(halves[0], StandardCharsets.UTF_8);
            String value = halves.length == 2
                    ? URLDecoder.decode(halves[1], StandardCharsets.UTF_8) : "";
            if (!allowed.contains(key) || parsed.putIfAbsent(key, value) != null) {
                throw invalid("remote_skill_url_invalid");
            }
        }
        return parsed;
    }

    private static boolean validVersion(String value) {
        return value != null && VERSION.matcher(value).matches();
    }

    private static boolean validGithubSegment(String part) {
        return part != null && part.length() <= 100 && GITHUB_SEGMENT.matcher(part).matches()
                && !part.equals(".") && !part.equals("..");
    }

    private static boolean validGithubPathSegment(String part) {
        return part != null && !part.isBlank() && part.length() <= 240
                && !part.equals(".") && !part.equals("..")
                && !part.endsWith(".") && !part.endsWith(" ")
                && part.codePoints().noneMatch(ch -> ch < 32 || ch == 127
                        || "\\<>:\"|?*".indexOf(ch) >= 0);
    }

    private static boolean validRepo(String repo) {
        if (repo == null) return false;
        String[] parts = repo.split("/", -1);
        return parts.length == 2 && validGithubSegment(parts[0]) && validGithubSegment(parts[1]);
    }

    private static String githubSlug(String repo, String path) {
        String raw = (repo + (path.isEmpty() ? "" : "-" + path.replace('/', '-')))
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        String hash = sha256((repo.toLowerCase(Locale.ROOT) + "/" + path)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        int maxPrefix = Math.min(raw.length(), 64 - hash.length() - 1);
        String prefix = raw.substring(0, maxPrefix).replaceAll("-$", "");
        return prefix + "-" + hash;
    }

    private static String encodePath(String path) {
        return java.net.URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean looksLikeZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 3 && bytes[3] == 4;
    }

    static boolean githubCommitNotFound(URI uri, int status, byte[] body) {
        return status == 422 && "api.github.com".equalsIgnoreCase(uri.getHost())
                && uri.getPath().matches("^/repos/[^/]+/[^/]+/commits/.+$")
                && new String(body, StandardCharsets.UTF_8)
                        .contains("No commit found for SHA:");
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    /** JDK client disables automatic redirects so every Location is checked before a new request. */
    private static final class JdkFetcher implements Fetcher {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public byte[] get(URI initial, int limit, Duration timeout)
                throws IOException, InterruptedException {
            URI current = initial;
            long deadline = System.nanoTime() + timeout.toNanos();
            for (int redirects = 0; redirects <= 5; redirects++) {
                checkFetchUri(current);
                long budget = deadline - System.nanoTime();
                if (budget <= 0) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "remote_skill_timeout");
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofNanos(budget))
                        .header("Accept", "application/json, application/zip, application/octet-stream")
                        .header("User-Agent", "Yuzhi-Skill-Remote-Importer")
                        .GET().build();
                HttpResponse<InputStream> response;
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (HttpTimeoutException timeoutError) {
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                            "remote_skill_timeout", timeoutError);
                }
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status >= 300 && status < 400) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IOException("remote_skill_redirect_missing"));
                        current = current.resolve(location);
                        checkFetchUri(current);
                        continue;
                    }
                    if (status == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "remote_skill_not_found");
                    if (status == 422
                            && githubCommitNotFound(current, status, readBounded(body, 16_384))) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "github_commit_ref_not_found");
                    }
                    if (status == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "remote_skill_rate_limited");
                    if (status == 403 || status == 409 || status == 410 || status == 423) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "remote_skill_blocked");
                    }
                    if (status < 200 || status >= 300) throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "remote_skill_upstream_failed");
                    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (declared > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "remote_skill_response_too_large");
                    FutureTask<byte[]> reading = new FutureTask<>(() -> readBounded(body, limit));
                    Thread.ofVirtual().name("remote-skill-download").start(reading);
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        reading.cancel(true);
                        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                                "remote_skill_timeout");
                    }
                    try {
                        return reading.get(remaining, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException timeoutError) {
                        reading.cancel(true);
                        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                                "remote_skill_timeout", timeoutError);
                    } catch (ExecutionException readError) {
                        Throwable cause = readError.getCause();
                        if (cause instanceof IOException io) throw io;
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        throw new IOException("remote_skill_read_failed", cause);
                    }
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "remote_skill_redirect_limit");
        }

        private static byte[] readBounded(InputStream body, int limit) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int read; (read = body.read(chunk)) >= 0;) {
                if (bytes.size() + read > limit) throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "remote_skill_response_too_large");
                bytes.write(chunk, 0, read);
            }
            return bytes.toByteArray();
        }

        static void checkFetchUri(URI uri) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !FETCH_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw invalid("remote_skill_redirect_unsafe");
            }
        }
    }
}
