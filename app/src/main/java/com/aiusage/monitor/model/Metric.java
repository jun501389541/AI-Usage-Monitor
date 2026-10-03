package com.aiusage.monitor.model;

/**
 * A single measurable value that is neither a monetary balance nor a quota
 * window. Spec §12.
 *
 * <p>Exists so that future provider data (tokens, request counts, cache hit
 * rates, credits) can be carried without changing {@link UsageResult}.
 */
public final class Metric {

    private final String key;
    private final String label;
    private final double value;
    private final String unit;

    public Metric(String key, String label, double value, String unit) {
        this.key = key == null ? "" : key;
        this.label = label == null ? "" : label;
        this.value = value;
        this.unit = unit == null ? "" : unit;
    }

    /** Stable identifier, for example {@code today_usage}. */
    public String getKey() {
        return key;
    }

    /** Human-readable label, for example "今日使用". */
    public String getLabel() {
        return label;
    }

    public double getValue() {
        return value;
    }

    /** Unit such as {@code CNY}, {@code %}, {@code requests}; may be empty. */
    public String getUnit() {
        return unit;
    }

    @Override
    public String toString() {
        return key + "=" + value + (unit.isEmpty() ? "" : unit);
    }
}
