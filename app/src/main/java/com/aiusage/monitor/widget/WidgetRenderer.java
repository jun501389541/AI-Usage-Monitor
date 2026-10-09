package com.aiusage.monitor.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;
import com.aiusage.monitor.R;
import com.aiusage.monitor.model.QuotaWindow;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.util.Money;
import com.aiusage.monitor.util.PeakTimeUtils;
import com.aiusage.monitor.util.QuotaWords;
import java.util.List;
import java.util.TimeZone;

/** Renders stored readings without fetching or accessing credentials. */
public final class WidgetRenderer {
    public static final String TYPE_4X2 = "4x2";
    public static final String TYPE_2X2 = "2x2";
    public static final String TYPE_2X1 = "2x1";
    public static final int MAX_SLOTS = 3;
    private static final int MUTED = 0xFFA1A1AA;
    private static final int ATTENTION = 0xFFF59E0B;
    private WidgetRenderer() {}

    public static int layoutFor(String type) {
        return TYPE_4X2.equals(type) ? R.layout.widget_4x2
                : TYPE_2X2.equals(type) ? R.layout.widget_2x2 : R.layout.widget_2x1;
    }

    public static RemoteViews build(Context context, String type, List<WidgetSlotView> slots,
            PeakTimeUtils.Status peak, PendingIntent openApp, PendingIntent refresh,
            List<PendingIntent> taps) {
        RemoteViews draw = new RemoteViews(context.getPackageName(), layoutFor(type));
        boolean dashboard = TYPE_4X2.equals(type);
        boolean minimal = !dashboard && !TYPE_2X2.equals(type);
        draw.setOnClickPendingIntent(R.id.widget_root, dashboard ? openApp : tap(taps, 0, openApp));
        if (!minimal && refresh != null) draw.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        if (dashboard) {
            boolean hasMoneyAccount = false;
            for (int i = 0; i < MAX_SLOTS; i++) {
                int[] ids = rowIds(i);
                WidgetSlotView slot = i < slots.size() ? slots.get(i) : null;
                draw.setViewVisibility(ids[0], slot != null && slot.visible ? View.VISIBLE : View.GONE);
                if (slot == null || !slot.visible) continue;
                hasMoneyAccount |= !slot.quotaAccount;
                draw.setTextViewText(ids[1], slot.title);
                footer(draw, ids[2], slot);
                field(draw, slot, 0, ids[3], ids[4], ids[5], ids[6], true, false);
                field(draw, slot, 1, ids[7], ids[8], ids[9], ids[10], true, false);
                draw.setOnClickPendingIntent(ids[0], tap(taps, i, openApp));
            }
            peak(draw, peak, hasMoneyAccount);
        } else {
            WidgetSlotView slot = slots.isEmpty() ? null : slots.get(0);
            field(draw, slot, 0, R.id.widget_balance_label, R.id.widget_balance,
                    minimal ? 0 : R.id.widget_balance_progress,
                    minimal ? 0 : R.id.widget_balance_reset, false, minimal);
            field(draw, slot, 1, R.id.widget_usage_label, R.id.widget_usage,
                    minimal ? 0 : R.id.widget_usage_progress,
                    minimal ? 0 : R.id.widget_usage_reset, false, minimal);
            if (!minimal) {
                draw.setTextViewText(R.id.widget_account,
                        slot == null ? WidgetSlotResolver.UNCONFIGURED : slot.title);
                footer(draw, R.id.widget_footer, slot);
                peak(draw, peak, slot != null && !slot.quotaAccount);
            }
        }
        return draw;
    }

