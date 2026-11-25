package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import reactor.core.publisher.Mono;

class FileSafetyScannerTest {

    @Test
    void orchestratesFileAllowAndPersistsOnlySafeMetadata() {
        Harness harness = harness(FileSafetyResult.allow(4));

        ContentSafetyOrchestrator.FileDecision decision = harness.orchestrator.inspectFile(
                harness.apiKey, new byte[] {1, 2, 3, 4}, "private-name.txt", "text/plain", "req-file-allow").block();

        assertThat(decision).isEqualTo(new ContentSafetyOrchestrator.FileDecision(true, false, "allowed"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> fileResult = ArgumentCaptor.forClass(java.util.Map.class);
        verify(harness.checks).record(eq("req-file-allow"), eq(harness.apiKey.tenantId()),
                eq(harness.apiKey.userId()), eq(harness.apiKey.apiKeyId()), eq("FILE"), eq("ALLOW"),
                eq(7L), anyMap(), anyMap(), fileResult.capture(), anyLong(), any());
        assertThat(fileResult.getValue()).containsEntry("reasonCode", "allowed")
                .containsEntry("findingCount", 0).containsEntry("inspectedBytes", 4L);
        assertThat(fileResult.getValue().toString()).doesNotContain("private-name", "quarantine/", "1, 2, 3, 4");
        verify(harness.outbox, never()).enqueue(any());
    }

    @Test
    void orchestratesFileBlockAndWritesRedactedOutboxEvent() {
        Harness harness = harness(FileSafetyResult.block("unsafe_file", 9));

        ContentSafetyOrchestrator.FileDecision decision = harness.orchestrator.inspectFile(
                harness.apiKey, new byte[9], "secret.pdf", "application/pdf", "req-file-block").block();

        assertThat(decision).isEqualTo(new ContentSafetyOrchestrator.FileDecision(false, false, "unsafe_file"));
        ArgumentCaptor<SafetyEventPayload> event = ArgumentCaptor.forClass(SafetyEventPayload.class);
        verify(harness.outbox).enqueue(event.capture());
        assertThat(event.getValue().direction()).isEqualTo("FILE");
        assertThat(event.getValue().policyVersion()).isEqualTo(7);
        assertThat(event.getValue().redactedExcerpt()).isNull();
        assertThat(event.getValue().toString()).doesNotContain("secret.pdf", "quarantine/");
    }

    @Test
    void orchestratesUnavailableFileScanAsRetryableFailClosed() {
        Harness harness = harness(new FileSafetyResult(
                FileSafetyResult.Result.UNAVAILABLE, "content_safety_unavailable", 12));

        ContentSafetyOrchestrator.FileDecision decision = harness.orchestrator.inspectFile(
                harness.apiKey, new byte[12], "scan.png", "image/png", "req-file-unavailable").block();

        assertThat(decision).isEqualTo(new ContentSafetyOrchestrator.FileDecision(
                false, true, "content_safety_unavailable"));
        verify(harness.checks).record(eq("req-file-unavailable"), eq(harness.apiKey.tenantId()),
                eq(harness.apiKey.userId()), eq(harness.apiKey.apiKeyId()), eq("FILE"), eq("UNAVAILABLE"),
                eq(7L), anyMap(), anyMap(), anyMap(), anyLong(), any());
    }

    @Test
    void rejectsArchivePathTraversal() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape.txt"));
            zip.write("unsafe".getBytes());
            zip.closeEntry();
        }

        FileSafetyResult result = new FileSafetyScanner().scan(bytes.toByteArray(), "archive.zip", "application/zip");

        assertThat(result.result()).isEqualTo(FileSafetyResult.Result.BLOCK);
        assertThat(result.reasonCode()).isEqualTo("archive_path_traversal");
    }

    @Test
    void rejectsDeclaredImageWhenMagicIsPdf() {
        FileSafetyResult result = new FileSafetyScanner().scan(
                "%PDF-1.7".getBytes(), "image.png", "image/png");

        assertThat(result.result()).isEqualTo(FileSafetyResult.Result.BLOCK);
        assertThat(result.reasonCode()).isEqualTo("file_type_mismatch");
    }

    @Test
    void parsesPdfStructureAndRejectsDocumentOpenActions() throws Exception {
        byte[] safe;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            safe = output.toByteArray();
        }
        assertThat(new PdfOfficeSafetyScanner().scanPdf(safe).result())
                .isEqualTo(FileSafetyResult.Result.ALLOW);

        byte[] unsafe;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.getDocumentCatalog().setOpenAction(
                    new PDActionJavaScript("app.alert('blocked')"));
            document.save(output);
            unsafe = output.toByteArray();
        }

        FileSafetyResult result = new PdfOfficeSafetyScanner().scanPdf(unsafe);

        assertThat(result.result()).isEqualTo(FileSafetyResult.Result.BLOCK);
        assertThat(result.reasonCode()).isEqualTo("pdf_unsafe_object");
    }

    @Test
    void rejectsZipThatIsNotAValidOfficeDocument() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plain.txt"));
            zip.write("not office".getBytes());
            zip.closeEntry();
        }

        FileSafetyResult result = new PdfOfficeSafetyScanner()
                .scanOffice(bytes.toByteArray(), "report.docx");

        assertThat(result.result()).isEqualTo(FileSafetyResult.Result.BLOCK);
        assertThat(result.reasonCode()).isEqualTo("file_type_mismatch");
    }

    private Harness harness(FileSafetyResult result) {
        FileSafetyScanner fileScanner = mock(FileSafetyScanner.class);
        when(fileScanner.scan(any(), any(), any())).thenReturn(result);
        LocalTextSafetyScanner local = mock(LocalTextSafetyScanner.class);
        SemanticSafetyProviderRegistry semantic = mock(SemanticSafetyProviderRegistry.class);
        PolicySnapshotClient policies = new PolicySnapshotClient("test-secret", true);
        policies.accept(PolicySnapshot.signed(7, List.of(), Instant.parse("2026-08-10T00:00:00Z"), "test-secret"));
        ContentSafetyProperties properties = new ContentSafetyProperties();
        ContentSafetyCheckService checks = mock(ContentSafetyCheckService.class);
        ContentSafetyEventOutboxService outbox = mock(ContentSafetyEventOutboxService.class);
        when(checks.record(any(), any(), any(), any(), any(), any(), anyLong(),
                anyMap(), anyMap(), anyMap(), anyLong(), any())).thenReturn(Mono.empty());
        when(outbox.enqueue(any())).thenReturn(Mono.empty());
        ContentSafetyOrchestrator orchestrator = new ContentSafetyOrchestrator(
                local, semantic, policies, properties, checks, outbox,
                new ContentSafetyMetrics(new SimpleMeterRegistry()), fileScanner);
        ApiKeyService.ApiKeyContext apiKey = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, Set.of());
        return new Harness(orchestrator, apiKey, checks, outbox);
    }

    private record Harness(
            ContentSafetyOrchestrator orchestrator,
            ApiKeyService.ApiKeyContext apiKey,
            ContentSafetyCheckService checks,
            ContentSafetyEventOutboxService outbox) { }
}
