package com.yuzhihuihe.apiplatform.gateway.safety;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ArchiveSafetyScanner {

    private static final long MAX_EXPANDED_BYTES = 20_971_520L;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_DEPTH = 3;
    private static final long MAX_COMPRESSION_RATIO = 1000L;

    public FileSafetyResult scan(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return FileSafetyResult.block("archive_empty", 0);
        }
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ENTRIES) {
                    return FileSafetyResult.block("archive_entry_limit", bytes.length);
                }
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.startsWith("/") || name.matches("^[A-Za-z]:/.*")
                        || name.contains("../") || name.equals("..") || name.startsWith("../")) {
                    return FileSafetyResult.block("archive_path_traversal", bytes.length);
                }
                int depth = name.isBlank() ? 0 : name.split("/").length - 1;
                if (depth > MAX_DEPTH) {
                    return FileSafetyResult.block("archive_depth_limit", bytes.length);
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    expanded += read;
                    if (entryBytes > MAX_EXPANDED_BYTES || expanded > MAX_EXPANDED_BYTES) {
                        return FileSafetyResult.block("archive_expanded_size_limit", bytes.length);
                    }
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && entryBytes / compressed > MAX_COMPRESSION_RATIO) {
                    return FileSafetyResult.block("archive_compression_ratio_limit", bytes.length);
                }
                zip.closeEntry();
            }
            return FileSafetyResult.allow(bytes.length);
        } catch (IOException | RuntimeException exception) {
            return FileSafetyResult.block("archive_decode_failed", bytes.length);
        }
    }
}
