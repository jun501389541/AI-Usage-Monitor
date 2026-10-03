package com.aiusage.monitor.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One account's place inside a widget. Spec §33.
 *
 * <p>A widget is a list of these. The spec insists on that shape rather than on
 * two kinds of widget, because "multi-platform" and "several accounts on one
 * platform" are the same thing seen from the outside:
 *
 * <blockquote>不得将多平台和多账户设计成两种完全不同 Widget。本质都是多个
 * Account Slot。</blockquote>
 *
 * <p>The slot binds an <em>account</em> id and never a provider id, which is what
 * lets two DeepSeek accounts sit side by side in one widget and what keeps a
 * widget pointed at the same account after its key is replaced. Spec §28.
 *
 * <p>{@code metricIds} are ids rather than values: which numbers to draw is
 * decided later from what the account actually has, so a slot asking for a
 * metric its account cannot provide degrades to a clear message instead of a
 * blank cell.
 */
public final class WidgetSlot {

    private final int slotIndex;
    private final String accountId;
    private final List<String> metricIds;

    public WidgetSlot(int slotIndex, String accountId, List<String> metricIds) {
        this.slotIndex = slotIndex;
        this.accountId = accountId == null ? "" : accountId;
        this.metricIds = Collections.unmodifiableList(new ArrayList<>(
                metricIds == null ? Collections.<String>emptyList() : metricIds));
    }

    /** A slot that shows one account with the default metrics. */
    public static WidgetSlot ofAccount(int slotIndex, String accountId) {
        return new WidgetSlot(slotIndex, accountId, WidgetMetricId.defaults());
    }

    /**
     * Position inside the widget.
     *
     * <p>Also the display order, deliberately a single field: an independent
     * sort column would be a second source for the same fact, and the two drift
     * apart as soon as anything renumbers one of them.
     */
    public int getSlotIndex() {
        return slotIndex;
    }

    /** The bound account id, or empty when the slot is not filled. */
    public String getAccountId() {
        return accountId;
    }

    /** Never null; the order the user chose, which the display must respect. */
    public List<String> getMetricIds() {
        return metricIds;
    }

    public boolean isFilled() {
        return !accountId.isEmpty();
    }

    @Override
    public String toString() {
        return slotIndex + ":" + accountId + WidgetMetricId.encode(metricIds);
    }
}
