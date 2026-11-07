package com.archdroid.swipenav;

import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MIUI-style toggle shade, drawn straight onto a Canvas (no layouts, no bitmaps):
 * time + date + gear on top, a card with a 4x3 grid of icon/label toggles, a brightness bar,
 * and "Notifications | Toggles" tabs at the bottom. Look is driven by PanelPrefs
 * (accent colour, corner rounding, opacity, labels, tabs).
 */
public class MiuiPanelView extends View implements PanelApi {
    private static final int A_AIR = 0, A_BT = 1, A_SYNC = 2, A_SHOT = 3, A_ROT = 4, A_VIB = 5, A_RING = 6,
            A_GPS = 7, A_WIFI = 8, A_DATA = 9, A_AUTO = 10, A_MORE = 11, N = 12;
    private static final int I_GEAR = 30, I_SUN = 31;
    private static final String[] LABELS = {"Airplane", "BT", "Auto sync", "Screenshot", "Rotation", "Vibrate",
            "Ring", "GPS", "Wi-Fi", "Data", "Auto-Backlight", "More"};
    private static final int MIN_BRIGHT = 10;
    private static final int GRAY_ICON = 0xFFB4B4B4, GRAY_TEXT = 0xFFC4C4C4;

    private final float d = getResources().getDisplayMetrics().density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final RectF card = new RectF();
    private final RectF[] cell = new RectF[N];
    private final boolean[] on = new boolean[N];
    private final Handler h = new Handler();
    private final Runnable closer;
    private final AudioManager am;
    private final WifiManager wifi;
    private final BluetoothAdapter bt;

    // look
    private final int accent, bgAlpha;
    private final float corner;
    private final boolean showLabels, showTabs;

    // geometry
    private float sw, sh, top0, rowH, sliderH, sliderCy, trackL, trackR, gearX, gearY, tabTop, tabH;

    private int soundMode;
    private float bright;
    private String timeStr = "", dateStr = "";
    private float prog;
    private boolean closing, closed, manualSet, outsideDown, gearDown, dragSlider;
    private int pressTile = -1, tabDown;
    private float downX, downY;
    private long lastWrite;

    private final Runnable refreshRun = new Runnable() {
        public void run() { refresh(); }
    };

    public MiuiPanelView(Context c, Runnable closer) {
        super(c);
        this.closer = closer;
        SharedPreferences sp = c.getSharedPreferences(SwipeNavService.PREFS, Context.MODE_PRIVATE);
        int a = PanelPrefs.accentOverride(sp);
        accent = a != 0 ? a : PanelPrefs.MIUI_ORANGE;
        corner = PanelPrefs.cornerDp(sp, PanelPrefs.STYLE_MIUI) * d;
        bgAlpha = PanelPrefs.opacityPct(sp) * 255 / 100;
        showLabels = sp.getBoolean(SwipeNavService.KEY_LABELS, true);
        showTabs = sp.getBoolean(SwipeNavService.KEY_TABS, true);
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        bt = BluetoothAdapter.getDefaultAdapter();
        for (int i = 0; i < N; i++) cell[i] = new RectF();
        refresh();
    }

    // ------------------------------------------------------------------ state

