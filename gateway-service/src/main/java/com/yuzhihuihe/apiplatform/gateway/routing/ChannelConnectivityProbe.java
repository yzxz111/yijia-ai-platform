package com.yuzhihuihe.apiplatform.gateway.routing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.yuzhihuihe.apiplatform.gateway.security.GlobalUnicastAddress;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

final class ChannelConnectivityProbe {

    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 5000;
    private static final int MAX_CNAME_REDIRECTS = 8;
    private static final String[] DNS_RECORD_TYPES = {"A", "AAAA", "CNAME"};

    private final AddressResolver resolver;
    private final AddressConnector connector;
    private final Set<String> localHostAllowlist;

    ChannelConnectivityProbe() {
        this("");
    }

    ChannelConnectivityProbe(String localHostAllowlist) {
        this(ChannelConnectivityProbe::resolveWithJndi, ChannelConnectivityProbe::connect,
                parseAllowlist(localHostAllowlist));
    }

    ChannelConnectivityProbe(AddressResolver resolver, AddressConnector connector) {
        this(resolver, connector, Set.of());
    }

    ChannelConnectivityProbe(
            AddressResolver resolver,
            AddressConnector connector,
            Set<String> localHostAllowlist) {
        this.resolver = resolver;
        this.connector = connector;
        this.localHostAllowlist = Set.copyOf(localHostAllowlist);
    }

