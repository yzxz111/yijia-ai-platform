package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FileObjectStoreBoundedReadContractTest {

    @Test
    void requiresEveryStorageProviderToAcceptAReadLimit() throws Exception {
        assertThat(FileObjectStore.class.getMethod("get", String.class, long.class).getReturnType())
                .isEqualTo(Mono.class);
    }
}
