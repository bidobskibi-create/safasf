./build.sh -> out/SwipeNav-flash.zip. Flash in TWRP over the installed ROM (no need to reflash the ROM). Reboot after.
(GitHub: push this folder and download the "SwipeNav-flash" artifact from the Actions run.)

v4 - editable gestures + force close
  * Settings > Gestures: tap Swipe left / Swipe right / Swipe up / Swipe up and hold (or far)
    and choose: Nothing, Back, Home, Recent apps, Force close app, Control panel.
    Defaults are unchanged (Back, Back, Home, Recent apps), so existing installs behave as before.
  * "Force close app" force-stops the app you are in (never the launcher, system UI or lock screen).
    It needs FORCE_STOP_PACKAGES, which is granted because the app is installed in /system/priv-app.
    If that is ever refused it falls back to Home + killBackgroundProcesses.
  * The pill turns red when a gesture is set to Force close app.
  * New files: Actions.java. Changed: GestureView, SwipeNavService, SettingsActivity, AndroidManifest
    (added GET_TASKS, FORCE_STOP_PACKAGES, KILL_BACKGROUND_PROCESSES).

v5 - MIUI control panel + panel look settings
  * New control panel style modelled on the MIUI toggles shade (time/date + gear, 4x3 icon grid,
    brightness bar, Notifications | Toggles tabs). Settings > Panel look > Panel style: iOS / MIUI.
  * Panel look settings (both styles): accent colour, corner rounding (0-40 dp), panel opacity.
    MIUI only: show/hide labels, show/hide bottom tabs.
  * MIUI toggles: Airplane, Bluetooth, Auto sync, Screenshot, Rotation, Vibrate, Ring, GPS, Wi-Fi, Data,
    Auto-Backlight, More (opens Settings). Airplane and Data may be refused by the ROM (a toast says so).
  * "Notifications" tab closes the shade and pulls down the system notification shade.
  * New files: PanelApi, PanelPrefs, MiuiPanelView. Edited: PanelView, SwipeNavService, SettingsActivity, manifest.

GitHub Actions build fix
  * The workflow now installs the Android SDK platform/build-tools explicitly and uses current Node 24-compatible GitHub Actions.
  * build.sh finds D8 in either build-tools or the newer cmdline-tools package, and reports missing SDK tools clearly instead of failing with exit 127.
  * To build on GitHub, put the CONTENTS of the SwipeNav folder at the repository root, including the hidden .github folder. Do not put the entire SwipeNav folder inside another repository folder; GitHub only auto-detects workflows under the repository-root .github/workflows directory.
  * Push the files, then open Actions and download the SwipeNav-flash artifact from the successful build.
