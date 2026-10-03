package com.aiusage.monitor.widget;

import java.util.List;

/**
 * Persistence for per-widget configuration. Spec §28 and §33.
 *
 * <p>Kept as an interface so the widget layer does not contain SQL and so the
 * binding rules can be exercised without a database.
 *
 * <p>The one contract a fake must not fudge is that a widget's slots are stored
 * and replaced as a set, together with the widget row. Splitting them across two
 * commits is how a dashboard ends up with account A in row one and account B's
 * old row still in the table.
 */
public interface WidgetConfigStore {

    /** The configuration for one widget, or null when it has none yet. */
    WidgetConfig find(int widgetId);

    /** Inserts or replaces a widget's configuration, including its whole slot list. */
    void save(WidgetConfig config);

    /** Removes a widget's configuration and its slots, called when the widget is removed. */
    void delete(int widgetId);

    /** Every stored configuration. */
    List<WidgetConfig> all();

    /**
     * Every widget that shows this account in any of its slots. Spec §38.
     *
     * <p>The direction matters: refreshing an account has to repaint the widgets
     * that reference it, and one account may sit in several widgets — or twice in
     * one — because the spec allows it (§53 rules 16 and 17).
     */
    List<Integer> widgetIdsUsingAccount(String accountId);

    /**
     * The background refresh interval to use, in milliseconds.
     *
     * <p>Read from the first configured widget, or the default when none is
     * configured yet. The scheduler needs this synchronously from a broadcast
     * receiver, so it cannot depend on a network or a provider.
     */
    long backgroundRefreshIntervalMs();
}
