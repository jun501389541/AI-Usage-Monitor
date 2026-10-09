package com.aiusage.monitor.widget;

/**
 * What one alarm firing actually obliges this app to do. Spec §37, §42.
 *
 * <p>Midnight is a calendar event, not new information: the "today" figure a widget
 * shows is read from the rows already stored, and with no row for the new day the
 * answer is zero. The background refresh is the opposite — it exists to produce a
 * new row. Keeping the two apart is what makes "this phone has no widget" mean "no
 * daily network use"; the receiver used to test {@code midnight || hasWidgets}, so
 * removing every widget still left a daily query of every account and a prune
 * running behind an alarm nobody could see (docs/PHASE-0-7-REVIEW.md §2.2).
 *
 * <p>A decision this small is still worth its own type: it is the only place the
 * two triggers are distinguished, and it is the part that can be tested on a host
 * JVM rather than argued about.
 */
enum RefreshDuty {
    /** Query every enabled account, prune what retention judges, then redraw. */
    REFRESH_AND_REDRAW,

    /** Query only opted-in Direct accounts; there are no widgets to redraw. */
    DIRECT_ONLY,

    /** Redraw from the stored rows: no provider call, no pruning. */
    REDRAW_ONLY,

    /** Nothing to do. */
    NOTHING;

    /**
     * @param midnight     true for the daily midnight alarm, false for the interval
     *                     refresh
     * @param widgetsShown whether any widget instance still exists
     */
    static RefreshDuty forAlarm(boolean midnight, boolean widgetsShown) {
        return forAlarm(midnight, widgetsShown, false);
    }

    static RefreshDuty forAlarm(boolean midnight, boolean widgetsShown, boolean directAccountsEnabled) {
        if (midnight) return widgetsShown ? REDRAW_ONLY : NOTHING;
        if (widgetsShown) return REFRESH_AND_REDRAW;
        return directAccountsEnabled ? DIRECT_ONLY : NOTHING;
    }
}
