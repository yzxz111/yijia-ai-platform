package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.List;

public record StoredObjectPage(List<StoredObjectMetadata> objects, String nextContinuationToken) {

    public StoredObjectPage {
        objects = List.copyOf(objects);
    }
}
