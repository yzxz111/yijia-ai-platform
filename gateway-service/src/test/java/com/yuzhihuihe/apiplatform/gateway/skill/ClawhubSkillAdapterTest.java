package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClawhubSkillAdapterTest {

    private static final Path ACCEPTANCE_SAMPLE = Path.of(System.getProperty(
            "clawhub.sample.path",
            "D:/wechat/xwechat_files/wxid_aofvccp8m57s12_7072/msg/file/2026-09/self-improving.zip"));

    @TempDir Path directory;

    private final ClawhubSkillAdapter adapter = new ClawhubSkillAdapter();

    @Test
    void inspectsOptionalFrontmatterAndEndOfFileClosingDelimiterWithoutConverting() throws Exception {
        Map<String, String> files = Map.of(
                "skill.md", "---\nname: demo\nslug: demo\nversion: 1.2.3\n---",
                "references/guide.md", "Keep the source archive byte-for-byte.\n");

        ClawhubSkillAdapter.Report report = adapter.inspect(zip(files), "publisher", "demo", null);

        assertThat(report.sourceFormat()).isEqualTo("CLAWHUB_SKILL");
        assertThat(report.status()).isEqualTo("IMPORTABLE");
        assertThat(report.version()).isEqualTo("1.2.3");
        assertThat(report.sourceSha256()).matches("[0-9a-f]{64}");
        assertThat(report.code()).startsWith("publisher-demo-");
    }

    @Test
    void usesClawhubDisplayNameWhileKeepingTheOwnerAndSlugCode() throws Exception {
        ClawhubSkillAdapter.Report report = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: self-improving\nversion: 1.2.16\n---\n"
                        + "# Self-Improving + Proactive Agent\n")),
                "ivangdavila", "self-improving", null, "Self-Improving + Proactive Agent");

        assertThat(report.name()).isEqualTo("Self-Improving + Proactive Agent");
        assertThat(report.code()).isEqualTo(
                ClawhubSkillAdapter.nativeCode("ivangdavila", "self-improving"));
        assertThat(report.status()).isEqualTo("IMPORTABLE");
    }

    @Test
    void keepsImportedDescriptionsWithinTheConsoleSkillLimit() throws Exception {
        String description = "a".repeat(1000);
        ClawhubSkillAdapter.Report accepted = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: demo\nversion: 1.0.0\ndescription: "
                        + description + "\n---\n")),
                "publisher", "demo", null);
        assertThat(accepted.description()).isEqualTo(description);

        ClawhubSkillAdapter.Report tooLong = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: demo\nversion: 1.0.0\ndescription: "
                        + "a".repeat(1001) + "\n---\n")),
                "publisher", "demo", null);
        assertThat(tooLong.description()).isNull();
        assertThat(tooLong.decisions()).anyMatch(value -> value.contains("console detail limit"));
    }

    @Test
    void fallsBackFromTechnicalFrontmatterNameToAuthoredTitle() throws Exception {
        ClawhubSkillAdapter.Report report = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: self-improving\n---\n"
                        + "# Self-Improving + Proactive Agent\n")),
                "ivangdavila", "self-improving", null);

        assertThat(report.name()).isEqualTo("Self-Improving + Proactive Agent");
        assertThat(report.code()).isEqualTo(
                ClawhubSkillAdapter.nativeCode("ivangdavila", "self-improving"));
    }

    @Test
    void usesExplicitFrontmatterDisplayNameAndReadableSlugFallback() throws Exception {
        ClawhubSkillAdapter.Report declared = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: skill-id\ndisplayName: Human Friendly Name\n---\n")),
                "publisher", "skill-id", null);
        ClawhubSkillAdapter.Report fallback = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: skill-id\n---\n")),
                "publisher", "skill-id", null);

        assertThat(declared.name()).isEqualTo("Human Friendly Name");
        assertThat(fallback.name()).isEqualTo("Skill Id");
    }

    @Test
    void rejectsInvalidRequestedDisplayName() throws Exception {
        ClawhubSkillAdapter.Report report = adapter.inspect(
                zip(Map.of("SKILL.md", "# Demo\n")),
                "publisher", "demo", null, "bad\u0007name");

        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.blockingReasons()).anyMatch(value -> value.contains("display name"));
    }

    @Test
    void acceptsMissingFrontmatterAndVersionAsDraftReview() throws Exception {
        ClawhubSkillAdapter.Report report = adapter.inspect(
                zip(Map.of("skills.md", "# Instructions\nDo the useful thing.\n")),
                "publisher", "unversioned", null);

        assertThat(report.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.version()).isNull();
        assertThat(report.owner()).isEqualTo("publisher");
        assertThat(report.slug()).isEqualTo("unversioned");
        assertThat(report.name()).isEqualTo("Instructions");
        assertThat(report.blockingReasons()).isEmpty();
        assertThat(report.decisions()).anyMatch(value -> value.contains("version is missing"));
    }

    @Test
    void invalidDeclaredVersionIsStoredAsMissingSoAnAdminCanCompleteItLater() throws Exception {
        ClawhubSkillAdapter.Report report = adapter.inspect(
                zip(Map.of("SKILL.md", "---\nname: demo\nversion: latest\n---\n# Instructions\n")),
                "publisher", "demo", null);

        assertThat(report.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.version()).isNull();
        assertThat(report.decisions()).anyMatch(value -> value.contains("version is invalid"));
    }

    @Test
    void retainsOpenClawMetadataAndExecutableFilesAsReviewRequired() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: Example\nversion: 1.0.0\n"
                + "metadata:\n  clawdbot:\n    os: [linux]\n---\n# Example\n");
        files.put("scripts/check.sh", "#!/bin/sh\necho static-only\n");

        ClawhubSkillAdapter.Report report = adapter.inspect(
                zip(files), "publisher", "example", null);

        assertThat(report.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.decisions()).anyMatch(value -> value.contains("metadata"));
        assertThat(report.decisions()).anyMatch(value -> value.contains("Executable support files"));
    }

    @Test
    void distinctPublishersGetDistinctStableCodesForTheSameSlug() {
        String first = ClawhubSkillAdapter.nativeCode("publisher-one", "shared-skill");
        String second = ClawhubSkillAdapter.nativeCode("publisher-two", "shared-skill");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("[a-z0-9-]{1,64}");
        assertThat(first).isEqualTo(ClawhubSkillAdapter.nativeCode("publisher-one", "shared-skill"));
    }

    @Test
    void blocksMultipleSkillDocumentsSoNestedSkillRootsCannotHide() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "# Root\n");
        files.put("examples/nested/SKILL.md", "# Nested\n");

        ClawhubSkillAdapter.Report report = adapter.inspect(zip(files), "publisher", "demo", null);

        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.blockingReasons()).anyMatch(value -> value.contains("more than one Skill document"));
    }

    @Test
    void blocksZipSlipPathsAndWindowsUnsafeFilenames() throws Exception {
        ClawhubSkillAdapter.Report traversal = adapter.inspect(
                zip(Map.of("../outside.txt", "no", "SKILL.md", "# Safe\n")),
                "publisher", "demo", null);
        ClawhubSkillAdapter.Report windowsPath = adapter.inspect(
                zip(Map.of("SKILL.md", "# Safe\n", "references/bad?.txt", "no")),
                "publisher", "demo", null);

        assertThat(traversal.status()).isEqualTo("BLOCKED");
        assertThat(windowsPath.status()).isEqualTo("BLOCKED");
    }

    @Test
    void blocksCaseAndUnicodeNormalizationPathCollisions() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "# Safe\n");
        files.put("references/Caf\u00e9.md", "one\n");
        files.put("references/Cafe\u0301.md", "two\n");

        ClawhubSkillAdapter.Report report = adapter.inspect(zip(files), "publisher", "demo", null);

        assertThat(report.status()).isEqualTo("BLOCKED");
    }

    @Test
    void blocksHighCompressionRatioAndUnixSymlinkEntries() throws Exception {
        Map<String, String> bomb = new LinkedHashMap<>();
        bomb.put("SKILL.md", "# Safe\n");
        bomb.put("references/repetitive.txt", "x".repeat(64 * 1024));
        ClawhubSkillAdapter.Report compressed = adapter.inspect(zip(bomb), "publisher", "demo", null);

        Path symlinkArchive = zip(Map.of("SKILL.md", "# Safe\n", "alias", "target"));
        markUnixSymlink(symlinkArchive, "alias");
        ClawhubSkillAdapter.Report symlink = adapter.inspect(symlinkArchive, "publisher", "demo", null);

        assertThat(compressed.status()).isEqualTo("BLOCKED");
        assertThat(symlink.status()).isEqualTo("BLOCKED");
    }

    @Test
    void blocksSourceArchiveOverTheFiftyMebibyteLimit() throws Exception {
        Path oversized = Files.createTempFile(directory, "oversized-clawhub-", ".zip");
        try (RandomAccessFile file = new RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(ClawhubSkillAdapter.MAX_SOURCE_ZIP_BYTES + 1);
        }

        ClawhubSkillAdapter.Report report = adapter.inspect(oversized, "publisher", "demo", null);

        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.blockingReasons()).anyMatch(value -> value.contains("50 MiB"));
    }

    @Test
    void blocksInvalidUtf8SkillDocuments() throws Exception {
        Path archive = zipBytes(Map.of(
                "SKILL.md", new byte[] {'#', ' ', (byte) 0xC3, (byte) 0x28}));

        ClawhubSkillAdapter.Report report = adapter.inspect(archive, "publisher", "demo", null);

        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.blockingReasons()).contains("Skill document is not valid UTF-8");
    }

    @Test
    void inspectsTheSelfImprovingAcceptanceArchiveAsReviewableNativeSource() throws Exception {
        assumeTrue(Files.isRegularFile(ACCEPTANCE_SAMPLE), "local acceptance archive not present");

        ClawhubSkillAdapter.Report report = adapter.inspect(ACCEPTANCE_SAMPLE, null, null, null);

        assertThat(report.sourceFormat()).isEqualTo("CLAWHUB_SKILL");
        assertThat(report.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.owner()).isEqualTo("ivangdavila");
        assertThat(report.slug()).isEqualTo("self-improving");
        assertThat(report.version()).isEqualTo("1.2.16");
        assertThat(report.blockingReasons()).isEmpty();
    }

    private Path zip(Map<String, String> files) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((name, content) -> bytes.put(name, content.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    private Path zipBytes(Map<String, byte[]> files) throws IOException {
        Path source = Files.createTempFile(directory, "clawhub-", ".zip");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                archive.putNextEntry(new ZipEntry(file.getKey()));
                archive.write(file.getValue());
                archive.closeEntry();
            }
        }
        return source;
    }

    private static void markUnixSymlink(Path archive, String entryName) throws IOException {
        byte[] zip = Files.readAllBytes(archive);
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index <= zip.length - 46; index++) {
            if (u32(zip, index) != 0x02014b50L) continue;
            int nameLength = u16(zip, index + 28);
            if (nameLength != name.length || index + 46 + nameLength > zip.length) continue;
            boolean matches = true;
            for (int offset = 0; offset < name.length; offset++) {
                matches &= zip[index + 46 + offset] == name[offset];
            }
            if (!matches) continue;
            // Creator OS = Unix; high 16 bits carry the POSIX file mode (S_IFLNK | 0777).
            zip[index + 4] = 20;
            zip[index + 5] = 3;
            long externalAttributes = 0xA1FF0000L;
            for (int offset = 0; offset < 4; offset++) {
                zip[index + 38 + offset] = (byte) (externalAttributes >>> (8 * offset));
            }
            Files.write(archive, zip);
            return;
        }
        throw new IOException("ZIP central directory entry not found: " + entryName);
    }

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}
