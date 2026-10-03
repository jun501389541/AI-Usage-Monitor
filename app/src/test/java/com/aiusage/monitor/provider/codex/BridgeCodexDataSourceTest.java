package com.aiusage.monitor.provider.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.Http;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How the Bridge's answers become the app's failure categories - tested with a
 * fake transport rather than a device, because a 401, a 503-with-a-class and a
 * refused connection are three different code paths that a single
 * "provider returns an error" test would flatten into one.
 */
public class BridgeCodexDataSourceTest {

    private static final String BODY_OK = "{\"state\":{\"windows\":["
            + "{\"id\":\"codex:300\",\"label\":\"5 小时\",\"usedPercent\":20,\"remainingPercent\":80,"
            + "\"windowMinutes\":300,\"resetAtMillis\":1790955639000}]},"
            + "\"source\":\"codex\"}";

    /** One fetch call; declared so the helpers can see the checked exception. */
    private interface Call {
        UsageResult run() throws UsageException;
    }

    private static final class Recorded {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
    }

    private static final class FakeTransport implements BridgeTransport {
        final Recorded recorded = new Recorded();
        int calls;
        Http.Response response = new Http.Response(200, BODY_OK);
        IOException failure;

        @Override
        public Http.Response get(String url,
                                 Map<String, String> headers,
                                 int connectTimeoutMs,
                                 int readTimeoutMs) throws IOException {
            calls++;
            recorded.urls.add(url);
            recorded.headers.add(headers);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static Http.Response response(int code, String body) {
        return new Http.Response(code, body);
    }

    /** Runs a fetch that must fail, and returns which normalised error it raised. */
    private static UsageError errorOf(Call call) {
        try {
            call.run();
        } catch (UsageException exception) {
            return exception.getError();
        }
        fail("expected a UsageException");
        return null;
    }

    /** Runs a fetch that may succeed; used when the assertion is about the request. */
    private static void runQuietly(Call call) {
        try {
            call.run();
        } catch (UsageException exception) {
            fail("unexpected failure: " + exception.getMessage());
        }
    }

    @Test
    public void buildsTheUsageUrlAndSendsTheTokenAsHeader() {
        FakeTransport transport = new FakeTransport();
        final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

        runQuietly(() -> source.fetch("http://10.0.2.2:38411/", "tok-abc", "acct-9", 5L));

        assertEquals(1, transport.calls);
        assertEquals("http://10.0.2.2:38411/v1/accounts/codex/usage",
                transport.recorded.urls.get(0));
        assertEquals("Bearer tok-abc", transport.recorded.headers.get(0).get("Authorization"));
    }

    /**
     * The Phase 5 Bridge checks no token at all. Sending an empty Bearer header
     * would claim a credential the user never typed, and would make "no token
     * configured" indistinguishable from "token configured but blank".
     */
    @Test
    public void sendsNoHeaderWhenNoTokenIsConfigured() {
        FakeTransport transport = new FakeTransport();
        final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

        UsageResult result = null;
        try {
            result = source.fetch("http://127.0.0.1:38411", "", "a", 1L);
        } catch (UsageException exception) {
            fail("unexpected failure: " + exception.getMessage());
        }

        assertNotNull(result);
        Map<String, String> sent = transport.recorded.headers.get(0);
        assertTrue("expected no Authorization header, got " + sent,
                sent == null || sent.get("Authorization") == null);
    }

    @Test
    public void unauthorizedAndForbiddenBothMeanTheTokenWasRefused() {
        for (int code : new int[]{401, 403}) {
            FakeTransport transport = new FakeTransport();
            transport.response = response(code, "{\"error\":\"unauthorized\"}");
            final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

            final int status = code;
            assertEquals("HTTP " + code, UsageError.BRIDGE_UNAUTHORIZED,
                    errorOf(() -> source.fetch("http://h:1", "t", "a", status == 401 ? 1L : 2L)));
        }
    }

    /**
     * Spec §53 rule 19. The Bridge names its failure class in the 503 body, so
     * "the computer is offline" and "the authorisation expired" must not arrive at
     * the UI as the same thing.
     */
    @Test
    public void mapsEachBridgeClassToItsOwnFailure() {
        assertEquals(UsageError.BRIDGE_OFFLINE, BridgeCodexDataSource.classToError("CODEX_NOT_FOUND"));
        assertEquals(UsageError.BRIDGE_OFFLINE, BridgeCodexDataSource.classToError("CODEX_TIMEOUT"));
        assertEquals(UsageError.BRIDGE_UNAUTHORIZED, BridgeCodexDataSource.classToError("CODEX_AUTH_REQUIRED"));
        assertEquals(UsageError.UNSUPPORTED, BridgeCodexDataSource.classToError("CODEX_METHOD_UNAVAILABLE"));
        assertEquals(UsageError.UNKNOWN, BridgeCodexDataSource.classToError("CODEX_UNKNOWN"));
        assertEquals(UsageError.UNKNOWN, BridgeCodexDataSource.classToError(""));
        assertEquals(UsageError.UNKNOWN, BridgeCodexDataSource.classToError(null));
    }

    @Test
    public void readsTheClassOutOfAServiceUnavailableBody() {
        FakeTransport transport = new FakeTransport();
        transport.response = response(503, "{\"error\":\"CODEX_AUTH_REQUIRED\",\"detail\":\"x\"}");
        final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

        assertEquals(UsageError.BRIDGE_UNAUTHORIZED,
                errorOf(() -> source.fetch("http://h:1", "t", "a", 1L)));
    }

    /** A 503 whose body cannot be read is still "the Bridge answered", not "no route". */
    @Test
    public void unreadableErrorBodyIsNotReportedAsOffline() {
        FakeTransport transport = new FakeTransport();
        transport.response = response(503, "gateway said no");
        final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

        assertEquals(UsageError.UNKNOWN, errorOf(() -> source.fetch("http://h:1", "t", "a", 1L)));
    }

    @Test
    public void connectionFailureIsTheOfflineCase() {
        FakeTransport transport = new FakeTransport();
        transport.failure = new IOException("connect failed: ECONNREFUSED");
        final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

        UsageException thrown = null;
        try {
            source.fetch("http://10.0.2.2:38411", "t", "a", 1L);
            fail("expected UsageException");
        } catch (UsageException exception) {
            thrown = exception;
        }
        assertEquals(UsageError.BRIDGE_OFFLINE, thrown.getError());
        assertNotNull("the transport cause must survive", thrown.getCause());
    }

    @Test
    public void offlineAndUnauthorizedAreNotTheSameCategory() {
        final FakeTransport offline = new FakeTransport();
        offline.failure = new IOException("no route to host");
        final FakeTransport refused = new FakeTransport();
        refused.response = response(401, "{}");

        UsageError a = errorOf(() -> new BridgeCodexDataSource(offline).fetch("http://h:1", "t", "a", 1L));
        UsageError b = errorOf(() -> new BridgeCodexDataSource(refused).fetch("http://h:1", "t", "a", 1L));

        assertFalse("rule 19: these two must stay distinguishable", a == b);
    }

    /**
     * A malformed address is the user's configuration mistake, and fixing it needs
     * no socket: the transport must not be touched at all.
     */
    @Test
    public void rejectsBadAddressesWithoutTouchingTheNetwork() {
        for (String base : new String[]{"", "   ", "10.0.2.2:38411", "ftp://h"}) {
            FakeTransport transport = new FakeTransport();
            final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);
            final String value = base;
            assertEquals("for '" + base + "'", UsageError.UNSUPPORTED,
                    errorOf(() -> source.fetch(value, "t", "a", 1L)));
            assertEquals("network was reached for '" + base + "'", 0, transport.calls);
        }
    }

