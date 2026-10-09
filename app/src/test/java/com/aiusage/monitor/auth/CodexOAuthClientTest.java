package com.aiusage.monitor.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.model.UsageError;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CodexOAuthClientTest {
    @Test public void pollsAtTheServerIntervalAndExchangesTheOneTimeCodeOnce() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.responses.add(new OAuthHttpTransport.Response(200,
                "{\"device_auth_id\":\"device-id\",\"user_code\":\"ABCD-EFGH\","
                        + "\"interval\":\"2\",\"expires_in\":60}"));
        transport.responses.add(new OAuthHttpTransport.Response(404, "{}"));
        transport.responses.add(new OAuthHttpTransport.Response(429, "{}",
                "Thu, 01 Jan 1970 00:00:10 GMT"));
        transport.responses.add(new OAuthHttpTransport.Response(200,
                "{\"authorization_code\":\"one-time-code\",\"code_verifier\":\"verifier\","
                        + "\"code_challenge\":\"challenge\"}"));
        transport.responses.add(new OAuthHttpTransport.Response(200,
                "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}"));
        MutableClock clock = new MutableClock();
        List<Long> delays = new ArrayList<>();
        CodexOAuthClient client = new CodexOAuthClient(transport, clock, delay -> {
            delays.add(delay);
            clock.now += delay;
        });

        CodexOAuthClient.DeviceCode code = client.requestDeviceCode();
        CodexOAuthClient.Tokens tokens = client.authorize(code, () -> false);

        assertEquals("ABCD-EFGH", code.userCode);
        assertEquals("access", tokens.accessToken);
        assertEquals("refresh", tokens.refreshToken);
        assertEquals(3, delays.size());
        assertEquals(Long.valueOf(2000L), delays.get(0));
        assertEquals(Long.valueOf(2000L), delays.get(1));
        assertEquals(Long.valueOf(6000L), delays.get(2));
        assertEquals(5, transport.urls.size());
        assertTrue(transport.urls.get(1).endsWith("/deviceauth/token"));
        assertTrue(transport.urls.get(2).endsWith("/deviceauth/token"));
        assertTrue(transport.urls.get(3).endsWith("/deviceauth/token"));
        assertTrue(transport.urls.get(4).endsWith("/oauth/token"));
        assertTrue(transport.bodies.get(4).contains("one-time-code"));
        assertEquals(1, count(transport.bodies.get(4), "one-time-code"));
    }

    @Test public void reportsWhenDeviceCodeLoginIsNotEnabled() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.responses.add(new OAuthHttpTransport.Response(404, "{}"));
        CodexOAuthClient client = new CodexOAuthClient(transport, () -> 0L, ignored -> { });
        try {
            client.requestDeviceCode();
            fail("device-code login should report the unsupported account state");
        } catch (AuthException expected) {
            assertEquals(UsageError.UNSUPPORTED, expected.getError());
            assertTrue(expected.getMessage().contains("未启用"));
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static final class MutableClock implements CodexOAuthClient.Clock {
        long now;
        @Override public long nowMs() { return now; }
    }

    private static final class ScriptedTransport implements OAuthHttpTransport {
        final List<OAuthHttpTransport.Response> responses = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        @Override public Response post(String url, String contentType, String body) throws IOException {
            urls.add(url);
            bodies.add(body);
            if (responses.isEmpty()) throw new IOException("unexpected request");
            return responses.remove(0);
        }
    }
}
