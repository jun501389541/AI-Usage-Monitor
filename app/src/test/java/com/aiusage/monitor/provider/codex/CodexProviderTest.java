package com.aiusage.monitor.provider.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.Http;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The Codex provider's declared abilities and its behaviour on a real fetch,
 * driven through a fake transport. The point of declaring capabilities is that
 * the UI reads them instead of assuming, so "no balance" has to be a fact the
 * provider states - otherwise every screen would need to know Codex by name.
 */
public class CodexProviderTest {

    private static final String BODY = "{\"state\":{\"windows\":["
            + "{\"id\":\"codex:300\",\"label\":\"5 小时\",\"usedPercent\":25,\"remainingPercent\":75,"
            + "\"windowMinutes\":300,\"resetAtMillis\":1790955639000},"
            + "{\"id\":\"codex:10080\",\"label\":\"7 天\",\"usedPercent\":50,\"remainingPercent\":50,"
            + "\"windowMinutes\":10080,\"resetAtMillis\":1791419453000}]},"
            + "\"source\":\"codex\"}";

    private static final class FakeTransport implements BridgeTransport {
        int calls;
        Http.Response response = new Http.Response(200, BODY);

        @Override
        public Http.Response get(String url,
                                 Map<String, String> headers,
                                 int connectTimeoutMs,
                                 int readTimeoutMs) {
            calls++;
            return response;
        }
    }

    private static Account codexAccount() {
        return Account.builder()
                .id("acct-codex")
                .providerId(CodexProvider.ID)
                .displayName("我的 Codex")
                .authType(AuthType.BRIDGE_TOKEN)
                .build();
    }

    private static AuthContext context(String url, String token) {
        Map<String, String> values = new HashMap<>();
        if (url != null) {
            values.put(AuthContext.KEY_BRIDGE_URL, url);
        }
        if (token != null) {
            values.put(AuthContext.KEY_DEVICE_TOKEN, token);
        }
        return AuthContext.of(AuthType.BRIDGE_TOKEN, values);
    }

    @Test
    public void declaresNoBalanceAndQuotaWindows() {
        ProviderCapabilities capabilities = new CodexProvider().getCapabilities();

        assertFalse("Codex reports no money; a UI that assumes one will render 0",
                capabilities.reportsBalance());
        assertTrue(capabilities.reportsQuotaWindows());
        assertEquals(5 * 60 * 1000L, capabilities.getRecommendedRefreshIntervalMs());
        assertTrue(capabilities.supportsAuthType(AuthType.BRIDGE_TOKEN));
        assertFalse("an API key cannot reach Codex",
                capabilities.supportsAuthType(AuthType.API_KEY));
        assertEquals(1, capabilities.getSupportedAuthTypes().size());
    }

    @Test
    public void offersNoWidgetMetricsYet() {
        // D4 kept percentages out of the widget in Phase 6: a metric id needs a
        // metric_ids migration and a per-slot picker. Declaring none is the
        // honest state, and widget code that asks "balance?" gets an answer
        // rather than a crash.
        ProviderCapabilities capabilities = new CodexProvider().getCapabilities();

        assertFalse(capabilities.supportsWidgetMetric("balance"));
        assertFalse(capabilities.supportsWidgetMetric("today_usage"));
    }

    @Test
    public void carriesThePlatformIdentityTheBridgeReports() {
        CodexProvider provider = new CodexProvider();
        assertEquals("codex", provider.getId());
        assertEquals("OpenAI Codex", provider.getName());
    }

    @Test
    public void fetchReturnsTheBridgeWindowsUnderThisAccountId() throws Exception {
        FakeTransport transport = new FakeTransport();
        UsageResult result = new CodexProvider(transport)
                .fetchUsage(codexAccount(), context("http://10.0.2.2:38411", "t"));

        assertEquals(1, transport.calls);
        assertEquals("acct-codex", result.getAccountId());
        assertEquals(CodexProvider.ID, result.getProviderId());
        assertEquals(2, result.getQuotaWindows().size());
        assertNull(result.getBalance());
        assertEquals(UsageResult.Source.BRIDGE, result.getSource());
    }

