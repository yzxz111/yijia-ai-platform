package com.yuzhihuihe.apiplatform.gateway.security;

import java.net.InetAddress;

public final class GlobalUnicastAddress {

    private GlobalUnicastAddress() {
    }

    public static boolean isGlobalUnicast(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] value = address.getAddress();
        if (value.length == 4) {
            return isGlobalUnicastIpv4(value);
        }
        if (value.length != 16) {
            return false;
        }
        if (isIpv4Mapped(value)) {
            return isGlobalUnicastIpv4(new byte[] {value[12], value[13], value[14], value[15]});
        }
        int first = Byte.toUnsignedInt(value[0]);
        int second = Byte.toUnsignedInt(value[1]);
        int third = Byte.toUnsignedInt(value[2]);
        int fourth = Byte.toUnsignedInt(value[3]);
        if ((first & 0xe0) != 0x20) {
            return false;
        }
        if (first == 0x20 && second == 0x01 && (third & 0xfe) == 0x00) {
            return false;
        }
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) {
            return false;
        }
        if (first == 0x20 && second == 0x02) {
            return false;
        }
        return !(first == 0x3f && second == 0xff && (third & 0xf0) == 0x00);
    }

    private static boolean isGlobalUnicastIpv4(byte[] value) {
        long address = (long) Byte.toUnsignedInt(value[0]) << 24
                | (long) Byte.toUnsignedInt(value[1]) << 16
                | (long) Byte.toUnsignedInt(value[2]) << 8
                | Byte.toUnsignedInt(value[3]);
        return !inIpv4Range(address, 0x00000000L, 8)
                && !inIpv4Range(address, 0x0a000000L, 8)
                && !inIpv4Range(address, 0x64400000L, 10)
                && !inIpv4Range(address, 0x7f000000L, 8)
                && !inIpv4Range(address, 0xa9fe0000L, 16)
                && !inIpv4Range(address, 0xac100000L, 12)
                && !inIpv4Range(address, 0xc0000000L, 24)
                && !inIpv4Range(address, 0xc0000200L, 24)
                && !inIpv4Range(address, 0xc0586300L, 24)
                && !inIpv4Range(address, 0xc0a80000L, 16)
                && !inIpv4Range(address, 0xc6120000L, 15)
                && !inIpv4Range(address, 0xc6336400L, 24)
                && !inIpv4Range(address, 0xcb007100L, 24)
                && !inIpv4Range(address, 0xe0000000L, 4)
                && !inIpv4Range(address, 0xf0000000L, 4);
    }

    private static boolean inIpv4Range(long address, long network, int prefixLength) {
        long mask = (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        return (address & mask) == network;
    }

    private static boolean isIpv4Mapped(byte[] value) {
        for (int index = 0; index < 10; index++) {
            if (value[index] != 0) {
                return false;
            }
        }
        return value[10] == (byte) 0xff && value[11] == (byte) 0xff;
    }
}
