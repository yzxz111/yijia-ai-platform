package com.yuzhihuihe.apiplatform.platform.identity;

@FunctionalInterface
public interface IpLocationResolver {

    String UNKNOWN = "UNKNOWN";

    String resolve(String ipAddress);
}
