package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.usage.UsageSnapshotCodec;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResetCreditsPipelineTest {
    @Test public void overLimitWindowKeepsRawUsageAndClampsDerivedRemaining() throws Exception {
        JSONObject root = new JSONObject("{\"schemaVersion\":1,\"providerId\":\"codex\",\"status\":\"OK\","
                + "\"quotaWindows\":[{\"id\":\"weekly\",\"usedPercent\":120,\"remainingPercent\":0}]}");
        UsageResult result = BridgeUsageParser.parse(root.toString(), "account", 1);
        assertEquals(120, result.getQuotaWindows().get(0).getUsedPercent(), 0);
        assertEquals(0, result.getQuotaWindows().get(0).getRemainingPercent(), 0);
        root.getJSONArray("quotaWindows").getJSONObject(0).remove("remainingPercent");
        assertEquals(0, BridgeUsageParser.parse(root.toString(), "account", 1)
                .getQuotaWindows().get(0).getRemainingPercent(), 0);
    }

    @Test public void invalidRemainingAndNonFiniteUsageStillFail() throws Exception {
        for (String window : new String[] {"{\"usedPercent\":120,\"remainingPercent\":-1}",
                "{\"remainingPercent\":101}", "{\"usedPercent\":\"NaN\"}",
                "{\"usedPercent\":\"Infinity\"}"}) {
            String body = "{\"schemaVersion\":1,\"providerId\":\"codex\",\"status\":\"OK\","
                    + "\"quotaWindows\":[" + window + "]}";
            try {
                BridgeUsageParser.parse(body, "account", 1);
                fail("Invalid percentage was accepted");
            } catch (com.aiusage.monitor.provider.UsageException expected) {
                assertEquals(com.aiusage.monitor.model.UsageError.UNKNOWN, expected.getError());
            }
        }
    }

    private static UsageResult read(String resets) throws Exception {
        JSONObject root = new JSONObject("{\"schemaVersion\":1,\"providerId\":\"codex\",\"status\":\"OK\","
                + "\"quotaWindows\":[{\"id\":\"weekly\",\"usedPercent\":14,\"remainingPercent\":86}]}" );
        if (resets != null) root.put("rateLimitResetCredits", new JSONObject(resets));
        return BridgeUsageParser.parse(root.toString(), "account", 1);
    }
    private static UsageResult readWithStatus(String resets, String status) throws Exception {
        JSONObject root = new JSONObject("{\"schemaVersion\":1,\"providerId\":\"codex\",\"status\":\"OK\","
                + "\"quotaWindows\":[{\"id\":\"weekly\",\"usedPercent\":14,\"remainingPercent\":86}]}");
        if (resets != null) root.put("rateLimitResetCredits", new JSONObject(resets));
        if (status != null) root.put("rateLimitResetCreditsStatus", status);
        return BridgeUsageParser.parse(root.toString(), "account", 1);
    }
    private static JSONObject saved(UsageResult usage) throws Exception {
        return new JSONObject(UsageSnapshotCodec.encode(usage));
    }

    @Test public void bridgeToCacheAndStaleCopyKeepFullResetDetails() throws Exception {
        UsageResult usage = read("{\"availableCount\":3,\"credits\":[{\"resetType\":\"codexRateLimits\","
                + "\"status\":\"available\",\"grantedAt\":\"2026-10-07T20:00:06Z\","
                + "\"expiresAt\":\"2026-11-06T20:00:06Z\",\"title\":\"Full reset (Weekly + 5 hr)\"}]}" );
        JSONObject first = saved(usage);
        assertTrue("Bridge reset details were discarded", first.has("rateLimitResetCredits"));
        assertEquals("AVAILABLE", first.getString("rateLimitResetCreditsStatus"));
        UsageResult restored = UsageSnapshotCodec.decode(first.toString()).asStale().toBuilder().updatedAt(5).build();
        JSONObject resets = saved(restored).getJSONObject("rateLimitResetCredits");
        assertEquals(3, resets.getLong("availableCount"));
        assertEquals(1, resets.getJSONArray("credits").length());
        assertEquals("2026-11-06T20:00:06Z", resets.getJSONArray("credits").getJSONObject(0).getString("expiresAt"));
        assertEquals(86, restored.getQuotaWindows().get(0).getRemainingPercent(), 0);
    }

    @Test public void countOnlyDoesNotFabricateCardDetails() throws Exception {
        JSONObject cached = saved(read("{\"availableCount\":2,\"credits\":null}"));
        assertTrue("count-only reset credits were discarded", cached.has("rateLimitResetCredits"));
        JSONObject resets = cached.getJSONObject("rateLimitResetCredits");
        assertEquals(2, resets.getLong("availableCount"));
        assertTrue(resets.isNull("credits"));
    }

    @Test public void oldBridgeAndInvalidOptionalCountsPreserveOrdinaryQuota() throws Exception {
        for (String payload : new String[] {null, "{}", "{\"availableCount\":-1}", "{\"availableCount\":1.5}", "{\"availableCount\":\"1\"}"}) {
            UsageResult usage = read(payload);
            assertTrue(saved(usage).isNull("rateLimitResetCredits"));
            assertEquals(1, usage.getQuotaWindows().size());
        }
    }

    @Test public void diagnosticStatusDistinguishesCurrentAndLegacyBridgeResponses() throws Exception {
        assertEquals("LEGACY_BRIDGE", read(null).getLimitResetCreditsStatus().name());
        assertEquals("AVAILABLE", read("{\"availableCount\":0,\"credits\":[]}")
                .getLimitResetCreditsStatus().name());
        assertEquals("NOT_RETURNED", readWithStatus(null, "NOT_RETURNED").getLimitResetCreditsStatus().name());
        assertEquals("INVALID_FORMAT", readWithStatus(null, "INVALID_FORMAT").getLimitResetCreditsStatus().name());
        assertEquals("INVALID_FORMAT", read("{\"availableCount\":\"1\"}")
                .getLimitResetCreditsStatus().name());

        UsageResult restored = UsageSnapshotCodec.decode(saved(read(null)).toString());
        assertEquals("LEGACY_BRIDGE", restored.getLimitResetCreditsStatus().name());
    }
    @Test public void retainedQuotaAfterUpstreamFailureIsNotReportedAsNewSuccess() throws Exception {
        JSONObject root = new JSONObject("{\"schemaVersion\":1,\"providerId\":\"codex\",\"status\":\"OK\","
                + "\"isStale\":false,\"errorCode\":\"UPSTREAM_UNAVAILABLE\","
                + "\"updatedAt\":\"2026-10-09T09:10:00Z\",\"dataTimestamp\":\"2026-10-09T09:00:00Z\","
                + "\"quotaWindows\":[{\"id\":\"weekly\",\"remainingPercent\":86}],"
                + "\"rateLimitResetCredits\":{\"availableCount\":2,\"credits\":null}}");
        UsageResult usage = BridgeUsageParser.parse(root.toString(), "account", 1);
        assertEquals(com.aiusage.monitor.model.UsageStatus.STALE, usage.getStatus());
        assertEquals(java.time.Instant.parse("2026-10-09T09:00:00Z").toEpochMilli(), usage.getUpdatedAt());
        assertEquals(2, UsageSnapshotCodec.decode(UsageSnapshotCodec.encode(usage)).getLimitResetCredits().getAvailableCount());
        root.remove("errorCode");
        assertEquals(com.aiusage.monitor.model.UsageStatus.OK, BridgeUsageParser.parse(root.toString(), "account", 1).getStatus());
    }
}
