package com.aiusage.monitor.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconciles one-shot alarms and OS events that can invalidate their time base. */
public final class NotificationAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!NotificationScheduler.ACTION_DELIVER.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {
            return;
        }
        NotificationScheduler.reconcile(context);
    }
}
