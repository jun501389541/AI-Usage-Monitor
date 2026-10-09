package com.aiusage.monitor.provider.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.aiusage.monitor.model.UsageResult;

import org.json.JSONObject;
import org.junit.Test;

public final class CodexDirectUsageParserTest {
    @Test public void readsProviderWindowNamesAndLeavesMissingValuesUnknown() throws Exception {
        JSONObject body = new JSONObject("{\"rate_limit\":{\"primary_window\":{"
                + "\"used_percent\":37.5,\"limit_window_seconds\":900,\"reset_at\":2000},"
                + "\"secondary_window\":{\"used_percent\":12}}}");

        UsageResult result = CodexDirectUsageParser.parse(body, "acct", 1000L);

        assertEquals(2, result.getQuotaWindows().size());
        assertEquals(37.5, result.findQuotaWindow("primary_window").getUsedPercent(), 0.001);
        assertEquals(900L, result.findQuotaWindow("primary_window").getWindowMinutes() * 60L);
        assertEquals(2_000_000L, result.findQuotaWindow("primary_window").getResetAt());
        assertEquals(0L, result.findQuotaWindow("secondary_window").getWindowMinutes());
        assertEquals(0L, result.findQuotaWindow("secondary_window").getResetAt());
    }

    @Test public void absentWindowsStayAbsent() throws Exception {
        UsageResult result = CodexDirectUsageParser.parse(new JSONObject("{}"), "acct", 1000L);
        assertEquals(0, result.getQuotaWindows().size());
    }
}
