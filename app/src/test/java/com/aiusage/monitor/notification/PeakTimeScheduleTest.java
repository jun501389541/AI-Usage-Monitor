package com.aiusage.monitor.notification;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class PeakTimeScheduleTest {

    @Test public void nextTransitionUsesBothDailyPeakWindows() {
        PeakTimeSchedule.Transition morning = PeakTimeSchedule.nextTransition(
                at(2026, Calendar.OCTOBER, 13, 8, 59), Collections.emptySet());
        assertEquals(at(2026, Calendar.OCTOBER, 13, 9, 0), morning.getAtMillis());
        assertTrue(morning.isPeakAfter());

        PeakTimeSchedule.Transition lunch = PeakTimeSchedule.nextTransition(
                at(2026, Calendar.OCTOBER, 13, 11, 59), Collections.emptySet());
        assertEquals(at(2026, Calendar.OCTOBER, 13, 12, 0), lunch.getAtMillis());
        assertFalse(lunch.isPeakAfter());

        PeakTimeSchedule.Transition afternoon = PeakTimeSchedule.nextTransition(
                at(2026, Calendar.OCTOBER, 13, 13, 59), Collections.emptySet());
        assertEquals(at(2026, Calendar.OCTOBER, 13, 14, 0), afternoon.getAtMillis());
        assertTrue(afternoon.isPeakAfter());

        PeakTimeSchedule.Transition evening = PeakTimeSchedule.nextTransition(
                at(2026, Calendar.OCTOBER, 13, 17, 59), Collections.emptySet());
        assertEquals(at(2026, Calendar.OCTOBER, 13, 18, 0), evening.getAtMillis());
        assertFalse(evening.isPeakAfter());
    }

    @Test public void previousTransitionIsInclusiveAtTheBoundary() {
        PeakTimeSchedule.Transition atNoon = PeakTimeSchedule.previousTransition(
                at(2026, Calendar.OCTOBER, 13, 12, 0), Collections.emptySet());
        assertNotNull(atNoon);
        assertEquals(at(2026, Calendar.OCTOBER, 13, 12, 0), atNoon.getAtMillis());
        assertFalse(atNoon.isPeakAfter());
    }

    @Test public void weekendsAndChinesePublicHolidaysStayOffPeak() {
        HashSet<String> holidays = new HashSet<>(Arrays.asList("2026-10-12"));
        long monday = at(2026, Calendar.OCTOBER, 12, 10, 0);
        assertFalse(PeakTimeSchedule.isPeakAt(monday, holidays));
        PeakTimeSchedule.Transition next = PeakTimeSchedule.nextTransition(monday, holidays);
        assertEquals(at(2026, Calendar.OCTOBER, 13, 9, 0), next.getAtMillis());

        long saturday = at(2026, Calendar.OCTOBER, 17, 10, 0);
        assertFalse(PeakTimeSchedule.isPeakAt(saturday, holidays));
        assertEquals(at(2026, Calendar.OCTOBER, 19, 9, 0),
                PeakTimeSchedule.nextTransition(saturday, holidays).getAtMillis());
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.US);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
