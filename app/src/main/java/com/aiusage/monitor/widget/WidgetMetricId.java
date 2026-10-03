package com.aiusage.monitor.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The metric ids a widget slot may ask for, and their stored form. Spec §34.
 *
 * <p>Ids are stable lowercase strings because they are persisted in
 * {@code widget_slots.metric_ids}: renaming one is a data migration, not an edit
 * to a constant, and every reader has to agree on the spelling for a widget to
 * show the thing the user picked.
 *
 * <p>{@code encode}/{@code decode} live here rather than in the SQL store so the
 * round-trip through the stored column is testable without a database — the
 * migration writes the encoded form directly from SQL, so both sides of this
 * format have to match exactly.
 */
public final class WidgetMetricId {

    /** The account's current balance, as reported by its provider. */
    public static final String BALANCE = "balance";

    /**
     * Today's spend. Estimated from how far the balance has fallen since the
     * first reading of the day — a local derivation, not a platform field, and
     * the widget wording ("今日") reflects that. See the README's 今日用量口径.
     */
    public static final String TODAY_USAGE = "today_usage";

    /** Separator of the stored list. No id contains it, so nothing needs escaping. */
    static final String SEPARATOR = "|";

    private WidgetMetricId() {
    }

    /**
     * What a slot shows when the user has not chosen anything.
     *
     * <p>The migration writes exactly these two ids for every existing widget,
     * because existing widgets are the ones whose appearance must not change on
     * upgrade.
     */
    public static List<String> defaults() {
        return Collections.unmodifiableList(Arrays.asList(BALANCE, TODAY_USAGE));
    }

    /** The stored form of a metric list. */
    public static String encode(List<String> metricIds) {
        if (metricIds == null || metricIds.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String id : metricIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(SEPARATOR);
            }
            text.append(id);
        }
        return text.toString();
    }

    /** The stored form back into a list; empty text means no metrics. */
    public static List<String> decode(String stored) {
        List<String> ids = new ArrayList<>();
        if (stored != null && !stored.isEmpty()) {
            for (String part : stored.split(java.util.regex.Pattern.quote(SEPARATOR))) {
                if (!part.isEmpty()) {
                    ids.add(part);
                }
            }
        }
        return Collections.unmodifiableList(ids);
    }
}
