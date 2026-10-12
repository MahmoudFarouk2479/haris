package dev.haris.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides which internet addresses page fetching may connect to: public ones only. Without
 * this, a page or an injected instruction could make Haris request the router's admin page,
 * another device on the LAN, a cloud metadata service, or Haris's own local engines.
 */
public final class NetGuard {

    private NetGuard() {}

    /** Validates scheme, port, credentials and literal-IP hosts of a URL before any request. */
    public static URI checkUrl(String raw) throws BlockedException {
        URI uri;
        try {
            uri = URI.create(raw.strip()).normalize();
        } catch (IllegalArgumentException e) {
            throw new BlockedException("That is not a valid link.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BlockedException("Only http and https pages can be read.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BlockedException("That link has no host.");
        }
        if (uri.getUserInfo() != null) {
            throw new BlockedException("Links with embedded credentials are refused.");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new BlockedException("Only the standard web ports (80, 443) are allowed.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa")
                || !host.contains(".") && !host.startsWith("[")) {
            throw new BlockedException("Local network names are off-limits.");
        }
        if (isIpLiteral(host)) {
            try {
                if (!isPublic(InetAddress.getByName(host.replace("[", "").replace("]", "")))) {
                    throw new BlockedException("Private and local network addresses are off-limits.");
                }
            } catch (UnknownHostException e) {
                throw new BlockedException("That address is not valid.");
            }
        }
        return uri;
    }

    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xFF;
            int o1 = b[1] & 0xFF;
            return !(o0 == 0                                   // "this network"
                    || o0 == 100 && o1 >= 64 && o1 <= 127      // carrier-grade NAT
                    || o0 == 192 && o1 == 0 && (b[2] & 0xFF) == 0   // IETF protocol assignments
                    || o0 == 198 && (o1 == 18 || o1 == 19)     // benchmarking
                    || o0 >= 224);                             // multicast, reserved, broadcast
        }
        if (a instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return false;                                  // fc00::/7 unique local
            }
            if (first == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
                return false;                                  // 2001:db8::/32 documentation
            }
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) {
                v4Mapped &= b[i] == 0;
            }
            if (v4Mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isIpLiteral(String host) {
        return host.startsWith("[") || host.matches("[0-9.]+") || host.matches("[0-9a-f:]+") && host.contains(":")
                || host.matches("0x[0-9a-f.x]+");
    }

    public static final class BlockedException extends Exception {
        public BlockedException(String message) {
            super(message);
        }
    }
}
