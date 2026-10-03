package com.aiusage.monitor;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 0 harness probe.
 *
 * <p>Proves that the host-side JVM unit test harness actually runs, so later
 * phases can rely on it for pure-logic tests ({@code UsageMath},
 * {@code PeakTimeRules}, {@code AmountFormatter}). It deliberately asserts
 * nothing about the app; its only job is to fail loudly if the harness is
 * broken.
 */
public class HarnessSmokeTest {

    @Test
    public void jvmTestHarnessRuns() {
        assertTrue("host JVM test harness is alive", true);
    }

    @Test
    public void jvmRunsWithExpectedIntegerSemantics() {
        // A trivial arithmetic check that would break under a broken toolchain.
        int expected = 38;
        assertTrue(2 + 36 == expected);
    }
}
