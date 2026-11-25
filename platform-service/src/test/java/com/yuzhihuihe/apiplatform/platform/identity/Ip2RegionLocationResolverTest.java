package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.lionsoul.ip2region.service.Ip2Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;

class Ip2RegionLocationResolverTest {

    @Test
    void springCreatesResolverUsingTheConfiguredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Ip2RegionLocationResolver.class);
            context.refresh();

            assertThat(context.getBean(Ip2RegionLocationResolver.class)).isNotNull();
        }
    }

    @Test
    void normalizesCountryProvinceAndCityWithoutCarrierOrPlaceholders() {
        Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                ip -> "中国|广东省|东莞市|电信|CN");

        assertThat(resolver.resolve("113.96.10.1")).isEqualTo("中国/广东省/东莞市");
    }

    @Test
    void removesDuplicateMunicipalityAndFallsBackForEmptyRegion() {
        Ip2RegionLocationResolver municipality = new Ip2RegionLocationResolver(
                ip -> "中国|北京市|北京市|联通|CN");
        Ip2RegionLocationResolver empty = new Ip2RegionLocationResolver(
                ip -> "0|0|0|内网IP|0");

        assertThat(municipality.resolve("1.2.3.4")).isEqualTo("中国/北京市");
        assertThat(empty.resolve("10.0.0.1")).isEqualTo(IpLocationResolver.UNKNOWN);
    }

    @Test
    void rejectsHostnamesAndFailsOpenWhenLookupThrows() {
        Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                ip -> { throw new IllegalStateException("database unavailable"); });

        assertThat(resolver.resolve("localhost")).isEqualTo(IpLocationResolver.UNKNOWN);
        assertThat(resolver.resolve("203.0.113.42")).isEqualTo(IpLocationResolver.UNKNOWN);
        assertThat(resolver.resolve(null)).isEqualTo(IpLocationResolver.UNKNOWN);
    }

    @Test
    void rejectsNonCanonicalIpv4BeforeLookup() {
        AtomicInteger lookups = new AtomicInteger();
        Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(ip -> {
            lookups.incrementAndGet();
            return "Country|Province|City|ISP|CN";
        });

        for (String invalid : new String[] {
                "127.1", "1.2.3", "2130706433", "256.1.1.1", "1..2.3", "01.2.3.4"
        }) {
            assertThat(resolver.resolve(invalid)).as(invalid).isEqualTo(IpLocationResolver.UNKNOWN);
        }
        assertThat(lookups).hasValue(0);
    }

    @Test
    void preservesInterruptFlagWhenLookupIsInterrupted() {
        Thread.interrupted();
        try {
            Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                    ip -> { throw new InterruptedException("lookup interrupted"); });

            assertThat(resolver.resolve("203.0.113.42")).isEqualTo(IpLocationResolver.UNKNOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingDatabasesDisableLookupWithoutBlockingConstruction() {
        Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver("", "");

        assertThat(resolver.resolve("203.0.113.42")).isEqualTo(IpLocationResolver.UNKNOWN);
    }

    @Test
    void resolvesIpv4AndIpv6FromRealXdbFiles() throws Exception {
        File v4Data = new ClassPathResource("geoip/ip2region_test_v4.xdb").getFile();
        File v6Data = new ClassPathResource("geoip/ip2region_test_v6.xdb").getFile();

        try (Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                v4Data.getAbsolutePath(), v6Data.getAbsolutePath())) {
            assertThat(resolver.resolve("113.96.10.1"))
                    .isEqualTo("\u4e2d\u56fd/\u5e7f\u4e1c\u7701/\u4e1c\u839e\u5e02");
            assertThat(resolver.resolve("2400:3200::1"))
                    .isEqualTo("\u4e2d\u56fd/\u6d59\u6c5f\u7701/\u676d\u5dde\u5e02");
        }
    }

    @Test
    void keepsIpv4AvailableWhenIpv6DatabaseCannotBeLoaded(@TempDir Path tempDir) throws Exception {
        Path v4Data = Files.createFile(tempDir.resolve("v4.xdb"));
        Path v6Data = Files.createFile(tempDir.resolve("v6.xdb"));
        Ip2Region v4Service = mock(Ip2Region.class);
        when(v4Service.search("113.96.10.1")).thenReturn("Country|Province|City|ISP|CN");

        try (MockedStatic<Ip2Region> factory = mockStatic(Ip2Region.class)) {
            factory.when(() -> Ip2Region.create(v4Data.toFile(), null)).thenReturn(v4Service);
            factory.when(() -> Ip2Region.create(null, v6Data.toFile()))
                    .thenThrow(new IOException("broken IPv6 database"));

            Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                    v4Data.toString(), v6Data.toString());

            assertThat(resolver.resolve("113.96.10.1")).isEqualTo("Country/Province/City");
        }
    }

    @Test
    void closesBothIpv4AndIpv6Services(@TempDir Path tempDir) throws Exception {
        Path v4Data = Files.createFile(tempDir.resolve("v4.xdb"));
        Path v6Data = Files.createFile(tempDir.resolve("v6.xdb"));
        Ip2Region v4Service = mock(Ip2Region.class);
        Ip2Region v6Service = mock(Ip2Region.class);

        try (MockedStatic<Ip2Region> factory = mockStatic(Ip2Region.class)) {
            factory.when(() -> Ip2Region.create(v4Data.toFile(), null)).thenReturn(v4Service);
            factory.when(() -> Ip2Region.create(null, v6Data.toFile())).thenReturn(v6Service);
            Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                    v4Data.toString(), v6Data.toString());

            resolver.close();

            verify(v4Service).close();
            verify(v6Service).close();
        }
    }

    @Test
    void closesLookupResource() {
        AtomicBoolean closed = new AtomicBoolean();
        Ip2RegionLocationResolver resolver = new Ip2RegionLocationResolver(
                ip -> "中国|广东省|东莞市|电信|CN", () -> closed.set(true));

        resolver.close();

        assertThat(closed).isTrue();
    }
}
