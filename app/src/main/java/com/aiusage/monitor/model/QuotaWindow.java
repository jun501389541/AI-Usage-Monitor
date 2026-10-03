package com.aiusage.monitor.model;

/**
 * One quota window reported by a provider. Spec §11.
 *
 * <p>The spec is explicit that {@code primary} must not be assumed to be a 5-hour
 * window and {@code secondary} a weekly one. Windows are therefore identified by
 * the provider's own id and labelled with whatever the provider calls them.
 */
public final class QuotaWindow {

    private final String id;
    private final String label;
    private final double usedPercent;
    private final double remainingPercent;
    private final long windowMinutes;
    private final long resetAt;

    public QuotaWindow(String id,
                       String label,
                       double usedPercent,
                       double remainingPercent,
                       long windowMinutes,
                       long resetAt) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label;
        this.usedPercent = usedPercent;
        this.remainingPercent = remainingPercent;
        this.windowMinutes = windowMinutes;
        this.resetAt = resetAt;
    }

    /** Provider-scoped identifier, for example {@code quota_5h}. */
    public String getId() {
        return id;
    }

    /** Human-readable label, for example "5 小时额度". */
    public String getLabel() {
        return label;
    }

    public double getUsedPercent() {
        return usedPercent;
    }

    public double getRemainingPercent() {
        return remainingPercent;
    }

    /** Window length in minutes; 0 when the provider does not report one. */
    public long getWindowMinutes() {
        return windowMinutes;
    }

    /** Epoch millis at which the window resets; 0 when unknown. */
    public long getResetAt() {
        return resetAt;
    }

    @Override
    public String toString() {
        return label + " used=" + usedPercent + " remaining=" + remainingPercent;
    }
}
