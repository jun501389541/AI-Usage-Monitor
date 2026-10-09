package com.aiusage.monitor.notification;

import com.aiusage.monitor.model.QuotaWindow;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CodexResetPlannerTest {
    @Test public void groupsKnownWindowsWithinSameMinuteAtLaterTimestamp() {
        List<CodexResetPlanner.Event> events = CodexResetPlanner.events(Arrays.asList(
                window("5h", 300, 12_345_000L),
                window("weekly", 10080, 12_354_000L),
                window("unknown", 1440, 12_345_000L)));
        assertEquals(1, events.size());
        assertEquals(12_354_000L, events.get(0).getAtMillis());
        assertTrue(events.get(0).hasFiveHour());
        assertTrue(events.get(0).hasWeekly());
    }

    @Test public void keepsDifferentResetMinutesSeparateAndIgnoresUnknownTimes() {
        List<CodexResetPlanner.Event> events = CodexResetPlanner.events(Arrays.asList(
                window("5h", 300, 12_000_000L),
                window("weekly", 10080, 12_060_000L),
                window("unknown", 0, 12_000_000L),
                window("unknown-reset", 300, 0L)));
        assertEquals(2, events.size());
        assertTrue(events.get(0).hasFiveHour());
        assertFalse(events.get(0).hasWeekly());
        assertTrue(events.get(1).hasWeekly());
        assertFalse(events.get(1).hasFiveHour());
    }

    @Test public void emptyWindowsProduceNoEvents() {
        assertTrue(CodexResetPlanner.events(Collections.<QuotaWindow>emptyList()).isEmpty());
    }

    private static QuotaWindow window(String id, long minutes, long resetAt) {
        return new QuotaWindow(id, id, 50, 50, minutes, resetAt);
    }
}