    private static void field(RemoteViews draw, WidgetSlotView slot, int index,
            int labelId, int valueId, int progressId, int resetId, boolean compact, boolean minimal) {
        boolean quota = slot != null && slot.quotaAccount;
        QuotaWindow window = !quota ? null : index == 0 ? slot.fiveHour : slot.weekly;
        String label = quota ? (index == 0 ? "5小时剩余" : "每周剩余")
                : index == 0 ? "余额" : "今日用量";
        boolean valid = window != null && !Double.isNaN(window.getRemainingPercent())
                && !Double.isInfinite(window.getRemainingPercent());
        int percent = valid ? (int) Math.round(Math.max(0, Math.min(100, window.getRemainingPercent()))) : 0;
        String value = quota ? (valid ? percent + "%" : slot.hasQuotaReading ? "未提供" : Money.EMPTY)
                : slot == null || slot.valueLines.size() <= index ? Money.EMPTY : slot.valueLines.get(index);
        draw.setTextViewText(labelId, label);
        draw.setViewVisibility(labelId, compact ? View.GONE : View.VISIBLE);
        draw.setTextViewText(valueId, compact ? label + " " + value : value);
        if (!minimal) {
            draw.setViewVisibility(progressId, valid ? View.VISIBLE : View.GONE);
            draw.setProgressBar(progressId, 100, percent, false);
            draw.setViewVisibility(resetId, quota ? View.VISIBLE : View.GONE);
            String reset = window == null ? (slot != null && slot.hasQuotaReading ? "未提供此窗口" : "等待查询") : QuotaWords.resetText(window.getResetAt(), System.currentTimeMillis());
            draw.setTextViewText(resetId, reset);
            if (window != null) draw.setContentDescription(resetId,
                    QuotaWords.resetSummary(window.getResetAt(), System.currentTimeMillis(), TimeZone.getDefault()));
        }
        draw.setContentDescription(valueId, (slot == null ? "" : slot.title + "，") + label + " " + value);
    }

    private static void footer(RemoteViews draw, int id, WidgetSlotView slot) {
        draw.setTextViewText(id, slot == null ? "" : slot.footer);
        draw.setContentDescription(id, slot == null ? "" : slot.footer);
        draw.setTextColor(id, slot != null && slot.status != UsageStatus.OK ? ATTENTION : MUTED);
    }
    private static void peak(RemoteViews draw, PeakTimeUtils.Status status, boolean visible) {
        draw.setViewVisibility(R.id.widget_peak_status, visible && status != null ? View.VISIBLE : View.GONE);
        if (status != null) {
            draw.setTextViewText(R.id.widget_peak_status, status.title);
            draw.setTextColor(R.id.widget_peak_status, status.peak ? 0xFFDC2626 : MUTED);
        }
    }
    private static PendingIntent tap(List<PendingIntent> taps, int index, PendingIntent fallback) {
        return taps != null && index < taps.size() && taps.get(index) != null ? taps.get(index) : fallback;
    }
    private static int[] rowIds(int index) {
        switch (index) {
            case 0: return new int[] {R.id.widget_slot_0_root, R.id.widget_slot_0_name,
                R.id.widget_slot_0_footer, R.id.widget_slot_0_value_label, R.id.widget_slot_0_value,
                R.id.widget_slot_0_value_progress, R.id.widget_slot_0_value_reset,
                R.id.widget_slot_0_value2_label, R.id.widget_slot_0_value2,
                R.id.widget_slot_0_value2_progress, R.id.widget_slot_0_value2_reset};
            case 1: return new int[] {R.id.widget_slot_1_root, R.id.widget_slot_1_name,
                R.id.widget_slot_1_footer, R.id.widget_slot_1_value_label, R.id.widget_slot_1_value,
                R.id.widget_slot_1_value_progress, R.id.widget_slot_1_value_reset,
                R.id.widget_slot_1_value2_label, R.id.widget_slot_1_value2,
                R.id.widget_slot_1_value2_progress, R.id.widget_slot_1_value2_reset};
            case 2: return new int[] {R.id.widget_slot_2_root, R.id.widget_slot_2_name,
                R.id.widget_slot_2_footer, R.id.widget_slot_2_value_label, R.id.widget_slot_2_value,
                R.id.widget_slot_2_value_progress, R.id.widget_slot_2_value_reset,
                R.id.widget_slot_2_value2_label, R.id.widget_slot_2_value2,
                R.id.widget_slot_2_value2_progress, R.id.widget_slot_2_value2_reset};
            default: throw new IllegalArgumentException("Unknown widget row");
        }
    }
}
