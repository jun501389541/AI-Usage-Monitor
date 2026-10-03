package com.aiusage.monitor.widget;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.usage.SnapshotRetention;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles the background refresh and the midnight redraw.
 *
 * <p>Upstream this class carried a second, private copy of the DeepSeek HTTP
 * call and its own JSON parsing — with different timeouts, a different
 * acceptance rule and a {@code catch (Exception ignored) {}} that swallowed
 * every failure. That is gone. The receiver now asks
 * {@link AccountRefreshManager} for fresh data and then redraws the widgets, so
 * the background path and the app path are the same code and cannot disagree.
 * Spec §37, §53 rules 8 and 9.
 *
 * <p>The receiver still does its own scheduling bookkeeping, and deliberately
 * still skips the actual fetch on {@code BOOT_COMPLETED}: a boot is not a reason
 * to spend a network request, and the alarm that is armed here will fire soon
 * enough. Spec §42.
 */
public final class WidgetRefreshReceiver extends BroadcastReceiver {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            WidgetRefreshScheduler.schedule(context);
            WidgetRefreshScheduler.scheduleMidnight(context);
            return;
        }

        final boolean midnight = WidgetRefreshScheduler.ACTION_MIDNIGHT.equals(action);
        if (WidgetRefreshScheduler.ACTION_WIDGET_REFRESH.equals(action)) {
            refreshOneWidget(context, intent);
            return;
        }
        if (!midnight && !WidgetRefreshScheduler.ACTION_REFRESH.equals(action)) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            RefreshDuty duty = RefreshDuty.forAlarm(midnight,
                    WidgetUpdateManager.hasWidgets(appContext));
            try {
                if (duty == RefreshDuty.REFRESH_AND_REDRAW) {
                    refresh(appContext);
                } else if (duty == RefreshDuty.REDRAW_ONLY) {
                    // Midnight changes what "today" means, not what was read. The
                    // numbers come from the stored rows, so this redraws and asks
                    // the platform for nothing.
                    new WidgetUpdateManager(appContext).updateAllWidgets();
                }
            } finally {
                // Re-armed in finally so a failed or thrown duty still leaves the
                // next alarm scheduled; otherwise one bad response would silently
                // stop background updates for good. Both schedulers decline when
                // no widget is left, which is what ends the chain instead of
                // keeping a daily alarm alive for a screen nobody has.
                if (midnight) {
                    WidgetRefreshScheduler.scheduleMidnight(appContext);
                } else {
                    WidgetRefreshScheduler.schedule(appContext);
                }
                pendingResult.finish();
            }
        });
    }

    /**
     * Refreshes the accounts one widget shows, then redraws. Spec §36, §38.
     *
     * <p>Goes through {@link AccountRefreshManager} like every other trigger, so a
     * tap cannot disagree with the app about what an account's balance is, and the
     * result is stored rather than shown from the response. Because the same
     * manager also reports which widgets display an account, refreshing here
     * repaints <em>every</em> widget showing it — the spec's rule 16 — not only the
     * one that was tapped.
     */
    private void refreshOneWidget(Context context, Intent intent) {
        final int widgetId = intent.getIntExtra(WidgetRefreshScheduler.EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                AppGraph graph = AppGraph.get(appContext);
                graph.ensureMigrated();
                if (graph.widgetConfigStore().find(widgetId) == null) {
                    // The widget was removed while this broadcast was queued.
                    // Repainting its id anyway would paint the fallback account
                    // onto whatever instance has reused that id.
                    return;
                }
                for (String accountId : accountsOf(graph, widgetId)) {
                    graph.refreshManager().refresh(accountId);
                    new WidgetUpdateManager(appContext).updateWidgetsForAccount(accountId);
                }
                // Also on this path: it is the only one a person can trigger on
                // demand, so a device check can prove retention ran without
                // waiting out the alarm, and a user who taps refresh after a long
                // pause gets the same trim the alarm would have given them.
                graph.usageRepository().prune(new SnapshotRetention(),
                        System.currentTimeMillis());
                new WidgetUpdateManager(appContext).updateWidget(widgetId);
            } finally {
                pendingResult.finish();
            }
        });
    }

    /**
     * The accounts one widget displays.
     *
     * <p>An unbound widget falls back to the first enabled account when it draws,
     * so its refresh label means that account too; a widget whose account was
     * deleted has nothing to refresh and redraws to say so.
     */
    private static List<String> accountsOf(AppGraph graph, int widgetId) {
        WidgetConfig config = graph.widgetConfigStore().find(widgetId);
        if (config != null && !config.accountIds().isEmpty()) {
            return config.accountIds();
        }
        List<Account> enabled = graph.accountManager().listEnabled();
        return enabled.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.singletonList(enabled.get(0).getId());
    }

    /**
     * Refreshes every enabled account and redraws the widgets.
     *
     * <p>Accounts are refreshed through the shared manager, which stores both
     * successes and failures, so a widget redrawn afterwards shows the newest
     * successful reading and never a blank because one attempt failed. Spec §39.
     */
    private static void refresh(Context context) {
        AppGraph graph = AppGraph.get(context);
        // A user who never opens the app still expects their upgraded widget to
        // work, so the legacy import is attempted on this path too.
        graph.ensureMigrated();
        graph.refreshManager().refreshAll();
        // Retention runs after the refresh, on this executor, and nowhere else:
        // the readings it judges were just written, it is the one path guaranteed
        // to run without the app being opened, and a UI thread has no business
        // deleting rows. Spec §26 keeps history; this is what stops "keep
        // history" from meaning "never delete anything".
        graph.usageRepository().prune(new SnapshotRetention(), System.currentTimeMillis());
        new WidgetUpdateManager(context).updateAllWidgets();
    }
}
