package com.aiusage.monitor.refresh;

import static org.junit.Assert.assertEquals;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.UsageResult;

import org.junit.Test;

/**
 * Which channel a failure snapshot is filed under.
 *
 * <p>recordFailure() used to write DIRECT_API for every provider, so a Codex
 * failure landed in the same history the UI and the retention policy read,
 * labelled as a DeepSeek reading. The source of a snapshot is how later code
 * tells those apart, so it is asserted here as a pure rule and again on a device
 * (docs/PHASE-6-PLAN.md C4b) where the stored row can be inspected.
 */
public class FailureSnapshotSourceTest {

    @Test
    public void bridgeAccountsRecordBridgeFailures() {
        assertEquals(UsageResult.Source.BRIDGE, AccountRefreshManager.sourceFor(AuthType.BRIDGE_TOKEN));
    }

    @Test
    public void everythingElseRecordsTheDirectApiSourceItAlwaysUsed() {
        assertEquals(UsageResult.Source.DIRECT_API, AccountRefreshManager.sourceFor(AuthType.API_KEY));
        assertEquals(UsageResult.Source.DIRECT_API, AccountRefreshManager.sourceFor(AuthType.OAUTH));
        // No mechanism must default to BRIDGE: a mislabelled row is the bug this
        // method replaced, so an unknown type staying on the old path is the safe
        // answer rather than a new mistake.
        assertEquals(UsageResult.Source.DIRECT_API, AccountRefreshManager.sourceFor(null));
    }
}
