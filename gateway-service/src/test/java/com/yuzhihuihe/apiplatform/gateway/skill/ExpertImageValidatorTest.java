package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ExpertImageValidatorTest {

    @Test
    void readsLittleEndianVp8xDimensions() {
        byte[] bytes = riffChunk("VP8X", new byte[] {
                0, 0, 0, 0,
                (byte) 0xff, 0x01, 0x00, // 512 - 1
                (byte) 0xff, 0x01, 0x00, // 512 - 1
        });

        ExpertImageValidator.ImageInfo info = ExpertImageValidator.validate(
                bytes, "icon", "image/webp");

        assertThat(info.mediaType()).isEqualTo("image/webp");
        assertThat(info.width()).isEqualTo(512);
        assertThat(info.height()).isEqualTo(512);
    }

    @Test
    void readsLittleEndianVp8lDimensions() {
        int width = 256;
        int height = 128;
        int bits = (width - 1) | ((height - 1) << 14);
        byte[] data = new byte[] {
                0x2f,
                (byte) bits, (byte) (bits >>> 8), (byte) (bits >>> 16), (byte) (bits >>> 24),
        };

        ExpertImageValidator.ImageInfo info = ExpertImageValidator.validate(
                riffChunk("VP8L", data), "exampleImage", "image/webp");

        assertThat(info.width()).isEqualTo(width);
        assertThat(info.height()).isEqualTo(height);
    }

    @Test
    void readsLittleEndianLossyVp8Dimensions() {
        byte[] data = new byte[] {
                0, 0, 0, (byte) 0x9d, 0x01, 0x2a,
                (byte) 0xa0, 0x05, // 1440
                (byte) 0xe0, 0x01, // 480
        };

        ExpertImageValidator.ImageInfo info = ExpertImageValidator.validate(
                riffChunk("VP8 ", data), "illustration", "image/webp");

        assertThat(info.width()).isEqualTo(1440);
        assertThat(info.height()).isEqualTo(480);
    }

    @Test
    void rejectsBytesOutsideTheDeclaredRiffBoundary() {
        byte[] valid = riffChunk("VP8X", new byte[] {
                0, 0, 0, 0, 127, 0, 0, 127, 0, 0,
        });
        byte[] withTail = Arrays.copyOf(valid, valid.length + 8);
        writeAscii(withTail, valid.length, "VP8X");
        little32(withTail, valid.length + 4, 10);

        assertThatThrownBy(() -> ExpertImageValidator.validate(
                withTail, "icon", "image/webp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_expert_image_icon_webp_size");
    }

    @Test
    void rejectsSvgAndMismatchedMagic() {
        assertThatThrownBy(() -> ExpertImageValidator.validate(
                "<svg/>".getBytes(StandardCharsets.UTF_8), "icon", "image/svg+xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_expert_image_icon_type");
    }

    private static byte[] riffChunk(String name, byte[] data) {
        int size = 4 + 8 + data.length;
        byte[] result = new byte[8 + size];
        writeAscii(result, 0, "RIFF");
        little32(result, 4, size);
        writeAscii(result, 8, "WEBP");
        writeAscii(result, 12, name);
        little32(result, 16, data.length);
        System.arraycopy(data, 0, result, 20, data.length);
        return result;
    }

    private static void writeAscii(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void little32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
