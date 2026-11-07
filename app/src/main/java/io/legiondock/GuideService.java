package io.legiondock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Controller service (accessibility):
 *  - Guide/PS/Xbox button: tap = Home, hold = quick menu.  Select+Start = quick menu.
 *  - foreground app tracking for per-app profiles
 *  - hosts the quick menu, virtual mouse and performance overlay windows
 */
public class GuideService extends AccessibilityService {
    static volatile GuideService inst;
    static volatile boolean running;

    Handler main;
    QuickMenu menu;
    VirtualMouse mouse;
    PerfOverlay perf;

    private boolean guideHeld, selDown, startDown, l3Down, r3Down;
    private android.widget.TextView toast;
    private final Map<String, Boolean> appCache = new HashMap<>();
    private Set<String> homes;

    private final Runnable holdOpensMenu = () -> { guideHeld = true; menu.show(); };

    @Override
    protected void onServiceConnected() {
        if (android.os.Process.myUserHandle().hashCode() != 0) { disableSelf(); return; }
        android.util.Log.i("LegionDock", "guide connected " + System.identityHashCode(this));
        inst = this;
        running = true;
        main = new Handler(Looper.getMainLooper());
        menu = new QuickMenu(this);
        mouse = new VirtualMouse(this);
        perf = new PerfOverlay(this);
        DockService.start(this);
        applyFeatures();
        DockService.notifyUi();
    }

    @Override
    public boolean onUnbind(Intent i) { shutdown(); return super.onUnbind(i); }

    @Override
    public void onDestroy() { shutdown(); super.onDestroy(); }

    private void shutdown() {
        android.util.Log.i("LegionDock", "guide shutdown " + System.identityHashCode(this) + " current=" + (inst == this));
        if (menu != null) menu.hide();
        if (mouse != null) mouse.setEnabled(false);
        if (perf != null) perf.setEnabled(false);
        // The system may rebind (new instance connected) before the old one is destroyed: only clear if we're still current.
        if (inst == this || inst == null) { inst = null; running = false; }
        DockService.notifyUi();
    }

    @Override public void onInterrupt() {}

    static void featuresChanged() { GuideService s = inst; if (s != null) s.main.post(s::applyFeatures); }

    static void padsChanged() { GuideService s = inst; if (s != null) s.main.post(() -> s.mouse.restart()); }

    void applyFeatures() {
        mouse.setEnabled(DockService.fMouse);
        perf.setEnabled(DockService.fOverlay);
        if (menu.isShowing()) menu.refresh();
    }

