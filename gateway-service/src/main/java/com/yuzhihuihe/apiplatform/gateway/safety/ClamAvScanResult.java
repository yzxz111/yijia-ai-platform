package com.yuzhihuihe.apiplatform.gateway.safety;

public record ClamAvScanResult(Status status) {

    public enum Status { CLEAN, INFECTED, ERROR }
}
