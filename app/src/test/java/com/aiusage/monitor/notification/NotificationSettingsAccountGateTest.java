package com.aiusage.monitor.notification;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the Codex account-level notification gate. */
public final class NotificationSettingsAccountGateTest {
    @Test public void accountSwitchGatesBothFollowAndExplicitCategoryOverrides() {
        assertFalse(NotificationSettings.effectiveCodexEnabled(true, false, true, "follow"));
        assertFalse(NotificationSettings.effectiveCodexEnabled(true, false, false, "on"));
    }

    @Test public void enabledAccountKeepsExistingGlobalAndCategoryOverrideRules() {
        assertTrue(NotificationSettings.effectiveCodexEnabled(true, true, true, "follow"));
        assertFalse(NotificationSettings.effectiveCodexEnabled(true, true, false, "follow"));
        assertTrue(NotificationSettings.effectiveCodexEnabled(true, true, false, "on"));
        assertFalse(NotificationSettings.effectiveCodexEnabled(true, true, true, "off"));
        assertFalse(NotificationSettings.effectiveCodexEnabled(false, true, true, "on"));
    }

    @Test public void accountSwitchDefaultsOnAndReenableDoesNotBackfillOldReset() {
        MapStore store = new MapStore();
        NotificationSettings settings = new NotificationSettings(store);
        String first = "codex-one";
        String second = "codex-two";
        settings.setEnabled(true, 100L);

        assertTrue(settings.isCodexAccountEnabled(first));
        assertTrue(settings.isCodexAccountEnabled(second));
        assertTrue(settings.isCodexEnabled(first, NotificationSettings.CODEX_FIVE_HOUR));
        settings.setCodexAccountEnabled(first, false, 200L);
        assertFalse(settings.isCodexEnabled(first, NotificationSettings.CODEX_FIVE_HOUR));
        assertTrue(settings.isCodexEnabled(second, NotificationSettings.CODEX_FIVE_HOUR));

        settings.setCodexAccountEnabled(first, true, 400L);
        assertTrue(settings.isCodexEnabled(first, NotificationSettings.CODEX_FIVE_HOUR));
        assertEquals(400L, settings.codexEnabledSince(first, NotificationSettings.CODEX_FIVE_HOUR));
        assertFalse(NotificationDeliveryPolicy.shouldDeliver(300L,
                settings.codexEnabledSince(first, NotificationSettings.CODEX_FIVE_HOUR), 0L, 500L));
    }

    private static final class MapStore implements NotificationSettings.Store {
        private final Map<String, Long> longs = new HashMap<>();
        private final Map<String, String> strings = new HashMap<>();

        @Override public long getLong(String key, long fallback) {
            return longs.containsKey(key) ? longs.get(key) : fallback;
        }
        @Override public void setLong(String key, long value) { longs.put(key, value); }
        @Override public String getString(String key, String fallback) {
            return strings.containsKey(key) ? strings.get(key) : fallback;
        }
        @Override public void setString(String key, String value) { strings.put(key, value); }
    }
}
