package com.yuzhihuihe.apiplatform.platform.identity;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import jakarta.annotation.PreDestroy;

import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Ip2RegionLocationResolver implements IpLocationResolver, AutoCloseable {

    private static final int MAX_REGION_LENGTH = 128;

    private final RegionLookup lookup;
    private final CloseAction closeAction;

    @Autowired
    public Ip2RegionLocationResolver(
            @Value("${identity.login-risk.geoip-v4-data-source:}") String v4DataSource,
            @Value("${identity.login-risk.geoip-v6-data-source:}") String v6DataSource) {
        this(resources(v4DataSource, v6DataSource));
    }

    Ip2RegionLocationResolver(RegionLookup lookup) {
        this(lookup, () -> { });
    }

    Ip2RegionLocationResolver(RegionLookup lookup, CloseAction closeAction) {
        this.lookup = lookup == null ? ip -> null : lookup;
        this.closeAction = closeAction == null ? () -> { } : closeAction;
    }

    private Ip2RegionLocationResolver(Resources resources) {
        this(resources.lookup(), resources.closeAction());
    }

    @Override
    public String resolve(String ipAddress) {
        if (!isIpLiteral(ipAddress)) return UNKNOWN;
        try {
            String literal = ipAddress.trim();
            InetAddress.getByName(literal);
            return normalize(lookup.search(literal));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    private static Resources resources(String v4Path, String v6Path) {
        Resources v4 = resource(v4Path, false);
        Resources v6 = resource(v6Path, true);
        return new Resources(
                ip -> (isIpv6(ip) ? v6 : v4).lookup().search(ip),
                () -> closeAll(v4.closeAction(), v6.closeAction()));
    }

    private static Resources resource(String path, boolean ipv6) {
        try {
            File database = readableFile(path);
            if (database == null) return Resources.disabled();
            Ip2Region service = ipv6
                    ? Ip2Region.create(null, database)
                    : Ip2Region.create(database, null);
            if (service == null) return Resources.disabled();
            return new Resources(service::search, service::close);
        } catch (Exception ignored) {
            return Resources.disabled();
        }
    }

    private static boolean isIpv6(String ipAddress) {
        return ipAddress.indexOf(':') >= 0;
    }

    private static void closeAll(CloseAction... actions) throws Exception {
        Exception failure = null;
        for (CloseAction action : actions) {
            try {
                action.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else if (exception instanceof InterruptedException
                        && !(failure instanceof InterruptedException)) {
                    exception.addSuppressed(failure);
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private static File readableFile(String value) {
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value.trim());
        return Files.isRegularFile(path) && Files.isReadable(path) ? path.toFile() : null;
    }

    private static String normalize(String rawRegion) {
        if (rawRegion == null || rawRegion.isBlank()) return UNKNOWN;
        String[] fields = rawRegion.split("\\|", -1);
        Set<String> parts = new LinkedHashSet<>();
        add(parts, fields, 0);
        add(parts, fields, 1);
        add(parts, fields, 2);
        if (parts.isEmpty()) return UNKNOWN;
        String region = String.join("/", parts);
        return region.length() <= MAX_REGION_LENGTH
                ? region : region.substring(0, MAX_REGION_LENGTH);
    }

    private static void add(Set<String> parts, String[] fields, int index) {
        if (index >= fields.length) return;
        String value = fields[index] == null ? "" : fields[index].trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!value.isEmpty() && !"0".equals(value) && !"null".equals(normalized)
                && !"unknown".equals(normalized) && !"未知".equals(value)) {
            parts.add(value);
        }
    }

    private static boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) return false;
        String candidate = value.trim();
        if (candidate.indexOf(':') >= 0) return candidate.matches("[0-9A-Fa-f:.]+");
        return isIpv4Literal(candidate);
    }

    private static boolean isIpv4Literal(String candidate) {
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            int number = 0;
            for (int index = 0; index < octet.length(); index++) {
                char digit = octet.charAt(index);
                if (digit < '0' || digit > '9') return false;
                number = number * 10 + digit - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }

    @Override
    @PreDestroy
    public void close() {
        try {
            closeAction.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 位置查询为失败即放行（fail-open），不得阻塞应用关闭。
        }
    }

    @FunctionalInterface
    interface RegionLookup {
        String search(String ipAddress) throws Exception;
    }

    @FunctionalInterface
    interface CloseAction {
        void close() throws Exception;
    }

    private record Resources(RegionLookup lookup, CloseAction closeAction) {
        private static Resources disabled() {
            return new Resources(ip -> null, () -> { });
        }
    }
}
