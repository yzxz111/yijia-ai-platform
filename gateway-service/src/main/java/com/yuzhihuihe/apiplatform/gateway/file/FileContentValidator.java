package com.yuzhihuihe.apiplatform.gateway.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class FileContentValidator {

    private static final int MAX_OOXML_ENTRIES = 256;
    private static final long MAX_OOXML_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_OOXML_EXPANSION_RATIO = 20;
    private static final int ZIP_BUFFER_SIZE = 8192;
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Map<String, DocumentType> DOCUMENT_TYPES = Map.ofEntries(
            Map.entry(".pdf", new DocumentType(
                    Set.of("application/pdf"), FileContentValidator::isPdf)),
            Map.entry(".doc", new DocumentType(
                    Set.of("application/msword"), content -> hasOleStream(content, "WordDocument"))),
            Map.entry(".docx", new DocumentType(
                    Set.of(DOCX_MIME), content -> isOoxml(content, "word/document.xml"))),
            Map.entry(".xls", new DocumentType(
                    Set.of("application/vnd.ms-excel"),
                    content -> hasOleStream(content, "Workbook", "Book"))),
            Map.entry(".xlsx", new DocumentType(
                    Set.of(XLSX_MIME), content -> isOoxml(content, "xl/workbook.xml"))),
            Map.entry(".csv", new DocumentType(
                    Set.of("text/csv", "application/csv"), FileContentValidator::isUtf8Text)),
            Map.entry(".txt", new DocumentType(
                    Set.of("text/plain"), FileContentValidator::isUtf8Text)),
            Map.entry(".md", new DocumentType(
                    Set.of("text/markdown", "text/x-markdown"), FileContentValidator::isUtf8Text)),
            Map.entry(".markdown", new DocumentType(
                    Set.of("text/markdown", "text/x-markdown"), FileContentValidator::isUtf8Text)));
    private static final Map<String, ImageType> IMAGE_TYPES = Map.ofEntries(
            Map.entry(".png", new ImageType("image/png", FileContentValidator::isPng)),
            Map.entry(".jpg", new ImageType("image/jpeg", FileContentValidator::isJpeg)),
            Map.entry(".jpeg", new ImageType("image/jpeg", FileContentValidator::isJpeg)),
            Map.entry(".gif", new ImageType("image/gif", FileContentValidator::isGif)),
            Map.entry(".webp", new ImageType("image/webp", FileContentValidator::isWebp)));

    public ValidatedContent validateUpload(
            String filename, String declaredMimeType, byte[] content, long maxBytes) {
        return isDeclaredImage(filename, declaredMimeType)
                ? validateImage(filename, declaredMimeType, content, maxBytes)
                : validateDocument(filename, declaredMimeType, content, maxBytes);
    }

    public ValidatedContent validateDocument(
            String filename, String declaredMimeType, byte[] content, long maxBytes) {
        requireSize(content, maxBytes);
        String extension = extension(filename);
        DocumentType type = DOCUMENT_TYPES.get(extension);
        String mimeType = normalizeMimeType(declaredMimeType);
        if (type == null || !type.mimeTypes().contains(mimeType) || !type.signature().test(content)) {
            throw invalidContent();
        }
        return validated(mimeType, content);
    }

    public ValidatedContent validateImage(String declaredMimeType, byte[] content, long maxBytes) {
        requireSize(content, maxBytes);
        String mimeType = normalizeMimeType(declaredMimeType);
        ImageType type = IMAGE_TYPES.values().stream()
                .filter(candidate -> candidate.mimeType().equals(mimeType))
                .findFirst()
                .orElse(null);
        if (type == null || !type.signature().test(content)) {
            throw invalidContent();
        }
        return validated(mimeType, content);
    }

    public ValidatedContent validateImage(
            String filename, String declaredMimeType, byte[] content, long maxBytes) {
        requireSize(content, maxBytes);
        String mimeType = normalizeMimeType(declaredMimeType);
        ImageType type = IMAGE_TYPES.get(extension(filename));
        if (type == null || !type.mimeType().equals(mimeType) || !type.signature().test(content)) {
            throw invalidContent();
        }
        return validated(mimeType, content);
    }

    private static boolean isDeclaredImage(String filename, String declaredMimeType) {
        String mimeType = normalizeMimeType(declaredMimeType);
        return IMAGE_TYPES.containsKey(extension(filename))
                || IMAGE_TYPES.values().stream().anyMatch(type -> type.mimeType().equals(mimeType));
    }

    private static void requireSize(byte[] content, long maxBytes) {
        if (content == null || content.length == 0 || maxBytes <= 0) {
            throw invalidContent();
        }
        if (content.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large");
        }
    }

    private static String extension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String normalized = filename.strip().toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? "" : normalized.substring(dot);
    }

    private static String normalizeMimeType(String declaredMimeType) {
        if (declaredMimeType == null) {
            return "";
        }
        int parameters = declaredMimeType.indexOf(';');
        String value = parameters < 0 ? declaredMimeType : declaredMimeType.substring(0, parameters);
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean isPdf(byte[] content) {
        return startsWith(content, new byte[] {'%', 'P', 'D', 'F', '-'});
    }

    private static boolean hasOleStream(byte[] content, String... expectedStreams) {
        if (!startsWith(content, new byte[] {
                (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1})) {
            return false;
        }
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            for (String stream : expectedStreams) {
                if (filesystem.getRoot().hasEntry(stream)) {
                    return true;
                }
            }
            return false;
        } catch (Exception invalidOle) {
            return false;
        }
    }

    private static boolean isOoxml(byte[] content, String requiredDocumentEntry) {
        if (!startsWith(content, new byte[] {'P', 'K', 3, 4})) {
            return false;
        }
        boolean contentTypes = false;
        boolean document = false;
        int entryCount = 0;
        long expandedBytes = 0;
        long ratioLimit = content.length * MAX_OOXML_EXPANSION_RATIO;
        Set<String> entries = new HashSet<>();
        byte[] buffer = new byte[ZIP_BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_OOXML_ENTRIES) {
                    return false;
                }
                String entryName = entry.getName().replace('\\', '/');
                String lowerEntryName = entryName.toLowerCase(Locale.ROOT);
                if (!isSafeZipEntry(entryName) || !entries.add(entryName)
                        || lowerEntryName.endsWith("vbaproject.bin")
                        || lowerEntryName.startsWith("word/embeddings/")
                        || lowerEntryName.startsWith("xl/embeddings/")
                        || lowerEntryName.startsWith("xl/externallinks/")) {
                    return false;
                }
                contentTypes |= "[Content_Types].xml".equals(entryName);
                document |= requiredDocumentEntry.equals(entryName);
                ByteArrayOutputStream relationship = lowerEntryName.endsWith(".rels")
                        ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > MAX_OOXML_EXPANDED_BYTES || expandedBytes > ratioLimit) {
                        return false;
                    }
                    if (relationship != null) {
                        relationship.write(buffer, 0, read);
                    }
                }
                if (relationship != null && hasExternalRelationship(relationship.toByteArray())) {
                    return false;
                }
            }
            return contentTypes && document;
        } catch (Exception invalidZip) {
            return false;
        }
    }

    private static boolean isSafeZipEntry(String entryName) {
        return !entryName.isBlank()
                && !entryName.startsWith("/")
                && !entryName.equals("..")
                && !entryName.startsWith("../")
                && !entryName.contains("/../");
    }

    private static boolean hasExternalRelationship(byte[] content) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        try {
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT
                            || !"Relationship".equals(reader.getLocalName())) {
                        continue;
                    }
                    for (int index = 0; index < reader.getAttributeCount(); index++) {
                        if ("TargetMode".equals(reader.getAttributeLocalName(index))
                                && "External".equalsIgnoreCase(reader.getAttributeValue(index))) {
                            return true;
                        }
                    }
                }
                return false;
            } finally {
                reader.close();
            }
        } catch (Exception invalidRelationships) {
            return true;
        }
    }

    private static boolean isUtf8Text(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException invalidText) {
            return false;
        }
    }

    private static boolean isPng(byte[] content) {
        return startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
    }

    private static boolean isJpeg(byte[] content) {
        return content.length >= 4 && content[0] == (byte) 0xff && content[1] == (byte) 0xd8
                && content[content.length - 2] == (byte) 0xff && content[content.length - 1] == (byte) 0xd9;
    }

    private static boolean isGif(byte[] content) {
        return startsWith(content, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(content, "GIF89a".getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isWebp(byte[] content) {
        return content.length >= 12
                && startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static ValidatedContent validated(String mimeType, byte[] content) {
        try {
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            return new ValidatedContent(mimeType, sha256, content.length);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static ResponseStatusException invalidContent() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_file_content");
    }

    private record DocumentType(Set<String> mimeTypes, Predicate<byte[]> signature) { }

    private record ImageType(String mimeType, Predicate<byte[]> signature) { }

    public record ValidatedContent(String mimeType, String sha256, long sizeBytes) { }
}