    // ── foreground tracking ──

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (!pkg.equals("com.android.systemui") && !pkg.equals(getPackageName()) && !pkg.equals("android")) {
            String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime == null || !ime.startsWith(pkg + "/")) DockService.onAnyWindow(pkg);   // keyboard popping up isn't "leaving"
        }
        if (isRealApp(pkg)) DockService.onForeground(pkg);
    }

    private boolean isRealApp(String pkg) {
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") || pkg.equals("android")) return false;
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return false;
        Boolean cached = appCache.get(pkg);
        if (cached != null) return cached;
        if (homes == null) {
            homes = new HashSet<>();
            for (ResolveInfo r : getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL))
                homes.add(r.activityInfo.packageName);
        }
        boolean real = homes.contains(pkg) || getPackageManager().getLaunchIntentForPackage(pkg) != null;
        appCache.put(pkg, real);
        return real;
    }

    // ── keys ──

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (menu.isShowing()) return menu.onKey(e);

        if (k == KeyEvent.KEYCODE_BUTTON_SELECT) selDown = down;
        if (k == KeyEvent.KEYCODE_BUTTON_START) startDown = down;
        if (k == KeyEvent.KEYCODE_BUTTON_THUMBL) l3Down = down;
        if (k == KeyEvent.KEYCODE_BUTTON_THUMBR) r3Down = down;

        // mouse mode toggle combo (default Share/Select + L3)
        if (down && e.getRepeatCount() == 0 && (k == KeyEvent.KEYCODE_BUTTON_THUMBL || k == KeyEvent.KEYCODE_BUTTON_THUMBR)) {
            int combo = Modes.prefs(this).getInt("mouse_combo", 0);
            boolean hit = (combo == 0 && selDown && k == KeyEvent.KEYCODE_BUTTON_THUMBL)
                    || (combo == 1 && selDown && k == KeyEvent.KEYCODE_BUTTON_THUMBR)
                    || (combo == 2 && ((k == KeyEvent.KEYCODE_BUTTON_THUMBL && r3Down) || (k == KeyEvent.KEYCODE_BUTTON_THUMBR && l3Down)));
            if (hit) {
                boolean on = !DockService.fMouse;
                DockService.setManual(on, null);
                flash(on ? "Mouse mode ON · ✕ tap · ○ back · right stick scroll" : "Mouse mode OFF · controller mode");
                return true;
            }
        }
        if (down && selDown && startDown && Modes.prefs(this).getBoolean("combo_menu", true)
                && (DockService.fGuide || Modes.prefs(this).getBoolean("guide_always", false))) {
            selDown = startDown = false;
            menu.show();
            return true;
        }

        if (k == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (!DockService.fGuide) return false;
            if (down) {
                if (e.getRepeatCount() == 0) {
                    guideHeld = false;
                    main.postDelayed(holdOpensMenu, 450);
                }
            } else {
                main.removeCallbacks(holdOpensMenu);
                if (!guideHeld) performGlobalAction(GLOBAL_ACTION_HOME);
                guideHeld = false;
            }
            return true;
        }

        return mouse.isActive() && mouse.onKey(e);
    }

    // ── dock / undock transition: a short full-screen fade that hides the relayout ──
    private android.widget.FrameLayout curtain;
    private long curtainShownAt;

    static void transition(boolean show, String title) {
        GuideService g = inst;
        if (g == null) return;
        if (show) g.main.post(() -> g.showCurtain(title));
        else g.main.postDelayed(g::hideCurtain, Math.max(250, 700 - (android.os.SystemClock.uptimeMillis() - g.curtainShownAt)));
    }

    private void showCurtain(String title) {
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        hideCurtainNow();
        curtain = new android.widget.FrameLayout(this);
        curtain.setBackgroundColor(Ui.BG);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_fg);
        box.addView(logo, new android.widget.LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));
        android.widget.TextView t = Ui.text(this, title == null ? "" : title, 22, Ui.TEXT, true);
        t.setGravity(android.view.Gravity.CENTER);
        box.addView(t);
        android.widget.TextView sub = Ui.text(this, "Switching…", 14, Ui.SUB, false);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(sub);
        curtain.addView(box, new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER));
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(-1, -1,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try { wm.addView(curtain, lp); } catch (Throwable e) { curtain = null; return; }
        curtainShownAt = android.os.SystemClock.uptimeMillis();
        curtain.setAlpha(0f);
        curtain.animate().alpha(1f).setDuration(220).start();
        logo.setScaleX(0.8f); logo.setScaleY(0.8f);
        logo.animate().scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(new android.view.animation.OvershootInterpolator()).start();
        sub.animate().alpha(0.35f).setDuration(700).withEndAction(new Runnable() {
            @Override public void run() {
                if (curtain == null) return;
                sub.animate().alpha(sub.getAlpha() < 0.5f ? 1f : 0.35f).setDuration(700).withEndAction(this).start();
            }
        }).start();
    }

    private void hideCurtain() {
        final android.widget.FrameLayout c = curtain;
        if (c == null) return;
        curtain = null;
        c.animate().alpha(0f).setDuration(320).withEndAction(() -> {
            try { getSystemService(android.view.WindowManager.class).removeView(c); } catch (Throwable ignored) {}
        }).start();
    }

    private void hideCurtainNow() {
        if (curtain == null) return;
        try { getSystemService(android.view.WindowManager.class).removeView(curtain); } catch (Throwable ignored) {}
        curtain = null;
    }

    /** Small message at the bottom of the screen (shows on the TV too). */
    void flash(String msg) {
        main.post(() -> {
            android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
            if (toast != null) try { wm.removeView(toast); } catch (Throwable ignored) {}
            toast = Ui.text(this, msg, 16, Ui.TEXT, true);
            toast.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
            toast.setBackground(Ui.round(0xE6141A24, Ui.dp(this, 14), Ui.ACCENT, Ui.dp(this, 1)));
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    -2, -2, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            lp.y = Ui.dp(this, 48);
            final android.widget.TextView t = toast;
            try { wm.addView(t, lp); } catch (Throwable ignored) { return; }
            main.postDelayed(() -> { try { wm.removeView(t); } catch (Throwable ignored) {} if (toast == t) toast = null; }, 1800);
        });
    }

    // ── actions used by the quick menu ──

    void closeForegroundApp() {
        final String pkg = DockService.foreground;
        DockService s = DockService.instance;
        if (s == null || pkg.isEmpty()) return;
        performGlobalAction(GLOBAL_ACTION_HOME);
        s.h.post(() -> DockService.log("  " + Shell.out(s, "close_app", "PKG", pkg).trim()));
    }
}
