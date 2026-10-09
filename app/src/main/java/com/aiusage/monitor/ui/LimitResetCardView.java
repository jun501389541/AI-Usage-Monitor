package com.aiusage.monitor.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.aiusage.monitor.model.LimitResetCredits;
import com.aiusage.monitor.model.LimitResetCreditsStatus;
import com.aiusage.monitor.util.LimitResetWords;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Updating expiry labels never rebuilds the history list. */
public final class LimitResetCardView extends LinearLayout {
    private final int textColor, mutedColor;
    private final TextView count;
    private final LinearLayout rows;
    private final List<TextView> expiryLabels = new ArrayList<>();
    public LimitResetCardView(Context context, int textColor, int mutedColor) {
        super(context);
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("使用限额重置", 11, mutedColor, true), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        count = label("—", 11, textColor, true);
        count.setGravity(Gravity.END);
        header.addView(count, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(header);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        addView(rows);
        addHint("等待读取重置卡信息");
    }
    public void show(LimitResetCredits resets, LimitResetCreditsStatus status, boolean stale, TimeZone timezone) {
        count.setText(LimitResetWords.count(resets, stale));
        rows.removeAllViews();
        expiryLabels.clear();
        if (resets == null) { addHint(LimitResetWords.diagnostic(status)); return; }
        if (resets.getCredits() == null || resets.getCredits().isEmpty()) {
            addHint(resets.getAvailableCount() == 0 ? "当前没有可用重置" : "卡片明细暂不可用");
            return;
        }
        long shownAvailable = 0;
        for (LimitResetCredits.Credit credit : resets.getCredits()) {
            String creditStatus = LimitResetWords.status(credit);
            addRow(label(LimitResetWords.title(credit) + (creditStatus.isEmpty() ? "" : " · " + creditStatus), 13, textColor, true), 18);
            TextView expiry = label("", 11, mutedColor, false);
            expiry.setTag(credit);
            expiry.setSingleLine(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                expiry.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            }
            addRow(expiry, 5);
            expiryLabels.add(expiry);
            if ("available".equals(credit.getStatus())) shownAvailable++;
        }
        if (shownAvailable < resets.getAvailableCount()) addHint("部分卡片明细暂不可用");
        updateExpiryTimes(timezone);
    }
    public void updateExpiryTimes(TimeZone timezone) {
        long now = System.currentTimeMillis();
        for (TextView label : expiryLabels) {
            label.setText(LimitResetWords.expiry((LimitResetCredits.Credit) label.getTag(), now, timezone));
        }
    }
    private void addHint(String message) { addRow(label(message, 11, mutedColor, false), 16); }
    private void addRow(TextView view, int margin) {
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (margin * getResources().getDisplayMetrics().density + 0.5f);
        rows.addView(view, params);
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }
}