    /**
     * A Codex account reached by API key is a configuration that cannot exist -
     * answering it with a network call would be guessing at what the user meant.
     */
    /**
     * The fixture deliberately carries a usable Bridge url and token alongside an
     * API_KEY mechanism: if the mechanism check were removed, the request would go
     * out and the test would still see UNSUPPORTED from the next check, which is
     * how this test was proven to actually cover the guard.
     */
    @Test
    public void refusesAnyMechanismOtherThanTheBridge() {
        FakeTransport transport = new FakeTransport();
        Map<String, String> values = new HashMap<>();
        values.put(AuthContext.KEY_API_KEY, "sk-x");
        values.put(AuthContext.KEY_BRIDGE_URL, "http://10.0.2.2:38411");
        values.put(AuthContext.KEY_DEVICE_TOKEN, "t");
        AuthContext apiKey = AuthContext.of(AuthType.API_KEY, values);

        try {
            new CodexProvider(transport).fetchUsage(codexAccount(), apiKey);
            fail("expected UsageException");
        } catch (UsageException exception) {
            assertEquals(UsageError.UNSUPPORTED, exception.getError());
        }
        assertEquals("no request should have been attempted", 0, transport.calls);
    }

    /**
     * An account with no address must fail without reaching any host. The check
     * lives in the data source (one owner), and what this proves about the
     * provider is that it forwards whatever it has rather than substituting a
     * default machine.
     */
    @Test
    public void anAccountWithoutAnAddressIsNotFetchedAgainstSomethingElse() {
        for (String url : new String[]{null, "", "   ", "10.0.2.2:38411"}) {
            FakeTransport transport = new FakeTransport();
            try {
                new CodexProvider(transport).fetchUsage(codexAccount(), context(url, "t"));
                fail("expected UsageException for url " + url);
            } catch (UsageException exception) {
                assertEquals(UsageError.UNSUPPORTED, exception.getError());
            }
            assertEquals("transport was used for url '" + url + "'", 0, transport.calls);
        }
    }

    /**
     * Wiring is asserted by reading the registry source, the same way
     * DeepSeekProviderStabilityTest pins architecture: the registry is a
     * process-wide singleton whose built-ins are not idempotent, so "proving"
     * registration by calling it would depend on which test ran first.
     */
    @Test
    public void registryShipsTheCodexProvider() throws IOException {
        String source = readRepoFile("app/src/main/java/com/aiusage/monitor/provider/ProviderRegistry.java");
        assertTrue("registerBuiltIns() must register CodexProvider, or the platform "
                        + "exists in source but not in any running app",
                source.contains("codex.CodexProvider()"));
    }

    @Test
    public void bridgeTransportIsTheOnlyNetworkPath() throws IOException {
        String source = readRepoFile(
                "app/src/main/java/com/aiusage/monitor/provider/codex/CodexProvider.java");
        assertFalse("the provider must not open sockets of its own",
                source.contains("HttpURLConnection"));
        assertTrue("the provider must read through the injected transport",
                source.contains("BridgeTransport"));
    }

    private static String readRepoFile(String relativePath) throws IOException {
        File fromRoot = new File(System.getProperty("user.dir"), relativePath);
        File fromModule = new File(System.getProperty("user.dir"),
                relativePath.startsWith("app/") ? relativePath.substring("app/".length()) : relativePath);
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;
        assertTrue("cannot find " + relativePath + " from " + System.getProperty("user.dir")
                        + " - this guard would scan nothing",
                candidate.isFile());
        String text = new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
        assertTrue("scanned the wrong file: " + candidate, text.contains("package com.aiusage.monitor"));
        return text;
    }
}
