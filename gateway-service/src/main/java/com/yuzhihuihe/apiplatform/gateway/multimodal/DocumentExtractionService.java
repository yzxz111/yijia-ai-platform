package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class DocumentExtractionService {

    private static final float PDF_RENDER_DPI = 96.0f;
    private static final int MAX_PDF_RENDER_DIMENSION = 4_096;
    private static final long MAX_PDF_RENDER_PIXELS = 16_777_216L;

    private final FileObjectStore objectStore;
    private final AttachmentExtractionCache cache;
    private final FileContentValidator validator;
    private final LegacyDocumentConverterClient converter;
    private final MultimodalProperties properties;
    private final Clock clock;
    private final Scheduler preprocessingScheduler;

    @Autowired
    public DocumentExtractionService(
            FileObjectStore objectStore,
            AttachmentExtractionCache cache,
            FileContentValidator validator,
            LegacyDocumentConverterClient converter,
            MultimodalProperties properties,
            @Qualifier("multimodalPreprocessingScheduler") Scheduler preprocessingScheduler) {
        this(objectStore, cache, validator, converter, properties, Clock.systemUTC(),
                preprocessingScheduler);
    }

    DocumentExtractionService(
            FileObjectStore objectStore,
            AttachmentExtractionCache cache,
            FileContentValidator validator,
            LegacyDocumentConverterClient converter,
            MultimodalProperties properties,
            Clock clock) {
        this(objectStore, cache, validator, converter, properties, clock, Schedulers.immediate());
    }

    DocumentExtractionService(
            FileObjectStore objectStore,
            AttachmentExtractionCache cache,
            FileContentValidator validator,
            LegacyDocumentConverterClient converter,
            MultimodalProperties properties,
            Clock clock,
            Scheduler preprocessingScheduler) {
        this.objectStore = objectStore;
        this.cache = cache;
        this.validator = validator;
        this.converter = converter;
        this.properties = properties;
        this.clock = clock;
        this.preprocessingScheduler = preprocessingScheduler;
    }

    public Mono<NormalizedAttachment> extract(FileObjectRepository.FileObject source) {
        if (!usable(source)) {
            return Mono.error(error(HttpStatus.NOT_FOUND, "file_not_found"));
        }
        String extractorVersion = properties.getExtractorVersion();
        String policyVersion = properties.getPolicyVersion();
        Mono<NormalizedAttachment> extraction = cache.find(source, extractorVersion, policyVersion)
                .switchIfEmpty(Mono.defer(() -> objectStore
                        .get(source.objectKey(), source.sizeBytes())
                        .switchIfEmpty(Mono.error(invalidContent()))
                        .flatMap(content -> parseVerified(source, content))
                        .flatMap(result -> cache.put(
                                        source, result, extractorVersion, policyVersion,
                                        properties.getCacheTtl())
                                .thenReturn(result))));
        return extraction.timeout(
                properties.getPreprocessingTimeout(),
                Mono.error(error(HttpStatus.GATEWAY_TIMEOUT, "multimodal_preprocessing_timeout")));
    }

    private boolean usable(FileObjectRepository.FileObject source) {
        return source != null
                && "READY".equals(source.status())
                && source.expiresAt() != null
                && source.expiresAt().isAfter(clock.instant())
                && source.deletedAt() == null;
    }

    private Mono<NormalizedAttachment> parseVerified(
            FileObjectRepository.FileObject source, byte[] content) {
        return Mono.fromCallable(() -> {
            verifyStoredContent(source, content);
            String sourceExtension = extension(source.originalFilename());
            if (".pdf".equals(sourceExtension)
                    && content.length > properties.getMaxPdfDecodedBytes().toBytes()) {
                throw complexity();
            }
            validator.validateUpload(
                    source.originalFilename(), source.mimeType(), content,
                    properties.getMaxConvertedOutput().toBytes());
            return sourceExtension;
        }).subscribeOn(preprocessingScheduler).flatMap(sourceExtension -> {
            if (".doc".equals(sourceExtension) || ".xls".equals(sourceExtension)) {
                return converter.convert(source.originalFilename(), source.mimeType(), content)
                        .flatMap(converted -> parseConverted(source, converted));
            }
            return parseNative(source, content, sourceExtension);
        });
    }

    private Mono<NormalizedAttachment> parseConverted(
            FileObjectRepository.FileObject source,
            LegacyDocumentConverterClient.ConvertedDocument converted) {
        return Mono.fromCallable(() -> {
            try {
                validator.validateDocument(
                        "converted" + converted.format().extension(),
                        converted.format().mimeType(),
                        converted.content(),
                        properties.getMaxConvertedOutput().toBytes());
                return converted.content();
            } catch (ResponseStatusException invalidOutput) {
                if (invalidOutput.getStatusCode().value() == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
                    throw invalidOutput;
                }
                throw conversionFailed();
            }
                }).subscribeOn(preprocessingScheduler)
                .flatMap(content -> parseNative(source, content, converted.format().extension()))
                .onErrorMap(
                        failure -> failure instanceof ResponseStatusException response
                                && "invalid_file_content".equals(response.getReason()),
                        failure -> conversionFailed());
    }

    private Mono<NormalizedAttachment> parseNative(
            FileObjectRepository.FileObject source,
            byte[] content,
            String sourceExtension) {
        AtomicReference<Thread> worker = new AtomicReference<>();
        return Mono.fromCallable(() -> {
                    worker.set(Thread.currentThread());
                    try {
                        return parseNativeBlocking(source, content, sourceExtension);
                    } finally {
                        worker.compareAndSet(Thread.currentThread(), null);
                    }
                })
                .subscribeOn(preprocessingScheduler)
                .doOnCancel(() -> interrupt(worker));
    }

    private static void interrupt(AtomicReference<Thread> worker) {
        Thread running = worker.get();
        if (running != null) {
            running.interrupt();
        }
    }

    private NormalizedAttachment parseNativeBlocking(
            FileObjectRepository.FileObject source,
            byte[] content,
            String sourceExtension) {
        try {
            return switch (sourceExtension) {
                case ".txt", ".md", ".markdown" -> extractText(source, content);
                case ".csv" -> extractCsv(source, content);
                case ".docx" -> extractDocx(source, content);
                case ".xlsx" -> extractXlsx(source, content);
                case ".pdf" -> extractPdf(source, content);
                case ".png", ".jpg", ".jpeg", ".gif", ".webp" -> extractImage(source, content);
                default -> throw invalidContent();
            };
        } catch (PdfCancellationException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        } catch (InvalidPasswordException encrypted) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "encrypted_file_not_supported");
        } catch (ResponseStatusException stableError) {
            throw stableError;
        } catch (Exception invalidDocument) {
            throw invalidContent();
        }
    }

    private void verifyStoredContent(FileObjectRepository.FileObject source, byte[] content) {
        if (content == null || content.length != source.sizeBytes()) {
            throw invalidContent();
        }
        try {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            if (source.sha256() == null || !actual.equalsIgnoreCase(source.sha256())) {
                throw invalidContent();
            }
        } catch (ResponseStatusException invalid) {
            throw invalid;
        } catch (Exception impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private NormalizedAttachment extractText(
            FileObjectRepository.FileObject source, byte[] content) {
        TextCollector collector = collector();
        collector.append(label(source.originalFilename()), new String(content, StandardCharsets.UTF_8));
        return textResult(source, collector, Map.of("format", extension(source.originalFilename())));
    }

    private NormalizedAttachment extractCsv(
            FileObjectRepository.FileObject source, byte[] content) throws Exception {
        TextCollector collector = collector();
        int rows = 0;
        int cells = 0;
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                if (++rows > properties.getMaxRowsPerSheet()) {
                    throw complexity();
                }
                cells += record.size();
                if (cells > properties.getMaxCells()) {
                    throw complexity();
                }
                List<String> values = new ArrayList<>(record.size());
                record.forEach(values::add);
                collector.append(
                        rowLabel(source.originalFilename(), rows),
                        String.join(",", values));
            }
        }
        return textResult(source, collector, Map.of("format", ".csv", "rows", rows, "cells", cells));
    }

    private NormalizedAttachment extractDocx(
            FileObjectRepository.FileObject source, byte[] content) throws Exception {
        TextCollector collector = collector();
        int rows = 0;
        int cells = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    collector.append(label(source.originalFilename()), paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        if (++rows > properties.getMaxRowsPerSheet()) {
                            throw complexity();
                        }
                        List<String> values = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (++cells > properties.getMaxCells()) {
                                throw complexity();
                            }
                            values.add(cell.getText());
                        }
                        collector.append(
                                rowLabel(source.originalFilename(), rows),
                                String.join(" | ", values));
                    }
                }
            }
        }
        return textResult(source, collector, Map.of("format", ".docx", "rows", rows, "cells", cells));
    }

    private NormalizedAttachment extractXlsx(
            FileObjectRepository.FileObject source, byte[] content) throws Exception {
        TextCollector collector = collector();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int totalRows = 0;
        int totalCells = 0;
        int sheetCount;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            sheetCount = workbook.getNumberOfSheets();
            if (sheetCount > properties.getMaxSheets()) {
                throw complexity();
            }
            for (Sheet sheet : workbook) {
                int sheetRows = 0;
                for (Row row : sheet) {
                    if (++sheetRows > properties.getMaxRowsPerSheet()) {
                        throw complexity();
                    }
                    totalRows++;
                    List<String> values = new ArrayList<>();
                    for (Cell cell : row) {
                        if (++totalCells > properties.getMaxCells()) {
                            throw complexity();
                        }
                        values.add(cell.getAddress().formatAsString() + "="
                                + cellValue(cell, formatter));
                    }
                    collector.append(
                            "[file=" + safe(source.originalFilename())
                                    + " sheet=" + safe(sheet.getSheetName())
                                    + " row=" + (row.getRowNum() + 1) + "]",
                            String.join(" | ", values));
                }
            }
        }
        return textResult(source, collector, Map.of(
                "format", ".xlsx", "sheets", sheetCount,
                "rows", totalRows, "cells", totalCells));
    }

    private String cellValue(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() == CellType.FORMULA) {
            return "=" + cell.getCellFormula();
        }
        return formatter.formatCellValue(cell);
    }

    private NormalizedAttachment extractPdf(
            FileObjectRepository.FileObject source, byte[] content) throws Exception {
        if (content.length > properties.getMaxPdfDecodedBytes().toBytes()) {
            throw complexity();
        }
        PdfWorkBudget work = new PdfWorkBudget(properties.getMaxPdfWorkUnits());
        work.checkCancelled();
        long decodedBytes = content.length;
        try (PDDocument document = Loader.loadPDF(content)) {
            work.checkCancelled();
            int pages = document.getNumberOfPages();
            if (pages > properties.getMaxPdfPages()) {
                throw complexity();
            }
            TextCollector collector = collector();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                work.consume();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedTextWriter pageText = new BoundedTextWriter(properties.getMaxCharacters(), work);
                stripper.writeText(document, pageText);
                String text = pageText.toString();
                decodedBytes = addDecodedBytes(
                        decodedBytes, text.getBytes(StandardCharsets.UTF_8).length,
                        properties.getMaxPdfDecodedBytes().toBytes());
                collector.append(
                        "[file=" + safe(source.originalFilename()) + " page=" + page + "]",
                        text);
            }
            if (!collector.isEmpty()) {
                return textResult(source, collector, Map.of("format", ".pdf", "pages", pages));
            }

            int renderCount = Math.min(pages, properties.getMaxRenderedPdfPages());
            long renderedBytes = 0;
            List<String> images = new ArrayList<>(renderCount);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < renderCount; page++) {
                work.consume();
                work.checkCancelled();
                validatePdfRenderBounds(document.getPage(page));
                BufferedImage image = renderer.renderImageWithDPI(page, PDF_RENDER_DPI, ImageType.RGB);
                work.checkCancelled();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", output)) {
                    throw invalidContent();
                }
                byte[] png = output.toByteArray();
                decodedBytes = addDecodedBytes(
                        decodedBytes, png.length, properties.getMaxPdfDecodedBytes().toBytes());
                renderedBytes += png.length;
                if (renderedBytes > properties.getMaxConvertedOutput().toBytes()) {
                    throw complexity();
                }
                images.add("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            }
            return new NormalizedAttachment(
                    source.externalFileId(), source.originalFilename(),
                    NormalizedAttachment.Kind.IMAGE, "", images,
                    Map.of("format", ".pdf", "pages", pages, "renderedPages", renderCount));
        }
    }

    private static void validatePdfRenderBounds(PDPage page) {
        double renderScale = PDF_RENDER_DPI / 72.0d;
        double widthPoints = page.getCropBox().getWidth();
        double heightPoints = page.getCropBox().getHeight();
        double userUnit = page.getCOSObject().getFloat(COSName.USER_UNIT, 1.0f);
        if (!Double.isFinite(widthPoints) || widthPoints <= 0.0d
                || !Double.isFinite(heightPoints) || heightPoints <= 0.0d
                || !Double.isFinite(userUnit) || userUnit <= 0.0d) {
            throw complexity();
        }
        double effectiveWidthPixels = widthPoints * userUnit * renderScale;
        double effectiveHeightPixels = heightPoints * userUnit * renderScale;
        if (!Double.isFinite(effectiveWidthPixels) || !Double.isFinite(effectiveHeightPixels)
                || effectiveWidthPixels <= 0.0d || effectiveHeightPixels <= 0.0d
                || effectiveWidthPixels > MAX_PDF_RENDER_DIMENSION
                || effectiveHeightPixels > MAX_PDF_RENDER_DIMENSION) {
            throw complexity();
        }
        if (effectiveWidthPixels > MAX_PDF_RENDER_PIXELS / effectiveHeightPixels) {
            throw complexity();
        }
    }

    private NormalizedAttachment extractImage(
            FileObjectRepository.FileObject source, byte[] content) {
        String dataUri = "data:" + source.mimeType() + ";base64,"
                + Base64.getEncoder().encodeToString(content);
        return new NormalizedAttachment(
                source.externalFileId(), source.originalFilename(),
                NormalizedAttachment.Kind.IMAGE, "", List.of(dataUri),
                Map.of("format", extension(source.originalFilename())));
    }

    private NormalizedAttachment textResult(
            FileObjectRepository.FileObject source,
            TextCollector collector,
            Map<String, Object> metadata) {
        return new NormalizedAttachment(
                source.externalFileId(), source.originalFilename(),
                NormalizedAttachment.Kind.TEXT, collector.value(), List.of(), metadata);
    }

    private TextCollector collector() {
        return new TextCollector(properties.getMaxCharacters());
    }

    private static String label(String filename) {
        return "[file=" + safe(filename) + "]";
    }

    private static String rowLabel(String filename, int row) {
        return "[file=" + safe(filename) + " row=" + row + "]";
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace(']', '_').strip();
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.strip().toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? "" : normalized.substring(dot);
    }

    private static ResponseStatusException complexity() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "file_complexity_limit");
    }

    private static ResponseStatusException invalidContent() {
        return error(HttpStatus.BAD_REQUEST, "invalid_file_content");
    }

    private static ResponseStatusException conversionFailed() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "document_conversion_failed");
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private static long addDecodedBytes(long current, long addition, long maximum) {
        if (addition < 0 || current > maximum - addition) {
            throw complexity();
        }
        return current + addition;
    }

    private static final class PdfWorkBudget {

        private final int maxUnits;
        private int units;

        private PdfWorkBudget(int maxUnits) {
            this.maxUnits = maxUnits;
        }

        private void consume() {
            checkCancelled();
            if (++units > maxUnits) {
                throw complexity();
            }
        }

        private void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) {
                throw new PdfCancellationException();
            }
        }
    }

    private static final class PdfCancellationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BoundedTextWriter extends java.io.Writer {

        private final int maxCharacters;
        private final PdfWorkBudget work;
        private final StringBuilder value = new StringBuilder();

        private BoundedTextWriter(int maxCharacters, PdfWorkBudget work) {
            this.maxCharacters = maxCharacters;
            this.work = work;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            work.checkCancelled();
            if (length < 0 || offset < 0 || offset > characters.length - length
                    || (long) value.length() + length > maxCharacters) {
                throw complexity();
            }
            value.append(characters, offset, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private static final class TextCollector {

        private final int maxCharacters;
        private final StringBuilder value = new StringBuilder();

        private TextCollector(int maxCharacters) {
            this.maxCharacters = maxCharacters;
        }

        private void append(String provenance, String text) {
            String normalized = text == null ? "" : text.replace("\r\n", "\n")
                    .replace('\r', '\n').strip();
            if (normalized.isBlank()) {
                return;
            }
            String block = provenance + "\n" + normalized;
            int separator = value.isEmpty() ? 0 : 2;
            if ((long) value.length() + separator + block.length() > maxCharacters) {
                throw complexity();
            }
            if (separator > 0) {
                value.append("\n\n");
            }
            value.append(block);
        }

        private boolean isEmpty() {
            return value.isEmpty();
        }

        private String value() {
            return value.toString();
        }
    }
}
