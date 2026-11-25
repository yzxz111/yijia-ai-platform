package com.yuzhihuihe.apiplatform.gateway.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

class DocumentExtractionServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void exposesPositiveTypedDefaultBounds() {
        MultimodalProperties properties = new MultimodalProperties();

        assertThat(properties.getMaxAttachments()).isEqualTo(8);
        assertThat(properties.getMaxPdfPages()).isEqualTo(200);
        assertThat(properties.getMaxRenderedPdfPages()).isEqualTo(4);
        assertThat(properties.getMaxSheets()).isEqualTo(20);
        assertThat(properties.getMaxRowsPerSheet()).isEqualTo(10_000);
        assertThat(properties.getMaxCells()).isEqualTo(100_000);
        assertThat(properties.getMaxCharacters()).isEqualTo(200_000);
        assertThat(properties.getMaxConvertedOutput().toMegabytes()).isEqualTo(20);
        assertThat(properties.getPreprocessingTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.getExtractorVersion()).isEqualTo("multimodal-v1");
        assertThat(properties.getPolicyVersion()).isEqualTo("multimodal-v1");
        assertThatThrownBy(() -> properties.setMaxCells(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsTextMarkdownAndCsvWithSourceProvenance() {
        ExtractionCase text = extraction(
                "notes.md", "text/markdown", "# Title\nDetails".getBytes(StandardCharsets.UTF_8),
                new MultimodalProperties());
        ExtractionCase csv = extraction(
                "data.csv", "text/csv", "name,amount\nAda,12\n".getBytes(StandardCharsets.UTF_8),
                new MultimodalProperties());

        NormalizedAttachment textResult = text.service().extract(text.file()).block();
        NormalizedAttachment csvResult = csv.service().extract(csv.file()).block();

        assertThat(textResult.kind()).isEqualTo(NormalizedAttachment.Kind.TEXT);
        assertThat(textResult.normalizedText())
                .contains("[file=notes.md]")
                .contains("# Title")
                .contains("Details");
        assertThat(csvResult.normalizedText())
                .contains("[file=data.csv row=1]")
                .contains("name")
                .contains("[file=data.csv row=2]")
                .contains("Ada")
                .contains("12");
    }

    @Test
    void extractsDocxParagraphsAndTables() {
        byte[] docx = docx("Quarterly report", "North", "42");
        ExtractionCase extraction = extraction(
                "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx, new MultimodalProperties());

        NormalizedAttachment result = extraction.service().extract(extraction.file()).block();

        assertThat(result.normalizedText())
                .contains("[file=report.docx]")
                .contains("Quarterly report")
                .contains("North")
                .contains("42");
    }

    @Test
    void convertsLegacyDocAndReusesTheDocxExtractor() {
        byte[] legacyDoc = legacyOle("WordDocument");
        byte[] convertedDocx = docx("Converted report", "South", "17");
        LegacyDocumentConverterClient converter = mock(LegacyDocumentConverterClient.class);
        when(converter.convert("report.doc", "application/msword", legacyDoc))
                .thenReturn(Mono.just(new LegacyDocumentConverterClient.ConvertedDocument(
                        LegacyDocumentConverterClient.ConvertedFormat.DOCX,
                        convertedDocx)));
        ExtractionCase extraction = extraction(
                "report.doc", "application/msword", legacyDoc,
                new MultimodalProperties(), converter);

        NormalizedAttachment result = extraction.service().extract(extraction.file()).block();

        assertThat(result.normalizedText())
                .contains("[file=report.doc]")
                .contains("Converted report")
                .contains("South")
                .contains("17");
        verify(converter).convert("report.doc", "application/msword", legacyDoc);
    }

    @Test
    void convertsLegacyXlsAndReusesTheXlsxExtractor() {
        byte[] legacyXls = legacyOle("Workbook");
        byte[] convertedXlsx = xlsx(1, 1, 2, false);
        LegacyDocumentConverterClient converter = mock(LegacyDocumentConverterClient.class);
        when(converter.convert("budget.xls", "application/vnd.ms-excel", legacyXls))
                .thenReturn(Mono.just(new LegacyDocumentConverterClient.ConvertedDocument(
                        LegacyDocumentConverterClient.ConvertedFormat.XLSX,
                        convertedXlsx)));
        ExtractionCase extraction = extraction(
                "budget.xls", "application/vnd.ms-excel", legacyXls,
                new MultimodalProperties(), converter);

        NormalizedAttachment result = extraction.service().extract(extraction.file()).block();

        assertThat(result.normalizedText())
                .contains("[file=budget.xls sheet=Sheet1 row=1]")
                .contains("A1=value-1-1")
                .contains("B1=value-1-2");
        verify(converter).convert("budget.xls", "application/vnd.ms-excel", legacyXls);
    }

    @Test
    void rejectsInvalidConvertedOfficeContentAsConversionFailure() {
        byte[] legacyDoc = legacyOle("WordDocument");
        LegacyDocumentConverterClient converter = mock(LegacyDocumentConverterClient.class);
        when(converter.convert("invalid.doc", "application/msword", legacyDoc))
                .thenReturn(Mono.just(new LegacyDocumentConverterClient.ConvertedDocument(
                        LegacyDocumentConverterClient.ConvertedFormat.DOCX,
                        new byte[] {1, 2, 3})));
        ExtractionCase extraction = extraction(
                "invalid.doc", "application/msword", legacyDoc,
                new MultimodalProperties(), converter);

        assertError(
                () -> extraction.service().extract(extraction.file()).block(),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "document_conversion_failed");
    }

    @Test
    void extractsXlsxCellsWithSheetAndRowProvenanceWithoutEvaluatingFormulas() {
        byte[] xlsx = xlsx(1, 1, 3, true);
        ExtractionCase extraction = extraction(
                "budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx, new MultimodalProperties());

        NormalizedAttachment result = extraction.service().extract(extraction.file()).block();

        assertThat(result.normalizedText())
                .contains("[file=budget.xlsx sheet=Sheet1 row=1]")
                .contains("A1=value-1-1")
                .contains("C1==SUM(1,1)");
    }

    @Test
    void extractsTextPdfPagesAndRendersOnlyBoundedScannedPages() {
        byte[] textPdf = pdf(2, true, false);
        byte[] scannedPdf = pdf(2, false, false);
        ExtractionCase text = extraction(
                "report.pdf", "application/pdf", textPdf, new MultimodalProperties());
        MultimodalProperties scannedProperties = new MultimodalProperties();
        scannedProperties.setMaxRenderedPdfPages(1);
        ExtractionCase scanned = extraction(
                "scan.pdf", "application/pdf", scannedPdf, scannedProperties);

        NormalizedAttachment textResult = text.service().extract(text.file()).block();
        NormalizedAttachment scannedResult = scanned.service().extract(scanned.file()).block();

        assertThat(textResult.kind()).isEqualTo(NormalizedAttachment.Kind.TEXT);
        assertThat(textResult.normalizedText())
                .contains("[file=report.pdf page=1]")
                .contains("page 1")
                .contains("[file=report.pdf page=2]");
        assertThat(scannedResult.kind()).isEqualTo(NormalizedAttachment.Kind.IMAGE);
        assertThat(scannedResult.imageDataUris()).hasSize(1);
        assertThat(scannedResult.imageDataUris().getFirst()).startsWith("data:image/png;base64,");
    }

    @Test
    void rejectsScannedPdfWithOversizedEffectiveRenderDimensions() {
        byte[] oversizedPdf = oversizedScannedPdf();

        assertThat(oversizedPdf.length).isLessThan(2_000);
        assertComplexity(extraction(
                "oversized-scan.pdf", "application/pdf",
                oversizedPdf, new MultimodalProperties()));
    }

    @Test
    void rejectsPdfBeforePdfBoxLoadWhenDecodedByteBudgetIsExceeded() {
        byte[] content = pdf(1, false, false);
        MultimodalProperties properties = new MultimodalProperties();
        properties.setMaxPdfDecodedBytes(DataSize.ofBytes(content.length - 1L));

        assertComplexity(extraction(
                "decoded-budget.pdf", "application/pdf", content, properties));
    }

    @Test
    void enforcesDecodedPdfOutputBudgetAcrossRenderedPages() {
        byte[] content = pdf(1, false, false);
        MultimodalProperties properties = new MultimodalProperties();
        properties.setMaxPdfDecodedBytes(DataSize.ofBytes(content.length + 1L));

        assertComplexity(extraction(
                "decoded-output-budget.pdf", "application/pdf", content, properties));
    }

    @Test
    void stopsPdfParsingWhenConfiguredWorkBudgetIsExceeded() {
        MultimodalProperties properties = new MultimodalProperties();
        properties.setMaxPdfWorkUnits(1);

        assertComplexity(extraction(
                "work-budget.pdf", "application/pdf", pdf(2, true, false), properties));
    }

    @Test
    void rejectsFractionalEffectiveRenderDimensionAboveLimit() {
        byte[] oversizedPdf = scannedPdf(3_072.375f, 72.0f, 1.0f);

        assertComplexity(extraction(
                "fractional-oversized-scan.pdf", "application/pdf",
                oversizedPdf, new MultimodalProperties()));
    }

    @Test
    void rejectsNonFiniteAndNonPositiveRawPdfRenderGeometry() {
        assertRenderComplexity(renderPage(Float.NaN, 600.0f, 1.0f));
        assertRenderComplexity(renderPage(600.0f, 600.0f, Float.POSITIVE_INFINITY));
        PDPage zeroUserUnit = renderPage(600.0f, 600.0f, 1.0f);
        zeroUserUnit.getCOSObject().setFloat(COSName.USER_UNIT, 0.0f);
        assertRenderComplexity(zeroUserUnit);
        assertRenderComplexity(renderPage(Float.NEGATIVE_INFINITY, 600.0f, 1.0f));
        assertRenderComplexity(renderPage(0.0f, 600.0f, 1.0f));
    }

    @Test
    void enforcesExactPdfRenderPixelBoundaryWithoutOverflow() {
        assertThatCode(() -> invokePdfRenderBounds(renderPage(3_072.0f, 3_072.0f, 1.0f)))
                .doesNotThrowAnyException();
        assertRenderComplexity(renderPage(3_072.1875f, 3_072.1875f, 1.0f));
        assertRenderComplexity(renderPage(
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE));
    }

    @Test
    void mapsEncryptedPdfToStableError() {
        byte[] encryptedPdf = pdf(1, true, true);
        ExtractionCase extraction = extraction(
                "secret.pdf", "application/pdf", encryptedPdf, new MultimodalProperties());

        assertError(
                () -> extraction.service().extract(extraction.file()).block(),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "encrypted_file_not_supported");
    }

    @Test
    void enforcesPageSheetRowCellAndCharacterBounds() {
        MultimodalProperties pageLimit = new MultimodalProperties();
        pageLimit.setMaxPdfPages(1);
        assertComplexity(extraction(
                "pages.pdf", "application/pdf", pdf(2, true, false), pageLimit));

        MultimodalProperties sheetLimit = new MultimodalProperties();
        sheetLimit.setMaxSheets(1);
        assertComplexity(extraction(
                "sheets.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx(2, 1, 1, false), sheetLimit));

        MultimodalProperties rowLimit = new MultimodalProperties();
        rowLimit.setMaxRowsPerSheet(1);
        assertComplexity(extraction(
                "rows.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx(1, 2, 1, false), rowLimit));

        MultimodalProperties cellLimit = new MultimodalProperties();
        cellLimit.setMaxCells(1);
        assertComplexity(extraction(
                "cells.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx(1, 1, 2, false), cellLimit));

        MultimodalProperties characterLimit = new MultimodalProperties();
        characterLimit.setMaxCharacters(20);
        assertComplexity(extraction(
                "long.txt", "text/plain",
                "this content exceeds the configured character limit"
                        .getBytes(StandardCharsets.UTF_8), characterLimit));
    }

    @Test
    void usesOwnerScopedCacheAndSkipsObjectStorageOnHit() {
        byte[] content = "fresh".getBytes(StandardCharsets.UTF_8);
        FileObjectStore store = mock(FileObjectStore.class);
        AttachmentExtractionCache cache = mock(AttachmentExtractionCache.class);
        MultimodalProperties properties = new MultimodalProperties();
        FileObjectRepository.FileObject file = file("cached.txt", "text/plain", content);
        NormalizedAttachment cached = new NormalizedAttachment(
                file.externalFileId(), file.originalFilename(), NormalizedAttachment.Kind.TEXT,
                "[file=cached.txt]\ncached", java.util.List.of(), Map.of("cached", true));
        when(cache.find(file, properties.getExtractorVersion(), properties.getPolicyVersion()))
                .thenReturn(Mono.just(cached));
        DocumentExtractionService service = service(store, cache, properties);

        assertThat(service.extract(file).block()).isEqualTo(cached);

        verify(store, never()).get(any(), anyLong());
        verify(cache, never()).put(any(), any(), any(), any(), any());
    }

    @Test
    void cachesMissesOnlyUntilTheSourceLifetime() {
        byte[] content = "fresh".getBytes(StandardCharsets.UTF_8);
        ExtractionCase extraction = extraction(
                "fresh.txt", "text/plain", content, new MultimodalProperties());

        NormalizedAttachment result = extraction.service().extract(extraction.file()).block();

        assertThat(result.normalizedText()).contains("fresh");
        verify(extraction.cache()).put(
                eq(extraction.file()), eq(result),
                eq(extraction.properties().getExtractorVersion()),
                eq(extraction.properties().getPolicyVersion()),
                eq(extraction.properties().getCacheTtl()));
    }

    @Test
    void rejectsDeletedExpiredAndHashMismatchedSources() {
        byte[] original = "alpha".getBytes(StandardCharsets.UTF_8);
        ExtractionCase valid = extraction(
                "source.txt", "text/plain", original, new MultimodalProperties());
        FileObjectRepository.FileObject deleted = valid.file().withStatus("DELETED");
        FileObjectRepository.FileObject expired = new FileObjectRepository.FileObject(
                valid.file().id(), valid.file().externalFileId(), TENANT_ID, OWNER_ID,
                valid.file().objectKey(), valid.file().originalFilename(), valid.file().sha256(),
                valid.file().mimeType(), valid.file().sizeBytes(), "READY", NOW, null, NOW.minusSeconds(1));

        assertError(() -> valid.service().extract(deleted).block(), HttpStatus.NOT_FOUND, "file_not_found");
        assertError(() -> valid.service().extract(expired).block(), HttpStatus.NOT_FOUND, "file_not_found");

        when(valid.store().get(valid.file().objectKey(), valid.file().sizeBytes()))
                .thenReturn(Mono.just("bravo".getBytes(StandardCharsets.UTF_8)));
        assertError(
                () -> valid.service().extract(valid.file()).block(),
                HttpStatus.BAD_REQUEST,
                "invalid_file_content");
    }

    @Test
    void timesOutAndPropagatesCancellationToObjectStorage() {
        byte[] content = "wait".getBytes(StandardCharsets.UTF_8);
        FileObjectRepository.FileObject file = file("wait.txt", "text/plain", content);
        AttachmentExtractionCache cache = emptyCache(new MultimodalProperties(), file);
        FileObjectStore timeoutStore = mock(FileObjectStore.class);
        MultimodalProperties timeoutProperties = new MultimodalProperties();
        timeoutProperties.setPreprocessingTimeout(Duration.ofMillis(25));
        when(timeoutStore.get(file.objectKey(), file.sizeBytes())).thenReturn(Mono.never());
        DocumentExtractionService timeoutService = service(timeoutStore, cache, timeoutProperties);

        assertError(
                () -> timeoutService.extract(file).block(Duration.ofSeconds(1)),
                HttpStatus.GATEWAY_TIMEOUT,
                "multimodal_preprocessing_timeout");

        AtomicBoolean cancelled = new AtomicBoolean();
        FileObjectStore cancellableStore = mock(FileObjectStore.class);
        when(cancellableStore.get(file.objectKey(), file.sizeBytes()))
                .thenReturn(Mono.<byte[]>never().doOnCancel(() -> cancelled.set(true)));
        AttachmentExtractionCache cancellationCache = emptyCache(new MultimodalProperties(), file);
        Disposable subscription = service(
                cancellableStore, cancellationCache, new MultimodalProperties())
                .extract(file).subscribe();

        subscription.dispose();
        assertThat(cancelled).isTrue();
    }

    private static ExtractionCase extraction(
            String filename, String mimeType, byte[] content, MultimodalProperties properties) {
        return extraction(filename, mimeType, content, properties,
                mock(LegacyDocumentConverterClient.class));
    }

    private static ExtractionCase extraction(
            String filename,
            String mimeType,
            byte[] content,
            MultimodalProperties properties,
            LegacyDocumentConverterClient converter) {
        FileObjectStore store = mock(FileObjectStore.class);
        AttachmentExtractionCache cache = mock(AttachmentExtractionCache.class);
        FileObjectRepository.FileObject file = file(filename, mimeType, content);
        when(store.get(file.objectKey(), file.sizeBytes())).thenReturn(Mono.just(content));
        when(cache.find(file, properties.getExtractorVersion(), properties.getPolicyVersion()))
                .thenReturn(Mono.empty());
        when(cache.put(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        return new ExtractionCase(
                service(store, cache, properties, converter), file, store, cache, properties);
    }

    private static AttachmentExtractionCache emptyCache(
            MultimodalProperties properties, FileObjectRepository.FileObject file) {
        AttachmentExtractionCache cache = mock(AttachmentExtractionCache.class);
        when(cache.find(file, properties.getExtractorVersion(), properties.getPolicyVersion()))
                .thenReturn(Mono.empty());
        return cache;
    }

    private static DocumentExtractionService service(
            FileObjectStore store,
            AttachmentExtractionCache cache,
            MultimodalProperties properties) {
        return service(store, cache, properties, mock(LegacyDocumentConverterClient.class));
    }

    private static DocumentExtractionService service(
            FileObjectStore store,
            AttachmentExtractionCache cache,
            MultimodalProperties properties,
            LegacyDocumentConverterClient converter) {
        return new DocumentExtractionService(
                store, cache, new FileContentValidator(), converter, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FileObjectRepository.FileObject file(
            String filename, String mimeType, byte[] content) {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_" + UUID.randomUUID().toString().replace("-", ""),
                TENANT_ID, OWNER_ID, "files/" + UUID.randomUUID(), filename, sha256(content),
                mimeType, content.length, "READY", NOW.plus(Duration.ofHours(2)), null, NOW);
    }

    private static byte[] docx(String paragraph, String left, String right) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(paragraph);
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText(left);
            table.getRow(0).getCell(1).setText(right);
            document.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] legacyOle(String streamName) {
        try (POIFSFileSystem filesystem = new POIFSFileSystem();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filesystem.getRoot().createDocument(
                    streamName, new java.io.ByteArrayInputStream(new byte[] {1}));
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] xlsx(int sheets, int rows, int cells, boolean formula) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int sheetIndex = 0; sheetIndex < sheets; sheetIndex++) {
                var sheet = workbook.createSheet("Sheet" + (sheetIndex + 1));
                for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                    var row = sheet.createRow(rowIndex);
                    for (int cellIndex = 0; cellIndex < cells; cellIndex++) {
                        row.createCell(cellIndex).setCellValue(
                                "value-" + (rowIndex + 1) + "-" + (cellIndex + 1));
                    }
                    if (formula && cells >= 3) {
                        row.getCell(2).setCellFormula("SUM(1,1)");
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] pdf(int pages, boolean text, boolean encrypted) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(72, 700);
                        stream.showText("page " + (index + 1));
                        stream.endText();
                    }
                }
            }
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(
                        "owner-password", "user-password", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] oversizedScannedPdf() {
        return scannedPdf(600.0f, 600.0f, 6.0f);
    }

    private static byte[] scannedPdf(float width, float height, float userUnit) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(width, height));
            page.setUserUnit(userUnit);
            document.addPage(page);
            document.save(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static PDPage renderPage(float width, float height, float userUnit) {
        PDPage page = new PDPage(new PDRectangle(width, height));
        page.setUserUnit(userUnit);
        return page;
    }

    private static void assertRenderComplexity(PDPage page) {
        assertThatThrownBy(() -> invokePdfRenderBounds(page))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("file_complexity_limit");
                });
    }

    private static void invokePdfRenderBounds(PDPage page) throws Throwable {
        var method = DocumentExtractionService.class
                .getDeclaredMethod("validatePdfRenderBounds", PDPage.class);
        method.setAccessible(true);
        try {
            method.invoke(null, page);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void assertComplexity(ExtractionCase extraction) {
        assertError(
                () -> extraction.service().extract(extraction.file()).block(),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "file_complexity_limit");
    }

    private static void assertError(Runnable action, HttpStatus status, String reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(status);
                    assertThat(response.getReason()).isEqualTo(reason);
                });
    }

    private record ExtractionCase(
            DocumentExtractionService service,
            FileObjectRepository.FileObject file,
            FileObjectStore store,
            AttachmentExtractionCache cache,
            MultimodalProperties properties) { }
}