    private boolean mobileData() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            return (Boolean) ConnectivityManager.class.getMethod("getMobileDataEnabled").invoke(cm);
        } catch (Throwable t) {
            return false;
        }
    }

    private void refresh() {
        ContentResolver cr = getContext().getContentResolver();
        try { on[A_AIR] = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1; } catch (Throwable t) { }
        on[A_BT] = bt != null && (bt.isEnabled() || bt.getState() == BluetoothAdapter.STATE_TURNING_ON);
        on[A_SYNC] = ContentResolver.getMasterSyncAutomatically();
        on[A_SHOT] = false;
        on[A_ROT] = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
        soundMode = am.getRingerMode();
        on[A_VIB] = soundMode == AudioManager.RINGER_MODE_VIBRATE;
        on[A_RING] = soundMode == AudioManager.RINGER_MODE_NORMAL;
        on[A_GPS] = Settings.Secure.getInt(cr, "location_mode", 0) != 0;
        on[A_WIFI] = wifi != null && wifi.isWifiEnabled();
        on[A_DATA] = mobileData();
        on[A_AUTO] = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        on[A_MORE] = false;
        float b = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) - MIN_BRIGHT)
                / (float) (255 - MIN_BRIGHT);
        bright = b < 0 ? 0 : (b > 1 ? 1 : b);
        Date now = new Date();
        timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        dateStr = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault());
        invalidate();
    }

    // ----------------------------------------------------------------- layout

    @Override protected void onSizeChanged(int w, int hgt, int ow, int oh) {
        sw = w; sh = hgt;
        float m = 10 * d;
        top0 = 26 * d;
        card.left = m;
        card.right = w - m;
        card.top = top0 + 54 * d;
        float cw = card.width(), cellW = cw / 4f;
        rowH = Math.min(cw * 0.28f, 100 * d);
        for (int i = 0; i < N; i++) {
            int col = i % 4, row = i / 4;
            cell[i].set(card.left + col * cellW, card.top + row * rowH,
                    card.left + (col + 1) * cellW, card.top + (row + 1) * rowH);
        }
        sliderH = Math.min(cw * 0.17f, 60 * d);
        card.bottom = card.top + 3 * rowH + sliderH;
        sliderCy = card.bottom - sliderH / 2f;
        trackL = card.left + cw * 0.153f;
        trackR = card.left + cw * 0.82f;
        gearX = w - m - 20 * d;
        gearY = top0 + 26 * d;
        tabH = showTabs ? 52 * d : 0;
        tabTop = hgt - tabH;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        float target = closing ? 0f : 1f;
        boolean animating = false;
        if (Math.abs(target - prog) > 0.004f) {
            prog += (target - prog) * 0.24f;
            animating = true;
        } else {
            prog = target;
        }
        if (closing && prog <= 0f && !closed) {
            closed = true;
            h.post(closer);
            return;
        }
        c.save();
        c.translate(0, -(1f - prog) * sh);

        // shade background
        p.setStyle(Paint.Style.FILL);
        p.setColor((bgAlpha << 24) | 0x3A3A3A);
        c.drawRect(0, 0, sw, sh, p);

        // header: time, date, gear button
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(30 * d);
        p.setColor(0xFFFFFFFF);
        float hx = 14 * d, hy = top0 + 36 * d;
        c.drawText(timeStr, hx, hy, p);
        float tw = p.measureText(timeStr);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(11 * d);
        p.setColor(0xFFA8A8A8);
        c.drawText(dateStr, hx + tw + 10 * d, hy - 4 * d, p);
        p.setColor(0x66000000);
        c.drawCircle(gearX, gearY, 19 * d, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x33FFFFFF);
        c.drawCircle(gearX, gearY, 19 * d, p);
        icon(c, I_GEAR, gearX, gearY, 8.5f * d, 0xFFE6E6E6);

        // card
        p.setStyle(Paint.Style.FILL);
        p.setColor((bgAlpha << 24) | 0x262626);
        c.drawRoundRect(card, corner, corner, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x22FFFFFF);
        c.drawRoundRect(card, corner, corner, p);
        for (int r = 1; r <= 3; r++) {
            float ly = card.top + r * rowH;
            c.drawLine(card.left + 8 * d, ly, card.right - 8 * d, ly, p);
        }

        for (int i = 0; i < N; i++) drawCell(c, i);
        drawSlider(c);
        if (showTabs) drawTabs(c);

        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private void drawCell(Canvas c, int i) {
        RectF r = cell[i];
        if (i == pressTile) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x24FFFFFF);
            rf.set(r.left + 3 * d, r.top + 3 * d, r.right - 3 * d, r.bottom - 3 * d);
            c.drawRoundRect(rf, corner, corner, p);
        }
        int fg = on[i] ? accent : GRAY_ICON;
        float cy = showLabels ? r.top + rowH * 0.37f : r.centerY();
        icon(c, i, r.centerX(), cy, 12 * d, fg);
        if (showLabels) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(11.5f * d);
            p.setColor(on[i] ? accent : GRAY_TEXT);
            c.drawText(LABELS[i], r.centerX(), r.bottom - rowH * 0.17f, p);
        }
    }

    private void drawSlider(Canvas c) {
        float cw = card.width(), cy = sliderCy, th = 4.5f * d;
        float kx = trackL + (trackR - trackL) * bright;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFD9D9D9);
        rf.set(trackL, cy - th, trackR, cy + th);
        c.drawRoundRect(rf, th, th, p);
        p.setColor(accent);
        rf.set(trackL, cy - th, kx, cy + th);
        c.drawRoundRect(rf, th, th, p);
        p.setColor(0x44000000);
        c.drawCircle(kx, cy + d, 12.5f * d, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(kx, cy, 11.5f * d, p);
        // dim (small ring) on the left, sun on the right
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * d);
        p.setColor(GRAY_ICON);
        c.drawCircle(card.left + cw * 0.066f, cy, 5 * d, p);
        icon(c, I_SUN, card.left + cw * 0.93f, cy, 9 * d, GRAY_ICON);
    }

    private void drawTabs(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor((bgAlpha << 24) | 0x1E1E1E);
        c.drawRect(0, tabTop, sw, sh, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x22FFFFFF);
        c.drawLine(0, tabTop, sw, tabTop, p);
        // little notch above the selected tab
        float nx = sw * 0.75f;
        p.setStyle(Paint.Style.FILL);
        p.setColor((bgAlpha << 24) | 0x1E1E1E);
        path.reset();
        path.moveTo(nx - 8 * d, tabTop + 1f);
        path.lineTo(nx, tabTop - 6 * d);
        path.lineTo(nx + 8 * d, tabTop + 1f);
        path.close();
        c.drawPath(path, p);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(15 * d);
        float by = tabTop + tabH / 2f + 5 * d;
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(0xFF9A9A9A);
        c.drawText("Notifications", sw * 0.25f, by, p);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setColor(0xFFFFFFFF);
        c.drawText("Toggles", nx, by, p);
    }

    private void head(Canvas c, float px, float py, float nx, float ny, float tx, float ty, float s) {
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(px + tx * 0.6f * s, py + ty * 0.6f * s);
        path.lineTo(px + nx * 0.5f * s, py + ny * 0.5f * s);
        path.lineTo(px - nx * 0.5f * s, py - ny * 0.5f * s);
        path.close();
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
    }

    private void centerText(Canvas c, String t, float cx, float baseY, float size) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        c.drawText(t, cx, baseY, p);
        p.setStyle(Paint.Style.STROKE);
    }

    /** Hand-drawn outline icons: strokes and paths only, no assets. */
    private void icon(Canvas c, int id, float cx, float cy, float s, int fg) {
        p.setColor(fg);
        p.setStrokeWidth(1.9f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        switch (id) {
            case A_AIR: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx, cy - s);
                path.lineTo(cx + 0.14f * s, cy - 0.55f * s);
                path.lineTo(cx + 0.14f * s, cy - 0.15f * s);
                path.lineTo(cx + 0.95f * s, cy + 0.35f * s);
                path.lineTo(cx + 0.95f * s, cy + 0.55f * s);
                path.lineTo(cx + 0.14f * s, cy + 0.3f * s);
                path.lineTo(cx + 0.14f * s, cy + 0.75f * s);
                path.lineTo(cx + 0.4f * s, cy + 0.95f * s);
                path.lineTo(cx + 0.4f * s, cy + 1.05f * s);
                path.lineTo(cx, cy + 0.95f * s);
                path.lineTo(cx - 0.4f * s, cy + 1.05f * s);
                path.lineTo(cx - 0.4f * s, cy + 0.95f * s);
                path.lineTo(cx - 0.14f * s, cy + 0.75f * s);
                path.lineTo(cx - 0.14f * s, cy + 0.3f * s);
                path.lineTo(cx - 0.95f * s, cy + 0.55f * s);
                path.lineTo(cx - 0.95f * s, cy + 0.35f * s);
                path.lineTo(cx - 0.14f * s, cy - 0.15f * s);
                path.lineTo(cx - 0.14f * s, cy - 0.55f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case A_BT: {
                float a = 0.62f * s;
                path.reset();
                path.moveTo(cx - a, cy - 0.5f * s);
                path.lineTo(cx + a, cy + 0.5f * s);
                path.lineTo(cx, cy + s);
                path.lineTo(cx, cy - s);
                path.lineTo(cx + a, cy - 0.5f * s);
                path.lineTo(cx - a, cy + 0.5f * s);
                c.drawPath(path, p);
                break;
            }
            case A_SYNC: {
                float rr = 0.85f * s;
                rf.set(cx - rr, cy - rr, cx + rr, cy + rr);
                c.drawArc(rf, 200, 130, false, p);
                c.drawArc(rf, 20, 130, false, p);
                for (int k = 0; k < 2; k++) {
                    double ang = Math.toRadians(k == 0 ? 330 : 150);
                    float nx = (float) Math.cos(ang), ny = (float) Math.sin(ang);
                    head(c, cx + rr * nx, cy + rr * ny, nx, ny, -ny, nx, s);
                }
                centerText(c, "A", cx, cy + 0.3f * s, 0.8f * s);
                break;
            }
            case A_SHOT: {
                float q = 0.95f * s, l = 0.45f * s;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        c.drawLine(cx + sx * q, cy + sy * (q - l), cx + sx * q, cy + sy * q, p);
                        c.drawLine(cx + sx * (q - l), cy + sy * q, cx + sx * q, cy + sy * q, p);
                    }
                }
                rf.set(cx - 0.45f * s, cy - 0.3f * s, cx + 0.45f * s, cy + 0.4f * s);
                c.drawRoundRect(rf, 0.12f * s, 0.12f * s, p);
                c.drawCircle(cx, cy + 0.05f * s, 0.17f * s, p);
                break;
            }
            case A_ROT: {
                float rr = 0.85f * s;
                rf.set(cx - rr, cy - rr, cx + rr, cy + rr);
                c.drawArc(rf, 40, 280, false, p);
                double a = Math.toRadians(320);
                float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
                head(c, cx + rr * nx, cy + rr * ny, nx, ny, -ny, nx, s);
                break;
            }
            case A_VIB: {
                rf.set(cx - 0.45f * s, cy - 0.85f * s, cx + 0.45f * s, cy + 0.85f * s);
                c.drawRoundRect(rf, 0.2f * s, 0.2f * s, p);
                c.drawLine(cx - 0.85f * s, cy - 0.4f * s, cx - 0.85f * s, cy + 0.4f * s, p);
                c.drawLine(cx + 0.85f * s, cy - 0.4f * s, cx + 0.85f * s, cy + 0.4f * s, p);
                c.drawLine(cx - 1.15f * s, cy - 0.2f * s, cx - 1.15f * s, cy + 0.2f * s, p);
                c.drawLine(cx + 1.15f * s, cy - 0.2f * s, cx + 1.15f * s, cy + 0.2f * s, p);
                break;
            }
            case A_RING: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - 0.75f * s, cy + 0.55f * s);
                path.lineTo(cx - 0.6f * s, cy + 0.4f * s);
                path.lineTo(cx - 0.6f * s, cy - 0.15f * s);
                rf.set(cx - 0.6f * s, cy - 0.85f * s, cx + 0.6f * s, cy + 0.35f * s);
                path.arcTo(rf, 180, 180);
                path.lineTo(cx + 0.6f * s, cy + 0.4f * s);
                path.lineTo(cx + 0.75f * s, cy + 0.55f * s);
                path.close();
                c.drawPath(path, p);
                c.drawCircle(cx, cy + 0.85f * s, 0.22f * s, p);
                break;
            }
            case A_GPS: {
                c.drawCircle(cx, cy, 0.62f * s, p);
                c.drawLine(cx, cy - 0.95f * s, cx, cy - 0.62f * s, p);
                c.drawLine(cx, cy + 0.62f * s, cx, cy + 0.95f * s, p);
                c.drawLine(cx - 0.95f * s, cy, cx - 0.62f * s, cy, p);
                c.drawLine(cx + 0.62f * s, cy, cx + 0.95f * s, cy, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 0.22f * s, p);
                break;
            }
            case A_WIFI: {
                float oy = cy + 0.8f * s;
                for (int k = 1; k <= 3; k++) {
                    float rad = s * 0.55f * k;
                    rf.set(cx - rad, oy - rad, cx + rad, oy + rad);
                    c.drawArc(rf, 225, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy, 1.7f * d, p);
                break;
            }
            case A_DATA: {
                float lx = cx - 0.45f * s, rx = cx + 0.45f * s;
                c.drawLine(lx, cy + 0.8f * s, lx, cy - 0.5f * s, p);
                head(c, lx, cy - 0.7f * s, 0, -1, 1, 0, s);
                c.drawLine(rx, cy - 0.8f * s, rx, cy + 0.5f * s, p);
                head(c, rx, cy + 0.7f * s, 0, 1, -1, 0, s);
                break;
            }
            case A_AUTO: {
                c.drawCircle(cx, cy, 0.95f * s, p);
                centerText(c, "A", cx, cy + 0.38f * s, 1.15f * s);
                break;
            }
            case A_MORE: {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - 0.75f * s, cy, 1.9f * d, p);
                c.drawCircle(cx, cy, 1.9f * d, p);
                c.drawCircle(cx + 0.75f * s, cy, 1.9f * d, p);
                break;
            }
            case I_GEAR: {
                c.drawCircle(cx, cy, 0.6f * s, p);
                for (int k = 0; k < 8; k++) {
                    float a = (float) (k * Math.PI / 4);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(cx + ca * 0.85f * s, cy + sa * 0.85f * s, cx + ca * 1.1f * s, cy + sa * 1.1f * s, p);
                }
                break;
            }
            case I_SUN: {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 0.42f * s, p);
                p.setStyle(Paint.Style.STROKE);
                for (int k = 0; k < 8; k++) {
                    float a = (float) (k * Math.PI / 4);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(cx + ca * 0.7f * s, cy + sa * 0.7f * s, cx + ca * 1.0f * s, cy + sa * 1.0f * s, p);
                }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ touch

    public void requestClose() {
        if (!closing) {
            closing = true;
            invalidate();
        }
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closing || prog < 0.98f) return true;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y;
                pressTile = -1; dragSlider = false; tabDown = 0; gearDown = false; outsideDown = false;
                if (showTabs && y >= tabTop) {
                    tabDown = x < sw / 2f ? 1 : 2;
                } else if (Math.abs(x - gearX) < 26 * d && Math.abs(y - gearY) < 26 * d) {
                    gearDown = true;
                } else if (card.contains(x, y)) {
                    if (y >= card.top + 3 * rowH) {
                        dragSlider = true;
                        setBright(x, false);
                    } else {
                        for (int i = 0; i < N; i++) if (cell[i].contains(x, y)) pressTile = i;
                    }
                } else {
                    outsideDown = true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragSlider) {
                    setBright(x, false);
                } else {
                    if (Math.abs(x - downX) > 12 * d || Math.abs(y - downY) > 12 * d) {
                        pressTile = -1; tabDown = 0; gearDown = false;
                    }
                    if (downY - y > 56 * d) requestClose();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragSlider) {
                    setBright(x, true);
                } else if (pressTile >= 0) {
                    int t = pressTile;
                    pressTile = -1;
                    tap(t);
                } else if (tabDown == 1) {
                    openShade();
                } else if (gearDown) {
                    openSettings();
                } else if (outsideDown && Math.abs(y - downY) < 12 * d) {
                    requestClose();
                }
                pressTile = -1; dragSlider = false; tabDown = 0; gearDown = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                pressTile = -1; dragSlider = false; tabDown = 0; gearDown = false;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void setBright(float x, boolean commit) {
        float v = (x - trackL) / (trackR - trackL);
        v = v < 0 ? 0 : (v > 1 ? 1 : v);
        bright = v;
        long now = SystemClock.uptimeMillis();
        if (commit || now - lastWrite >= 60) {
            try {
                ContentResolver cr = getContext().getContentResolver();
                if (!manualSet) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    manualSet = true;
                    on[A_AUTO] = false;
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                        MIN_BRIGHT + Math.round(v * (255 - MIN_BRIGHT)));
            } catch (Throwable t) { }
            lastWrite = now;
        }
    }

    private void openSettings() {
        try {
            getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) { }
        requestClose();
    }

    /** "Notifications" tab: close this shade, then pull down the system notification shade. */
    private void openShade() {
        final Context ctx = getContext();
        requestClose();
        new Handler().postDelayed(new Runnable() {
            public void run() {
                try {
                    Object sb = ctx.getSystemService("statusbar");
                    Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
                } catch (Throwable t) { }
            }
        }, 380);
    }

    private void tap(int t) {
        final Context ctx = getContext();
        ContentResolver cr = ctx.getContentResolver();
        try {
            switch (t) {
                case A_AIR: {
                    boolean en = !on[A_AIR];
                    Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, en ? 1 : 0);
                    boolean sent = true;
                    try {
                        ctx.sendBroadcast(new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", en));
                    } catch (Throwable ex) {
                        sent = false;
                    }
                    if (sent) {
                        on[A_AIR] = en;
                    } else {
                        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, en ? 0 : 1);
                        Toast.makeText(ctx, "Airplane mode is not allowed on this ROM", Toast.LENGTH_SHORT).show();
                    }
                    break;
                }
                case A_BT:
                    if (bt != null) {
                        if (on[A_BT]) bt.disable(); else bt.enable();
                        on[A_BT] = !on[A_BT];
                    } else {
                        Toast.makeText(ctx, "No Bluetooth on this device", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case A_SYNC:
                    ContentResolver.setMasterSyncAutomatically(!on[A_SYNC]);
                    on[A_SYNC] = !on[A_SYNC];
                    break;
                case A_SHOT:
                    requestClose();
                    new Handler().postDelayed(new Runnable() {
                        public void run() { Keys.press(ctx, KeyEvent.KEYCODE_SYSRQ); }
                    }, 650);
                    return;
                case A_ROT:
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, on[A_ROT] ? 0 : 1);
                    on[A_ROT] = !on[A_ROT];
                    break;
                case A_VIB:
                    soundMode = on[A_VIB] ? AudioManager.RINGER_MODE_NORMAL : AudioManager.RINGER_MODE_VIBRATE;
                    am.setRingerMode(soundMode);
                    on[A_VIB] = soundMode == AudioManager.RINGER_MODE_VIBRATE;
                    on[A_RING] = soundMode == AudioManager.RINGER_MODE_NORMAL;
                    break;
                case A_RING:
                    soundMode = on[A_RING] ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                    am.setRingerMode(soundMode);
                    on[A_VIB] = soundMode == AudioManager.RINGER_MODE_VIBRATE;
                    on[A_RING] = soundMode == AudioManager.RINGER_MODE_NORMAL;
                    break;
                case A_GPS:
                    Settings.Secure.putInt(cr, "location_mode", on[A_GPS] ? 0 : 3);
                    on[A_GPS] = !on[A_GPS];
                    break;
                case A_WIFI:
                    if (wifi != null) { boolean en = !on[A_WIFI]; wifi.setWifiEnabled(en); on[A_WIFI] = en; }
                    break;
                case A_DATA: {
                    ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                    boolean en = !on[A_DATA];
                    ConnectivityManager.class.getMethod("setMobileDataEnabled", boolean.class).invoke(cm, en);
                    on[A_DATA] = en;
                    break;
                }
                case A_AUTO: {
                    boolean en = !on[A_AUTO];
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, en
                            ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    on[A_AUTO] = en;
                    manualSet = !en;
                    break;
                }
                case A_MORE:
                    openSettings();
                    return;
                default:
                    break;
            }
        } catch (Throwable ex) {
            Toast.makeText(ctx, "Not allowed on this ROM", Toast.LENGTH_SHORT).show();
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        h.removeCallbacks(refreshRun);
        h.postDelayed(refreshRun, 900);
        invalidate();
    }
}
