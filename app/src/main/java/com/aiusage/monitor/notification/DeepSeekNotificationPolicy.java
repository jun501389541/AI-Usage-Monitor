package com.aiusage.monitor.notification;

import java.util.Set;

/** Delivery gate for a DeepSeek transition that may have fired late. */
public final class DeepSeekNotificationPolicy {
    private DeepSeekNotificationPolicy() { }

    public static boolean shouldDeliver(long transitionAt, boolean targetPeak,
                                        long enabledAt, long lastDeliveredAt,
                                        long now, Set<String> offDays) {
        return NotificationDeliveryPolicy.shouldDeliver(transitionAt,
                        enabledAt, lastDeliveredAt, now)
                && PeakTimeSchedule.isPeakAt(now, offDays) == targetPeak;
    }
}
