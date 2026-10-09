package com.aiusage.monitor.notification;

import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Pure Beijing-time rules shared by DeepSeek peak-period notifications. */
public final class PeakTimeSchedule {
    private static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");
    private static final int[] TRANSITION_MINUTES = {9 * 60, 12 * 60, 14 * 60, 18 * 60};

    private PeakTimeSchedule() { }

    public static boolean isPeakAt(long atMillis, Set<String> offDays) {
        Calendar calendar = calendarAt(atMillis);
        int minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        return isWorkday(calendar, offDays)
                && ((minute >= 9 * 60 && minute < 12 * 60)
                || (minute >= 14 * 60 && minute < 18 * 60));
    }

    /** Returns the first state transition strictly after {@code afterMillis}. */
    public static Transition nextTransition(long afterMillis, Set<String> offDays) {
        Calendar day = calendarAt(afterMillis);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 370; offset++) {
            if (isWorkday(day, offDays)) {
                for (int minute : TRANSITION_MINUTES) {
                    Calendar candidate = atMinute(day, minute);
                    if (candidate.getTimeInMillis() > afterMillis) {
                        return new Transition(candidate.getTimeInMillis(), minute == 9 * 60 || minute == 14 * 60);
                    }
                }
            }
            day.add(Calendar.DAY_OF_YEAR, 1);
        }
        throw new IllegalStateException("No peak period transition found in the next year");
    }

    /** Returns the latest state transition at or before {@code atMillis}. */
    public static Transition previousTransition(long atMillis, Set<String> offDays) {
        Calendar day = calendarAt(atMillis);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 370; offset++) {
            if (isWorkday(day, offDays)) {
                for (int index = TRANSITION_MINUTES.length - 1; index >= 0; index--) {
                    int minute = TRANSITION_MINUTES[index];
                    Calendar candidate = atMinute(day, minute);
                    if (candidate.getTimeInMillis() <= atMillis) {
                        return new Transition(candidate.getTimeInMillis(), minute == 9 * 60 || minute == 14 * 60);
                    }
                }
            }
            day.add(Calendar.DAY_OF_YEAR, -1);
        }
        return null;
    }

    private static boolean isWorkday(Calendar calendar, Set<String> offDays) {
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false;
        return offDays == null || !offDays.contains(dateKey(calendar));
    }

    private static Calendar calendarAt(long millis) {
        Calendar calendar = Calendar.getInstance(BEIJING, Locale.US);
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    private static Calendar atMinute(Calendar day, int minuteOfDay) {
        Calendar candidate = (Calendar) day.clone();
        candidate.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        candidate.set(Calendar.MINUTE, minuteOfDay % 60);
        return candidate;
    }

    private static String dateKey(Calendar calendar) {
        return String.format(Locale.US, "%04d-%02d-%02d", calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static final class Transition {
        private final long atMillis;
        private final boolean peakAfter;

        private Transition(long atMillis, boolean peakAfter) {
            this.atMillis = atMillis;
            this.peakAfter = peakAfter;
        }

        public long getAtMillis() { return atMillis; }
        public boolean isPeakAfter() { return peakAfter; }
    }
}
