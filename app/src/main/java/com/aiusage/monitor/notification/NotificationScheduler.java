package com.aiusage.monitor.notification;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.codex.CodexProvider;
import com.aiusage.monitor.provider.deepseek.DeepSeekProvider;
import com.aiusage.monitor.storage.HolidayStore;
import com.aiusage.monitor.ui.MainActivity;
import com.aiusage.monitor.ui.account.AccountListActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One-shot alarm scheduling; no polling or provider requests are performed here. */
public final class NotificationScheduler {
    static final String ACTION_DELIVER = "com.aiusage.monitor.notification.DELIVER";
    private static final String CHANNEL_PEAK = "notifications_peak_period";
    private static final String CHANNEL_CODEX = "notifications_quota_reset";
    private static final int ALARM_REQUEST_CODE = 6137;

    private NotificationScheduler() { }

    public static void reconcile(Context context) {
        Context app = context.getApplicationContext();
        AppGraph graph = AppGraph.get(app);
        NotificationSettings settings = new NotificationSettings(graph.settings());
        createChannels(app);
        if (!settings.isEnabled() || !notificationsAllowed(app)) {
            cancelAlarm(app);
            return;
        }

        long now = System.currentTimeMillis();
        long nextAlarm = Long.MAX_VALUE;
        Set<String> offDays = offDaysForWindow(app, now);
        boolean hasDeepSeek = false;
        for (Account account : graph.accountManager().listEnabled()) {
            if (DeepSeekProvider.ID.equals(account.getProviderId())) {
                hasDeepSeek = true;
                break;
            }
        }
        if (settings.isDeepSeekEnabled() && hasDeepSeek) {
            PeakTimeSchedule.Transition previous = PeakTimeSchedule.previousTransition(now, offDays);
            if (previous != null && DeepSeekNotificationPolicy.shouldDeliver(previous.getAtMillis(),
                    previous.isPeakAfter(),
                    settings.deepSeekEnabledSince(), settings.lastSent(NotificationSettings.DEEPSEEK),
                    now, offDays)) {
                if (postDeepSeek(app, previous)) {
                    settings.markSent(NotificationSettings.DEEPSEEK, previous.getAtMillis());
                }
            }
            PeakTimeSchedule.Transition next = PeakTimeSchedule.nextTransition(now, offDays);
            nextAlarm = Math.min(nextAlarm, next.getAtMillis());
        }

        for (Account account : graph.accountManager().listEnabled()) {
            if (!CodexProvider.ID.equals(account.getProviderId())) continue;
            UsageResult latest = graph.usageRepository().latest(account.getId());
            if (latest == null) continue;
            List<CodexResetPlanner.Event> events = CodexResetPlanner.events(latest.getQuotaWindows());
            for (CodexResetPlanner.Event event : events) {
                boolean notifyFiveHour = event.hasFiveHour() && settings.isCodexEnabled(
                        account.getId(), NotificationSettings.CODEX_FIVE_HOUR);
                boolean notifyWeekly = event.hasWeekly() && settings.isCodexEnabled(
                        account.getId(), NotificationSettings.CODEX_WEEKLY);
                boolean deliverFiveHour = notifyFiveHour && NotificationDeliveryPolicy.shouldDeliver(
                        event.getAtMillis(), settings.codexEnabledSince(account.getId(),
                                NotificationSettings.CODEX_FIVE_HOUR),
                        settings.lastSent(codexStream(account.getId(), NotificationSettings.CODEX_FIVE_HOUR)), now);
                boolean deliverWeekly = notifyWeekly && NotificationDeliveryPolicy.shouldDeliver(
                        event.getAtMillis(), settings.codexEnabledSince(account.getId(),
                                NotificationSettings.CODEX_WEEKLY),
                        settings.lastSent(codexStream(account.getId(), NotificationSettings.CODEX_WEEKLY)), now);
                if (deliverFiveHour || deliverWeekly) {
                    if (postCodex(app, account, event, deliverFiveHour, deliverWeekly)) {
                        if (deliverFiveHour) settings.markSent(
                                codexStream(account.getId(), NotificationSettings.CODEX_FIVE_HOUR), event.getAtMillis());
                        if (deliverWeekly) settings.markSent(
                                codexStream(account.getId(), NotificationSettings.CODEX_WEEKLY), event.getAtMillis());
                    }
                }
                if (event.getAtMillis() > now && (notifyFiveHour || notifyWeekly)) {
                    nextAlarm = Math.min(nextAlarm, event.getAtMillis());
                }
            }
        }

        if (nextAlarm == Long.MAX_VALUE) cancelAlarm(app);
        else scheduleAlarm(app, nextAlarm);
    }

    private static Set<String> offDaysForWindow(Context context, long now) {
        java.util.Calendar current = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        current.setTimeInMillis(now);
        Set<String> result = new HashSet<>();
        int year = current.get(java.util.Calendar.YEAR);
        result.addAll(HolidayStore.getOffDays(context, year));
        result.addAll(HolidayStore.getOffDays(context, year + 1));
        return result;
    }

    private static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
    }

    private static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_PEAK, "DeepSeek 峰谷切换", NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_CODEX, "Codex 额度重置", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static boolean postDeepSeek(Context context, PeakTimeSchedule.Transition transition) {
        String title = transition.isPeakAfter() ? "DeepSeek 已进入高峰时段" : "DeepSeek 已进入空闲时段";
        String text = transition.isPeakAfter()
                ? "当前为高峰时段，使用费用按高峰价格计算。"
                : "当前为空闲时段，使用费用按高峰价格的 50% 计算。";
        Intent open = new Intent(context, AccountListActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 6231, open, pendingFlags());
        return show(context, CHANNEL_PEAK, title, text, content,
                (NotificationSettings.DEEPSEEK + transition.getAtMillis()).hashCode());
    }

    private static boolean postCodex(Context context, Account account, CodexResetPlanner.Event event,
                                  boolean fiveHour, boolean weekly) {
        StringBuilder windows = new StringBuilder();
        if (fiveHour) windows.append("5 小时额度");
        if (weekly) {
            if (windows.length() > 0) windows.append("、");
            windows.append("每周额度");
        }
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ACCOUNT_ID, account.getId());
        PendingIntent content = PendingIntent.getActivity(context,
                account.getId().hashCode(), open, pendingFlags());
        return show(context, CHANNEL_CODEX, account.getDisplayName() + " · 额度重置",
                windows + "预计重置时间已到。", content,
                (account.getId() + event.getAtMillis()).hashCode());
    }

    private static boolean show(Context context, String channel, String title, String text,
                             PendingIntent content, int notificationId) {
        if (!channelAllowed(context, channel)) return false;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(com.aiusage.monitor.R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        manager.notify(notificationId, notification);
        return true;
    }

    private static boolean channelAllowed(Context context, String channel) {
        if (!notificationsAllowed(context)) return false;
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        NotificationChannel value = manager.getNotificationChannel(channel);
        return value == null || value.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static void scheduleAlarm(Context context, long atMillis) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = alarmPendingIntent(context);
        long triggerAt = Math.max(System.currentTimeMillis() + 1_000L, atMillis);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            }
        } catch (SecurityException denied) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(alarmPendingIntent(context));
    }

    private static PendingIntent alarmPendingIntent(Context context) {
        Intent intent = new Intent(context, NotificationAlarmReceiver.class).setAction(ACTION_DELIVER);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, pendingFlags());
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE;
    }

    private static String codexStream(String accountId, String category) {
        return "codex." + accountId + "." + category;
    }
}