    @Test
    public void aTwoHundredWithGarbageIsNotSilentlyAccepted() {
        FakeTransport transport = new FakeTransport();
        transport.response = response(200, "<html>proxy intercepted us</html>");
        final BridgeCodexDataSource source = new BridgeCodexDataSource(transport);

        assertEquals(UsageError.UNKNOWN, errorOf(() -> source.fetch("http://h:1", "t", "a", 1L)));
    }

    /**
     * Spec §50 items 1-3: a credential must not ride along in message text, which
     * is what ends up in a log or on screen.
     */
    @Test
    public void failureMessagesNeverCarryTheToken() {
        FakeTransport refusedTransport = new FakeTransport();
        refusedTransport.response = response(401, "{}");
        FakeTransport offlineTransport = new FakeTransport();
        offlineTransport.failure = new IOException("connect refused");

        final BridgeCodexDataSource refused = new BridgeCodexDataSource(refusedTransport);
        final BridgeCodexDataSource offline = new BridgeCodexDataSource(offlineTransport);

        for (final BridgeCodexDataSource source :
                new BridgeCodexDataSource[]{refused, offline}) {
            UsageException thrown = null;
            try {
                source.fetch("http://10.0.2.2:38411", "SUPER-SECRET-TOKEN", "a", 1L);
                fail("expected UsageException");
            } catch (UsageException exception) {
                thrown = exception;
            }
            String message = String.valueOf(thrown.getMessage()) + " | cause=" + thrown.getCause();
            assertFalse("token leaked: " + message, message.contains("SUPER-SECRET-TOKEN"));
        }
    }
}
