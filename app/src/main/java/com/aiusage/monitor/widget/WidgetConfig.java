package com.aiusage.monitor.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-widget configuration. Spec §28 and §33.
 *
 * <p>The binding a widget cannot do without is to an <em>account</em>:
 * "Widget 必须绑定 Account ID，不能只绑 Provider ID", because one platform hosts
 * several accounts and a widget pointed at the platform would have to guess.
 * Phase 2 recorded one account id per widget; the spec's slot model is the same
 * fact generalised, so a widget is now a list of {@link WidgetSlot} and one
 * account can appear in several of them, in several widgets, at once.
 *
 * <p>The remaining spec fields ({@code layoutMode}, {@code density},
 * {@code theme}) are still absent rather than stubbed, so nothing here pretends
 * to be configurable when it is not.
 */
public final class WidgetConfig {

    private final int widgetId;
    private final String widgetType;
    private final List<WidgetSlot> slots;
    private final long refreshIntervalMs;
    private final int sortOrder;
    private final long updatedAt;

    public WidgetConfig(int widgetId,
                        String widgetType,
                        List<WidgetSlot> slots,
                        long refreshIntervalMs,
                        int sortOrder,
                        long updatedAt) {
        this.widgetId = widgetId;
        this.widgetType = widgetType == null ? "" : widgetType;
        this.slots = sorted(slots);
        this.refreshIntervalMs = refreshIntervalMs;
        this.sortOrder = sortOrder;
        this.updatedAt = updatedAt;
    }

    /** A config for a widget that has not been configured yet: no slots at all. */
    public static WidgetConfig unbound(int widgetId, String widgetType, long refreshIntervalMs) {
        return new WidgetConfig(widgetId, widgetType,
                Collections.<WidgetSlot>emptyList(), refreshIntervalMs, 0, 0L);
    }

    /**
     * The slots in index order.
     *
     * <p>Sorted here rather than trusted from the caller: the renderer draws by
     * index into pre-built layout rows, and a config built from a set, or from
     * SQL without an ORDER BY, would put account B's balance in account A's row.
     */
    private static List<WidgetSlot> sorted(List<WidgetSlot> source) {
        List<WidgetSlot> copy = new ArrayList<>(
                source == null ? Collections.<WidgetSlot>emptyList() : source);
        Collections.sort(copy, (a, b) -> Integer.compare(a.getSlotIndex(), b.getSlotIndex()));
        return Collections.unmodifiableList(copy);
    }

    public int getWidgetId() {
        return widgetId;
    }

    /** A stable label such as {@code 4x2}, {@code 2x2} or {@code 2x1}. */
    public String getWidgetType() {
        return widgetType;
    }

    /** Never null; empty until the user picks something. */
    public List<WidgetSlot> getSlots() {
        return slots;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Every account this widget references, in slot order, without duplicates.
     *
     * <p>The single answer to "which accounts does this widget show". There is
     * deliberately no companion like {@code primaryAccountId()} or
     * {@code hasAccount()}: the display decides that per slot, and a second
     * whole-widget opinion is how one surface ends up disagreeing with another
     * about the same stored rows.
     */
    public List<String> accountIds() {
        List<String> ids = new ArrayList<>();
        for (WidgetSlot slot : slots) {
            if (slot.isFilled() && !ids.contains(slot.getAccountId())) {
                ids.add(slot.getAccountId());
            }
        }
        return ids;
    }

    public WidgetConfig withSlots(List<WidgetSlot> newSlots) {
        return new WidgetConfig(widgetId, widgetType, newSlots, refreshIntervalMs, sortOrder,
                System.currentTimeMillis());
    }
}
