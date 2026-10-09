package com.aiusage.monitor.notification;

/** Pure due-time and deduplication rules for alarm delivery. */
public final class NotificationDeliveryPolicy {
    public static final long MISSED_EVENT_GRACE_MS = 60L * 60L * 1000L;

    private NotificationDeliveryPolicy() { }

    public static boolean shouldDeliver(long triggerAt, long enabledAt,
                                        long lastDeliveredAt, long now) {
        return triggerAt > 0
                && enabledAt <= triggerAt
                && triggerAt <= now
                && now - triggerAt <= MISSED_EVENT_GRACE_MS
                && lastDeliveredAt < triggerAt;
    }
}
