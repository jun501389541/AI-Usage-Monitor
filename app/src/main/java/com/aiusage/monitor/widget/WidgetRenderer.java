package com.aiusage.monitor.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import com.aiusage.monitor.R;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.PeakTimeUtils;

import java.util.List;

/**
 * Draws a widget's {@link RemoteViews} from already-resolved slot views.
 *
 * <p>This class is deliberately dumb: it receives text and colours and lays them
 * out. It does not fetch, decrypt, decode or decide anything. That separation is
 * what makes the spec's rule enforceable — a widget cannot leak a token it never
 * has access to, and cannot disagree with the app about a balance it never
 * computes. Spec §27, §53 rules 8 and 9.
 *
 * <p>The layout choice is driven by an explicit type string rather than by
 * inspecting a layout resource id. The upstream code inferred the widget type
 * from {@code layoutId == R.layout.widget_4x2}, which meant any new layout
 * silently fell into the "no peak status" branch; a named type cannot do that.
 *
 * <p>Size-by-size the slots differ on purpose: the 4×2 is the dashboard and has
 * three pre-built rows (decision D2), the 2×2 is one account given room for a
 * large balance, and the 2×1 is the minimal strip that shows a value and nothing
 * else. A slot beyond a layout's row count is hidden rather than squeezed in.
 */
public final class WidgetRenderer {

    public static final String TYPE_4X2 = "4x2";
    public static final String TYPE_2X2 = "2x2";
    public static final String TYPE_2X1 = "2x1";

    /** How many account rows the 4×2 dashboard has room for. */
    public static final int MAX_SLOTS = 3;

    /** The colour upstream used for a peak-period label. */
    private static final int PEAK_COLOR = 0xFFDC2626;
    /** The colour upstream used for an off-peak label. */
    private static final int OFF_PEAK_COLOR = 0xFFA1A1AA;

    /** A row whose reading is stale, failed or missing warns in the peak colour. */
    private static final int STATUS_ATTENTION_COLOR = 0xFFF59E0B;

    private WidgetRenderer() {
    }

    /** The layout for a widget type, defaulting to the smallest. */
    public static int layoutFor(String widgetType) {
        if (TYPE_4X2.equals(widgetType)) {
            return R.layout.widget_4x2;
        }
        if (TYPE_2X2.equals(widgetType)) {
            return R.layout.widget_2x2;
        }
        return R.layout.widget_2x1;
    }

    /**
     * Builds the views for one widget instance.
     *
     * @param views      the resolved slots, in slot order; a widget draws at most
     *                   {@link #MAX_SLOTS} of them
     * @param peakStatus the current peak/off-peak state, for the layouts that show it
     * @param openApp    what a tap on the widget body opens
     * @param refresh    what a tap on the refresh label fires; null on the 2×1,
     *                   which has no room for one
     * @param slotIntents per-slot taps, index-aligned with {@code views}
     */
    public static RemoteViews build(Context context,
                                   String widgetType,
                                   List<WidgetSlotView> views,
                                   PeakTimeUtils.Status peakStatus,
                                   PendingIntent openApp,
                                   PendingIntent refresh,
                                   List<PendingIntent> slotIntents) {
        RemoteViews draw = new RemoteViews(context.getPackageName(), layoutFor(widgetType));
        if (TYPE_4X2.equals(widgetType)) {
            buildDashboard(context, draw, views, peakStatus, openApp, refresh, slotIntents);
        } else if (TYPE_2X2.equals(widgetType)) {
            buildSingle(context, draw, views, peakStatus, openApp, refresh, slotIntents);
        } else {
            buildMinimal(context, draw, views, openApp, slotIntents);
        }
        return draw;
    }

    // ------------------------------------------------------------- 4×2 dashboard

    private static void buildDashboard(Context context,
                                      RemoteViews views,
                                      List<WidgetSlotView> slots,
                                      PeakTimeUtils.Status peakStatus,
                                      PendingIntent openApp,
                                      PendingIntent refresh,
                                      List<PendingIntent> slotIntents) {
        applyPeak(context, views, peakStatus);
        views.setOnClickPendingIntent(R.id.widget_root, openApp);
        if (refresh != null) {
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        }
        for (int index = 0; index < MAX_SLOTS; index++) {
            SlotIds ids = slotIds(index);
            WidgetSlotView slot = index < slots.size() ? slots.get(index) : null;
            if (slot == null || !slot.visible) {
                // Hidden rather than blank: an unfilled row of dashes in a
                // dashboard the user is still configuring reads as three
                // accounts with no money.
                views.setViewVisibility(ids.root, View.GONE);
                continue;
            }
            views.setViewVisibility(ids.root, View.VISIBLE);
            views.setTextViewText(ids.name, slot.title);
            views.setTextViewText(ids.value, valueOf(slot, 0));
            // The row has room for two metrics, and the slot was configured with
            // two: drawing only the first silently dropped today's usage from the
            // dashboard, which is the one surface the spec says is a dashboard.
            views.setTextViewText(ids.value2, slot.valueLines.size() > 1
                    ? valueOf(slot, 1) : "");
            views.setTextViewText(ids.footer, slot.footer);
            views.setTextColor(ids.footer, attention(slot.status)
                    ? STATUS_ATTENTION_COLOR : OFF_PEAK_COLOR);
            PendingIntent tap = intentFor(slotIntents, index, openApp);
            views.setOnClickPendingIntent(ids.root, tap);
        }
    }

