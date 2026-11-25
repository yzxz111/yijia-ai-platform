package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

class GlobalUnicastAddressTest {

    @Test
    void classifiesSpecialUseAddressesConservatively() throws Exception {
        for (String address : List.of(
                "0.0.0.1", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.0.1",
                "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "192.168.0.1",
                "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
                "::1", "fc00::1", "2001::1", "2001:db8::1", "2002::1", "3fff::1")) {
            assertThat(GlobalUnicastAddress.isGlobalUnicast(InetAddress.getByName(address)))
                    .as(address).isFalse();
        }

        assertThat(GlobalUnicastAddress.isGlobalUnicast(
                InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(GlobalUnicastAddress.isGlobalUnicast(
                InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        assertThat(GlobalUnicastAddress.isGlobalUnicast(null)).isFalse();
    }
}
