package com.aiusage.monitor.auth;

import java.io.IOException;

/** Small POST seam for OAuth protocol tests; implementations must not log bodies. */
public interface OAuthHttpTransport {
    Response post(String url, String contentType, String body) throws IOException;

    final class Response {
        private final int code;
        private final String body;
        private final String retryAfter;
        public Response(int code, String body) { this(code, body, ""); }
        public Response(int code, String body, String retryAfter) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.retryAfter = retryAfter == null ? "" : retryAfter;
        }
        public int getCode() { return code; }
        public String getBody() { return body; }
        public String getRetryAfter() { return retryAfter; }
        public boolean isSuccess() { return code >= 200 && code < 300; }
    }
}
