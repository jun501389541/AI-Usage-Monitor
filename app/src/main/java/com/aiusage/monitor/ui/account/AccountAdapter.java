package com.aiusage.monitor.ui.account;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aiusage.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders one account per row on the account list. Spec §51.
 *
 * <p>A pure renderer: the row's strings are computed by the activity from data
 * already in storage, and this class does no I/O, no formatting decisions and
 * no provider calls. That keeps a scrolling list from turning into a second
 * refresh path — Spec §25 allows exactly one.
 */
public final class AccountAdapter extends BaseAdapter {

    /** One rendered account. Built by the activity from {@code UsageRepository}. */
    public static final class Row {

        final String accountId;
        final String name;
        final String balance;
        final String usage;
        final String status;
        final boolean enabled;
        final boolean degraded;

        public Row(String accountId, String name, String balance, String usage,
                   String status, boolean enabled, boolean degraded) {
            this.accountId = accountId;
            this.name = name;
            this.balance = balance;
            this.usage = usage;
            this.status = status;
            this.enabled = enabled;
            this.degraded = degraded;
        }

        public String getAccountId() {
            return accountId;
        }
    }

    private final Context context;
    private final List<Row> rows = new ArrayList<>();

    public AccountAdapter(Context context) {
        this.context = context;
    }

    /** Replaces the whole list; the list is small and rebuilt on resume. */
    public void setRows(List<Row> newRows) {
        rows.clear();
        if (newRows != null) {
            rows.addAll(newRows);
        }
        notifyDataSetChanged();
    }

    public Row rowAt(int position) {
        return rows.get(position);
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout card;
        if (convertView instanceof LinearLayout) {
            card = (LinearLayout) convertView;
        } else {
            card = UiKit.card(context);
        }

        Row row = rows.get(position);

        // Rebuilt rather than diffed: a row is four TextViews, and a recycled
        // balance that belongs to the previous account is a data-correctness
        // bug, not a performance opportunity.
        card.removeAllViews();

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = UiKit.text(context, row.name, 17, UiKit.COLOR_TEXT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView balance = UiKit.text(context, row.balance, 17,
                row.enabled ? UiKit.COLOR_TEXT : UiKit.COLOR_MUTED, Typeface.BOLD);
        balance.setGravity(Gravity.END);
        titleRow.addView(balance, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(titleRow, UiKit.matchWrap(context, 0));

        LinearLayout detailRow = new LinearLayout(context);
        detailRow.setOrientation(LinearLayout.HORIZONTAL);
        detailRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView status = UiKit.text(context, row.status, 12, UiKit.COLOR_MUTED, Typeface.NORMAL);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        detailRow.addView(status, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView usage = UiKit.text(context, row.usage, 12, UiKit.COLOR_HINT, Typeface.NORMAL);
        usage.setGravity(Gravity.END);
        detailRow.addView(usage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(detailRow, UiKit.matchWrap(context, 6));

        if (!row.enabled) {
            card.addView(UiKit.text(context, "已停用 · 不计入自动刷新", 11,
                    UiKit.COLOR_HINT, Typeface.NORMAL), UiKit.matchWrap(context, 8));
        }
        if (row.degraded) {
            // Degraded protection is a real security state, not a cosmetic one:
            // it means the key is stored without hardware-backed encryption.
            card.addView(UiKit.text(context, "密钥保护降级，建议重新保存", 11,
                    UiKit.COLOR_PEAK, Typeface.NORMAL), UiKit.matchWrap(context, 8));
        }

        // A disabled account stays readable: it keeps its history and its
        // widget bindings, so it must not look deleted.
        card.setAlpha(row.enabled ? 1f : 0.6f);

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int six = UiKit.dp(context, 5);
        wrapper.setPadding(0, six, 0, six);
        wrapper.addView(card, UiKit.matchWrap(context, 0));
        return wrapper;
    }
}
