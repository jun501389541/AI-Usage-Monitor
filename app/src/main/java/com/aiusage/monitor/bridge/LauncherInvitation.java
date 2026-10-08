package com.aiusage.monitor.bridge;

import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** CodexLauncher QR format, distinct from the Go Bridge payload. */
public final class LauncherInvitation {
    public final String bridgeId;
    public final String endpoint;
    public final String certificatePin;
    public final String pairToken;
    public final long expiresAt;

    private LauncherInvitation(String id, String endpoint, String pin, String token, long expires) {
        this.bridgeId = id; this.endpoint = endpoint; this.certificatePin = pin;
        this.pairToken = token; this.expiresAt = expires;
    }

    public static LauncherInvitation parse(String text) throws PairingPayload.Invalid {
        try {
            if (text == null || text.length() > 16384) throw new IllegalArgumentException();
            URI link = new URI(text.trim());
            if (!"https".equals(link.getScheme()) || !"/v1/device".equals(link.getPath())
                    || link.getRawFragment() == null || link.getUserInfo() != null
                    || link.getQuery() != null) throw new IllegalArgumentException();
            JSONObject json = new JSONObject(URLDecoder.decode(link.getRawFragment(), "UTF-8"));
            Set<String> fields = new HashSet<>(Arrays.asList("schemaVersion", "bridgeId", "endpoint",
                    "certificateSha256", "pairToken", "expiresAt"));
            for (Iterator<String> keys = json.keys(); keys.hasNext();) {
                if (!fields.contains(keys.next())) throw new IllegalArgumentException();
            }
            if (json.getInt("schemaVersion") != 1) throw new IllegalArgumentException();
            String id = json.getString("bridgeId");
            java.util.UUID.fromString(id);
            URI target = new URI(json.getString("endpoint"));
            if (!"https".equals(target.getScheme()) || target.getHost() == null
                    || !target.getHost().equalsIgnoreCase(link.getHost())
                    || effectivePort(target) != effectivePort(link)
                    || target.getUserInfo() != null || target.getQuery() != null
                    || target.getFragment() != null
                    || !(target.getPath().isEmpty() || "/".equals(target.getPath()))) {
                throw new IllegalArgumentException();
            }
            String token = json.getString("pairToken");
            if (!token.matches("[A-Za-z0-9_-]{43}") || Base64Url.decode(token).length != 32) {
                throw new IllegalArgumentException();
            }
            FingerprintPin pin = new FingerprintPin(FingerprintPin.CERTIFICATE_PREFIX
                    + json.getString("certificateSha256"));
            long expires = Instant.parse(json.getString("expiresAt")).toEpochMilli();
            String endpoint = "https://" + target.getRawAuthority();
            return new LauncherInvitation(id, endpoint, pin.value(), token, expires);
        } catch (Exception invalid) {
            throw new PairingPayload.Invalid("不是有效的 CodexLauncher 配对二维码，请在电脑端重新生成。");
        }
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port == -1) return 443;
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        return port;
    }
}
