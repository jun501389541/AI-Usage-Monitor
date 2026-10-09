package com.aiusage.monitor.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.storage.AppSettings;

import java.util.Calendar;

/**
 * Schedules the background refresh and the midnight redraw.
 *
 * <p>Reads its interval from {@link AppSettings} rather than from
 * {@code SharedPreferences}, so that the background interval has one home and is
 * covered by the same storage as everything else.
 *
 * <p>The upstream scheduler had three properties worth keeping and one worth
 * fixing: it re-arms itself from the receiver after every fire (kept), it does
 * nothing when no widget exists (kept, but now asked of the system rather than
 * remembered), and it treats midnight as the device's midnight (kept — the
 * daily-usage reset is a local-calendar concept). What changed is that the
 * interval lookup can no longer fail because a preferences key moved.
 */
public final class WidgetRefreshScheduler {

    public static final String ACTION_REFRESH = "com.aiusage.monitor.action.WIDGET_REFRESH";
    public static final String ACTION_MIDNIGHT = "com.aiusage.monitor.action.MIDNIGHT_REFRESH";

    /**
     * Fired by one widget's own refresh affordance. Spec §36.
     *
     * <p>Distinct from {@link #ACTION_REFRESH}, which refreshes every account on
     * the alarm schedule: a tap on one widget means "the accounts on this
     * widget", and a five-account user who taps a one-account widget should not
     * spend five requests.
     */
    public static final String ACTION_WIDGET_REFRESH =
            "com.aiusage.monitor.action.REFRESH_WIDGET_ACCOUNTS";

    /** Which widget fired. Same key the configuration screen uses. */
    public static final String EXTRA_WIDGET_ID = WidgetConfigActivity.EXTRA_WIDGET_ID;

    private static final int REFRESH_REQUEST_CODE = 7101;
    private static final int MIDNIGHT_REQUEST_CODE = 7102;
    private static final int WIDGET_REFRESH_REQUEST_CODE = 7201;

    private WidgetRefreshScheduler() {
    }

    /**
     * The broadcast a widget's refresh label fires.
     *
     * <p>The request code carries the widget id: two widgets whose labels do the
     * same thing must not share one {@link PendingIntent}, because intents that
     * differ only in extras are considered equal and the second widget would
     * refresh the first one's accounts.
     */
    public static PendingIntent widgetRefreshIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, WidgetRefreshReceiver.class);
        intent.setAction(ACTION_WIDGET_REFRESH);
        intent.putExtra(EXTRA_WIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(
                context,
                WIDGET_REFRESH_REQUEST_CODE + widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The configured background interval, or the default when unset. */
    public static long intervalMs(Context context) {
        return new AppSettings(context).backgroundRefreshIntervalMs();
    }

    public static void schedule(Context context) {
        boolean hasWidgets = WidgetUpdateManager.hasWidgets(context);
        boolean hasDirect = AppGraph.get(context).refreshManager().hasDirectRefreshAccounts();
        if (!hasWidgets && !hasDirect) {
            cancel(context);
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + intervalMs(context),
                pendingIntent(context, ACTION_REFRESH, REFRESH_REQUEST_CODE));
    }

    public static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent(context, ACTION_REFRESH, REFRESH_REQUEST_CODE));
        }
    }

    /**
     * Takes the daily redraw alarm down with the last widget. {@link #cancel} only
     * ever covered the interval alarm, so the midnight one outlived every widget on
     * the screen.
     */
    public static void cancelMidnight(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent(context, ACTION_MIDNIGHT, MIDNIGHT_REQUEST_CODE));
        }
    }

    public static void scheduleMidnight(Context context) {
        // Same guard {@link #schedule} has. The midnight alarm is a redraw
        // obligation, and with no widget on the screen there is nothing to redraw:
        // armed unconditionally from boot and from the app's own start-up, it used
        // to keep a daily query of every account alive behind an alarm nobody could
        // see (docs/PHASE-0-7-REVIEW.md §2.2).
        if (!WidgetUpdateManager.hasWidgets(context)) {
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        long triggerAt = nextMidnight();
        PendingIntent pendingIntent = pendingIntent(context, ACTION_MIDNIGHT, MIDNIGHT_REQUEST_CODE);
        if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private static long nextMidnight() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, WidgetRefreshReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
