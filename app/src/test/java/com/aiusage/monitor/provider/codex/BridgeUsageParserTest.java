package com.aiusage.monitor.provider.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.UsageException;

import org.junit.Test;

import java.util.List;

/**
 * The Phase 6 mapping rules, tested on the payload shape the Bridge actually
 * produces. The golden document below is the response recorded from a live
 * account in docs/PHASE-5-BRIDGE-FEASIBILITY.md §2.4 (percentages changed
 * between runs, so only presence and types are asserted, never a value that
 * depends on when the reading was taken).
 */
public class BridgeUsageParserTest {

    private static final String PHASE5_SHAPE = "{\"state\":{\"codexVersion\":\"0.121.0\","
            + "\"planType\":\"plus\",\"creditsBalance\":\"0\",\"windows\":["
            + "{\"id\":\"codex:300\",\"label\":\"5 小时\",\"usedPercent\":31,\"remainingPercent\":69,"
            + "\"windowMinutes\":300,\"resetAtMillis\":1790955639000,\"kind\":\"primary\"},"
            + "{\"id\":\"codex:10080\",\"label\":\"7 天\",\"usedPercent\":49,\"remainingPercent\":51,"
            + "\"windowMinutes\":10080,\"resetAtMillis\":1791419453000,\"kind\":\"secondary\"}],"
            + "\"sourceFetchedAt\":\"2026-10-02T19:31:25.615Z\"},"
            + "\"source\":\"codex\",\"dataTimestamp\":\"2026-10-02T19:31:25.615Z\","
            + "\"sourceTimestamp\":\"2026-10-02T19:31:25.615Z\",\"fromCache\":false}";

    private static QuotaWindow windowByLabel(UsageResult result, String label) {
        for (QuotaWindow window : result.getQuotaWindows()) {
            if (label.equals(window.getLabel())) {
                return window;
            }
        }
        fail("no window labelled " + label + " in " + result.getQuotaWindows());
        return null;
    }

    @Test
    public void readsBothWindowsAndKeepsTheirIdentity() throws Exception {
        UsageResult result = BridgeUsageParser.parse(PHASE5_SHAPE, "acct-1", 1000L);

        assertEquals("acct-1", result.getAccountId());
        assertEquals(BridgeUsageParser.PROVIDER_ID, result.getProviderId());
        assertEquals(UsageStatus.OK, result.getStatus());
        assertEquals(UsageResult.Source.BRIDGE, result.getSource());

        List<QuotaWindow> windows = result.getQuotaWindows();
        assertEquals(2, windows.size());

        QuotaWindow five = windowByLabel(result, "5 小时");
        assertEquals("codex:300", five.getId());
        assertEquals(300L, five.getWindowMinutes());
        // The Bridge already converts Codex's epoch seconds to milliseconds; the
        // app must not convert again.
        assertEquals(1790955639000L, five.getResetAt());
        assertTrue(five.getUsedPercent() > 0d && five.getUsedPercent() < 100d);
        assertEquals(100d, five.getUsedPercent() + five.getRemainingPercent(), 0.001d);

        QuotaWindow week = windowByLabel(result, "7 天");
        assertEquals(10080L, week.getWindowMinutes());
    }

    /**
     * A Codex account has no money in it. Silence here is the correct result, and
     * the reason the class exists as a separate type: a zero Balance would render
     * as "spent nothing".
     */
    @Test
    public void reportsNoBalanceAtAll() throws Exception {
        UsageResult result = BridgeUsageParser.parse(PHASE5_SHAPE, "acct-1", 1000L);
        assertNull(result.getBalance());
    }

    @Test
    public void marksTheAccountAvailableBecauseTheBridgeAnswered() throws Exception {
        UsageResult result = BridgeUsageParser.parse(PHASE5_SHAPE, "acct-1", 1000L);
        assertNotNull(result.findMetric(UsageResult.METRIC_ACCOUNT_AVAILABLE));
        assertEquals(1d, result.findMetric(UsageResult.METRIC_ACCOUNT_AVAILABLE).getValue(), 0.001d);
    }

    /**
     * Spec §53 rule 18: a failed refresh must not take the numbers off the screen.
     * The Bridge implements that by answering 200 with old windows plus a
     * {@code degraded} marker, so the app reads it as stale-but-present.
     */
    @Test
    public void degradedReadingStaysUsableAndIsMarkedStale() throws Exception {
        String withDegraded = PHASE5_SHAPE.replace("\"fromCache\":false}",
                "\"fromCache\":true,\"degraded\":{\"class\":\"CODEX_NOT_FOUND\","
                        + "\"message\":\"no Codex\",\"at\":\"2026-10-02T20:00:00Z\"}}");

        UsageResult result = BridgeUsageParser.parse(withDegraded, "acct-1", 2000L);

        assertEquals(UsageStatus.STALE, result.getStatus());
        assertEquals(2, result.getQuotaWindows().size());
    }

    @Test
    public void derivesRemainingPercentWhenTheBridgeOmitsIt() throws Exception {
        String body = "{\"state\":{\"windows\":[{\"id\":\"codex:60\",\"label\":\"1 小时\","
                + "\"usedPercent\":40,\"windowMinutes\":60}]}}";
        UsageResult result = BridgeUsageParser.parse(body, "a", 1L);

        QuotaWindow window = result.getQuotaWindows().get(0);
        assertEquals(60d, window.getRemainingPercent(), 0.001d);
        assertEquals(0L, window.getResetAt());
    }

    /**
     * A window without usedPercent has nothing to show. Inventing 0 would read as
     * "nothing used yet", which is a statement about the account, not about the
     * payload - the same rule the Bridge's own parser applies.
     */
    @Test
    public void skipsWindowsWithoutAPercentage() throws Exception {
        String body = "{\"state\":{\"windows\":["
                + "{\"id\":\"a\",\"label\":\"甲\",\"windowMinutes\":300},"
                + "{\"id\":\"b\",\"label\":\"乙\",\"usedPercent\":12,\"windowMinutes\":10080}]}}";
        UsageResult result = BridgeUsageParser.parse(body, "a", 1L);

        assertEquals(1, result.getQuotaWindows().size());
        assertEquals("b", result.getQuotaWindows().get(0).getId());
    }

    @Test
    public void refusesToReportAnEmptyReadingAsSuccess() {
        String[] bodies = {
                "{\"state\":{\"windows\":[]}}",
                "{\"state\":{}}",
                "{\"state\":{\"windows\":[{\"id\":\"a\",\"label\":\"甲\"}]}}",
        };
        for (String body : bodies) {
            try {
                BridgeUsageParser.parse(body, "a", 1L);
                fail("expected a failure for " + body);
            } catch (UsageException expected) {
                assertEquals(UsageError.UNKNOWN, expected.getError());
            }
        }
    }

    @Test
    public void rejectsDocumentsItCannotTrust() {
        assertParseFails("", UsageError.UNKNOWN);
        assertParseFails(null, UsageError.UNKNOWN);
        assertParseFails("not json at all", UsageError.UNKNOWN);
        assertParseFails("{\"source\":\"codex\"}", UsageError.UNKNOWN);
    }

    private static void assertParseFails(String body, UsageError expected) {
        try {
            BridgeUsageParser.parse(body, "a", 1L);
            fail("expected UsageException for: " + body);
        } catch (UsageException exception) {
            assertEquals(expected, exception.getError());
        }
    }
}
