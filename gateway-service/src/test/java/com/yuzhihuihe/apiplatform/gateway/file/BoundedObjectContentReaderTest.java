package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class BoundedObjectContentReaderTest {

    @Test
    void readsBlockingContentUpToTheInclusiveLimit() throws Exception {
        byte[] content = "bounded".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(BoundedObjectContentReader.read(
                        new ByteArrayInputStream(content), content.length))
                .isEqualTo(content);
    }

    @Test
    void rejectsBlockingContentBeforeRetainingBytesBeyondTheLimit() {
        byte[] content = "too-large".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> BoundedObjectContentReader.read(
                        new ByteArrayInputStream(content), 3))
                .isInstanceOf(FileObjectTooLargeException.class)
                .hasMessage("file_object_too_large");
    }

    @Test
    void rejectsReactiveContentAsSoonAsTheLimitIsExceeded() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<ByteBuffer> content = Flux.just(
                ByteBuffer.wrap(new byte[] {1, 2}),
                ByteBuffer.wrap(new byte[] {3, 4}))
                .concatWith(Flux.never())
                .doOnCancel(() -> cancelled.set(true));

        assertThatThrownBy(() -> BoundedObjectContentReader.collect(content, 3).block())
                .isInstanceOf(FileObjectTooLargeException.class)
                .hasMessage("file_object_too_large");
        assertThat(cancelled).isTrue();
    }
}
