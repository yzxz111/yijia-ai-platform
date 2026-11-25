package com.yuzhihuihe.apiplatform.gateway.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class BoundedObjectContentReader {

    private static final int BUFFER_SIZE = 8192;

    private BoundedObjectContentReader() {}

    static byte[] read(InputStream input, long maximumBytes) throws IOException {
        int limit = requireLimit(maximumBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - total) {
                throw new FileObjectTooLargeException();
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    static Mono<byte[]> collect(Publisher<ByteBuffer> content, long maximumBytes) {
        int limit = requireLimit(maximumBytes);
        return Flux.from(content)
                .reduceWith(() -> new Accumulator(limit), (accumulator, buffer) -> {
                    accumulator.append(buffer);
                    return accumulator;
                })
                .map(Accumulator::bytes);
    }

    static int requireLimit(long maximumBytes) {
        if (maximumBytes < 0 || maximumBytes > Integer.MAX_VALUE - BUFFER_SIZE) {
            throw new IllegalArgumentException("invalid_file_object_read_limit");
        }
        return Math.toIntExact(maximumBytes);
    }

    private static final class Accumulator {
        private final int limit;
        private final ByteArrayOutputStream output;
        private int total;

        private Accumulator(int limit) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit, BUFFER_SIZE));
        }

        private void append(ByteBuffer source) {
            ByteBuffer buffer = source.duplicate();
            int count = buffer.remaining();
            if (count > limit - total) {
                throw new FileObjectTooLargeException();
            }
            byte[] chunk = new byte[count];
            buffer.get(chunk);
            output.writeBytes(chunk);
            total += count;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }
}
