package com.aiusage.monitor.util;

import com.aiusage.monitor.model.QuotaWindow;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The wording for quota windows - the only reading a Codex account has.
 *
 * <p>Kept separate from the screen for the same reason {@link StatusWords} and
 * {@link Freshness} exist: the text a user reads should be decided in one place
 * that can be tested without an Android context, not re-derived per surface.
 *
 * <p>Nothing here assumes what a window <em>is</em>. DeepSeek has no windows and
 * Codex's own ids and durations are the only names available, so the label comes
 * from the window (Spec §11 via {@code QuotaWindow}'s own comment: primary is not
 * necessarily the five-hour one, and a plan without a five-hour window exists).
 */
public final class QuotaWords {

    /** Shown when the source gave no reset time. */
    public static final String RESET_UNKNOWN = "重置时间未知";

    /**
     * Shown when the reset moment has already passed. Not "已重置": the app has no
     * way to know the new window has begun, only that the old deadline is behind
     * it - the next refresh decides what happens after that.
     */
    public static final String RESET_DUE = "重置时间已过";

    private QuotaWords() {
    }

    /** One window on one line, including the full reset date and time. */
    public static String line(QuotaWindow window, long nowMs) {
        if (window == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append(window.getLabel().isEmpty() ? window.getId() : window.getLabel());
        text.append(" · 已用 ").append(clamped(window.getUsedPercent())).append("%");
        text.append(" · ").append(resetText(window.getResetAt(), nowMs));
        return text.toString();
    }

    /**
     * The reset moment, phrased relative to now: a bare timestamp is unreadable
     * for a window that rolls over every few hours.
     */
    public static String resetText(long resetAtMs, long nowMs) {
        if (resetAtMs <= 0L) {
            return RESET_UNKNOWN;
        }
        if (resetAtMs <= nowMs) {
            return RESET_DUE;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(resetAtMs)) + " 重置";
    }

    /** Time until reset, independent of the device's timezone. */
    public static String resetCountdown(long resetAtMs, long nowMs) {
        if (resetAtMs <= 0L) {
            return RESET_UNKNOWN;
        }
        if (resetAtMs <= nowMs) {
            return RESET_DUE;
        }
        long difference = resetAtMs - nowMs;
        long minutes = difference / 60_000L + (difference % 60_000L == 0L ? 0L : 1L);
        long days = minutes / (24L * 60L);
        long hours = minutes / 60L % 24L;
        if (days > 0L) {
            return days + " 天" + (hours > 0L ? " " + hours + " 小时" : "") + "后重置";
        }
        long remainder = minutes % 60L;
        if (hours > 0L) {
            return hours + " 小时" + (remainder > 0L ? " " + remainder + " 分钟" : "") + "后重置";
        }
        return minutes + " 分钟后重置";
    }

    /** China is the fallback until a device timezone has been resolved. */
    public static String resetSummary(long resetAtMs, long nowMs) {
        return resetSummary(resetAtMs, nowMs, TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static String resetSummary(long resetAtMs, long nowMs, TimeZone timezone) {
        if (resetAtMs <= 0L) {
            return RESET_UNKNOWN;
        }
        TimeZone zone = timezone == null ? TimeZone.getTimeZone("Asia/Shanghai") : timezone;
        Date reset = new Date(resetAtMs);
        SimpleDateFormat momentFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        momentFormat.setTimeZone(zone);
        String offset = utcOffsetAt(zone, resetAtMs);
        String zoneLabel = "Asia/Shanghai".equals(zone.getID()) ? "" : " (UTC" + offset + ")";
        return resetCountdown(resetAtMs, nowMs).replace(" ", "")
                + " · " + momentFormat.format(reset) + zoneLabel;
    }

    /** Uses the offset at the displayed date, including DST; compatible with API 23. */
    static String utcOffsetAt(TimeZone zone, long atMillis) {
        int minutes = zone.getOffset(atMillis) / 60_000;
        int absolute = Math.abs(minutes);
        return String.format(Locale.US, "%s%02d:%02d", minutes < 0 ? "-" : "+", absolute / 60, absolute % 60);
    }

    /** Remaining quota for a history row; explicitly names the percentage meaning. */
    public static String remainingCompact(List<QuotaWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (QuotaWindow window : windows) {
            if (window == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" · ");
            }
            String label = window.getLabel().isEmpty() ? window.getId() : window.getLabel();
            text.append(label).append(" 剩余 ").append(clamped(window.getRemainingPercent())).append('%');
        }
        return text.toString();
    }

    /**
     * Joins the windows with newlines. Empty when there are none, so a caller can
     * hide the block instead of rendering a heading over nothing.
     */
    public static String lines(List<QuotaWindow> windows, long nowMs) {
        if (windows == null || windows.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (QuotaWindow window : windows) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line(window, nowMs));
        }
        return text.toString();
    }

    /**
     * One window per line, without the reset clause: a history row is a fact about
     * the moment it was read, and judging its deadline by today's clock would
     * rewrite that fact. The full {@link #line} form belongs to the detail page,
     * where "now" is the reading on screen.
     */
    public static String compact(List<QuotaWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (QuotaWindow window : windows) {
            if (window == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" · ");
            }
            String label = window.getLabel().isEmpty() ? window.getId() : window.getLabel();
            text.append(label).append(' ').append(clamped(window.getUsedPercent())).append('%');
        }
        return text.toString();
    }

    private static int clamped(double percent) {
        long rounded = Math.round(percent);
        if (rounded < 0L) {
            return 0;
        }
        if (rounded > 100L) {
            return 100;
        }
        return (int) rounded;
    }

}
