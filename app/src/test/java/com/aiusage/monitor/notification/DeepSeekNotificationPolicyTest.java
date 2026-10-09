package com.aiusage.monitor.notification;

import org.junit.Test;

import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeepSeekNotificationPolicyTest {
    @Test public void sendsLateTransitionOnlyWhileCurrentStateStillMatches() {
        long nine = at(2026, Calendar.OCTOBER, 13, 9, 0);
        PeakTimeSchedule.Transition morning = PeakTimeSchedule.nextTransition(
                nine - 60_000L, Collections.emptySet());
        assertTrue(DeepSeekNotificationPolicy.shouldDeliver(morning.getAtMillis(), morning.isPeakAfter(), nine - 1,
                0, at(2026, Calendar.OCTOBER, 13, 10, 0), Collections.emptySet()));
        assertFalse(DeepSeekNotificationPolicy.shouldDeliver(nine, false, nine - 1,
                0, at(2026, Calendar.OCTOBER, 13, 10, 0), Collections.emptySet()));
    }

    @Test public void noNotificationForTransitionOlderThanGrace() {
        long nine = at(2026, Calendar.OCTOBER, 13, 9, 0);
        PeakTimeSchedule.Transition morning = PeakTimeSchedule.nextTransition(
                nine - 60_000L, Collections.emptySet());
        assertFalse(DeepSeekNotificationPolicy.shouldDeliver(morning.getAtMillis(), morning.isPeakAfter(), nine - 1,
                0, nine + NotificationDeliveryPolicy.MISSED_EVENT_GRACE_MS + 1,
                Collections.emptySet()));
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.US);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
