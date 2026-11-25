package com.yuzhihuihe.apiplatform.gateway.file;

/** 请求的对象在对象存储中不存在。 */
public class FileObjectMissingException extends RuntimeException {

    public FileObjectMissingException() {
        super("file_object_missing");
    }
}
