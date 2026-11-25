package com.yuzhihuihe.apiplatform.gateway.safety;

import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.web.server.ResponseStatusException;

public final class PdfOfficeSafetyScanner {

    private static final long MAX_BYTES = 20_971_520L;
    private static final int MAX_PAGES = 200;
    private final FileContentValidator validator;

    public PdfOfficeSafetyScanner() {
        this(new FileContentValidator());
    }

    public PdfOfficeSafetyScanner(FileContentValidator validator) {
        this.validator = validator;
    }

    public FileSafetyResult scanPdf(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return FileSafetyResult.block("pdf_size_limit", bytes == null ? 0 : bytes.length);
        }
        if (!startsWith(bytes, new byte[] {'%', 'P', 'D', 'F', '-'})) {
            return FileSafetyResult.block("file_type_mismatch", bytes.length);
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || hasUnsafeCatalog(document.getDocumentCatalog())) {
                return FileSafetyResult.block("pdf_unsafe_object", bytes.length);
            }
            return document.getNumberOfPages() > MAX_PAGES
                    ? FileSafetyResult.block("pdf_page_limit", bytes.length)
                    : FileSafetyResult.allow(bytes.length);
        } catch (InvalidPasswordException encrypted) {
            return FileSafetyResult.block("pdf_unsafe_object", bytes.length);
        } catch (IOException | RuntimeException invalidPdf) {
            return FileSafetyResult.block("pdf_decode_failed", bytes.length);
        }
    }

    public FileSafetyResult scanOffice(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return FileSafetyResult.block("office_size_limit", bytes == null ? 0 : bytes.length);
        }
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (unsafeOfficeObject(bytes, normalized)) {
            return FileSafetyResult.block("office_unsafe_object", bytes.length);
        }
        try {
            validator.validateDocument(
                    normalized, officeMimeType(normalized), bytes, MAX_BYTES);
            return FileSafetyResult.allow(bytes.length);
        } catch (ResponseStatusException invalidOffice) {
            return FileSafetyResult.block("file_type_mismatch", bytes.length);
        }
    }

    private boolean hasUnsafeCatalog(PDDocumentCatalog catalog) throws IOException {
        if (catalog == null || catalog.getCOSObject().containsKey(COSName.OPEN_ACTION)
                || catalog.getCOSObject().containsKey(COSName.AA)
                || catalog.getCOSObject().containsKey(COSName.getPDFName("AF"))) {
            return true;
        }
        if (!catalog.getCOSObject().containsKey(COSName.NAMES)) {
            return false;
        }
        PDDocumentNameDictionary names = catalog.getNames();
        return names != null
                && (names.getEmbeddedFiles() != null || names.getJavaScript() != null);
    }

    private boolean unsafeOfficeObject(byte[] bytes, String filename) {
        if (filename.endsWith(".docx") || filename.endsWith(".xlsx")) {
            return unsafeOoxmlEntry(bytes);
        }
        if (!filename.endsWith(".doc") && !filename.endsWith(".xls")) {
            return false;
        }
        try (POIFSFileSystem filesystem =
                new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            return filesystem.getRoot().hasEntry("Macros")
                    || filesystem.getRoot().hasEntry("VBA")
                    || filesystem.getRoot().hasEntry("_VBA_PROJECT_CUR")
                    || filesystem.getRoot().hasEntry("ObjectPool");
        } catch (Exception invalidOle) {
            return false;
        }
    }

    private boolean unsafeOoxmlEntry(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin")
                        || name.startsWith("word/embeddings/")
                        || name.startsWith("xl/embeddings/")
                        || name.startsWith("xl/externallinks/")) {
                    return true;
                }
            }
            return false;
        } catch (IOException invalidZip) {
            return false;
        }
    }

    private String officeMimeType(String filename) {
        if (filename.endsWith(".doc")) return "application/msword";
        if (filename.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (filename.endsWith(".xls")) return "application/vnd.ms-excel";
        if (filename.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }
}
