package com.aiusage.monitor.util;

import java.io.IOException;
import java.util.Map;

/**
 * The one network operation a Bridge data source needs, behind an interface.
 *
 * <p>{@link Http} is a static helper, and every existing provider test avoids
 * the network by implementing a fake {@code UsageProvider} instead. That works
 * for the *decision* a provider makes but not for *how it reacts to a socket*:
 * a 401, a 503 carrying a class name, and a connection refusal all have to be
 * covered where they are actually handled. This seam is small enough that the
 * production implementation is one line, and the fake transport can return
 * exactly those responses without a device or a server.
 */
public interface BridgeTransport {

    /**
     * GETs {@code url}, mirroring {@link Http#get(String, Map, int, int)} so the
     * production implementation adds nothing of its own. {@code headers} may be
     * null or empty, exactly as {@link Http} accepts it.
     */
    Http.Response get(String url,
                      Map<String, String> headers,
                      int connectTimeoutMs,
                      int readTimeoutMs) throws IOException;
}
