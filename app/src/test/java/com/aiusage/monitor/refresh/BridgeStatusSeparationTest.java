package com.aiusage.monitor.refresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.util.StatusWords;

import org.junit.Test;

/**
 * Spec §53 rule 19 and docs/PHASE-6-PLAN.md D2, asserted at the two places a user
 * actually sees: the status a failure is filed under, and the words that status
 * renders as.
 *
 * <p>The distinction was already half-built — BRIDGE_UNAUTHORIZED existed and was
 * mapped to AUTH_REQUIRED, whose wording is "API Key 无效或已失效". For a Codex
 * account that sentence names a credential the account does not have, so a Bridge
 * token failure now gets its own status and its own wording.
 */
public class BridgeStatusSeparationTest {

    @Test
    public void bridgeTokenFailureIsNotAnApiKeyFailure() {
        UsageStatus bridge = AccountRefreshManager.statusFor(UsageError.BRIDGE_UNAUTHORIZED);
        UsageStatus apiKey = AccountRefreshManager.statusFor(UsageError.INVALID_CREDENTIAL);

        assertEquals(UsageStatus.BRIDGE_AUTH_REQUIRED, bridge);
        assertEquals(UsageStatus.AUTH_REQUIRED, apiKey);
        assertNotEquals("a Codex account must not be told its API key is invalid",
                bridge, apiKey);
    }

    @Test
    public void theTwoAuthWordingsSayWhatTheyRespectivelyMean() {
        String bridgeText = StatusWords.describe(UsageStatus.BRIDGE_AUTH_REQUIRED);
        String keyText = StatusWords.describe(UsageStatus.AUTH_REQUIRED);

        assertFalse("bridge wording names an API key: " + bridgeText, bridgeText.contains("API Key"));
        assertTrue("bridge wording should mention the computer side: " + bridgeText,
                bridgeText.contains("电脑端"));
        assertTrue("the API key wording still names an API key", keyText.contains("API Key"));
    }

    /** Rule 19 end to end: three different failures, three different sentences. */
    @Test
    public void offlineExpiredAndNoConnectionRemainDistinct() {
        String offline = StatusWords.describe(AccountRefreshManager.statusFor(UsageError.BRIDGE_OFFLINE));
        String expired = StatusWords.describe(AccountRefreshManager.statusFor(UsageError.BRIDGE_UNAUTHORIZED));
        String network = StatusWords.describe(AccountRefreshManager.statusFor(UsageError.NETWORK_ERROR));

        assertNotEquals(offline, expired);
        assertNotEquals(offline, network);
        assertNotEquals(expired, network);
    }

    @Test
    public void theRetainedDataMarkerSurvivesTheNewStatus() {
        String text = StatusWords.describe(UsageStatus.BRIDGE_AUTH_REQUIRED, true);

        assertTrue("retention marker lost: " + text, text.endsWith(StatusWords.RETAINED_SUFFIX));
        assertTrue(text.startsWith("电脑端授权已失效"));
    }
}
