package com.yuzhihuihe.apiplatform.gateway.safety;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class ImageSafetyScanner {

    private static final long MAX_BYTES = 20_971_520L;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_FRAMES = 120;

    public FileSafetyResult scan(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return FileSafetyResult.block("image_size_limit", bytes == null ? 0 : bytes.length);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return FileSafetyResult.block("image_decode_failed", bytes.length);
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return FileSafetyResult.block("image_decode_failed", bytes.length);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    return FileSafetyResult.block("image_pixel_limit", bytes.length);
                }
                int frames = 1;
                try {
                    while (reader.getNumImages(true) > frames) {
                        frames++;
                    }
                } catch (IOException ignored) {
                    // 单帧格式不会暴露帧数。
                }
                return frames > MAX_FRAMES
                        ? FileSafetyResult.block("image_frame_limit", bytes.length)
                        : FileSafetyResult.allow(bytes.length);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return FileSafetyResult.block("image_decode_failed", bytes.length);
        }
    }
}
