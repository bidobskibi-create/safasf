package com.archdroid.swipenav;

import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One lightweight control panel, drawn straight onto a Canvas (no layouts, no bitmaps).
 * iOS-style layout: a 2x2 connectivity block + two tall sliders, then a row of quick tiles.
 * The view only exists while the panel is open and only redraws while animating or touched.
 */
public class PanelView extends View implements PanelApi {
    static final int T_WIFI = 0, T_BT = 1, T_LOC = 2, T_ROT = 3, T_SND = 4, T_TMO = 5, T_GST = 6, T_SET = 7;
    private static final int I_SUN = 20, I_VOL = 21;
    private static final int[] TIMEOUTS = {15000, 30000, 60000, 120000, 300000, 600000};
    private static final int[] ACCENT = {0xFF2F8CFF, 0xFF2F8CFF, 0xFF30C46B, 0xFFFF9500,
            0xFFAF6BFF, 0xFF8E8E93, 0xFF5E5CE6, 0xFF8E8E93};
    private static final int MIN_BRIGHT = 10;
    private float rTile, rBox;          // corner radii (px), from Panel look settings
    private int accentAll, boxAlpha;    // accent override (0 = per-tile colours), box opacity 0..255

    private final float d = getResources().getDisplayMetrics().density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final Handler h = new Handler();
    private final Runnable closer;
    private final AudioManager am;
    private final WifiManager wifi;
    private final BluetoothAdapter bt;

    private final RectF[] tile = new RectF[8];
    private final RectF[] slider = new RectF[2];   // 0 = brightness, 1 = volume
    private final RectF box = new RectF();
    private final boolean[] on = new boolean[8];
    private float pad, hh;
    private int soundMode, timeoutMs, battery = -1;
    private float bright, vol;
    private String timeStr = "", dateStr = "";

    private float prog;            // open animation 0..1
    private boolean closing, closed, manualSet;
    private int pressTile = -1, dragSlider = -1;
    private float downX, downY;
    private boolean outsideDown;
    private long lastWrite;

    private final Runnable refreshRun = new Runnable() {
        public void run() { refresh(); }
    };

