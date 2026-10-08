package com.aiusage.monitor.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Fixed-height history rows identified by their stored snapshot, not their position. */
final class RecentReadingsAdapter extends BaseAdapter {
    static final class Row {
        final long id;
        final String text;

        Row(long id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private final Context context;
    private final int textColor;
    private final List<Row> rows = new ArrayList<>();

    RecentReadingsAdapter(Context context, int textColor) {
        this.context = context;
        this.textColor = textColor;
    }

    boolean submit(List<Row> next) {
        boolean same = rows.size() == next.size();
        for (int i = 0; same && i < rows.size(); i++) {
            same = rows.get(i).id == next.get(i).id && rows.get(i).text.equals(next.get(i).text);
        }
        if (same) {
            return false;
        }
        rows.clear();
        rows.addAll(next);
        notifyDataSetChanged();
        return true;
    }

    int positionOf(long id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    @Override public int getCount() { return Math.max(1, rows.size()); }
    @Override public Row getItem(int position) { return rows.isEmpty() ? null : rows.get(position); }
    @Override public long getItemId(int position) { return rows.isEmpty() ? Long.MIN_VALUE : rows.get(position).id; }
    @Override public boolean hasStableIds() { return true; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return false; }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
        TextView view = recycled instanceof TextView ? (TextView) recycled
                : UiKit.text(context, "", 12, textColor, Typeface.NORMAL);
        view.setIncludeFontPadding(false);
        view.setMinHeight(UiKit.dp(context, 24));
        view.setPadding(0, UiKit.dp(context, 2), UiKit.dp(context, 10), UiKit.dp(context, 2));
        view.setFocusable(false);
        view.setText(rows.isEmpty() ? "还没有读数记录" : rows.get(position).text);
        return view;
    }
}