    // ------------------------------------------------------------------- 2×2

    private static void buildSingle(Context context,
                                    RemoteViews views,
                                    List<WidgetSlotView> slots,
                                    PeakTimeUtils.Status peakStatus,
                                    PendingIntent openApp,
                                    PendingIntent refresh,
                                    List<PendingIntent> slotIntents) {
        WidgetSlotView slot = slots.isEmpty() ? null : slots.get(0);
        views.setTextViewText(R.id.widget_account,
                slot == null ? WidgetSlotResolver.UNCONFIGURED
                        : slot.title);
        views.setTextViewText(R.id.widget_balance, valueOf(slot, 0));
        views.setTextViewText(R.id.widget_usage, valueOf(slot, 1));
        views.setTextViewText(R.id.widget_footer, slot == null ? "" : slot.footer);
        views.setTextColor(R.id.widget_footer, slot != null && attention(slot.status)
                ? STATUS_ATTENTION_COLOR : OFF_PEAK_COLOR);
        applyPeak(context, views, peakStatus);
        // A single-account widget opens that account when tapped, wherever on it
        // the tap landed: that is Phase 2's acceptance 4, and a user pointing one
        // widget at one account means to see that account. The dashboard differs,
        // because its rows are different accounts — Spec §36.
        views.setOnClickPendingIntent(R.id.widget_root,
                intentFor(slotIntents, 0, openApp));
        if (refresh != null) {
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        }
        PendingIntent tap = intentFor(slotIntents, 0, openApp);
        views.setOnClickPendingIntent(R.id.widget_account, tap);
    }

    // ------------------------------------------------------------------- 2×1

    private static void buildMinimal(Context context,
                                     RemoteViews views,
                                     List<WidgetSlotView> slots,
                                     PendingIntent openApp,
                                     List<PendingIntent> slotIntents) {
        WidgetSlotView slot = slots.isEmpty() ? null : slots.get(0);
        views.setTextViewText(R.id.widget_balance, valueOf(slot, 0));
        views.setTextViewText(R.id.widget_usage, valueOf(slot, 1));
        views.setOnClickPendingIntent(R.id.widget_root,
                intentFor(slotIntents, 0, openApp));
    }

    // ------------------------------------------------------------- shared bits

    /**
     * The value line for a slot, or an em dash when the slot has none.
     *
     * <p>A missing line is not a zero: the layouts ship with "—" and an index
     * beyond the slot's metric list must keep showing that rather than an empty
     * string the user could read as a cleared balance.
     */
    private static String valueOf(WidgetSlotView slot, int index) {
        if (slot == null || slot.valueLines.size() <= index) {
            return Money.EMPTY;
        }
        return slot.valueLines.get(index);
    }

    private static boolean attention(UsageStatus status) {
        return status != UsageStatus.OK;
    }

    private static PendingIntent intentFor(List<PendingIntent> intents, int index,
                                           PendingIntent fallback) {
        if (intents != null && index < intents.size() && intents.get(index) != null) {
            return intents.get(index);
        }
        return fallback;
    }

    private static void applyPeak(Context context, RemoteViews views,
                                  PeakTimeUtils.Status peakStatus) {
        if (peakStatus == null) {
            return;
        }
        views.setTextViewText(R.id.widget_peak_status, peakStatus.title);
        views.setTextColor(R.id.widget_peak_status,
                peakStatus.peak ? PEAK_COLOR : OFF_PEAK_COLOR);
    }

    /**
     * The row ids for one dashboard slot.
     *
     * <p>Written out per index rather than resolved by name: RemoteViews needs a
     * compile-time id, and {@code getIdentifier} would turn a typo into a row
     * that silently never updates.
     */
    private static SlotIds slotIds(int index) {
        switch (index) {
            case 0:
                return new SlotIds(R.id.widget_slot_0_root, R.id.widget_slot_0_name,
                        R.id.widget_slot_0_value, R.id.widget_slot_0_value2,
                        R.id.widget_slot_0_footer);
            case 1:
                return new SlotIds(R.id.widget_slot_1_root, R.id.widget_slot_1_name,
                        R.id.widget_slot_1_value, R.id.widget_slot_1_value2,
                        R.id.widget_slot_1_footer);
            default:
                return new SlotIds(R.id.widget_slot_2_root, R.id.widget_slot_2_name,
                        R.id.widget_slot_2_value, R.id.widget_slot_2_value2,
                        R.id.widget_slot_2_footer);
        }
    }

    private static final class SlotIds {
        private final int root;
        private final int name;
        private final int value;
        private final int value2;
        private final int footer;

        SlotIds(int root, int name, int value, int value2, int footer) {
            this.root = root;
            this.name = name;
            this.value = value;
            this.value2 = value2;
            this.footer = footer;
        }
    }
}
