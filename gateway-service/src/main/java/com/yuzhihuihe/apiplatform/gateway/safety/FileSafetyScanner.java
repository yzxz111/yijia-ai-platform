package com.yuzhihuihe.apiplatform.gateway.safety;

import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class FileSafetyScanner {

    private final ImageSafetyScanner imageScanner;
    private final PdfOfficeSafetyScanner pdfOfficeScanner;
    private final ArchiveSafetyScanner archiveScanner;
    private final ClamAvClient clamAvClient;
    private final boolean clamavEnabled;

    public FileSafetyScanner() {
        this(new ImageSafetyScanner(), new PdfOfficeSafetyScanner(), new ArchiveSafetyScanner(), null, false);
    }

    @Autowired
    public FileSafetyScanner(ContentSafetyProperties properties,
            FileContentValidator validator) {
        this(new ImageSafetyScanner(), new PdfOfficeSafetyScanner(validator), new ArchiveSafetyScanner(),
                new ClamAvClient(properties.getFiles().getClamavHost(), properties.getFiles().getClamavPort(),
                        java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(10)),
                properties.getFiles().isClamavEnabled());
    }

    public FileSafetyScanner(
            ImageSafetyScanner imageScanner,
            PdfOfficeSafetyScanner pdfOfficeScanner,
            ArchiveSafetyScanner archiveScanner) {
        this(imageScanner, pdfOfficeScanner, archiveScanner, null, false);
    }

    public FileSafetyScanner(
            ImageSafetyScanner imageScanner,
            PdfOfficeSafetyScanner pdfOfficeScanner,
            ArchiveSafetyScanner archiveScanner,
            ClamAvClient clamAvClient,
            boolean clamavEnabled) {
        this.imageScanner = imageScanner;
        this.pdfOfficeScanner = pdfOfficeScanner;
        this.archiveScanner = archiveScanner;
        this.clamAvClient = clamAvClient;
        this.clamavEnabled = clamavEnabled;
    }

    public FileSafetyResult scan(byte[] bytes, String filename, String declaredMime) {
        if (bytes == null || bytes.length == 0 || filename == null || filename.isBlank()) {
            return FileSafetyResult.block("file_invalid", bytes == null ? 0 : bytes.length);
        }
        String name = filename.toLowerCase(Locale.ROOT);
        String mime = declaredMime == null ? "" : declaredMime.toLowerCase(Locale.ROOT);
        if (isImage(name, mime)) {
            if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.ISO_8859_1))) {
                return FileSafetyResult.block("file_type_mismatch", bytes.length);
            }
            return malwareGate(bytes, imageScanner.scan(bytes));
        }
        if (name.endsWith(".pdf") || mime.equals("application/pdf")) {
            return malwareGate(bytes, pdfOfficeScanner.scanPdf(bytes));
        }
        if (isOffice(name, mime)) {
            FileSafetyResult office = pdfOfficeScanner.scanOffice(bytes, name);
            if (office.result() != FileSafetyResult.Result.ALLOW) return office;
            boolean modernOffice = name.endsWith(".docx") || name.endsWith(".xlsx")
                    || mime.contains("openxml") || mime.equals("application/zip");
            return modernOffice ? malwareGate(bytes, archiveScanner.scan(bytes)) : malwareGate(bytes, office);
        }
        if (name.endsWith(".zip") || name.endsWith(".docx") || name.endsWith(".xlsx")
                || mime.equals("application/zip")) {
            return malwareGate(bytes, archiveScanner.scan(bytes));
        }
        if (isText(name, mime)) {
            String value = new String(bytes, StandardCharsets.UTF_8);
            FileSafetyResult textResult = new LocalTextSafetyScanner().scan(TextSafetyInput.of(
                    java.util.List.of(new TextSafetyInput.Field("file", value))), null).result()
                    == SafetyScanResult.Result.ALLOW
                    ? FileSafetyResult.allow(bytes.length)
                    : FileSafetyResult.block("unsafe_text_file", bytes.length);
            return malwareGate(bytes, textResult);
        }
        return FileSafetyResult.block("unsupported_file_type", bytes.length);
    }

    private FileSafetyResult malwareGate(byte[] bytes, FileSafetyResult structuralResult) {
        if (structuralResult.result() != FileSafetyResult.Result.ALLOW || !clamavEnabled) {
            return structuralResult;
        }
        if (clamAvClient == null) {
            return new FileSafetyResult(FileSafetyResult.Result.UNAVAILABLE, "content_safety_unavailable", bytes.length);
        }
        ClamAvScanResult malware = clamAvClient.scan(bytes).block();
        if (malware == null || malware.status() == ClamAvScanResult.Status.ERROR) {
            return new FileSafetyResult(FileSafetyResult.Result.UNAVAILABLE, "content_safety_unavailable", bytes.length);
        }
        return malware.status() == ClamAvScanResult.Status.INFECTED
                ? FileSafetyResult.block("unsafe_file", bytes.length)
                : structuralResult;
    }

    private boolean isImage(String name, String mime) {
        return mime.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif");
    }

    private boolean isOffice(String name, String mime) {
        return name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".xls")
                || name.endsWith(".xlsx") || mime.contains("word") || mime.contains("excel")
                || mime.contains("spreadsheet");
    }

    private boolean isText(String name, String mime) {
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")
                || mime.startsWith("text/");
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
