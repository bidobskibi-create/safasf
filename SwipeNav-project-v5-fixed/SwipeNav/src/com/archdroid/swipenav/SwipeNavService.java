package com.archdroid.swipenav;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;

public class SwipeNavService extends Service {
    static final String PREFS = "swipenav", KEY_SHOW_BAR = "show_bar", KEY_VOL_HUD = "vol_hud";
    static final String KEY_POS = "pos", KEY_WIDTH = "width", KEY_HEIGHT = "height", KEY_SENS = "sens",
            KEY_OPACITY = "opacity", KEY_HAPTIC = "haptic", KEY_HITBOX = "show_hitbox",
            KEY_PANEL = "panel", KEY_PANEL_W = "panel_w", KEY_PANEL_RIGHT = "panel_right";
    static final String KEY_ACT_LEFT = "act_left", KEY_ACT_RIGHT = "act_right",
            KEY_ACT_UP = "act_up", KEY_ACT_HOLD = "act_hold";
    static final String KEY_PSTYLE = "panel_style", KEY_CORNER = "panel_corner", KEY_ACCENT = "panel_accent",
            KEY_PALPHA = "panel_alpha", KEY_LABELS = "panel_labels", KEY_TABS = "panel_tabs";
    static final String ACTION_TOGGLE = "com.archdroid.swipenav.TOGGLE_PANEL";
    private EdgeView edge;
    private PanelApi panel;
    private View panelView;
    private final BroadcastReceiver sysRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (ACTION_TOGGLE.equals(i.getAction())) {
                if (panel == null) openPanel(); else panel.requestClose();
            } else if (panel != null) {
                removePanel();   // Home pressed / system dialog -> close at once
            }
        }
    };
    private VolumeHud hud;
    private final BroadcastReceiver volRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (hud == null || panel != null || !sp.getBoolean(KEY_VOL_HUD, true)) return;
            int st = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", 3);
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            hud.show(am.getStreamVolume(st), am.getStreamMaxVolume(st));
        }
    };
    private WindowManager wm;
    private GestureView view;
    private SharedPreferences sp;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences p, String k) { apply(); }
    };
    private final ContentObserver obs = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) { apply(); }
    };

    @Override public void onCreate() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener(prefListener);
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor("dev_force_show_navbar"), false, obs);
        // iOS 9 style volume HUD (centered, touch-through)
        hud = new VolumeHud(this);
        float d = getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams hp = new WindowManager.LayoutParams((int) (150 * d), (int) (150 * d), 2010,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        hp.gravity = Gravity.CENTER;
        hp.setTitle("SwipeNavVolume");
        wm.addView(hud, hp);
        registerReceiver(volRx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        IntentFilter sf = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        sf.addAction(ACTION_TOGGLE);
        registerReceiver(sysRx, sf);
        apply();
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private WindowManager.LayoutParams buildParams() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        int sw = dm.widthPixels;
        int w = sw * clamp(sp.getInt(KEY_WIDTH, 100), 20, 100) / 100;
        int h = (int) (clamp(sp.getInt(KEY_HEIGHT, 24), 12, 80) * dm.density);
        int pos = clamp(sp.getInt(KEY_POS, 50), 0, 100);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                2010 /* TYPE_SYSTEM_ERROR */,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.x = (sw - w) * pos / 100;
        lp.setTitle("SwipeNav");
        return lp;
    }

    /** Gestures on (nav bar hidden) -> show strip. Off -> remove strip. */
    private void apply() {
        boolean on = Settings.System.getInt(getContentResolver(), "dev_force_show_navbar", 1) == 0;
        if (on && view == null) {
            view = new GestureView(this);
            wm.addView(view, buildParams());
        } else if (!on && view != null) {
            wm.removeView(view);
            view = null;
        } else if (on && view != null) {
            try { wm.updateViewLayout(view, buildParams()); } catch (Throwable t) { }
        }
        if (view != null) {
            view.setShowBar(sp.getBoolean(KEY_SHOW_BAR, true));
            view.setActions(sp.getInt(KEY_ACT_LEFT, Actions.BACK), sp.getInt(KEY_ACT_RIGHT, Actions.BACK),
                    sp.getInt(KEY_ACT_UP, Actions.HOME), sp.getInt(KEY_ACT_HOLD, Actions.RECENTS));
            view.configure(clamp(sp.getInt(KEY_SENS, 100), 50, 150) / 100f,
                    sp.getBoolean(KEY_HAPTIC, true),
                    clamp(sp.getInt(KEY_OPACITY, 100), 20, 100) / 100f,
                    sp.getBoolean(KEY_HITBOX, false));
        }
        applyEdge();
    }

    private WindowManager.LayoutParams edgeParams() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        int sw = dm.widthPixels;
        int w = sw * clamp(sp.getInt(KEY_PANEL_W, 50), 20, 100) / 100;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, (int) (10 * dm.density),
                2010,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = sp.getBoolean(KEY_PANEL_RIGHT, true) ? sw - w : 0;
        lp.setTitle("SwipeNavEdge");
        return lp;
    }

    private void applyEdge() {
        boolean want = sp.getBoolean(KEY_PANEL, true);
        if (want && edge == null) {
            edge = new EdgeView(this, new EdgeView.OnOpen() {
                public void open() { openPanel(); }
            });
            wm.addView(edge, edgeParams());
        } else if (!want && edge != null) {
            wm.removeView(edge);
            edge = null;
        } else if (want && edge != null) {
            try { wm.updateViewLayout(edge, edgeParams()); } catch (Throwable t) { }
        }
    }

    private void openPanel() {
        if (panel != null) return;
        Runnable closeCb = new Runnable() {
            public void run() { removePanel(); }
        };
        View pv = PanelPrefs.style(sp) == PanelPrefs.STYLE_MIUI ? new MiuiPanelView(this, closeCb) : new PanelView(this, closeCb);
        panel = (PanelApi) pv;
        panelView = pv;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                2010,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("SwipeNavPanel");
        try { wm.addView(panelView, lp); } catch (Throwable t) { panel = null; panelView = null; }
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panelView); } catch (Throwable t) { }
            panel = null;
            panelView = null;
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        apply();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        unregisterReceiver(volRx);
        unregisterReceiver(sysRx);
        removePanel();
        if (edge != null) { wm.removeView(edge); edge = null; }
        if (hud != null) { wm.removeView(hud); hud = null; }
        sp.unregisterOnSharedPreferenceChangeListener(prefListener);
        getContentResolver().unregisterContentObserver(obs);
        if (view != null) { wm.removeView(view); view = null; }
    }
}
