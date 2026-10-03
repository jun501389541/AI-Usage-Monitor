package com.aiusage.monitor.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/**
 * The 2×2 widget: one account, with the peak-period label.
 *
 * <p>Records its type against each instance so the renderer can pick the layout
 * explicitly instead of inferring it from a resource id.
 */
public final class BalanceWidget2x2Provider extends AppWidgetProvider {

    /** The type string stored for this size. */
    public static final String WIDGET_TYPE = WidgetRenderer.TYPE_2X2;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        WidgetUpdateManager updateManager = new WidgetUpdateManager(context);
        if (appWidgetIds != null) {
            for (int widgetId : appWidgetIds) {
                if (updateManager.configStore().find(widgetId) == null) {
                    updateManager.configStore().save(
                            WidgetConfig.unbound(widgetId, WIDGET_TYPE, WidgetRefreshScheduler.intervalMs(context)));
                }
            }
        }
        updateManager.updateInstances(manager, appWidgetIds);
    }

    @Override
    public void onEnabled(Context context) {
        WidgetRefreshScheduler.schedule(context);
        // The daily redraw exists to move the "today" figure off yesterday's date.
        // It is armed with the first widget rather than with the app being opened,
        // so a phone with no widget has no daily alarm behind it.
        WidgetRefreshScheduler.scheduleMidnight(context);
    }

    @Override
    public void onDisabled(Context context) {
        if (!WidgetUpdateManager.hasWidgets(context)) {
            WidgetRefreshScheduler.cancel(context);
            WidgetRefreshScheduler.cancelMidnight(context);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetUpdateManager updateManager = new WidgetUpdateManager(context);
        if (appWidgetIds != null) {
            for (int widgetId : appWidgetIds) {
                updateManager.forget(widgetId);
            }
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle newOptions) {
        new WidgetUpdateManager(context).updateWidget(appWidgetId);
    }
}