    Mono<Boolean> probe(String baseUrl, String environment, int configuredTimeoutMs) {
        int timeoutMs = Math.min(MAX_TIMEOUT_MS, Math.max(MIN_TIMEOUT_MS, configuredTimeoutMs));
        return Mono.fromCallable(() -> canConnect(baseUrl, environment, timeoutMs))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(timeoutMs), Mono.just(false))
                .onErrorReturn(false);
    }

    private boolean canConnect(String baseUrl, String environment, int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            URI uri = URI.create(baseUrl);
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if ((!http && !https)
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return false;
            }
            String host = normalizeHost(uri.getHost());
            List<InetAddress> addresses = resolve(host, deadline);
            if (addresses.isEmpty()) {
                return false;
            }
            if (addresses.stream().anyMatch(address -> !isGlobalUnicast(address))) {
                if (!"LOCAL".equals(environment) || !localHostAllowlist.contains(host)) {
                    return false;
                }
            }

            int port = uri.getPort() >= 0 ? uri.getPort()
                    : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            for (InetAddress address : addresses) {
                try {
                    connector.connect(uri, address, port, deadline);
                    return true;
                } catch (Exception ignored) {
                    // 在共享截止时间仍有剩余的情况下，尝试下一个已校验的应答。
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private static Set<String> parseAllowlist(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String host : configured.split(",")) {
            if (!host.isBlank()) {
                hosts.add(normalizeHost(host));
            }
        }
        return Set.copyOf(hosts);
    }

    private List<InetAddress> resolve(String host, long deadline) throws Exception {
        InetAddress literal = parseLiteral(host);
        if (literal != null) {
            return List.of(literal);
        }
        return List.copyOf(new LinkedHashSet<>(resolver.resolve(host, remainingMillis(deadline))));
    }

    private static List<InetAddress> resolveWithJndi(String host, int timeoutMs) throws Exception {
        return resolveWithFallback(
                host,
                timeoutMs,
                (currentHost, remainingTimeoutMs) -> resolveCnameChain(
                        currentHost, remainingTimeoutMs, ChannelConnectivityProbe::lookupWithJndi),
                (currentHost, ignored) -> List.of(InetAddress.getAllByName(currentHost)));
    }

    static List<InetAddress> resolveWithFallback(
            String host, int timeoutMs, AddressResolver primary, AddressResolver fallback) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        try {
            List<InetAddress> addresses = primary.resolve(host, remainingMillis(deadline));
            if (!addresses.isEmpty()) {
                return addresses;
            }
        } catch (Exception ignored) {
            // Docker 的内嵌 DNS 可能返回不了任何 JNDI 记录，而 JVM 解析器仍然能成功。
        }
        return fallback.resolve(host, remainingMillis(deadline));
    }

    static List<InetAddress> resolveCnameChain(String host, int timeoutMs, DnsLookup lookup) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        Set<String> visited = new LinkedHashSet<>();
        String currentHost = normalizeDnsName(host);
        int redirects = 0;
        while (true) {
            if (!visited.add(currentHost.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("channel_dns_cname_loop");
            }
            DnsAnswer answer = lookup.lookup(currentHost, remainingMillis(deadline));
            if (!answer.addresses().isEmpty()) {
                return answer.addresses();
            }
            if (answer.canonicalName() == null || answer.canonicalName().isBlank()) {
                return List.of();
            }
            if (redirects++ >= MAX_CNAME_REDIRECTS) {
                throw new IllegalArgumentException("channel_dns_cname_depth_exceeded");
            }
            currentHost = normalizeDnsName(answer.canonicalName());
        }
    }

    private static DnsAnswer lookupWithJndi(String host, int timeoutMs) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", Integer.toString(timeoutMs));
        environment.put("com.sun.jndi.dns.timeout.retries", "1");

        DirContext context = new InitialDirContext(environment);
        try {
            Attributes attributes = context.getAttributes(host, DNS_RECORD_TYPES);
            Set<InetAddress> addresses = new LinkedHashSet<>();
            for (String recordType : List.of("A", "AAAA")) {
                Attribute attribute = attributes.get(recordType);
                if (attribute == null) {
                    continue;
                }
                NamingEnumeration<?> values = attribute.getAll();
                try {
                    while (values.hasMore()) {
                        InetAddress address = parseLiteral(values.next().toString());
                        if (address != null) {
                            addresses.add(address);
                        }
                    }
                } finally {
                    values.close();
                }
            }
            Attribute cname = attributes.get("CNAME");
            String canonicalName = cname == null || cname.size() == 0 ? null : cname.get(0).toString();
            return new DnsAnswer(new ArrayList<>(addresses), canonicalName);
        } finally {
            context.close();
        }
    }

    private static void connect(URI uri, InetAddress address, int port, long deadline) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), remainingMillis(deadline));
            }
            return;
        }

        String host = normalizeHost(uri.getHost());
        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress(address, port), remainingMillis(deadline));
            try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(transport, host, port, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (parseLiteral(host) == null) {
                    parameters.setServerNames(List.of(new SNIHostName(host)));
                }
                socket.setSSLParameters(parameters);
                socket.setSoTimeout(remainingMillis(deadline));
                socket.startHandshake();
            }
        }
    }

    static boolean isGlobalUnicast(InetAddress address) {
        return GlobalUnicastAddress.isGlobalUnicast(address);
    }

    private static InetAddress parseLiteral(String host) throws Exception {
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = normalizeHost(host);
        if (normalized.indexOf(':') >= 0) {
            return InetAddress.getByName(normalized);
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] value = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty() || parts[index].length() > 3
                    || !parts[index].chars().allMatch(Character::isDigit)) {
                return null;
            }
            int octet = Integer.parseInt(parts[index]);
            if (octet > 255) {
                return null;
            }
            value[index] = (byte) octet;
        }
        return InetAddress.getByAddress(value);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.strip();
        if (normalized.length() > 1
                && normalized.charAt(0) == '['
                && normalized.charAt(normalized.length() - 1) == ']') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeDnsName(String host) {
        String normalized = normalizeHost(host == null ? null : host.strip());
        while (normalized != null && normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("invalid_channel_host");
        }
        return normalized;
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("channel_test_timeout");
        }
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    @FunctionalInterface
    interface AddressResolver {
        List<InetAddress> resolve(String host, int timeoutMs) throws Exception;
    }

    @FunctionalInterface
    interface AddressConnector {
        void connect(URI uri, InetAddress address, int port, long deadline) throws Exception;
    }

    @FunctionalInterface
    interface DnsLookup {
        DnsAnswer lookup(String host, int timeoutMs) throws Exception;
    }

    record DnsAnswer(List<InetAddress> addresses, String canonicalName) {
        DnsAnswer {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
        }
    }
}
