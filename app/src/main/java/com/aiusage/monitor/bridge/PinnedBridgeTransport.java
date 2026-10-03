package com.aiusage.monitor.bridge;

import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Reads a paired computer over TLS, accepting only the certificate whose public key
 * hashes to the digest recorded when this phone paired with it. Phase 7 step 9.
 *
 * <p>Why this exists separately from pairing: A5 says everything after the pairing
 * carries the device token *and* runs on the pinned connection. Reading through the
 * plain transport would fail anyway — a paired Bridge serves TLS, and its certificate
 * is self-signed with no LAN address in it — but "fails with a network error" is not
 * the same as "refuses a certificate that is not the paired one". The difference is
 * what this class exists to keep true, and {@code PinnedBridgeTransportTest} asserts
 * both directions.
 *
 * <p>The timeouts come from the caller rather than from {@link BridgeTls}'s pairing
 * defaults: a pairing request is a few seconds of human patience, while a refresh has
 * always been bounded by the provider's own numbers, and a pinned read should not
 * silently change those.
 */
public final class PinnedBridgeTransport implements BridgeTransport {

    private final FingerprintPin pin;

    public PinnedBridgeTransport(FingerprintPin pin) {
        if (pin == null) {
            throw new IllegalArgumentException("钉指纹的连接必须带指纹");
        }
        this.pin = pin;
    }

    /** The digest this connection will accept, for a log line that names the computer. */
    public String pinnedFingerprint() {
        return pin.value();
    }

    @Override
    public Http.Response get(String url,
                             Map<String, String> headers,
                             int connectTimeoutMs,
                             int readTimeoutMs) throws IOException {
        HttpsURLConnection connection = BridgeTls.openUrl(url, pin);
        try {
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod("GET");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            return new Http.Response(code, stream == null ? "" : read(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int got;
        while ((got = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, got);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }
}
