package com.aiusage.monitor.notification;

import com.aiusage.monitor.model.QuotaWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds reset events only from explicitly reported Codex window lengths and reset times. */
public final class CodexResetPlanner {
    public static final String FIVE_HOUR = "five_hour";
    public static final String WEEKLY = "weekly";

    private CodexResetPlanner() { }

    public static List<Event> events(List<QuotaWindow> windows) {
        Map<Long, MutableEvent> byMinute = new TreeMap<>();
        if (windows != null) {
            for (QuotaWindow window : windows) {
                if (window == null || window.getResetAt() <= 0) continue;
                String category;
                if (window.getWindowMinutes() == 300) category = FIVE_HOUR;
                else if (window.getWindowMinutes() == 10080) category = WEEKLY;
                else continue;

                long minute = window.getResetAt() / 60_000L;
                MutableEvent event = byMinute.get(minute);
                if (event == null) {
                    event = new MutableEvent(minute * 60_000L);
                    byMinute.put(minute, event);
                }
                event.atMillis = Math.max(event.atMillis, window.getResetAt());
                if (FIVE_HOUR.equals(category)) event.fiveHour = true;
                else event.weekly = true;
            }
        }

        List<Event> result = new ArrayList<>();
        for (MutableEvent event : byMinute.values()) {
            result.add(new Event(event.atMillis, event.fiveHour, event.weekly));
        }
        return result;
    }

    private static final class MutableEvent {
        long atMillis;
        boolean fiveHour;
        boolean weekly;
        MutableEvent(long atMillis) { this.atMillis = atMillis; }
    }

    public static final class Event {
        private final long atMillis;
        private final boolean fiveHour;
        private final boolean weekly;

        Event(long atMillis, boolean fiveHour, boolean weekly) {
            this.atMillis = atMillis;
            this.fiveHour = fiveHour;
            this.weekly = weekly;
        }

        public long getAtMillis() { return atMillis; }
        public boolean hasFiveHour() { return fiveHour; }
        public boolean hasWeekly() { return weekly; }
    }
}
