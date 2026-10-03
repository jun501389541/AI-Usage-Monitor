package com.aiusage.monitor.refresh;

import com.aiusage.monitor.provider.ProviderCapabilities;

/**
 * How often an account should be refreshed. Spec §42 and §43.
 *
 * <p>The spec asks for a 30-minute default, a fixed menu of user choices, and a
 * provider-supplied recommendation that the user may still override. The
 * recommendation is a hint, not a floor or a ceiling — a provider knows its own
 * rate limits but not the user's battery budget.
 */
public final class RefreshPolicy {

    /** The spec's recommended background default. Spec §42. */
    public static final long DEFAULT_INTERVAL_MS = 30L * 60L * 1000L;

    /** The intervals offered in the settings UI, in milliseconds. */
    public static final long[] INTERVALS_MS = {
            15L * 60L * 1000L,
            30L * 60L * 1000L,
            60L * 60L * 1000L,
            2L * 60L * 60L * 1000L,
    };

    /** Matching labels for {@link #INTERVALS_MS}. */
    public static final String[] INTERVAL_LABELS = {
            "15 分钟",
            "30 分钟",
            "1 小时",
            "2 小时",
    };

    /** The sentinel meaning "no automatic refresh; manual only". */
    public static final long MANUAL_ONLY = -1L;

    private RefreshPolicy() {
    }

    /**
     * The interval to use for an account: the user's choice when set, otherwise
     * the provider's recommendation, otherwise the spec default.
     *
     * @param userOverrideMs user setting, or 0 when unset
     * @param capabilities   the provider's capabilities, or null
     */
    public static long intervalFor(long userOverrideMs, ProviderCapabilities capabilities) {
        if (userOverrideMs == MANUAL_ONLY) {
            return MANUAL_ONLY;
        }
        if (userOverrideMs > 0L) {
            return userOverrideMs;
        }
        if (capabilities != null && capabilities.getRecommendedRefreshIntervalMs() > 0L) {
            return capabilities.getRecommendedRefreshIntervalMs();
        }
        return DEFAULT_INTERVAL_MS;
    }

    /** Human label for an interval, falling back to a minutes rendering. */
    public static String labelFor(long intervalMs) {
        if (intervalMs == MANUAL_ONLY) {
            return "仅手动";
        }
        for (int index = 0; index < INTERVALS_MS.length; index++) {
            if (INTERVALS_MS[index] == intervalMs) {
                return INTERVAL_LABELS[index];
            }
        }
        long minutes = intervalMs / 60000L;
        if (minutes <= 0L) {
            return "仅手动";
        }
        return minutes + " 分钟";
    }

    /**
     * Whether a stale timestamp should be treated as no longer live. The spec
     * wants the user to be able to tell whether a percentage is current
     * (§41), so anything older than a few refresh cycles is marked stale.
     */
    public static boolean isStale(long updatedAt, long intervalMs, long now) {
        if (updatedAt <= 0L) {
            return true;
        }
        long effective = intervalMs > 0L ? intervalMs : DEFAULT_INTERVAL_MS;
        return now - updatedAt > effective * 2L;
    }
}
