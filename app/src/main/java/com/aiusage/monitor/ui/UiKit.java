package com.aiusage.monitor.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one place the app's visual language is defined. Spec §5.
 *
 * <p>Phase 1 carried these helpers inside {@code MainActivity}, which was fine
 * while it was the only screen. Phase 2 adds the account list, the account
 * editor and the widget picker, and four copies of a colour palette is how a
 * dark theme drifts apart one screen at a time.
 *
 * <p>Extracted verbatim from {@code MainActivity}: same colours, same corner
 * radii, same paddings, so every existing screen renders pixel-identically
 * after the move. The methods are static and take a {@link Context} rather than
 * being instance methods so that a plain {@code Context} — not only an
 * {@code Activity} — can build views, which the widget-configuration screen
 * needs.
 */
public final class UiKit {

    public static final int COLOR_BG = 0xFF0B0B0C;
    public static final int COLOR_CARD = 0xFF141416;
    public static final int COLOR_INPUT = 0xFF0F0F11;
    public static final int COLOR_BORDER = 0xFF2D2D31;
    public static final int COLOR_TEXT = 0xFFF5F5F7;
    public static final int COLOR_MUTED = 0xFFA1A1AA;
    public static final int COLOR_HINT = 0xFF71717A;
    public static final int COLOR_BUTTON = 0xFFE5E7EB;
    public static final int COLOR_BUTTON_TEXT = 0xFF0F0F11;
    public static final int COLOR_STATUS = 0xFF27272A;
    public static final int COLOR_PEAK = 0xFFDC2626;

    private UiKit() {
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A rounded rectangle with an optional hairline border. */
    public static GradientDrawable roundRect(Context context, int fillColor, int borderColor,
                                             int radiusDp, int borderDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (borderDp > 0) {
            drawable.setStroke(dp(context, borderDp), borderColor);
        }
        return drawable;
    }

    public static TextView text(Context context, String value, float size, int color, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    /** The card container every section of every screen sits in. */
    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(roundRect(context, COLOR_CARD, COLOR_BORDER, 18, 1));
        card.setElevation(dp(context, 1));
        return card;
    }

    public static TextView actionButton(Context context, String label, boolean primary) {
        TextView button = text(context, label, 15,
                primary ? COLOR_BUTTON_TEXT : COLOR_TEXT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        button.setBackground(primary
                ? roundRect(context, COLOR_BUTTON, 0, 14, 0)
                : roundRect(context, COLOR_INPUT, COLOR_BORDER, 14, 1));
        return button;
    }

    public static LinearLayout.LayoutParams matchWrap(Context context, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        return params;
    }

    public static LinearLayout.LayoutParams matchHeight(Context context, int heightDp, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp));
        params.topMargin = dp(context, topMarginDp);
        return params;
    }
}
