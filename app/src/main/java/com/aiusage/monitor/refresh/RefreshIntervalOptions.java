package com.aiusage.monitor.refresh;

/** Provider-aware automatic refresh choices used by the detail screen. */
public final class RefreshIntervalOptions {

    public static final long MIN_BACKGROUND_INTERVAL_MS = 15L * 60L * 1000L;
    public static final long DEFAULT_BACKGROUND_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long MIN_BRIDGE_INTERVAL_MS = 5L * 60L * 1000L;

    private static final long[] DEEPSEEK_INTERVALS = {
            15000L, 30000L, 60000L, 300000L, 600000L, 900000L, RefreshPolicy.MANUAL_ONLY
    };
    private static final String[] DEEPSEEK_LABELS = {
            "15 秒", "30 秒", "1 分钟", "5 分钟", "10 分钟", "15 分钟", "详情页仅手动"
    };
    private static final long[] BRIDGE_INTERVALS = {
            300000L, 600000L, 900000L, RefreshPolicy.MANUAL_ONLY
    };
    private static final String[] BRIDGE_LABELS = {
            "5 分钟", "10 分钟", "15 分钟", "详情页仅手动"
    };
    private static final long[] BACKGROUND_INTERVALS = {
            MIN_BACKGROUND_INTERVAL_MS, 30L * 60L * 1000L, 60L * 60L * 1000L
    };
    private static final String[] BACKGROUND_LABELS = {"15 分钟", "30 分钟", "1 小时"};

    private RefreshIntervalOptions() {
    }

    public static long[] foregroundIntervals(boolean bridge) {
        return (bridge ? BRIDGE_INTERVALS : DEEPSEEK_INTERVALS).clone();
    }

    public static String[] foregroundLabels(boolean bridge) {
        return (bridge ? BRIDGE_LABELS : DEEPSEEK_LABELS).clone();
    }

    public static long[] backgroundIntervals() {
        return BACKGROUND_INTERVALS.clone();
    }

    public static String[] backgroundLabels() {
        return BACKGROUND_LABELS.clone();
    }

    /** Keeps older saved choices within the current provider's safe range. */
    public static long normalizeForeground(long intervalMs, boolean bridge) {
        if (intervalMs == RefreshPolicy.MANUAL_ONLY) {
            return RefreshPolicy.MANUAL_ONLY;
        }
        long minimum = bridge ? MIN_BRIDGE_INTERVAL_MS : 15000L;
        return intervalMs < minimum ? minimum : intervalMs;
    }

    public static long normalizeBackground(long intervalMs) {
        if (intervalMs <= 0L) {
            return DEFAULT_BACKGROUND_INTERVAL_MS;
        }
        return Math.max(MIN_BACKGROUND_INTERVAL_MS, intervalMs);
    }

    public static int selectedIndex(long[] choices, long selected, int fallback) {
        for (int index = 0; index < choices.length; index++) {
            if (choices[index] == selected) {
                return index;
            }
        }
        return fallback;
    }

    public static String labelFor(long intervalMs) {
        if (intervalMs == RefreshPolicy.MANUAL_ONLY) {
            return "详情页仅手动";
        }
        if (intervalMs < 60000L) {
            return intervalMs / 1000L + " 秒";
        }
        if (intervalMs % 3600000L == 0L) {
            return intervalMs / 3600000L + " 小时";
        }
        return intervalMs / 60000L + " 分钟";
    }
}
