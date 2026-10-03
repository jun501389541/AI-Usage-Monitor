package com.aiusage.monitor.util;

import java.io.IOException;
import java.util.Map;

/**
 * Production {@link BridgeTransport}: a direct hand-off to {@link Http}.
 *
 * <p>The delegation is the point - there is no second HTTP stack here, and
 * {@link Http} does not log, which is what keeps a device token out of logcat
 * (Spec §50 items 1-3).
 */
public final class HttpBridgeTransport implements BridgeTransport {

    @Override
    public Http.Response get(String url,
                             Map<String, String> headers,
                             int connectTimeoutMs,
                             int readTimeoutMs) throws IOException {
        return Http.get(url, headers, connectTimeoutMs, readTimeoutMs);
    }
}
