package com.archdroid.swipenav;

import android.content.SharedPreferences;

/** Shared look settings for the control panels (style, accent, rounding, opacity ...). */
public final class PanelPrefs {
    private PanelPrefs() { }

    public static final int STYLE_IOS = 0, STYLE_MIUI = 1;
    public static final String[] STYLES = {"iOS style", "MIUI style"};
    /** index 0 = "Default" (each style uses its own colours). */
    public static final String[] ACCENT_NAMES = {"Default", "Orange", "Blue", "Green", "Pink", "Purple", "Red", "Teal"};
    private static final int[] ACCENTS = {0, 0xFFF07A1E, 0xFF2F8CFF, 0xFF30C46B, 0xFFFF4F8B, 0xFFAF6BFF, 0xFFFF5252, 0xFF1CC8C8};
    public static final int IOS_CORNER_DP = 18, MIUI_CORNER_DP = 6;
    public static final int MIUI_ORANGE = 0xFFF07A1E;

    public static int style(SharedPreferences sp) {
        int s = sp.getInt(SwipeNavService.KEY_PSTYLE, STYLE_IOS);
        return s == STYLE_MIUI ? STYLE_MIUI : STYLE_IOS;
    }

    /** 0 = no override (use the style's own colours), otherwise an ARGB colour. */
    public static int accentOverride(SharedPreferences sp) {
        int i = sp.getInt(SwipeNavService.KEY_ACCENT, 0);
        return i > 0 && i < ACCENTS.length ? ACCENTS[i] : 0;
    }

    public static int defaultCornerDp(int style) { return style == STYLE_MIUI ? MIUI_CORNER_DP : IOS_CORNER_DP; }

    /** Corner radius in dp for the given style (user value wins, else the style default). */
    public static int cornerDp(SharedPreferences sp, int style) {
        int v = sp.getInt(SwipeNavService.KEY_CORNER, -1);
        if (v < 0) return defaultCornerDp(style);
        return v > 40 ? 40 : v;
    }

    /** Panel background opacity 30..100 (%). */
    public static int opacityPct(SharedPreferences sp) {
        int v = sp.getInt(SwipeNavService.KEY_PALPHA, 95);
        return v < 30 ? 30 : (v > 100 ? 100 : v);
    }
}
