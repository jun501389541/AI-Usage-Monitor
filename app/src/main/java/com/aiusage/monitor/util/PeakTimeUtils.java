package com.aiusage.monitor.util;

import android.content.Context;

import com.aiusage.monitor.notification.PeakTimeSchedule;
import com.aiusage.monitor.storage.HolidayStore;

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
        Set<String> offDays = offDaysForCurrentWindow(context, now);
        boolean peak = PeakTimeSchedule.isPeakAt(now.getTimeInMillis(), offDays);

        String currentTime = String.format(
                Locale.US,
                "(UTC+8) %04d-%02d-%02d %02d:%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE));

        if (peak) {
            boolean morningPeak = minutes < 12 * 60;
            PeakTimeSchedule.Transition transition = PeakTimeSchedule.nextTransition(
                    now.getTimeInMillis(), offDays);
            Calendar peakEnd = Calendar.getInstance(BEIJING);
            peakEnd.setTimeInMillis(transition.getAtMillis());
            String end = String.format(Locale.US, "%02d:%02d",
                    peakEnd.get(Calendar.HOUR_OF_DAY), peakEnd.get(Calendar.MINUTE));
            String remaining = formatRemaining(transition.getAtMillis() - now.getTimeInMillis());
            return new Status(true, "高峰时段", "按高峰价格计费", currentTime, end + " 后转为空闲时段", remaining);
        }

        PeakTimeSchedule.Transition transition = PeakTimeSchedule.nextTransition(
                now.getTimeInMillis(), offDays);
        Calendar next = Calendar.getInstance(BEIJING);
        next.setTimeInMillis(transition.getAtMillis());
        String nextText;
        if (sameDate(now, next)) {
            nextText = String.format(Locale.US, "%02d:%02d 后进入高峰时段", next.get(Calendar.HOUR_OF_DAY), next.get(Calendar.MINUTE));
        } else {
            nextText = dateKey(next) + " 09:00 后进入高峰时段";
        }
        return new Status(false, "空闲时段", "按高峰价格的 50% 计费", currentTime, nextText,
                formatRemaining(transition.getAtMillis() - now.getTimeInMillis()));
    }

    private static String formatRemaining(long millis) {
        long totalSeconds = (Math.max(0L, millis) + 999L) / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours + ":" + String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static Set<String> offDaysForCurrentWindow(Context context, Calendar now) {
        Set<String> result = new HashSet<>();
        int year = now.get(Calendar.YEAR);
        result.addAll(HolidayStore.getOffDays(context, year));
        result.addAll(HolidayStore.getOffDays(context, year + 1));
        return result;
    }

    private static boolean sameDate(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
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
