package com.aiusage.monitor.bridge;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which address to dial, once the Bridge's own address list is in hand. Phase 7
 * plan §3.2 (the {@code PairingClient} row).
 *
 * <p>The one rewrite is a loopback address on an emulator. A Bridge started without
 * {@code --host} listens on {@code 127.0.0.1} and offers exactly that, and on a
 * physical phone looping back to itself reaches nothing. The emulator is the
 * exception the acceptance runs on: its host machine's loopback is reachable as
 * {@code 10.0.2.2}, so an offer naming {@code 127.0.0.1} or {@code localhost} means
 * {@code 10.0.2.2} there and nowhere else.
 *
 * <p>Two limits keep this from becoming "the app guesses at networks":
 * <ul>
 *   <li>it only ever fires on an emulator, where reaching the host's loopback through
 *       {@code 10.0.2.2} is a documented property of the platform rather than a
 *       guess;</li>
 *   <li>it changes the address and nothing else — the fingerprint still comes from the
 *       offer, so the rewritten connection is pinned exactly like the original. An
 *       address substitution that also relaxed pinning would be a man-in-the-middle
 *       hole with a friendly name, which is why {@code PairingClientTest} asserts the
 *       pin on both paths.</li>
 * </ul>
 */
public final class AddressResolver {

    /** The emulator's route to the host machine's own loopback. */
    public static final String EMULATOR_GATEWAY = "10.0.2.2";

    private static final List<String> LOOPBACK_NAMES =
            Arrays.asList("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0", "::");

    /** A phone on Wi-Fi: nothing to rewrite. */
    public static final AddressResolver REAL_DEVICE = new AddressResolver(false);

    /** The emulator image the device acceptance runs on. */
    public static final AddressResolver EMULATOR = new AddressResolver(true);

    private final boolean emulator;

    public AddressResolver(boolean emulator) {
        this.emulator = emulator;
    }

    /** The resolver for a device of the given kind; the two constants cover real use. */
    public static AddressResolver forEmulator(boolean emulator) {
        return emulator ? EMULATOR : REAL_DEVICE;
    }

    /**
     * The form a host takes inside a URL's authority. An IPv6 literal needs brackets
     * and a name or IPv4 does not, and the Bridge's offer carries the bare form:
     * {@code net.IP.String()} writes {@code 2001:db8::1}, and a URL of
     * {@code https://2001:db8::1:38411} is not a host and a port but a parse failure
     * several seconds later. Same reason {@link #portSuffixOf} counts colons.
     */
    public static String authorityFor(String host) {
        if (host == null) {
            return "";
        }
        String cleaned = host.trim();
        if (isIpv6Literal(cleaned)) {
            return cleaned.startsWith("[") ? cleaned : "[" + cleaned + "]";
        }
        return cleaned;
    }

    /** Two or more colons, or a bracketed form: an IPv6 literal rather than host:port. */
    public static boolean isIpv6Literal(String host) {
        if (host == null) {
            return false;
        }
        if (host.startsWith("[")) {
            return host.indexOf(']') > 0;
        }
        int first = host.indexOf(':');
        return first >= 0 && first != host.lastIndexOf(':');
    }

    /** The host to dial for an offered host; unchanged unless this is the emulator. */
    public String host(String offeredHost) {
        if (!emulator || offeredHost == null) {
            return offeredHost;
        }
        String cleaned = offeredHost.trim().toLowerCase(Locale.US);
        String port = portSuffixOf(cleaned);
        String bare = port.isEmpty() ? cleaned : cleaned.substring(0,
                cleaned.length() - port.length());
        if (!LOOPBACK_NAMES.contains(bare)) {
            return offeredHost;
        }
        return EMULATOR_GATEWAY + port;
    }

    /**
     * The same rewrite applied to a finished {@code https://host:port}, which is what
     * the transport dials. Kept as text rather than a {@code URL} because the port has
     * to survive and {@code java.net.URL} would need {@code URI} to do it.
     */
    public String baseUrl(String baseUrl) {
        if (!emulator || baseUrl == null) {
            return baseUrl;
        }
        int schemeEnd = baseUrl.indexOf("://");
        if (schemeEnd < 0) {
            return baseUrl;
        }
        String scheme = baseUrl.substring(0, schemeEnd + 3);
        String rest = baseUrl.substring(schemeEnd);
        int pathAt = rest.indexOf('/', 3);
        String authority = pathAt < 0 ? rest : rest.substring(0, pathAt);
        String path = pathAt < 0 ? "" : rest.substring(pathAt);
        return scheme + authorityFor(host(authority.substring(3))) + path;
    }

    /**
     * {@code :38411} for {@code 127.0.0.1:38411}, and empty for anything that has no
     * port on the end. The IPv6 cases are why this is not just {@code indexOf(':')}:
     * {@code ::1} and {@code fe80::1} carry colons that belong to the address, and
     * reading {@code ::1} as "host {@code :} port {@code 1}" would rewrite — or fail to
     * rewrite — a literal based on a misparse.
     */
    private static String portSuffixOf(String host) {
        if (host.startsWith("[")) {
            int bracketEnd = host.indexOf(']');
            return bracketEnd >= 0 && host.length() > bracketEnd + 1
                    && host.charAt(bracketEnd + 1) == ':'
                    ? host.substring(bracketEnd + 1) : "";
        }
        int first = host.indexOf(':');
        if (first <= 0) {
            return "";
        }
        // A second colon means the colons belong to the address, not to a port.
        return host.indexOf(':', first + 1) < 0 ? host.substring(first) : "";
    }

    /**
     * How this build decides it is on an emulator, from the build tags the images
     * share. Written as a pure function of three strings so the rule is testable;
     * {@link DeviceProfile} is the one place that reads them off the device.
     *
     * <p>A false negative only costs the rewrite — the pairing then tries the address
     * the Bridge named, which is what a real device does. A false positive would send
     * a phone dialling {@code 10.0.2.2}, and no pin change follows from either.
     */
    public static boolean looksLikeEmulator(String fingerprint, String hardware,
                                            String product) {
        return contains(hardware, "goldfish") || contains(hardware, "ranchero")
                || contains(product, "sdk_gphone") || contains(product, "google_apis_emulator")
                || contains(product, "aosp") || contains(fingerprint, "generic");
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.US).contains(needle);
    }
}
