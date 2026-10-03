package com.aiusage.monitor.util;

import java.util.Calendar;
import java.util.Locale;

/**
 * How old a reading is, in the forms Spec §41 requires a widget to say.
 *
 * <p>Spec §41 is a hard rule, not decoration: "用户必须知道自己看的是不是旧数据."
 * A balance that is quietly six hours old is a different fact from one fetched a
 * minute ago, and the four forms below — 刚刚 / N分钟前 / 12:31 / 2小时前 — are the
 * spec's own list.
 *
 * <p>Recent ages are relative because that is what the question is about, and
 * older ones switch to the clock time because "14小时前" makes the reader do
 * arithmetic they cannot check on a moving screen.
 */
public final class Freshness {

    static final long MINUTE_MS = 60_000L;
    static final long HOUR_MS = 60 * MINUTE_MS;

    /** Up to this age the wording stays relative; past it, it becomes a clock time. */
    static final long RELATIVE_LIMIT_MS = 6 * HOUR_MS;

    private Freshness() {
    }

    /**
     * @param updatedAtMs when the reading was obtained, in device epoch millis
     * @param nowMs       the instant to judge it against, passed in rather than
     *                    read from the clock so the boundary cases are testable
     */
    public static String describe(long updatedAtMs, long nowMs) {
        // A provider's own timestamps can land a few milliseconds ahead of a
        // device clock that has not settled yet; a negative age would read as
        // "-5分钟前".
        long age = Math.max(0L, nowMs - updatedAtMs);
        if (age < MINUTE_MS) {
            return "刚刚";
        }
        if (age < HOUR_MS) {
            return (age / MINUTE_MS) + "分钟前";
        }
        if (age < RELATIVE_LIMIT_MS) {
            return (age / HOUR_MS) + "小时前";
        }
        Calendar shown = Calendar.getInstance();
        shown.setTimeInMillis(updatedAtMs);
        if (isSameDay(shown, nowMs)) {
            return String.format(Locale.ROOT, "%02d:%02d",
                    shown.get(Calendar.HOUR_OF_DAY), shown.get(Calendar.MINUTE));
        }
        return String.format(Locale.ROOT, "%02d-%02d",
                shown.get(Calendar.MONTH) + 1, shown.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * True when the reading happened on the same calendar day as {@code nowMs}.
     *
     * <p>Calendar-day comparison, not a 24-hour span: this is the same boundary
     * the daily-usage accumulator resets on and the same one retention keeps
     * today's rows by, so a widget that says "今天" and a counter that rolled
     * over cannot disagree.
     */
    public static boolean isSameDay(Calendar shown, long nowMs) {
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(nowMs);
        return shown.get(Calendar.ERA) == today.get(Calendar.ERA)
                && shown.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && shown.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
    }
}
