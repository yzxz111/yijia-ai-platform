package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ChannelConnectivityProbeTest {

    @Test
    void rejectsAllAnswersWhenDnsIncludesANonGlobalAddress() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        ChannelConnectivityProbe probe = new ChannelConnectivityProbe(
                (host, timeoutMs) -> List.of(
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("192.168.1.10")),
                (uri, address, port, deadline) -> connections.incrementAndGet());

        assertThat(probe.probe("https://provider.example/v1", "SANDBOX", 1000).block()).isFalse();
        assertThat(connections).hasValue(0);
    }

    @Test
    void localPrivateAddressRequiresAnExplicitHostAllowlist() throws Exception {
        InetAddress privateAddress = InetAddress.getByName("10.20.30.40");
        AtomicInteger blockedConnections = new AtomicInteger();
        ChannelConnectivityProbe blocked = new ChannelConnectivityProbe(
                (host, timeoutMs) -> List.of(privateAddress),
                (uri, address, port, deadline) -> blockedConnections.incrementAndGet());

        assertThat(blocked.probe("http://internal-provider:8080/v1", "LOCAL", 1000).block())
                .isFalse();
        assertThat(blockedConnections).hasValue(0);

        AtomicInteger allowedConnections = new AtomicInteger();
        ChannelConnectivityProbe allowed = new ChannelConnectivityProbe(
                (host, timeoutMs) -> List.of(privateAddress),
                (uri, address, port, deadline) -> allowedConnections.incrementAndGet(),
                java.util.Set.of("internal-provider"));

        assertThat(allowed.probe("http://internal-provider:8080/v1", "LOCAL", 1000).block())
                .isTrue();
        assertThat(allowedConnections).hasValue(1);
    }

    @Test
    void rejectsLegacyUrlsWithUnsupportedSchemesOrSensitiveComponents() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        AtomicInteger connections = new AtomicInteger();
        ChannelConnectivityProbe probe = new ChannelConnectivityProbe(
                (host, timeoutMs) -> List.of(publicAddress),
                (uri, address, port, deadline) -> connections.incrementAndGet());

        for (String baseUrl : List.of(
                "ftp://example.test/v1",
                "https://user:secret@example.test/v1",
                "https://example.test/v1?api_key=secret",
                "https://example.test/v1#secret")) {
            assertThat(probe.probe(baseUrl, "SANDBOX", 1000).block()).isFalse();
        }
        assertThat(connections).hasValue(0);
    }

    @Test
    void resolverDelayCannotExceedTheEndToEndDeadline() {
        ChannelConnectivityProbe probe = new ChannelConnectivityProbe(
                (host, timeoutMs) -> {
                    Thread.sleep(1000);
                    return List.of();
                },
                (uri, address, port, deadline) -> {
                    throw new AssertionError("connector must not run after DNS timeout");
                });

        long started = System.nanoTime();
        Boolean result = probe.probe("https://provider.example/v1", "SANDBOX", 100)
                .block(Duration.ofSeconds(2));

        assertThat(result).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void followsCnameChainBeforeReturningAddresses() throws Exception {
        List<String> queriedHosts = new ArrayList<>();
        InetAddress expected = InetAddress.getByName("8.8.8.8");

        List<InetAddress> addresses = ChannelConnectivityProbe.resolveCnameChain(
                "provider.example.",
                1000,
                (host, timeoutMs) -> {
                    queriedHosts.add(host);
                    return host.equals("provider.example")
                            ? new ChannelConnectivityProbe.DnsAnswer(List.of(), "edge.example.")
                            : new ChannelConnectivityProbe.DnsAnswer(List.of(expected), null);
                });

        assertThat(queriedHosts).containsExactly("provider.example", "edge.example");
        assertThat(addresses).containsExactly(expected);
    }

    @Test
    void rejectsCnameLoops() {
        assertThatThrownBy(() -> ChannelConnectivityProbe.resolveCnameChain(
                        "provider.example",
                        1000,
                        (host, timeoutMs) -> new ChannelConnectivityProbe.DnsAnswer(
                                List.of(), host.equals("provider.example") ? "edge.example" : "provider.example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("channel_dns_cname_loop");
    }

    @Test
    void fallsBackWhenPrimaryResolverReturnsNoRecords() throws Exception {
        InetAddress expected = InetAddress.getByName("8.8.4.4");
        AtomicInteger fallbackCalls = new AtomicInteger();

        List<InetAddress> addresses = ChannelConnectivityProbe.resolveWithFallback(
                "provider.example",
                1000,
                (host, timeoutMs) -> List.of(),
                (host, timeoutMs) -> {
                    fallbackCalls.incrementAndGet();
                    return List.of(expected);
                });

        assertThat(addresses).containsExactly(expected);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void classifiesSpecialUseIpv4AndIpv6Conservatively() throws Exception {
        for (String address : List.of(
                "0.0.0.1", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.0.1",
                "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "192.168.0.1",
                "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
                "::1", "fc00::1", "2001::1", "2001:db8::1", "2002::1", "3fff::1")) {
            assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName(address)))
                    .as(address).isFalse();
        }

        assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(ChannelConnectivityProbe.isGlobalUnicast(InetAddress.getByName("2606:4700:4700::1111")))
                .isTrue();
    }
}
