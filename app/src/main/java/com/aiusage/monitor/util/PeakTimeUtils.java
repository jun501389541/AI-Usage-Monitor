package com.aiusage.monitor.util;

import android.content.Context;

import com.aiusage.monitor.storage.HolidayStore;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class PeakTimeUtils {
    private static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");
    private PeakTimeUtils() {
    }

    public static Status currentStatus(Context context) {
        Calendar now = Calendar.getInstance(BEIJING);
        int minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean workday = isWorkday(context, now);
        boolean peak = workday
                && ((minutes >= 9 * 60 && minutes < 12 * 60)
                || (minutes >= 14 * 60 && minutes < 18 * 60));

        String currentTime = String.format(
                Locale.US,
                "UTC+8 %04d-%02d-%02d %02d:%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE));

        if (peak) {
            boolean morningPeak = minutes < 12 * 60;
            String end = morningPeak ? "12:00" : "18:00";
            Calendar peakEnd = atTime((Calendar) now.clone(), morningPeak ? 12 : 18, 0);
            String remaining = formatRemaining(peakEnd.getTimeInMillis() - now.getTimeInMillis());
            return new Status(true, "高峰时段", "按高峰价格计费", currentTime, end + " 后转为空闲时段", remaining);
        }

        Calendar next = nextPeakStart(context, now, minutes);
        String nextText;
        if (sameDate(now, next)) {
            nextText = String.format(Locale.US, "%02d:%02d 后进入高峰时段", next.get(Calendar.HOUR_OF_DAY), next.get(Calendar.MINUTE));
        } else if (isTomorrow(now, next)) {
            nextText = "明天 09:00 后进入高峰时段";
        } else if (isWorkday(context, now) && minutes < 9 * 60) {
            nextText = "09:00 后进入高峰时段";
        } else {
            nextText = String.format(Locale.US, "%d月%d日 09:00 后进入高峰时段", next.get(Calendar.MONTH) + 1, next.get(Calendar.DAY_OF_MONTH));
        }
        return new Status(false, "空闲时段", "按高峰价格的 50% 计费", currentTime, nextText, formatRemaining(next.getTimeInMillis() - now.getTimeInMillis()));
    }

    private static String formatRemaining(long millis) {
        long totalSeconds = (Math.max(0L, millis) + 999L) / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours + ":" + String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static Calendar nextPeakStart(Context context, Calendar now, int minutes) {
        Calendar candidate = (Calendar) now.clone();
        if (isWorkday(context, now)) {
            if (minutes < 9 * 60) {
                return atTime(candidate, 9, 0);
            }
            if (minutes >= 12 * 60 && minutes < 14 * 60) {
                return atTime(candidate, 14, 0);
            }
        }

        do {
            candidate.add(Calendar.DAY_OF_YEAR, 1);
            candidate.set(Calendar.HOUR_OF_DAY, 0);
            candidate.set(Calendar.MINUTE, 0);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
        } while (!isWorkday(context, candidate));
        return atTime(candidate, 9, 0);
    }

    private static boolean isWorkday(Context context, Calendar calendar) {
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false;
        }
        return !HolidayStore.getOffDays(context, calendar.get(Calendar.YEAR)).contains(dateKey(calendar));
    }

    private static Calendar atTime(Calendar calendar, int hour, int minute) {
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static boolean sameDate(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean isTomorrow(Calendar now, Calendar next) {
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        return sameDate(tomorrow, next);
    }

    private static String dateKey(Calendar calendar) {
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static final class Status {
        public final boolean peak;
        public final String title;
        public final String price;
        public final String currentTime;
        public final String next;
        public final String remaining;

        Status(boolean peak, String title, String price, String currentTime, String next, String remaining) {
            this.peak = peak;
            this.title = title;
            this.price = price;
            this.currentTime = currentTime;
            this.next = next;
            this.remaining = remaining;
        }
    }
}
