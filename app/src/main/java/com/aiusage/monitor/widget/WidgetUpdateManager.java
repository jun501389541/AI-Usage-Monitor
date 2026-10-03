package com.aiusage.monitor.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.aiusage.monitor.AppGraph;
import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.refresh.RefreshPolicy;
import com.aiusage.monitor.ui.MainActivity;
import com.aiusage.monitor.ui.account.AccountListActivity;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.PeakTimeUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws widgets from stored data. Spec §38.
 *
 * <p>This class is the widget half of the single-refresh-chain rule. It never
 * contacts a provider and never touches a credential: it reads the last known
 * {@link UsageResult} for an account from the {@link UsageRepository} and hands
 * the formatted strings to {@link WidgetRenderer}. A widget therefore cannot
 * leak a token it never sees, and cannot disagree with the app about a balance
 * it never computes. Spec §27, §53 rules 8 and 9.
 *
 * <p>When an account has no successful result yet, or has none for a widget
 * that is bound to a deleted account, the widget draws an em dash. A failed
 * refresh never blanks a widget: the repository keeps returning the previous
 * successful row. Spec §39, rule 18.
 */
public final class WidgetUpdateManager {

    private final Context appContext;
    private final WidgetConfigStore configStore;
    private final AccountManager accountManager;
    private final UsageRepository usageRepository;
    private final AccountRefreshManager refreshManager;

    public WidgetUpdateManager(Context context) {
        AppGraph graph = AppGraph.get(context);
        this.appContext = graph.appContext();
        this.configStore = graph.widgetConfigStore();
        this.accountManager = graph.accountManager();
        this.usageRepository = graph.usageRepository();
        this.refreshManager = graph.refreshManager();
    }

    /** The binding store, so providers can register themselves on first update. */
    public WidgetConfigStore configStore() {
        return configStore;
    }

