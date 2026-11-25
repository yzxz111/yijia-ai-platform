package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.server.ResponseStatusException;

class RemoteSkillFetchServiceTest {
    private static final String DOC = "---\nname: PDF Guide\ndescription: Read PDFs\n---\n# PDF Guide\n";
    private static final String HOSTED_META = "{\"ownerId\":\"kn75s9ftw1pk74c5se78fq0r9182837z\","
            + "\"slug\":\"pdf\",\"version\":\"1.2.3\",\"publishedAt\":1778441820962}";

    @Test
    void acceptsOnlyPublicHttpsSourcePages() {
        assertThat(RemoteSkillFetchService.parseReference(
                "https://clawhub.ai/alice/skills/pdf?version=1.2.3").version())
                .isEqualTo("1.2.3");
        assertThat(RemoteSkillFetchService.parseReference(
                "https://github.com/acme/skills/tree/feature/branch/skills/pdf")
                .treeParts()).containsExactly("feature", "branch", "skills", "pdf");
        for (String unsafe : new String[] {
                "http://clawhub.ai/alice/skills/pdf",
                "https://clawhub.ai.evil.test/alice/skills/pdf",
                "https://clawhub.ai:443/alice/skills/pdf",
                "https://user@github.com/acme/skills",
                "https://github.com/acme/skills/tree/main/../private",
                "https://github.com/acme/skills?tab=readme"
        }) {
            assertThatThrownBy(() -> RemoteSkillFetchService.parseReference(unsafe))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void githubHandoffHashUsesJavascriptLocaleOrder() throws Exception {
        List<String> lines = new ArrayList<>(List.of(
                "a.txt\0" + "1\0" + "a".repeat(64),
                "a-b\0" + "1\0" + "b".repeat(64)));
        String expected = sha((lines.get(1) + "\n" + lines.get(0))
                .getBytes(StandardCharsets.UTF_8));
        RemoteSkillFetchService.verifyContentHash(lines, expected);
        assertThat(lines.getFirst()).startsWith("a-b\0");
    }

    @Test
    void githubCommitCandidateRecognizesOnlyNoCommit422() {
        URI lookup = URI.create("https://api.github.com/repos/anthropics/skills/commits/"
                + "main%2Fskills%2Fpdf");
        byte[] noCommit = "{\"message\":\"No commit found for SHA: main/skills/pdf\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(RemoteSkillFetchService.githubCommitNotFound(lookup, 422, noCommit)).isTrue();
        assertThat(RemoteSkillFetchService.githubCommitNotFound(lookup, 422,
                "{\"message\":\"Validation Failed\"}".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(RemoteSkillFetchService.githubCommitNotFound(
                URI.create("https://api.github.com/repos/anthropics/skills/git/trees/x"),
                422, noCommit)).isFalse();
    }

    @Test
    void githubSubdirectoryPreservesEveryFileAndSelectsOneSkill() throws Exception {
        FakeFetcher fake = new FakeFetcher();
        String commit = "a".repeat(40);
        fake.add("https://api.github.com/repos/acme/skills",
                "{\"default_branch\":\"main\"}");
        fake.add("https://api.github.com/repos/acme/skills/commits/main%2Fskills%2Fpdf", 404);
        fake.add("https://api.github.com/repos/acme/skills/commits/main%2Fskills", 404);
        fake.add("https://api.github.com/repos/acme/skills/commits/main",
                "{\"sha\":\"" + commit + "\"}");
        fake.add("https://api.github.com/repos/acme/skills/commits/" + commit,
                "{\"sha\":\"" + commit + "\"}");
        byte[] repository = zip(Map.of(
                "acme-skills-x/skills/pdf/SKILL.md", DOC,
                "acme-skills-x/skills/pdf/references/usage.txt", "keep me",
                "acme-skills-x/skills/other/SKILL.md", "# Other"));
        fake.add("https://api.github.com/repos/acme/skills/zipball/" + commit, repository);

        RemoteSkillFetchService.Result result = new RemoteSkillFetchService(fake)
                .fetchBlocking("https://github.com/acme/skills/tree/main/skills/pdf");
        assertThat(entries(result.archive()))
                .containsEntry("SKILL.md", DOC)
                .containsEntry("references/usage.txt", "keep me")
                .doesNotContainKey("../other/SKILL.md");
        assertThat(result.metadataJson()).contains("\"sourceKind\":\"GITHUB\"")
                .contains("\"owner\":\"github.acme\"")
                .contains("\"sourceRevision\":\"" + commit + "\"");
        assertThat(result.sha256()).isEqualTo(sha(result.archive()));
    }

    @Test
    void largeRepositoryFallsBackToFixedCommitSubtree() throws Exception {
        FakeFetcher fake = new FakeFetcher();
        String commit = "a".repeat(40);
        String rootTree = "d".repeat(40);
        String skillsTree = "e".repeat(40);
        String pdfTree = "f".repeat(40);
        byte[] document = DOC.getBytes(StandardCharsets.UTF_8);
        byte[] support = "keep me".getBytes(StandardCharsets.UTF_8);
        fake.add("https://api.github.com/repos/acme/skills", "{\"default_branch\":\"main\"}");
        fake.add("https://api.github.com/repos/acme/skills/commits/main%2Fskills%2Fpdf", 404);
        fake.add("https://api.github.com/repos/acme/skills/commits/main%2Fskills", 404);
        fake.add("https://api.github.com/repos/acme/skills/commits/main",
                "{\"sha\":\"" + commit + "\"}");
        fake.add("https://api.github.com/repos/acme/skills/commits/" + commit,
                "{\"sha\":\"" + commit + "\",\"commit\":{\"tree\":{\"sha\":\""
                        + rootTree + "\"}}}");
        fake.add("https://api.github.com/repos/acme/skills/zipball/" + commit, 413);
        fake.add("https://api.github.com/repos/acme/skills/git/trees/" + rootTree,
                "{\"tree\":[{\"path\":\"skills\",\"type\":\"tree\",\"sha\":\""
                        + skillsTree + "\"}]}");
        fake.add("https://api.github.com/repos/acme/skills/git/trees/" + skillsTree,
                "{\"tree\":[{\"path\":\"pdf\",\"type\":\"tree\",\"sha\":\""
                        + pdfTree + "\"}]}");
        fake.add("https://api.github.com/repos/acme/skills/git/trees/" + pdfTree + "?recursive=1",
                "{\"truncated\":false,\"tree\":["
                        + blob("SKILL.md", document) + ","
                        + blob("references.txt", support) + "]}");
        fake.add("https://raw.githubusercontent.com/acme/skills/" + commit
                + "/skills/pdf/SKILL.md", document);
        fake.add("https://raw.githubusercontent.com/acme/skills/" + commit
                + "/skills/pdf/references.txt", support);

        RemoteSkillFetchService.Result result = new RemoteSkillFetchService(fake)
                .fetchBlocking("https://github.com/acme/skills/tree/main/skills/pdf");
        assertThat(entries(result.archive())).containsEntry("SKILL.md", DOC)
                .containsEntry("references.txt", "keep me");
    }

    @Test
    void pinnedClawHubVersionIgnoresCurrentGithubInstallCommit() throws Exception {
        FakeFetcher fake = new FakeFetcher();
        String oldCommit = "b".repeat(40);
        String currentCommit = "c".repeat(40);
        fake.add("https://clawhub.ai/api/v1/skills/pdf?ownerHandle=alice",
                "{\"skill\":{\"slug\":\"pdf\",\"displayName\":\"PDF Guide\"},"
                        + "\"owner\":{\"handle\":\"alice\"}}");
        fake.add("https://clawhub.ai/api/v1/skills/pdf/install?ownerHandle=alice",
                "{\"ok\":true,\"slug\":\"pdf\",\"installKind\":\"github\","
                        + "\"github\":{\"repo\":\"acme/skills\",\"path\":\"skills/pdf\","
                        + "\"commit\":\"" + currentCommit + "\",\"contentHash\":\""
                        + "d".repeat(64) + "\"}}");
        fake.add("https://clawhub.ai/api/v1/skills/pdf/versions/1.2.3?ownerHandle=alice",
                "{\"skill\":{\"slug\":\"pdf\"},\"version\":{\"version\":\"1.2.3\","
                        + "\"files\":[{\"path\":\"SKILL.md\",\"size\":1,\"sha256\":\""
                        + "d".repeat(64) + "\"}]}}");
        byte[] repository = zip(Map.of("acme-skills-old/skills/pdf/SKILL.md", DOC));
        String contentHash = sha(("SKILL.md\0" + DOC.getBytes(StandardCharsets.UTF_8).length
                + "\0" + sha(DOC.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8));
        fake.add("https://clawhub.ai/api/v1/download?slug=pdf&ownerHandle=alice&version=1.2.3",
                "{\"sourceRef\":\"public-github\",\"repo\":\"acme/skills\","
                        + "\"path\":\"skills/pdf\",\"commit\":\"" + oldCommit + "\","
                        + "\"contentHash\":\"" + contentHash + "\"}");
        fake.add("https://api.github.com/repos/acme/skills/zipball/" + oldCommit, repository);

        RemoteSkillFetchService.Result result = new RemoteSkillFetchService(fake)
                .fetchBlocking("https://clawhub.ai/alice/skills/pdf/versions/1.2.3");
        assertThat(entries(result.archive())).containsEntry("SKILL.md", DOC);
        assertThat(result.metadataJson()).contains("\"version\":\"1.2.3\"")
                .contains("\"sourceRevision\":\"" + oldCommit + "\"");
        assertThat(fake.called).doesNotContain(
                URI.create("https://api.github.com/repos/acme/skills/zipball/" + currentCommit));
    }

    @Test
    void hostedClawHubArchiveIsReturnedByteForByte() throws Exception {
        FakeFetcher fake = new FakeFetcher();
        fake.add("https://clawhub.ai/api/v1/skills/pdf?ownerHandle=alice",
                "{\"skill\":{\"slug\":\"pdf\",\"displayName\":\"PDF Guide\"},"
                        + "\"owner\":{\"handle\":\"alice\"}}");
        fake.add("https://clawhub.ai/api/v1/skills/pdf/install?ownerHandle=alice",
                "{\"ok\":true,\"slug\":\"pdf\",\"installKind\":\"archive\","
                        + "\"archive\":{\"version\":\"1.2.3\"}}");
        fake.add("https://clawhub.ai/api/v1/skills/pdf/versions/1.2.3?ownerHandle=alice",
                "{\"skill\":{\"slug\":\"pdf\"},\"version\":{\"version\":\"1.2.3\","
                        + "\"files\":[{\"path\":\"SKILL.md\",\"size\":"
                        + DOC.getBytes(StandardCharsets.UTF_8).length + ",\"sha256\":\""
                        + sha(DOC.getBytes(StandardCharsets.UTF_8)) + "\"}]}}");
        byte[] source = zip(Map.of("pdf/SKILL.md", DOC, "pdf/_meta.json", HOSTED_META));
        fake.add("https://clawhub.ai/api/v1/download?slug=pdf&ownerHandle=alice&version=1.2.3",
                source);
        RemoteSkillFetchService.Result result = new RemoteSkillFetchService(fake)
                .fetchBlocking("https://clawhub.ai/alice/skills/pdf");
        assertThat(result.archive()).isEqualTo(source);
    }

    @Test
    void hostedManifestAcceptsRegeneratedTransportMetadataListedWithOldHash() throws Exception {
        String publishedMeta = HOSTED_META.replace("1778441820962", "177844182096");
        byte[] archive = zip(Map.of("pdf/SKILL.md", DOC,
                "pdf/references/usage.txt", "original content", "pdf/_meta.json", HOSTED_META));
        List<RemoteSkillFetchService.ManifestFile> manifest = List.of(
                manifestFile("SKILL.md", DOC),
                manifestFile("references/usage.txt", "original content"),
                manifestFile("_meta.json", publishedMeta));

        assertThat(HOSTED_META.getBytes(StandardCharsets.UTF_8).length)
                .isNotEqualTo(publishedMeta.getBytes(StandardCharsets.UTF_8).length);
        assertThatCode(() -> new RemoteSkillFetchService(new FakeFetcher())
                .verifyHostedManifest(archive, manifest, "pdf", "1.2.3"))
                .doesNotThrowAnyException();
    }

    @Test
    void hostedManifestAcceptsValidTransportMetadataAbsentFromManifest() throws Exception {
        byte[] archive = zip(Map.of("pdf/SKILL.md", DOC, "pdf/_meta.json", HOSTED_META));

        assertThatCode(() -> new RemoteSkillFetchService(new FakeFetcher())
                .verifyHostedManifest(archive, List.of(manifestFile("SKILL.md", DOC)),
                        "pdf", "1.2.3"))
                .doesNotThrowAnyException();
    }

    @Test
    void hostedManifestRejectsTransportMetadataWithWrongIdentity() throws Exception {
        List<RemoteSkillFetchService.ManifestFile> manifest = List.of(manifestFile("SKILL.md", DOC));
        for (String invalidMeta : List.of(
                HOSTED_META.replace("\"slug\":\"pdf\"", "\"slug\":\"other\""),
                HOSTED_META.replace("\"version\":\"1.2.3\"", "\"version\":\"1.2.4\""),
                HOSTED_META.replace("kn75s9ftw1pk74c5se78fq0r9182837z", " "),
                HOSTED_META.replace("1778441820962", "0"))) {
            byte[] archive = zip(Map.of("pdf/SKILL.md", DOC, "pdf/_meta.json", invalidMeta));
            assertThatThrownBy(() -> new RemoteSkillFetchService(new FakeFetcher())
                    .verifyHostedManifest(archive, manifest, "pdf", "1.2.3"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("clawhub_version_manifest_mismatch");
        }
    }

    @Test
    void hostedManifestStillRejectsChangedOrdinaryFile() throws Exception {
        byte[] archive = zip(Map.of("pdf/SKILL.md", DOC,
                "pdf/references/usage.txt", "modified content", "pdf/_meta.json", HOSTED_META));
        List<RemoteSkillFetchService.ManifestFile> manifest = List.of(
                manifestFile("SKILL.md", DOC),
                manifestFile("references/usage.txt", "original content"));

        assertThatThrownBy(() -> new RemoteSkillFetchService(new FakeFetcher())
                .verifyHostedManifest(archive, manifest, "pdf", "1.2.3"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("clawhub_version_manifest_mismatch");
    }

    @Test
    @EnabledIfSystemProperty(named = "remote.skill.smoke", matches = "true")
    void liveHostedClawHubSmoke() {
        RemoteSkillFetchService.Result result = new RemoteSkillFetchService()
                .fetchBlocking("https://clawhub.ai/steipete/skills/weather");
        assertThat(result.archive()).isNotEmpty();
        assertThat(result.metadataJson()).contains("\"sourceKind\":\"CLAWHUB\"")
                .contains("\"version\":");
    }

    @Test
    @EnabledIfSystemProperty(named = "remote.skill.smoke", matches = "true")
    void liveGitHubSubdirectorySmoke() {
        RemoteSkillFetchService.Result result = new RemoteSkillFetchService()
                .fetchBlocking("https://github.com/anthropics/skills/tree/main/skills/pdf");
        assertThat(result.archive()).isNotEmpty();
        assertThat(result.metadataJson()).contains("\"sourceKind\":\"GITHUB\"");
    }

    @Test
    @EnabledIfSystemProperty(named = "remote.skill.smoke", matches = "true")
    void liveClawHubGitHubHandoffSmoke() {
        RemoteSkillFetchService.Result result = new RemoteSkillFetchService()
                .fetchBlocking("https://clawhub.ai/nvidia/skills/aiq-deploy");
        assertThat(result.archive()).isNotEmpty();
        assertThat(result.metadataJson()).contains("\"sourceKind\":\"CLAWHUB\"")
                .contains("\"sourceRevision\":\"");
    }

    private static Map<String, String> entries(byte[] archive) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static RemoteSkillFetchService.ManifestFile manifestFile(String path, String contents)
            throws Exception {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        return new RemoteSkillFetchService.ManifestFile(path, bytes.length, sha(bytes));
    }

    private static String blob(String path, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return "{\"path\":\"" + path + "\",\"type\":\"blob\",\"mode\":\"100644\","
                + "\"size\":" + bytes.length + ",\"sha\":\""
                + HexFormat.of().formatHex(digest.digest(bytes)) + "\"}";
    }

    private static final class FakeFetcher implements RemoteSkillFetchService.Fetcher {
        final Map<URI, byte[]> requests = new HashMap<>();
        final Map<URI, Integer> statuses = new HashMap<>();
        final List<URI> called = new ArrayList<>();

        void add(String uri, String body) {
            add(uri, body.getBytes(StandardCharsets.UTF_8));
        }

        void add(String uri, byte[] body) {
            requests.put(URI.create(uri), body);
        }

        void add(String uri, int status) {
            statuses.put(URI.create(uri), status);
        }

        @Override
        public byte[] get(URI uri, int limit, Duration timeout) {
            called.add(uri);
            if (statuses.containsKey(uri)) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.valueOf(statuses.get(uri)));
            }
            byte[] body = requests.get(uri);
            if (body == null) throw new AssertionError("Unexpected remote request: " + uri);
            return body;
        }
    }
}
