package com.aiusage.monitor.provider;

/**
 * A value a provider can put on a widget, declared by id and label. Spec §34.
 *
 * <p>The spec asks providers to publish this list rather than let widgets guess,
 * because the alternative is a widget layer that hard-codes "DeepSeek has a
 * balance" — which is exactly the kind of {@code if (provider == DEEPSEEK)} chain
 * Spec §4 forbids. The picker offers what the account can actually provide, and a
 * slot whose metric disappears (a provider that stopped reporting it) shows a
 * clear message instead of a blank cell.
 *
 * <p>{@code id} is a stable, persisted string — see
 * {@code com.aiusage.monitor.widget.WidgetMetricId} — while {@code label} is
 * wording for the current locale and may be reworded freely.
 */
public final class WidgetMetric {

    private final String id;
    private final String label;

    public WidgetMetric(String id, String label) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label;
    }

    public String getId() {
        return id;
    }

    /** User-visible name, for example "余额". */
    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return id + "=" + label;
    }
}