    /**
     * True when at least one widget instance of any of the three sizes is on a
     * home screen.
     *
     * <p>Asked of the system rather than tracked in storage: the launcher is the
     * authority on what exists, and a remembered flag can only ever drift from
     * it. Upstream tracked this in preferences and could therefore schedule an
     * alarm for widgets that no longer existed.
     */
    public static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return false;
        }
        for (Class<?> provider : providerClasses()) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
            if (ids != null && ids.length > 0) {
                return true;
            }
        }
        return false;
    }

    /** The three widget providers, in one place so callers cannot miss one. */
    public static List<Class<?>> providerClasses() {
        List<Class<?>> classes = new ArrayList<>();
        classes.add(BalanceWidget4x2Provider.class);
        classes.add(BalanceWidget2x2Provider.class);
        classes.add(BalanceWidget2x1Provider.class);
        return classes;
    }

    /**
     * The widget type of an instance, asked of the system rather than of the
     * binding table. Spec §33.
     *
     * <p>This is the only reliable source while a widget is being placed: the
     * launcher's configuration intent carries the app widget id but does not
     * say which provider was chosen, so guessing would record a 2×1 widget as a
     * 4×2 one and render it with the wrong layout. Reading the provider back
     * from {@link AppWidgetManager} is authoritative and covers re-binding too.
     *
     * @return the type, or an empty string when the id is unknown
     */
    public static String typeOf(Context context, int widgetId) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return "";
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return "";
        }
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
        if (info == null || info.provider == null) {
            return "";
        }
        String className = info.provider.getClassName();
        if (BalanceWidget4x2Provider.class.getName().equals(className)) {
            return WidgetRenderer.TYPE_4X2;
        }
        if (BalanceWidget2x2Provider.class.getName().equals(className)) {
            return WidgetRenderer.TYPE_2X2;
        }
        if (BalanceWidget2x1Provider.class.getName().equals(className)) {
            return WidgetRenderer.TYPE_2X1;
        }
        return "";
    }

    /** Redraws every instance of every size. */
    public void updateAllWidgets() {
        AppWidgetManager manager = AppWidgetManager.getInstance(appContext);
        if (manager == null) {
            return;
        }
        for (Class<?> provider : providerClasses()) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(appContext, provider));
            if (ids != null && ids.length > 0) {
                updateInstances(manager, ids);
            }
        }
    }

    /** Redraws every instance of one provider, used by {@code onUpdate}. */
    public void updateInstances(AppWidgetManager manager, int[] widgetIds) {
        if (manager == null || widgetIds == null) {
            return;
        }
        for (int widgetId : widgetIds) {
            updateWidget(widgetId);
        }
    }

    /** Redraws one instance. */
    public void updateWidget(int widgetId) {
        AppWidgetManager manager = AppWidgetManager.getInstance(appContext);
        if (manager == null) {
            return;
        }
        String widgetType = widgetTypeOf(widgetId);
        manager.updateAppWidget(widgetId, buildViews(widgetId, widgetType));
    }

    /**
     * Redraws every instance that shows one account in any of its slots. Spec §38.
     *
     * <p>This is why a widget stores account ids: refreshing one account must not
     * make every other widget flicker, must not show account A's balance on a
     * widget the user pointed at account B, and must not miss the widget that
     * shows both. The system is asked which instances exist and the stored slots
     * say which of them care, so a row left behind by a removed widget cannot
     * make this draw something that is not on the screen.
     */
    public void updateWidgetsForAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return;
        }
        List<Integer> bound = configStore.widgetIdsUsingAccount(accountId);
        AppWidgetManager manager = AppWidgetManager.getInstance(appContext);
        if (manager == null) {
            return;
        }
        for (Class<?> provider : providerClasses()) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(appContext, provider));
            if (ids == null) {
                continue;
            }
            for (int widgetId : ids) {
                if (bound.contains(widgetId) || drawsFallback(widgetId)) {
                    manager.updateAppWidget(widgetId, buildViews(widgetId, widgetTypeOf(widgetId)));
                }
            }
        }
    }

    /**
     * True when this widget has no binding of its own.
     *
     * <p>An unbound widget draws the first enabled account, a choice the resolver
     * makes at draw time and no slot row records. Without this the widget would
     * keep showing a stale number for the very account it is displaying, because
     * the reverse lookup that repaints "every widget showing this account" cannot
     * find it.
     */
    private boolean drawsFallback(int widgetId) {
        WidgetConfig config = configStore.find(widgetId);
        return config == null || config.getSlots().isEmpty();
    }

    /**
     * Records which accounts a widget shows, creating its configuration if needed.
     *
     * <p>Replaces the whole slot list rather than editing one row: the picker
     * screens what the user sees, so what it confirms <em>is</em> the widget's
     * configuration, and a leftover slot from an earlier dashboard would keep
     * drawing and keep being refreshed for an account the user has just removed.
     *
     * @param accountIds in display order; each becomes one slot with the default metrics
     */
    public void bindAccounts(int widgetId, String widgetType, List<String> accountIds) {
        WidgetConfig existing = configStore.find(widgetId);
        WidgetConfig config = existing == null
                ? WidgetConfig.unbound(widgetId, widgetType, RefreshPolicy.DEFAULT_INTERVAL_MS)
                : existing;
        List<WidgetSlot> slots = new ArrayList<>();
        for (int index = 0; index < accountIds.size(); index++) {
            slots.add(WidgetSlot.ofAccount(index, accountIds.get(index)));
        }
        configStore.save(config.withSlots(slots));
    }

    /** Forgets a widget's configuration, called when it is removed. */
    public void forget(int widgetId) {
        configStore.delete(widgetId);
    }

    private RemoteViews buildViews(int widgetId, String widgetType) {
        WidgetConfig config = configStore.find(widgetId);
        long interval = configStore.backgroundRefreshIntervalMs();
        // Every number on a widget is resolved by the same Android-free rules the
        // tests assert on, so the widget cannot disagree with the account list
        // about what the same stored row means.
        List<WidgetSlotView> views = WidgetSlotResolver.resolve(config, dataSource(interval),
                System.currentTimeMillis());

        List<PendingIntent> slotIntents = new ArrayList<>();
        for (WidgetSlotView view : views) {
            slotIntents.add(tapTargetFor(widgetId, view));
        }

        return WidgetRenderer.build(
                appContext,
                widgetType,
                views,
                PeakTimeUtils.currentStatus(appContext),
                openListPendingIntent(widgetId),
                WidgetRefreshScheduler.widgetRefreshIntent(appContext, widgetId),
                slotIntents);
    }

    /** The resolver's reads, bound to this manager's stores. */
    private WidgetSlotResolver.DataSource dataSource(long interval) {
        return new WidgetSlotResolver.DataSource() {

            @Override
            public Account findAccount(String accountId) {
                return accountManager.find(accountId);
            }

            @Override
            public Account fallbackAccount() {
                List<Account> enabled = accountManager.listEnabled();
                return enabled.isEmpty() ? null : enabled.get(0);
            }

            @Override
            public AccountRefreshManager.AccountView view(String accountId) {
                return refreshManager.view(accountId);
            }

            @Override
            public BigDecimal dailyUsage(String accountId) {
                return usageRepository.dailyUsage(accountId);
            }

            @Override
            public ProviderCapabilities capabilities(String providerId) {
                return WidgetUpdateManager.this.capabilities(providerId);
            }

            @Override
            public long refreshIntervalMs() {
                return interval;
            }
        };
    }

    /**
     * What a provider offers a widget, or null when its id is not registered.
     *
     * <p>Reading declarations is not the same as calling a provider: the spec's
     * rule is that a widget must not fetch (§53 rule 8), and it keeps that rule
     * by asking the registry what exists rather than asking a provider anything.
     */
    private ProviderCapabilities capabilities(String providerId) {
        UsageProvider provider = ProviderRegistry.get().find(providerId);
        return provider == null ? null : provider.getCapabilities();
    }

    /**
     * Which layout an instance renders with.
     *
     * <p>The system is asked first because it is the authority on which provider
     * owns an id, and the stored row is only a cache of that answer: a row
     * written before the type was known, or one left behind by an earlier
     * version, would otherwise render a 2×1 widget with the 4×2 layout forever.
     * Spec §33.
     */
    private String widgetTypeOf(int widgetId) {
        String actual = typeOf(appContext, widgetId);
        if (!actual.isEmpty()) {
            return actual;
        }
        WidgetConfig config = configStore.find(widgetId);
        if (config != null && !config.getWidgetType().isEmpty()) {
            return config.getWidgetType();
        }
        return WidgetRenderer.TYPE_4X2;
    }

    /**
     * Where a tap on one row goes.
     *
     * <p>A row whose account is gone has no detail page to open: sending the tap
     * to {@code MainActivity} with the dead id made that screen fall back to the
     * first account, so tapping "账户已删除 · 请重新配置该 Slot" opened a
     * <em>different</em> account — the row said one thing and the screen showed
     * another. It now opens the picker for this widget, which is what the hint
     * promises and what makes the picker reachable after placement rather than
     * only while the launcher is placing a widget.
     */
    private PendingIntent tapTargetFor(int widgetId, WidgetSlotView view) {
        if (view.accountId.isEmpty() || accountManager.find(view.accountId) == null) {
            return configPendingIntent(widgetId);
        }
        return accountPendingIntent(widgetId, view.slotIndex, view.accountId);
    }

    /** Re-pointing this widget: opens the slot picker for it. Spec §35. */
    private PendingIntent configPendingIntent(int widgetId) {
        Intent intent = new Intent(appContext, WidgetConfigActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(WidgetConfigActivity.EXTRA_WIDGET_ID, widgetId);
        return PendingIntent.getActivity(
                appContext,
                5001 + widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Tapping the widget body, or a dashboard row, opens the account it shows.
     *
     * <p>Phase 2 gave each widget an account, so a single shared intent would
     * open whichever account happened to be first — the widget would say one
     * thing and the screen would say another. A dashboard now has one intent per
     * row for the same reason, and the request code carries both the widget and
     * the slot: intents that differ only in extras are considered equal, so
     * without distinct codes the second row would open the first row's account
     * and the second widget would open the first widget's.
     */
    private PendingIntent accountPendingIntent(int widgetId, int slotIndex, String accountId) {
        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_ACCOUNT_ID, accountId);
        return PendingIntent.getActivity(
                appContext,
                2001 + widgetId * 16 + slotIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Tapping a dashboard's background opens the account list. Spec §36. */
    private PendingIntent openListPendingIntent(int widgetId) {
        Intent intent = new Intent(appContext, AccountListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                appContext,
                3001 + widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
