package com.aiusage.monitor.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP GET helper.
 *
 * <p>The upstream project used {@link HttpURLConnection} directly and had no
 * third-party dependencies; that constraint is preserved here deliberately, so
 * this stays a thin wrapper rather than a networking library. Spec §1 records
 * "no third-party dependencies" as a property of the fork's starting point.
 *
 * <p>Nothing in this class logs, so a header carrying a credential cannot leak
 * through it. Spec §50 items 1–3.
 */
public final class Http {

    private Http() {
    }

    /** Status code plus decoded body. */
    public static final class Response {

        private final int code;
        private final String body;

        /**
         * Public so a test can hand back a scripted status/body pair. Nothing in
         * production constructs one: only {@link Http#get} does.
         */
        public Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public int getCode() {
            return code;
        }

        /** Response body decoded as UTF-8; empty string when there was none. */
        public String getBody() {
            return body;
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }

        @Override
        public String toString() {
            return "HTTP " + code + " (" + body.length() + " chars)";
        }
    }

    /**
     * Performs a GET and returns the status code with whatever body came back,
     * including the error stream on a non-2xx response.
     *
     * <p>Unlike a naive implementation this does not throw on 4xx/5xx: the caller
     * needs the code to decide which normalised error to raise. Spec §49.
     *
     * @throws IOException when the request could not be completed at all
     */
    public static Response get(String url,
                               Map<String, String> headers,
                               int connectTimeoutMs,
                               int readTimeoutMs) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            return new Response(code, readText(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Convenience overload for a single header. */
    public static Response get(String url,
                               String headerName,
                               String headerValue,
                               int connectTimeoutMs,
                               int readTimeoutMs) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headerName != null) {
            headers.put(headerName, headerValue);
        }
        return get(url, headers, connectTimeoutMs, readTimeoutMs);
    }

    /** Reads a stream fully as UTF-8, tolerating a null stream. */
    public static String readText(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
