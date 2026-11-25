package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Byte-level validation for the three images accepted by the Expert aggregate endpoint.
 *
 * <p>This deliberately does not delegate to {@link SkillImageValidator}: that validator keeps
 * SVG compatibility for the legacy Skill/Team endpoints, while Expert presentation assets are a
 * portable contract and must be PNG/JPEG/WebP only.
 */
final class ExpertImageValidator {

    static final long ICON_MAX_BYTES = 128L * 1024L;
    static final long ILLUSTRATION_MAX_BYTES = 640L * 1024L;
    static final long EXAMPLE_MAX_BYTES = 512L * 1024L;
    static final long TOTAL_MAX_BYTES = 1_310_720L; // 1.25 MiB

    private static final byte[] PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private ExpertImageValidator() {}

    static ImageInfo validate(byte[] content, String role, String suppliedType) {
        if (content == null || content.length == 0) {
            throw invalid(role, "empty");
        }
        String type = suppliedType == null ? "" : suppliedType.strip().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !type.equals("image/png") && !type.equals("image/jpeg")
                && !type.equals("image/jpg") && !type.equals("image/webp")) {
            throw invalid(role, "type");
        }
        ImageInfo info;
        if (startsWith(content, PNG)) {
            info = png(content, role);
        } else if (content.length >= 3 && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff) {
            info = jpeg(content, role);
        } else if (content.length >= 12 && ascii(content, 0, "RIFF")
                && ascii(content, 8, "WEBP")) {
            info = webp(content, role);
        } else {
            throw invalid(role, "magic");
        }
        String canonicalType = "image/jpeg".equals(type) || "image/jpg".equals(type)
                ? "image/jpeg" : type;
        if (!canonicalType.isEmpty() && !canonicalType.equals(info.mediaType())) {
            throw invalid(role, "type_magic_mismatch");
        }
        requireDimensions(info, role);
        return info;
    }

    static long maxBytes(String role) {
        return switch (role) {
            case "icon" -> ICON_MAX_BYTES;
            case "illustration" -> ILLUSTRATION_MAX_BYTES;
            case "exampleImage" -> EXAMPLE_MAX_BYTES;
            default -> throw invalid(role, "role");
        };
    }

    private static void requireDimensions(ImageInfo info, String role) {
        if (info.width() <= 0 || info.height() <= 0) {
            throw invalid(role, "dimensions");
        }
        switch (role) {
            case "icon" -> {
                if (info.width() != info.height() || info.width() > 512 || info.height() > 512) {
                    throw invalid(role, "icon_dimensions");
                }
            }
            case "illustration" -> {
                if (info.width() > 1440 || info.height() > 480
                        || info.width() != info.height() * 3) {
                    throw invalid(role, "illustration_dimensions");
                }
            }
            case "exampleImage" -> {
                if (info.width() > 1440 || info.height() > 1080) {
                    throw invalid(role, "example_dimensions");
                }
            }
            default -> throw invalid(role, "role");
        }
    }

    private static ImageInfo png(byte[] content, String role) {
        if (content.length < 24 || !ascii(content, 12, "IHDR")) {
            throw invalid(role, "png_header");
        }
        long width = uint32(content, 16);
        long height = uint32(content, 20);
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw invalid("image", "dimensions");
        }
        return new ImageInfo("image/png", (int) width, (int) height);
    }

    private static ImageInfo jpeg(byte[] content, String role) {
        int offset = 2;
        while (offset + 3 < content.length) {
            while (offset < content.length && (content[offset] & 0xff) != 0xff) {
                offset++;
            }
            while (offset < content.length && (content[offset] & 0xff) == 0xff) {
                offset++;
            }
            if (offset >= content.length) break;
            int marker = content[offset++] & 0xff;
            if (marker == 0xd8 || marker == 0xd9) continue;
            if (marker == 0xda) break;
            if (offset + 1 >= content.length) break;
            int length = u16(content, offset);
            if (length < 2 || offset + length > content.length) break;
            boolean frame = (marker >= 0xc0 && marker <= 0xc3)
                    || (marker >= 0xc5 && marker <= 0xc7)
                    || (marker >= 0xc9 && marker <= 0xcb)
                    || (marker >= 0xcd && marker <= 0xcf);
            if (frame && length >= 7) {
                int height = u16(content, offset + 3);
                int width = u16(content, offset + 5);
                return new ImageInfo("image/jpeg", width, height);
            }
            offset += length;
        }
        throw invalid(role, "jpeg_header");
    }

    private static ImageInfo webp(byte[] content, String role) {
        long riffSize = uint32Little(content, 4);
        if (riffSize < 4 || riffSize + 8L != content.length) {
            throw invalid(role, "webp_size");
        }
        long riffEnd = riffSize + 8L;
        int offset = 12;
        while (offset + 8L <= riffEnd) {
            String chunk = new String(content, offset, 4, StandardCharsets.US_ASCII);
            long size = uint32Little(content, offset + 4);
            if (size > Integer.MAX_VALUE || offset + 8L + size > riffEnd) break;
            int data = offset + 8;
            if ("VP8X".equals(chunk) && size >= 10) {
                int width = 1 + ((content[data + 4] & 0xff)
                        | ((content[data + 5] & 0xff) << 8)
                        | ((content[data + 6] & 0xff) << 16));
                int height = 1 + ((content[data + 7] & 0xff)
                        | ((content[data + 8] & 0xff) << 8)
                        | ((content[data + 9] & 0xff) << 16));
                return new ImageInfo("image/webp", width, height);
            }
            if ("VP8L".equals(chunk) && size >= 5 && (content[data] & 0xff) == 0x2f) {
                int bits = (content[data + 1] & 0xff)
                        | ((content[data + 2] & 0xff) << 8)
                        | ((content[data + 3] & 0xff) << 16)
                        | ((content[data + 4] & 0xff) << 24);
                int width = 1 + (bits & 0x3fff);
                int height = 1 + ((bits >>> 14) & 0x3fff);
                return new ImageInfo("image/webp", width, height);
            }
            if ("VP8 ".equals(chunk) && size >= 10 && content[data + 3] == (byte) 0x9d
                    && content[data + 4] == 0x01 && content[data + 5] == 0x2a) {
                return new ImageInfo("image/webp", u16Little(content, data + 6),
                        u16Little(content, data + 8));
            }
            offset += 8 + (int) size + ((size & 1) == 0 ? 0 : 1);
        }
        throw invalid(role, "webp_header");
    }

    private static int u16(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    private static int u16Little(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
    }

    private static long uint32(byte[] value, int offset) {
        return ((long) (value[offset] & 0xff) << 24)
                | ((long) (value[offset + 1] & 0xff) << 16)
                | ((long) (value[offset + 2] & 0xff) << 8)
                | (long) (value[offset + 3] & 0xff);
    }

    private static long uint32Little(byte[] value, int offset) {
        return (long) (value[offset] & 0xff)
                | ((long) (value[offset + 1] & 0xff) << 8)
                | ((long) (value[offset + 2] & 0xff) << 16)
                | ((long) (value[offset + 3] & 0xff) << 24);
    }

    private static boolean ascii(byte[] value, int offset, String expected) {
        if (offset < 0 || value.length - offset < expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            if (value[offset + index] != (byte) expected.charAt(index)) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static IllegalArgumentException invalid(String role, String reason) {
        return new IllegalArgumentException("invalid_expert_image_" + role + "_" + reason);
    }

    record ImageInfo(String mediaType, int width, int height) {}
}