    public PanelView(Context c, Runnable closer) {
        super(c);
        this.closer = closer;
        SharedPreferences sp = c.getSharedPreferences(SwipeNavService.PREFS, Context.MODE_PRIVATE);
        int cd = PanelPrefs.cornerDp(sp, PanelPrefs.STYLE_IOS);
        rTile = cd * d;
        rBox = (cd == PanelPrefs.IOS_CORNER_DP ? 28 : cd * 1.55f) * d;
        accentAll = PanelPrefs.accentOverride(sp);
        boxAlpha = PanelPrefs.opacityPct(sp) * 255 / 100;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        bt = BluetoothAdapter.getDefaultAdapter();
        for (int i = 0; i < tile.length; i++) tile[i] = new RectF();
        slider[0] = new RectF();
        slider[1] = new RectF();
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl >= 0 && sc > 0) battery = lvl * 100 / sc;
        }
        refresh();
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        ContentResolver cr = getContext().getContentResolver();
        on[T_WIFI] = wifi != null && wifi.isWifiEnabled();
        on[T_BT] = bt != null && (bt.isEnabled() || bt.getState() == BluetoothAdapter.STATE_TURNING_ON);
        on[T_LOC] = Settings.Secure.getInt(cr, "location_mode", 0) != 0;
        on[T_ROT] = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
        soundMode = am.getRingerMode();
        on[T_SND] = soundMode == AudioManager.RINGER_MODE_NORMAL;
        timeoutMs = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000);
        on[T_GST] = Settings.System.getInt(cr, "dev_force_show_navbar", 1) == 0;
        float b = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) - MIN_BRIGHT)
                / (float) (255 - MIN_BRIGHT);
        bright = b < 0 ? 0 : (b > 1 ? 1 : b);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        vol = max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0;
        Date now = new Date();
        timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        dateStr = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now);
        invalidate();
    }

    private String label(int i) {
        switch (i) {
            case T_WIFI: return "Wi-Fi";
            case T_BT: return "Bluetooth";
            case T_LOC: return "Location";
            case T_ROT: return "Rotate";
            case T_SND:
                return soundMode == AudioManager.RINGER_MODE_NORMAL ? "Ring"
                        : (soundMode == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Silent");
            case T_TMO:
                return timeoutMs < 60000 ? (timeoutMs / 1000) + "s" : (timeoutMs / 60000) + "m";
            case T_GST: return "Gestures";
            default: return "Settings";
        }
    }

    // ----------------------------------------------------------------- layout

    @Override protected void onSizeChanged(int w, int hgt, int ow, int oh) {
        float m = 14 * d, g = 10 * d;
        pad = 12 * d;
        hh = 30 * d;
        box.left = m;
        box.right = w - m;
        box.top = 10 * d;
        float u = (box.width() - 2 * pad - 3 * g) / 4f;
        float x0 = box.left + pad, y0 = box.top + pad + hh;
        for (int i = 0; i < 4; i++) {
            float l = x0 + (i % 2) * (u + g), t = y0 + (i / 2) * (u + g);
            tile[i].set(l, t, l + u, t + u);
        }
        for (int i = 4; i < 8; i++) {
            float l = x0 + (i - 4) * (u + g), t = y0 + 2 * (u + g);
            tile[i].set(l, t, l + u, t + u);
        }
        for (int k = 0; k < 2; k++) {
            float l = x0 + (2 + k) * (u + g);
            slider[k].set(l, y0, l + u, y0 + 2 * u + g);
        }
        box.bottom = y0 + 3 * u + 2 * g + pad + 10 * d;
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

        c.drawColor(((int) (120 * prog)) << 24);
        c.save();
        c.translate(0, -(1f - prog) * (box.bottom + 10 * d));

        float rad = rBox;
        p.setStyle(Paint.Style.FILL);
        p.setColor((boxAlpha << 24) | 0x111318);
        c.drawRoundRect(box, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x22FFFFFF);
        c.drawRoundRect(box, rad, rad, p);

        // header: time, date, battery
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(17 * d);
        p.setColor(0xFFFFFFFF);
        float hx = box.left + pad + 2 * d, hy = box.top + pad + 18 * d;
        c.drawText(timeStr, hx, hy, p);
        float tw = p.measureText(timeStr);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(11 * d);
        p.setColor(0xFF9AA0AE);
        c.drawText(dateStr, hx + tw + 8 * d, hy, p);
        if (battery >= 0) {
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(battery + "%", box.right - pad - 2 * d, hy, p);
        }
        p.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < 8; i++) drawTile(c, i);
        drawSlider(c, 0);
        drawSlider(c, 1);

        // grab handle
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        rf.set(box.centerX() - 18 * d, box.bottom - 9 * d, box.centerX() + 18 * d, box.bottom - 5 * d);
        c.drawRoundRect(rf, 2 * d, 2 * d, p);

        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private void drawTile(Canvas c, int i) {
        RectF r = tile[i];
        c.save();
        if (i == pressTile) c.scale(0.93f, 0.93f, r.centerX(), r.centerY());
        p.setStyle(Paint.Style.FILL);
        p.setColor(on[i] ? (accentAll != 0 ? accentAll : ACCENT[i]) : 0x26FFFFFF);
        c.drawRoundRect(r, rTile, rTile, p);
        int fg = on[i] ? 0xFFFFFFFF : 0xFFE6E8EE;
        icon(c, i, r.centerX(), r.top + r.height() * 0.38f, 10 * d, fg);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10 * d);
        p.setColor(fg);
        c.drawText(label(i), r.centerX(), r.bottom - 8 * d, p);
        c.restore();
    }

    private void drawSlider(Canvas c, int k) {
        RectF r = slider[k];
        float rad = rTile;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x26FFFFFF);
        c.drawRoundRect(r, rad, rad, p);
        float v = k == 0 ? bright : vol;
        float fh = r.height() * v;
        if (fh > 1f) {
            c.save();
            path.reset();
            path.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(path);
            p.setColor(0xFFF2F4F8);
            c.drawRect(r.left, r.bottom - fh, r.right, r.bottom, p);
            c.restore();
        }
        float cy = r.bottom - 22 * d;
        int fg = fh > 22 * d ? 0xFF2A2D36 : 0xFFE6E8EE;
        icon(c, k == 0 ? I_SUN : I_VOL, r.centerX(), cy, 10 * d, fg);
    }

    private void speaker(Canvas c, float cx, float cy, float s, int waves, boolean slash) {
        float x0 = cx - 0.2f * s;
        path.reset();
        path.moveTo(x0 - 0.8f * s, cy - 0.35f * s);
        path.lineTo(x0 - 0.35f * s, cy - 0.35f * s);
        path.lineTo(x0 + 0.25f * s, cy - 0.85f * s);
        path.lineTo(x0 + 0.25f * s, cy + 0.85f * s);
        path.lineTo(x0 - 0.35f * s, cy + 0.35f * s);
        path.lineTo(x0 - 0.8f * s, cy + 0.35f * s);
        path.close();
        c.drawPath(path, p);
        for (int k = 1; k <= waves; k++) {
            float rad = 0.5f * s * k + 0.1f * s;
            rf.set(x0 + 0.25f * s - rad, cy - rad, x0 + 0.25f * s + rad, cy + rad);
            c.drawArc(rf, -50, 100, false, p);
        }
        if (slash) c.drawLine(cx - 0.9f * s, cy - 0.9f * s, cx + 0.9f * s, cy + 0.9f * s, p);
    }

    /** Tiny hand-drawn icon set; everything is a stroke or a path, so there are no assets. */
    private void icon(Canvas c, int id, float cx, float cy, float s, int fg) {
        p.setColor(fg);
        p.setStrokeWidth(1.9f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        switch (id) {
            case T_WIFI: {
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
            case T_BT: {
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
            case T_LOC: {
                float r0 = 0.72f * s, ccy = cy - 0.3f * s;
                c.drawCircle(cx, ccy, r0, p);
                c.drawLine(cx - r0 * 0.8f, ccy + r0 * 0.6f, cx, cy + 1.1f * s, p);
                c.drawLine(cx + r0 * 0.8f, ccy + r0 * 0.6f, cx, cy + 1.1f * s, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, ccy, 0.25f * s, p);
                break;
            }
            case T_ROT: {
                float rr = 0.85f * s;
                rf.set(cx - rr, cy - rr, cx + rr, cy + rr);
                c.drawArc(rf, 40, 280, false, p);
                double a = Math.toRadians(320);
                float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
                float px = cx + rr * nx, py = cy + rr * ny;
                float tx = -ny, ty = nx;
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(px + tx * 0.6f * s, py + ty * 0.6f * s);
                path.lineTo(px + nx * 0.5f * s, py + ny * 0.5f * s);
                path.lineTo(px - nx * 0.5f * s, py - ny * 0.5f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case T_SND: {
                if (soundMode == AudioManager.RINGER_MODE_VIBRATE) {
                    rf.set(cx - 0.45f * s, cy - 0.85f * s, cx + 0.45f * s, cy + 0.85f * s);
                    c.drawRoundRect(rf, 0.2f * s, 0.2f * s, p);
                    c.drawLine(cx - 0.85f * s, cy - 0.4f * s, cx - 0.85f * s, cy + 0.4f * s, p);
                    c.drawLine(cx + 0.85f * s, cy - 0.4f * s, cx + 0.85f * s, cy + 0.4f * s, p);
                } else if (soundMode == AudioManager.RINGER_MODE_NORMAL) {
                    speaker(c, cx, cy, s, 2, false);
                } else {
                    speaker(c, cx, cy, s, 0, true);
                }
                break;
            }
            case T_TMO: {
                c.drawCircle(cx, cy, 0.95f * s, p);
                c.drawLine(cx, cy, cx, cy - 0.55f * s, p);
                c.drawLine(cx, cy, cx + 0.42f * s, cy + 0.2f * s, p);
                break;
            }
            case T_GST: {
                p.setStyle(Paint.Style.FILL);
                rf.set(cx - 0.95f * s, cy + 0.45f * s, cx + 0.95f * s, cy + 0.8f * s);
                c.drawRoundRect(rf, 0.18f * s, 0.18f * s, p);
                p.setStyle(Paint.Style.STROKE);
                path.reset();
                path.moveTo(cx - 0.5f * s, cy + 0.05f * s);
                path.lineTo(cx, cy - 0.5f * s);
                path.lineTo(cx + 0.5f * s, cy + 0.05f * s);
                c.drawPath(path, p);
                break;
            }
            case T_SET: {
                c.drawCircle(cx, cy, 0.6f * s, p);
                for (int k = 0; k < 8; k++) {
                    float a = (float) (k * Math.PI / 4);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(cx + ca * 0.85f * s, cy + sa * 0.85f * s,
                            cx + ca * 1.1f * s, cy + sa * 1.1f * s, p);
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
                    c.drawLine(cx + ca * 0.7f * s, cy + sa * 0.7f * s,
                            cx + ca * 1.0f * s, cy + sa * 1.0f * s, p);
                }
                break;
            }
            case I_VOL:
                speaker(c, cx, cy, s, 2, false);
                break;
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
                pressTile = -1; dragSlider = -1;
                outsideDown = !box.contains(x, y);
                if (!outsideDown) {
                    for (int i = 0; i < 8; i++) if (tile[i].contains(x, y)) pressTile = i;
                    for (int k = 0; k < 2; k++) {
                        if (slider[k].contains(x, y)) { dragSlider = k; setSlider(k, y, false); }
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragSlider >= 0) {
                    setSlider(dragSlider, y, false);
                } else {
                    if (Math.abs(x - downX) > 12 * d || Math.abs(y - downY) > 12 * d) pressTile = -1;
                    if (downY - y > 56 * d) requestClose();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragSlider >= 0) {
                    setSlider(dragSlider, y, true);
                } else if (pressTile >= 0) {
                    int t = pressTile;
                    pressTile = -1;
                    tap(t);
                } else if (outsideDown && Math.abs(y - downY) < 12 * d) {
                    requestClose();
                }
                pressTile = -1; dragSlider = -1;
                break;
            case MotionEvent.ACTION_CANCEL:
                pressTile = -1; dragSlider = -1;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void setSlider(int k, float y, boolean commit) {
        RectF r = slider[k];
        float v = 1f - (y - r.top) / r.height();
        v = v < 0 ? 0 : (v > 1 ? 1 : v);
        long now = SystemClock.uptimeMillis();
        boolean write = commit || now - lastWrite >= 60;
        if (k == 0) {
            bright = v;
            if (write) {
                try {
                    ContentResolver cr = getContext().getContentResolver();
                    if (!manualSet) {
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                        manualSet = true;
                    }
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                            MIN_BRIGHT + Math.round(v * (255 - MIN_BRIGHT)));
                } catch (Throwable t) { }
            }
        } else {
            vol = v;
            if (write) {
                try {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
                } catch (Throwable t) { }
            }
        }
        if (write) lastWrite = now;
    }

    private void tap(int t) {
        Context ctx = getContext();
        ContentResolver cr = ctx.getContentResolver();
        try {
            switch (t) {
                case T_WIFI:
                    if (wifi != null) { boolean en = !on[T_WIFI]; wifi.setWifiEnabled(en); on[T_WIFI] = en; }
                    break;
                case T_BT:
                    if (bt != null) {
                        if (on[T_BT]) bt.disable(); else bt.enable();
                        on[T_BT] = !on[T_BT];
                    } else {
                        Toast.makeText(ctx, "No Bluetooth on this device", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case T_LOC:
                    Settings.Secure.putInt(cr, "location_mode", on[T_LOC] ? 0 : 3);
                    on[T_LOC] = !on[T_LOC];
                    break;
                case T_ROT:
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, on[T_ROT] ? 0 : 1);
                    on[T_ROT] = !on[T_ROT];
                    break;
                case T_SND: {
                    int m = soundMode == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                            : (soundMode == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
                            : AudioManager.RINGER_MODE_NORMAL);
                    am.setRingerMode(m);
                    soundMode = m;
                    on[T_SND] = m == AudioManager.RINGER_MODE_NORMAL;
                    break;
                }
                case T_TMO: {
                    int next = TIMEOUTS[0];
                    for (int i = 0; i < TIMEOUTS.length; i++) {
                        if (TIMEOUTS[i] > timeoutMs) { next = TIMEOUTS[i]; break; }
                    }
                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, next);
                    timeoutMs = next;
                    break;
                }
                case T_GST:
                    Settings.System.putInt(cr, "dev_force_show_navbar", on[T_GST] ? 1 : 0);
                    on[T_GST] = !on[T_GST];
                    break;
                case T_SET:
                    ctx.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    requestClose();
                    return;
                default:
                    break;
            }
        } catch (Throwable ex) {
            Toast.makeText(ctx, "Not allowed on this ROM", Toast.LENGTH_SHORT).show();
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        h.removeCallbacks(refreshRun);
        h.postDelayed(refreshRun, 900);   // re-sync async toggles (Wi-Fi / Bluetooth)
        invalidate();
    }
}
