package com.example.shizukuaccessibilitygrant.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    public static final int COLOR_BACKGROUND = 0xFFF5F7F8;
    public static final int COLOR_SURFACE = 0xFFFFFFFF;
    public static final int COLOR_SURFACE_MUTED = 0xFFEFF3F2;
    public static final int COLOR_TEXT = 0xFF10201D;
    public static final int COLOR_MUTED = 0xFF64706D;
    public static final int COLOR_PRIMARY = 0xFF0F766E;
    public static final int COLOR_PRIMARY_DARK = 0xFF0F3B35;
    public static final int COLOR_BLUE = 0xFF2563EB;
    public static final int COLOR_WARN = 0xFF8A3A13;
    public static final int COLOR_DANGER = 0xFFB42318;
    public static final int COLOR_BORDER = 0xFFDCE4E2;

    private UiKit() {
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, Math.round(radiusDp)));
        return drawable;
    }

    public static GradientDrawable roundedStroke(int color, int strokeColor, float radiusDp, Context context) {
        GradientDrawable drawable = rounded(color, radiusDp, context);
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    public static void styleTitle(TextView view, int sp) {
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_TEXT);
        view.setIncludeFontPadding(true);
    }

    public static void styleBody(TextView view) {
        view.setTextSize(14);
        view.setTextColor(COLOR_MUTED);
        view.setLineSpacing(0, 1.08f);
    }

    public static void styleCaption(TextView view) {
        view.setTextSize(12);
        view.setTextColor(COLOR_MUTED);
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        card.setBackground(roundedStroke(COLOR_SURFACE, COLOR_BORDER, 8, context));
        return card;
    }

    public static void stylePrimaryButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(COLOR_PRIMARY, 8, button.getContext()));
    }

    public static void styleSecondaryButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(14);
        button.setBackground(roundedStroke(COLOR_SURFACE, COLOR_BORDER, 8, button.getContext()));
    }

    public static void styleDangerButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(COLOR_DANGER);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(roundedStroke(0xFFFFF5F3, 0xFFF4C7C0, 8, button.getContext()));
    }

    public static void styleTab(Button button, boolean selected) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setTextColor(selected ? 0xFFFFFFFF : COLOR_TEXT);
        button.setBackground(selected
                ? rounded(COLOR_PRIMARY_DARK, 8, button.getContext())
                : roundedStroke(COLOR_SURFACE, COLOR_BORDER, 8, button.getContext()));
    }

    public static void setEnabledVisual(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }
}
