package com.aiusage.monitor.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.net.ssl.HttpsURLConnection;

import com.aiusage.monitor.util.Http;

/**
 * The real {@link PairingClient.Transport}: TLS to a Bridge, pinned, over
 * {@link HttpsURLConnection}.
 *
 * <p>Kept separate from the client so the failure mapping — which is the part with
 * tests — does not need a socket, and so this class stays small enough to read as the
 * only place a pairing request actually leaves the phone.
 */
public final class PinnedPairingTransport implements PairingClient.Transport {

    @Override
    public String probe(String baseUrl, String path) throws IOException {
        HttpsURLConnection connection = BridgeTls.openProbe(baseUrl, path);
        try {
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("电脑端返回 HTTP " + code);
            }
            return read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public Http.Response post(String baseUrl, String path, String jsonBody, FingerprintPin pin)
            throws IOException {
        HttpsURLConnection connection = BridgeTls.open(baseUrl, path, pin);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] payload = jsonBody.getBytes("UTF-8");
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
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
