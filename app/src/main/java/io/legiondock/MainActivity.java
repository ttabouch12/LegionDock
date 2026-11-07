package io.legiondock;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String P_HOME = "home", P_MODES = "modes", P_PROFILES = "profiles", P_PROFILE = "profile",
            P_CONTROLLER = "controller", P_OVERLAY = "overlay", P_SYSTEM = "system";
    private static final String[][] NAV = {
            {P_HOME, "Home"}, {P_MODES, "TV modes"}, {P_PROFILES, "Game profiles"},
            {P_CONTROLLER, "Controller"}, {P_OVERLAY, "Performance"}, {P_SYSTEM, "System"}};

    private final Handler main = new Handler(Looper.getMainLooper());
    private String page = P_HOME, editing = null, modeTab = Modes.CONSOLE, pendingFocus;
    private boolean wide;
    private LinearLayout navBox, content;
    private ScrollView scroll;
    private final List<TextView> navViews = new ArrayList<>();

    // live views on the home page
    private TextView hState, hTv, hDetail, logView;

    private boolean lastStray;
    private final Runnable uiPing = () -> main.post(() -> {
        boolean st = DockService.stray || !DockService.lastError.isEmpty();
        if (st != lastStray && P_HOME.equals(page)) { lastStray = st; rerender(null); }
        else liveRefresh();
    });
    private final Runnable shizukuPing = () -> main.post(() -> { if (P_HOME.equals(page)) showPage(page); });
    private final Runnable ticker = new Runnable() { @Override public void run() { liveRefresh(); main.postDelayed(this, 2000); } };
    private final Runnable reapply = () -> DockService.send(this, DockService.A_REAPPLY);
    private final Runnable profilesChanged = () -> DockService.send(this, DockService.A_PROFILES);

    // ── lifecycle ──

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
        build();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        DockService.start(this);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DockService.uiListeners.add(uiPing);
        ShizukuBridge.addListener(shizukuPing);
        if (ShizukuBridge.isRunning()) ShizukuBridge.refreshPermission();
        if (P_HOME.equals(page)) showPage(page);
        main.post(ticker);
    }

    @Override
    protected void onPause() {
        DockService.uiListeners.remove(uiPing);
        ShizukuBridge.removeListener(shizukuPing);
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (P_PROFILE.equals(page)) showPage(P_PROFILES);
        else if (!P_HOME.equals(page)) showPage(P_HOME);
        else super.onBackPressed();
    }

    // ── shell layout ──

    private void build() {
        wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        navViews.clear();
        navBox = new LinearLayout(this);
        navBox.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (wide) {
            navBox.setBackgroundColor(Ui.SIDEBAR);
            navBox.setPadding(dp(14), dp(20), dp(14), dp(20));
            TextView brand = Ui.text(this, "LegionDock", 22, Ui.TEXT, true);
            brand.setPadding(dp(12), 0, 0, dp(2));
            navBox.addView(brand);
            TextView ver = Ui.text(this, "v" + BuildInfo.VERSION, 12, Ui.MUTED, false);
            ver.setPadding(dp(12), 0, 0, dp(18));
            navBox.addView(ver);
            for (String[] n : NAV) navBox.addView(navItem(n[0], n[1]), new LinearLayout.LayoutParams(-1, -2));
            root.addView(navBox, new LinearLayout.LayoutParams(dp(230), -1));
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            navBox.setPadding(dp(10), dp(8), dp(10), dp(8));
            for (String[] n : NAV) navBox.addView(navItem(n[0], n[1]));
            hs.addView(navBox);
            hs.setBackgroundColor(Ui.SIDEBAR);
            root.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        }

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = wide ? dp(36) : dp(16);
        content.setPadding(pad, dp(wide ? 28 : 16), pad, dp(40));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
        setContentView(root);
        showPage(page);
    }

    private TextView navItem(String id, String label) {
        TextView t = Ui.text(this, label, 15, Ui.SUB, true);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag("nav:" + id);
        t.setOnClickListener(v -> showPage(id));
        if (!wide) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            t.setLayoutParams(lp);
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(4);
            t.setLayoutParams(lp);
        }
        navViews.add(t);
        return t;
    }

    private void styleNav() {
        String cur = P_PROFILE.equals(page) ? P_PROFILES : page;
        for (TextView t : navViews) {
            boolean sel = ("nav:" + cur).equals(t.getTag());
            t.setTextColor(sel ? Ui.TEXT : Ui.SUB);
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            s.addState(new int[]{android.R.attr.state_focused}, Ui.round(sel ? Ui.ACCENT_SOFT : Ui.SURFACE2, dp(12), Ui.ACCENT, dp(2)));
            s.addState(new int[]{}, Ui.round(sel ? Ui.ACCENT_SOFT : Color.TRANSPARENT, dp(12), 0, 0));
            t.setBackground(s);
        }
    }

    private void showPage(String p) {
        boolean samePage = p.equals(page);
        page = p;
        hState = hTv = hDetail = logView = null;
        content.removeAllViews();
        switch (p) {
            case P_MODES: pageModes(); break;
            case P_PROFILES: pageProfiles(); break;
            case P_PROFILE: pageProfile(); break;
            case P_CONTROLLER: pageController(); break;
            case P_OVERLAY: pageOverlay(); break;
            case P_SYSTEM: pageSystem(); break;
            default: pageHome();
        }
        styleNav();
        if (!samePage) animateIn();
        if (pendingFocus != null) {
            final String tag = pendingFocus;
            pendingFocus = null;
            main.post(() -> { View v = content.findViewWithTag(tag); if (v != null) v.requestFocus(); });
        } else if (!samePage) {
            scroll.scrollTo(0, 0);
        }
        liveRefresh();
    }

    /** Page entrance: content fades and slides up, cards follow with a short stagger. */
    private void animateIn() {
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 10) * 28L).setDuration(260).setInterpolator(ease).start();
        }
    }

    private void rerender(String focusTag) {
        int y = scroll.getScrollY();
        pendingFocus = focusTag;
        showPage(page);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ── pages ──

    private void pageHome() {
        header("Home", "Plug in the TV and the tablet becomes a console.");
        if (DockService.stray || !DockService.lastError.isEmpty()) problemCard();

        LinearLayout hero = card();
        hState = Ui.text(this, "", 26, Ui.TEXT, true);
        hero.addView(hState);
        hTv = Ui.text(this, "", 15, Ui.SUB, false);
        hTv.setPadding(0, dp(6), 0, 0);
        hero.addView(hTv);
        hDetail = Ui.text(this, "", 14, Ui.SUB, false);
        hDetail.setPadding(0, dp(2), 0, dp(14));
        hero.addView(hDetail);
        boolean docked = Modes.docked(this);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(docked ? button("Undock", true, v -> DockService.send(this, DockService.A_UNDOCK))
                : button("Dock now", true, v -> DockService.send(this, DockService.A_DOCK)), weight());
        btns.addView(warnButton("Restore tablet", v -> confirmExitAll()), weight());
        hero.addView(btns);
        LinearLayout links = new LinearLayout(this);
        links.setPadding(dp(4), dp(10), 0, 0);
        if (docked) links.addView(link("Tablet screen on", v -> DockService.send(this, DockService.A_PANEL_ON)));
        links.addView(link("Quick menu", v -> openQuickMenu()));
        hero.addView(links);

        setupChecklist();

        section("When the TV connects");
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        String active = Modes.active(this);
        LinearLayout pair = null;
        final String[] allModes = Modes.all(this);
        for (int i = 0; i < allModes.length; i++) {
            final String m = allModes[i];
            boolean sel = m.equals(active);
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            t.setMinimumHeight(dp(96));
            t.setBackground(selBg(sel));
            t.setFocusable(true);
            t.setClickable(true);
            t.setTag("mode:" + m);
            TextView name = Ui.text(this, Modes.title(m), 18, sel ? Ui.ACCENT : Ui.TEXT, true);
            t.addView(name);
            TextView sub = Ui.text(this, Modes.subtitle(m), 13, Ui.SUB, false);
            sub.setPadding(0, dp(4), 0, 0);
            t.addView(sub);
            t.setOnClickListener(v -> {
                Modes.prefs(this).edit().putString("active_mode", m).apply();
                DockService.start(this);
                rerender("mode:" + m);
            });
            if (wide) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMargins(i == 0 ? 0 : dp(6), 0, i == allModes.length - 1 ? 0 : dp(6), 0);
                tiles.addView(t, lp);
            } else {
                if (i % 2 == 0) { pair = new LinearLayout(this); tiles.addView(pair, new LinearLayout.LayoutParams(-1, -2)); }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMargins(i % 2 == 0 ? 0 : dp(5), dp(5), i % 2 == 0 ? dp(5) : 0, dp(5));
                pair.addView(t, lp);
            }
        }
        content.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView link(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 14, Ui.SUB, true);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(Ui.focusable(this, Color.TRANSPARENT, 10, 0));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void setupChecklist() {
        List<String[]> todo = new ArrayList<>();
        String backend = Shell.backend();
        boolean shizukuInstalled = isInstalled(ShizukuBridge.MANAGER_PKG);
        if (backend == null) {
            if (ShizukuBridge.isRunning()) todo.add(new String[]{"Allow LegionDock in Shizuku", "Needed to switch resolution, screen and audio.", "Allow", "shz_allow"});
            else if (shizukuInstalled) todo.add(new String[]{"Start Shizuku", "Open Shizuku and start it with Wireless debugging.", "Open", "shz_open"});
            else todo.add(new String[]{"Install Shizuku", "LegionDock uses it for system-level switches (no root needed).", "Get", "shz_get"});
        }
        if (!GuideService.running && !accessibilityEnabled())
            todo.add(new String[]{"Turn on the controller service", "Guide button, quick menu, virtual mouse, overlay and game profiles.", "Set up", "a11y"});
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()))
            todo.add(new String[]{"Allow background running", "Stops Android from killing the TV watcher.", "Allow", "battery"});
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            todo.add(new String[]{"Allow notifications", "Shows the dock status and an Undock button.", "Allow", "notif"});
        if (todo.isEmpty()) return;

        section("Finish setup");
        LinearLayout c = card();
        for (String[] t : todo) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            TextView dot = Ui.text(this, "●", 12, Ui.WARN, false);
            dot.setPadding(0, 0, dp(12), 0);
            r.addView(dot);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(Ui.text(this, t[0], 15, Ui.TEXT, true));
            tx.addView(Ui.text(this, t[1], 13, Ui.SUB, false));
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            final String act = t[3];
            View b = button(t[2], true, v -> fix(act));
            r.addView(b);
            c.addView(r);
        }
    }

    private void fix(String act) {
        switch (act) {
            case "shz_allow": ShizukuBridge.requestPermission(); break;
            case "shz_open": {
                Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuBridge.MANAGER_PKG);
                if (i != null) startActivity(i);
                break;
            }
            case "shz_get":
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ShizukuBridge.MANAGER_PKG))); }
                catch (Throwable t) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))); }
                break;
            case "a11y":
                info("Controller service",
                        "In Accessibility, open \"LegionDock controller service\" and turn it on.\n\n"
                                + "If Android says it's a restricted setting: go to App info → ⋮ (top-right) → \"Allow restricted settings\", then try again.",
                        "Open Accessibility", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                        "App info", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
                break;
            case "battery":
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (Throwable t) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                break;
            case "notif":
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                break;
        }
    }

    private void pageModes() {
        header("TV modes", "What changes when the TV is connected. Pick the active mode on the Home page.");
        LinearLayout tabs = new LinearLayout(this);
        for (String m : Modes.dockModes(this)) {
            final String mm = m;
            TextView chip = chip(Modes.title(m) + (m.equals(Modes.active(this)) ? "  ●" : ""), m.equals(modeTab), "tab:" + m,
                    v -> { modeTab = mm; rerender("tab:" + mm); });
            chip.setTextSize(15);
            chip.setPadding(dp(18), dp(10), dp(18), dp(10));
            tabs.addView(chip);
        }
        content.addView(tabs);

        final String m = modeTab;
        if (Modes.DESKTOP.equals(m)) {
            TextView intro = Ui.text(this, "Turns on Lenovo PC mode in landscape on the TV: taskbar, resizable windows, keyboard shortcuts. Best with a mouse and keyboard.", 14, Ui.SUB, false);
            intro.setPadding(dp(4), dp(14), dp(4), 0);
            content.addView(intro);
            section("Desktop");
            LinearLayout d = card();
            chips(d, m, Modes.K_SCALE, "Desktop scale", "Smaller fits more on the TV. Applies in a few seconds.",
                    new int[]{100, 90, 80, 70, 60}, new String[]{"100%", "90%", "80%", "70%", "60%"});
            chips(d, m, Modes.K_RES, "Resolution", "16:9 uses the full panel width and fills a TV. Native keeps the tablet's shape.",
                    new int[]{1440, 0}, new String[]{"16:9 (fills the TV)", "Native 16:10"});
            chips(d, m, Modes.K_REFRESH, "Refresh rate", null,
                    new int[]{60, 90, 120, 0}, new String[]{"60 Hz", "90 Hz", "120 Hz", "Don't change"});
            toggle(d, m, Modes.K_PANEL, "Turn off tablet screen", "Only the TV shows the picture. Turn off this switch to use the tablet as a touchscreen.");
            chips(d, m, Modes.K_AUDIO, "Audio output", null,
                    new int[]{0, 1, 2, 3}, new String[]{"Auto", "TV", "Bluetooth", "Tablet"});
            toggle(d, m, Modes.K_AWAKE, "Keep awake", null);
            toggle(d, m, Modes.K_DND, "Do Not Disturb", null);
            powerSection(m);
            return;
        }
        section("Picture");
        LinearLayout c1 = card();
        chips(c1, m, Modes.K_RES, "Render resolution", "Lower = cooler and faster. 16:9 fills the TV.",
                new int[]{1080, 1440, 720, 0}, new String[]{"1080p", "1440p", "720p", "Native 16:10"});
        chips(c1, m, Modes.K_REFRESH, "Refresh rate", "Match your TV. 60 Hz saves the most power.",
                new int[]{60, 90, 120, 0}, new String[]{"60 Hz", "90 Hz", "120 Hz", "Don't change"});
        chips(c1, m, Modes.K_UI, "UI size on the TV", null,
                new int[]{0, 1, 2, 3}, new String[]{"Auto", "Smaller", "Larger", "Don't change"});
        toggle(c1, m, Modes.K_PANEL, "Turn off tablet screen", "Only the TV shows the picture.");
        toggle(c1, m, Modes.K_LAND, "Lock landscape", null);

        section("Apps & sound");
        LinearLayout c2 = card();
        appPick(c2, m, Modes.K_HOME, "Home screen on the TV", "Don't change", true);
        appPick(c2, m, Modes.K_LAUNCH, "Open automatically", "Nothing", false);
        chips(c2, m, Modes.K_AUDIO, "Audio output", "Where sound goes while docked.",
                new int[]{0, 1, 2, 3}, new String[]{"Auto", "TV", "Bluetooth", "Tablet"});
        toggle(c2, m, Modes.K_DND, "Do Not Disturb", null);

        section("Controller & performance");
        LinearLayout c3 = card();
        toggle(c3, m, Modes.K_GUIDE, "Guide button", "Tap = Home, hold = quick menu.");
        toggle(c3, m, Modes.K_MOUSE, "Virtual mouse", "Left stick moves a cursor, A taps, B goes back.");
        toggle(c3, m, Modes.K_OVERLAY, "Performance overlay", "FPS, temperatures and power on screen.");
        toggle(c3, m, Modes.K_CLEAN, "Free memory when docking", "Closes background apps (see System).");
        toggle(c3, m, Modes.K_AWAKE, "Keep awake", null);
        toggle(c3, m, Modes.K_ANIM, "Faster animations", null);
        powerSection(m);
    }

    private void powerSection(String m) {
        section("Power & connections");
        LinearLayout p = card();
        toggle(p, m, Modes.K_BT, "Turn on Bluetooth", "So your controller can connect right away.");
        if (Modes.hasBypass(this)) toggle(p, m, Modes.K_BYPASS, "Bypass charging", "Runs from the charger instead of the battery while docked (Lenovo's battery protection).");
        toggle(p, m, Modes.K_HAPTIC, "Turn off touch vibration", "Stops the buzz when clicking menus while docked.");
    }

    private void pageProfiles() {
        header("Game profiles", "Settings that switch on by themselves while an app is in front, and switch back when you leave it.");
        LinearLayout top = new LinearLayout(this);
        top.addView(button("+  Add app", true, v -> pickApp("Add a game profile", launchables(), null, comp -> {
            ComponentName cn = ComponentName.unflattenFromString(comp);
            if (cn == null) return;
            Profiles.add(this, cn.getPackageName());
            editing = cn.getPackageName();
            main.removeCallbacks(profilesChanged);
            main.postDelayed(profilesChanged, 400);
            showPage(P_PROFILE);
        })));
        content.addView(top);
        if (!GuideService.running && !accessibilityEnabled()) {
            TextView w = Ui.text(this, "Turn on the controller service (Home → Finish setup) so LegionDock can see which app is in front.", 13, Ui.WARN, false);
            w.setPadding(0, dp(12), 0, 0);
            content.addView(w);
        }

        List<String> pkgs = Profiles.packages(this);
        if (pkgs.isEmpty()) {
            LinearLayout c = card();
            c.addView(Ui.text(this, "No profiles yet", 16, Ui.TEXT, true));
            TextView t = Ui.text(this, "Add your emulators: for example a 60 Hz cap and memory cleanup for Switch, 50 Hz for PAL games in RetroArch, or the virtual mouse for Netflix and Winlator.", 14, Ui.SUB, false);
            t.setPadding(0, dp(6), 0, 0);
            c.addView(t);
            return;
        }
        Collections.sort(pkgs, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        section(pkgs.size() + (pkgs.size() == 1 ? " profile" : " profiles"));
        LinearLayout c = card();
        for (String pkg : pkgs) {
            final String p = pkg;
            LinearLayout r = row(c, "prof:" + p, v -> { editing = p; showPage(P_PROFILE); });
            r.addView(icon(p), new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(14), 0, 0, 0);
            tx.addView(Ui.text(this, label(p), 16, Ui.TEXT, true));
            TextView s = Ui.text(this, summary(p), 13, Ui.SUB, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(s);
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            if (p.equals(DockService.appliedProfile)) r.addView(pill("ACTIVE", Ui.GOOD, Ui.GOOD_SOFT));
            r.addView(Ui.text(this, "  ›", 20, Ui.MUTED, false));
        }
    }

    private String summary(String p) {
        List<String> s = new ArrayList<>();
        int r = Profiles.getInt(this, p, Profiles.REFRESH);
        if (r > 0) s.add(r + " Hz");
        int f = Profiles.getInt(this, p, Profiles.FPS);
        if (f > 0) s.add(f + " FPS cap");
        int mo = Profiles.getInt(this, p, Profiles.MOUSE);
        if (mo != 0) s.add("Mouse " + (mo == 1 ? "on" : "off"));
        int ov = Profiles.getInt(this, p, Profiles.OVERLAY);
        if (ov != 0) s.add("Overlay " + (ov == 1 ? "on" : "off"));
        int d = Profiles.getInt(this, p, Profiles.DND);
        if (d != 0) s.add("DND " + (d == 1 ? "on" : "off"));
        int a = Profiles.getInt(this, p, Profiles.AUDIO);
        if (a != 0) s.add(new String[]{"", "Audio → TV", "Audio → Bluetooth", "Audio → tablet"}[a]);
        if (Profiles.getInt(this, p, Profiles.CLEAN) == 1) s.add("Free memory");
        if (Profiles.getInt(this, p, Profiles.SCOPE) == 1) s.add("docked/handheld only");
        return s.isEmpty() ? "No changes yet" : TextUtils.join(" · ", s);
    }

    private void pageProfile() {
        final String p = editing;
        if (p == null || !Profiles.has(this, p)) { showPage(P_PROFILES); return; }
        TextView back = Ui.text(this, "‹  Game profiles", 15, Ui.SUB, true);
        back.setPadding(dp(10), dp(8), dp(14), dp(8));
        back.setFocusable(true);
        back.setClickable(true);
        back.setBackground(Ui.focusable(this, Color.TRANSPARENT, 10, 0));
        back.setOnClickListener(v -> showPage(P_PROFILES));
        content.addView(back, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(12), 0, dp(4));
        head.addView(icon(p), new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(16), 0, 0, 0);
        tx.addView(Ui.text(this, label(p), 26, Ui.TEXT, true));
        tx.addView(Ui.text(this, p, 13, Ui.MUTED, false));
        head.addView(tx);
        content.addView(head);

        section("When this app is in front");
        LinearLayout c = card();
        pchips(c, p, Profiles.SCOPE, "Applies", null, new int[]{0, 1}, new String[]{"Always", "Only on TV / handheld"});
        pchips(c, p, Profiles.REFRESH, "Refresh rate", "Use 50/60 to match the game, higher for smooth Android games.",
                new int[]{0, 60, 90, 120, 165}, new String[]{"Inherit", "60", "90", "120", "165"});
        pchips(c, p, Profiles.FPS, "FPS limit", "Experimental: uses Android game mode, not every app supports it.",
                new int[]{0, 30, 40, 45, 60}, new String[]{"Off", "30", "40", "45", "60"});
        pchips(c, p, Profiles.MOUSE, "Virtual mouse", null, new int[]{0, 1, 2}, new String[]{"Inherit", "On", "Off"});
        pchips(c, p, Profiles.OVERLAY, "Performance overlay", null, new int[]{0, 1, 2}, new String[]{"Inherit", "On", "Off"});
        pchips(c, p, Profiles.DND, "Do Not Disturb", null, new int[]{0, 1, 2}, new String[]{"Inherit", "On", "Off"});
        pchips(c, p, Profiles.AUDIO, "Audio output", null, new int[]{0, 1, 2, 3}, new String[]{"Inherit", "TV", "Bluetooth", "Tablet"});
        boolean clean = Profiles.getInt(this, p, Profiles.CLEAN) == 1;
        switchRow(c, "Free memory on launch", "Closes background apps and your cleanup list (keeps this app).", clean, "pclean",
                v -> { Profiles.put(this, p, Profiles.CLEAN, clean ? 0 : 1); profileEdited("pclean"); });

        LinearLayout danger = new LinearLayout(this);
        danger.setPadding(0, dp(18), 0, 0);
        View rm = button("Remove profile", false, v -> {
            Profiles.remove(this, p);
            main.removeCallbacks(profilesChanged);
            main.post(profilesChanged);
            showPage(P_PROFILES);
        });
        danger.addView(rm);
        content.addView(danger);
    }

    private void profileEdited(String focus) {
        main.removeCallbacks(profilesChanged);
        main.postDelayed(profilesChanged, 700);
        rerender(focus);
    }

    private void pageController() {
        header("Controller", "Console-style controls for the whole tablet.");

        section("Guide button & quick menu");
        LinearLayout c = card();
        gswitch(c, "guide_always", false, "Use the Guide button everywhere", "Otherwise only while docked or in handheld mode.", true);
        gswitch(c, "combo_menu", true, "Select + Start opens the quick menu", "For controllers without a Guide button.", false);
        actionRow(c, "Open the quick menu now", null, GuideService.running ? "Open ›" : "Service off", "qm", v -> openQuickMenu());

        section("Virtual mouse");
        LinearLayout m = card();
        TextView hint = Ui.text(this, "Left stick moves · ✕/A tap · hold for long-press · ○/B back · right stick scrolls. Toggle it with the combo below, per mode, per game, or from the quick menu.", 13, Ui.SUB, false);
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        m.addView(hint);
        gchips(m, "mouse_speed", 3, "Cursor speed", null, new int[]{2, 3, 4, 5}, new String[]{"Slow", "Normal", "Fast", "Very fast"});
        gchips(m, "mouse_combo", 0, "Mouse on / off combo", "Switch between mouse mode and normal controller from anywhere.",
                new int[]{0, 1, 2, 3}, new String[]{"Share/Select + L3", "Share/Select + R3", "L3 + R3", "Off"});

        section("Handheld mode");
        LinearLayout h = card();
        TextView ht = Ui.text(this, "Applies its own settings when your telescopic / clip-on controller attaches and no TV is connected.", 13, Ui.SUB, false);
        ht.setPadding(dp(4), 0, dp(4), dp(8));
        h.addView(ht);
        String want = Modes.prefs(this).getString("hh_controller", "");
        String wantLabel = want.isEmpty() ? "Off" : "*".equals(want) ? "Any controller" : want;
        actionRow(h, "Trigger controller", "Connected now: " + (DockService.connectedPads().isEmpty() ? "none" : TextUtils.join(", ", DockService.connectedPads())),
                wantLabel + "  ›", "hhc", v -> {
                    Set<String> known = new LinkedHashSet<>(DockService.connectedPads());
                    known.addAll(Modes.prefs(this).getStringSet("known_pads", new HashSet<>()));
                    final List<String> opts = new ArrayList<>(Arrays.asList("Off", "Any controller"));
                    opts.addAll(known);
                    pickList("Handheld mode controller", opts, i -> {
                        String val = i == 0 ? "" : i == 1 ? "*" : opts.get(i);
                        Modes.prefs(this).edit().putString("hh_controller", val).apply();
                        DockService.start(this);
                        rerender("hhc");
                    });
                });
        if (!want.isEmpty()) {
            String H = Modes.HANDHELD;
            chips(h, H, Modes.K_REFRESH, "Refresh rate", null, new int[]{0, 60, 90, 120, 165},
                    new String[]{"Don't change", "60", "90", "120", "165"});
            toggle(h, H, Modes.K_GUIDE, "Guide button", null);
            toggle(h, H, Modes.K_MOUSE, "Virtual mouse", null);
            toggle(h, H, Modes.K_OVERLAY, "Performance overlay", null);
            toggle(h, H, Modes.K_DND, "Do Not Disturb", null);
            toggle(h, H, Modes.K_CLEAN, "Free memory when attached", null);
        }
    }

    private void pageOverlay() {
        header("Performance overlay", "A small stats strip over your game: FPS, CPU / GPU temperature and load, battery power.");
        LinearLayout prev = card();
        TextView sample = Ui.text(this, "60 FPS  CPU 54° 3.1GHz  GPU 61° 82%  −6.4W 78% 37°", 13, Ui.TEXT, false);
        sample.setTypeface(Typeface.MONOSPACE);
        sample.setPadding(dp(12), dp(6), dp(12), dp(6));
        sample.setBackground(Ui.round(0xB0000000, dp(10), 0, 0));
        prev.addView(sample, new LinearLayout.LayoutParams(-2, -2));
        TextView n = Ui.text(this, "Turn it on per TV mode, per game profile, in handheld mode, or any time from the quick menu.", 13, Ui.SUB, false);
        n.setPadding(0, dp(10), 0, 0);
        prev.addView(n);

        section("Settings");
        LinearLayout c = card();
        gchips(c, "overlay_pos", 0, "Position", null, new int[]{0, 1, 2, 3}, new String[]{"Top left", "Top right", "Bottom left", "Bottom right"});
        gswitch(c, "overlay_always", false, "Show in tablet mode too", "Without TV or handheld controller.", true);
    }

    private void pageSystem() {
        header("System", "Memory cleanup, screen-off method and troubleshooting.");

        section("Memory cleanup");
        LinearLayout m = card();
        Set<String> list = cleanList();
        actionRow(m, "Apps to close", "Force-stopped when cleanup runs (background apps are always trimmed).",
                list.size() + (list.size() == 1 ? " app  ›" : " apps  ›"), "clist", v -> pickMulti());
        actionRow(m, "Free memory now", null, "Run ›", "cnow", v -> DockService.send(this, DockService.A_CLEAN));

        section("Display");
        LinearLayout d = card();
        String cur = Modes.prefs(this).getString("panel_method", "sf");
        final String[] keys = {"sf", "dim", "android"};
        int idx = Math.max(0, Arrays.asList(keys).indexOf(cur));
        chipRow(d, "Screen-off method", "Panel only keeps every app drawing on the TV.",
                new String[]{"Panel only", "Dim backlight", "Android power-off"}, idx, "pm", i -> {
                    Modes.prefs(this).edit().putString("panel_method", keys[i]).apply();
                    if (Modes.docked(this) && DockService.panelOff) {
                        DockService.send(this, DockService.A_PANEL_ON);
                        main.postDelayed(() -> DockService.send(this, DockService.A_PANEL_OFF), 1200);
                    }
                });
        gswitch(d, "force_mirror", true, "Force mirror mode on the TV", "Stops Lenovo's desktop mode from taking over the TV while docked.", false);
        gswitch(d, "compat_detect", true, "Compatibility TV detection", "Also checks SurfaceFlinger if Android doesn't report the TV.", false);

        section("Restore");
        LinearLayout r = card();
        actionRow(r, "Restore tablet (exit all)", "Undo every LegionDock change (resolution, screen, audio, refresh, profiles) and stay in tablet mode until the TV is unplugged.",
                "Restore ›", "exitall", v -> confirmExitAll());
        actionRow(r, "Reset LegionDock to default", "Restores the tablet, then erases all modes, game profiles and settings.",
                "Reset ›", "reset", v -> info("Reset everything?",
                        "This restores the tablet first, then deletes all your TV modes, game profiles, controller and overlay settings. LegionDock starts fresh.",
                        "Reset", () -> { DockService.send(this, DockService.A_RESET); modeTab = Modes.CONSOLE;
                            main.postDelayed(() -> showPage(P_HOME), 1500); },
                        "Cancel", () -> {}));

        section("Switch saves");
        LinearLayout sv = card();
        gswitch(sv, "save_sync", true, "Sync Eden and Citron saves", "When you leave either emulator, the newest save of each game is copied to the other. A backup is kept in Games/Saves-Backup.", false);
        actionRow(sv, "Sync now", DockService.lastSync.isEmpty() ? "Close both emulators first for a full sync." : "Last: " + DockService.lastSync,
                "Sync ›", "syncnow", v -> DockService.send(this, DockService.A_SYNC));

        section("App");
        LinearLayout a = card();
        gswitch(a, "start_on_boot", true, "Start after reboot", null, false);
        gswitch(a, "bt_off_after", false, "Turn Bluetooth off after undocking", "Only if it was off before docking.", false);
        actionRow(a, "Reset display size & rotation", "Puts UI size, resolution, animations and auto-rotate back to tablet defaults.",
                "Reset ›", "resetui", v -> DockService.send(this, DockService.A_RESET_UI));
        actionRow(a, "Diagnostics", "Copy and send this when something doesn't work.", "Run ›", "diag", v -> runDiag());

        section("Activity log");
        LinearLayout lc = card();
        logView = Ui.text(this, "", 12, Ui.SUB, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        lc.addView(logView);
    }

    // ── live refresh ──

    private void liveRefresh() {
        if (hState != null) {
            boolean docked = Modes.docked(this), hh = Modes.handheld(this);
            hState.setText(docked ? "Docked · " + Modes.title(Modes.dockedMode(this)) : hh ? "Handheld mode" : "Tablet mode");
            hState.setTextColor(docked ? Ui.ACCENT : hh ? Ui.WARN : Ui.TEXT);
            String ext = DockService.externalInfo;
            hTv.setText(ext.isEmpty() ? "No TV connected · active mode: " + Modes.title(Modes.active(this)) : "TV: " + ext);
            String prof = DockService.appliedProfile;
            String b = Shell.backend();
            hDetail.setText((b == null ? "Shizuku not connected" : "Connected via " + b)
                    + (prof.isEmpty() ? "" : " · profile: " + label(prof))
                    + (GuideService.running ? "" : " · controller service off"));
        }
        if (logView != null) {
            synchronized (DockService.LOG) {
                String[] lines = DockService.LOG.toString().split("\n");
                int max = P_HOME.equals(page) ? 8 : 60;
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 1, n = 0; i >= 0 && n < max; i--, n++) sb.append(lines[i]).append('\n');
                String s = sb.toString().trim();
                logView.setText(s.isEmpty() ? "Nothing yet." : s);
            }
        }
    }

    // ── setting row builders ──

    private void chips(LinearLayout parent, String mode, String key, String title, String sub, int[] vals, String[] labels) {
        int cur = Modes.getInt(this, mode, key);
        int idx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) idx = i;
        chipRow(parent, title, sub, labels, idx, mode + key, i -> {
            Modes.put(this, mode, key, vals[i]);
            modeEdited(mode, key);
        });
    }

    private void toggle(LinearLayout parent, String mode, String key, String title, String sub) {
        boolean on = Modes.getBool(this, mode, key);
        switchRow(parent, title, sub, on, mode + key, v -> {
            Modes.put(this, mode, key, !on);
            modeEdited(mode, key);
            rerender(mode + key);
        });
    }

    private void modeEdited(String mode, String key) {
        boolean live = Modes.HANDHELD.equals(mode) ? Modes.handheld(this) : (Modes.docked(this) && mode.equals(Modes.dockedMode(this)));
        if (!live) return;
        if (Modes.K_SCALE.equals(key)) { DockService.send(this, DockService.A_SCALE); return; }
        if (Modes.K_BT.equals(key) || Modes.K_BYPASS.equals(key) || Modes.K_HAPTIC.equals(key)) return;   // takes effect on the next dock
        if (Modes.K_PANEL.equals(key)) {
            DockService.send(this, Modes.getBool(this, mode, Modes.K_PANEL) ? DockService.A_PANEL_OFF : DockService.A_PANEL_ON);
            return;
        }
        if (Modes.K_MOUSE.equals(key) || Modes.K_OVERLAY.equals(key) || Modes.K_GUIDE.equals(key)) {
            DockService.send(this, DockService.A_FEATURES);
        } else {
            main.removeCallbacks(reapply);
            main.postDelayed(reapply, 1500);
        }
    }

    private void pchips(LinearLayout parent, String pkg, String key, String title, String sub, int[] vals, String[] labels) {
        int cur = Profiles.getInt(this, pkg, key);
        int idx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) idx = i;
        chipRow(parent, title, sub, labels, idx, "p" + key, i -> {
            Profiles.put(this, pkg, key, vals[i]);
            profileEdited(null);
        });
    }

    private void gchips(LinearLayout parent, String key, int def, String title, String sub, int[] vals, String[] labels) {
        int cur = Modes.prefs(this).getInt(key, def);
        int idx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) idx = i;
        chipRow(parent, title, sub, labels, idx, "g" + key, i -> {
            Modes.prefs(this).edit().putInt(key, vals[i]).apply();
            if ("overlay_pos".equals(key)) GuideService.featuresChanged();
        });
    }

    private void gswitch(LinearLayout parent, String key, boolean def, String title, String sub, boolean affectsFeatures) {
        boolean on = Modes.prefs(this).getBoolean(key, def);
        switchRow(parent, title, sub, on, "g" + key, v -> {
            Modes.prefs(this).edit().putBoolean(key, !on).apply();
            if (affectsFeatures) DockService.send(this, DockService.A_FEATURES);
            rerender("g" + key);
        });
    }

    interface IntCb { void on(int i); }
    interface StrCb { void on(String s); }

    /** Title (+ subtitle) with a row of selectable chips; chips sit right on wide screens, below on narrow. */
    private void chipRow(LinearLayout parent, String title, String sub, String[] labels, int selected, String tagBase, IntCb cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        r.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.START);
        r.setPadding(dp(4), dp(10), dp(4), dp(10));
        r.addView(titleBlock(title, sub), new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0));
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        if (!wide) chips.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = chip(labels[i], i == selected, tagBase + "#" + i, v -> {
                cb.on(idx);
                rerender(tagBase + "#" + idx);
            });
            chips.addView(c);
        }
        if (wide) r.addView(chips);
        else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chips);
            r.addView(hs);
        }
        divider(parent);
        parent.addView(r);
    }

    private TextView chip(String label, boolean sel, String tag, View.OnClickListener l) {
        TextView c = Ui.text(this, label, 13, sel ? Ui.TEXT : Ui.SUB, sel);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setFocusable(true);
        c.setClickable(true);
        c.setTag(tag);
        c.setSingleLine(true);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.round(sel ? Ui.ACCENT_SOFT : Ui.SURFACE2, dp(20), Ui.ACCENT, dp(2)));
        s.addState(new int[]{}, Ui.round(sel ? Ui.ACCENT_SOFT : Ui.SURFACE2, dp(20), sel ? Ui.ACCENT : 0, sel ? dp(1) : 0));
        c.setBackground(s);
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private void switchRow(LinearLayout parent, String title, String sub, boolean on, String tag, View.OnClickListener l) {
        divider(parent);
        LinearLayout r = row(parent, tag, l);
        r.addView(titleBlock(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout track = new FrameLayout(this);
        final android.graphics.drawable.GradientDrawable tbg = Ui.round(on ? Ui.ACCENT : Ui.STROKE, dp(14), 0, 0);
        track.setBackground(tbg);
        final View knob = new View(this);
        knob.setBackground(Ui.round(Color.WHITE, dp(11), 0, 0));
        knob.setElevation(dp(2));
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL | Gravity.START);
        kp.setMargins(dp(3), 0, dp(3), 0);
        track.addView(knob, kp);
        knob.setTranslationX(on ? dp(22) : 0);
        r.addView(track, new LinearLayout.LayoutParams(dp(50), dp(28)));
        // animate the knob + colour first, then apply the change
        r.setOnClickListener(v -> {
            r.setEnabled(false);
            knob.animate().translationX(on ? 0 : dp(22)).setDuration(160)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofArgb(on ? Ui.ACCENT : Ui.STROKE, on ? Ui.STROKE : Ui.ACCENT);
            va.setDuration(160);
            va.addUpdateListener(a -> tbg.setColor((Integer) a.getAnimatedValue()));
            va.start();
            main.postDelayed(() -> l.onClick(v), 170);
        });
    }

    private void actionRow(LinearLayout parent, String title, String sub, String value, String tag, View.OnClickListener l) {
        divider(parent);
        LinearLayout r = row(parent, tag, l);
        r.addView(titleBlock(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Ui.text(this, value, 14, Ui.ACCENT, true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setMaxWidth(dp(wide ? 320 : 180));
        v.setGravity(Gravity.END);
        r.addView(v);
    }

    private void appPick(LinearLayout parent, String mode, String key, String title, String none, boolean launchers) {
        String comp = Modes.getStr(this, mode, key);
        actionRow(parent, title, null, (comp.isEmpty() ? none : compLabel(comp)) + "  ›", mode + key, v ->
                pickApp(title, launchers ? launchers() : launchables(), none, val -> {
                    Modes.put(this, mode, key, val);
                    modeEdited(mode, key);
                    rerender(mode + key);
                }));
    }

    private LinearLayout row(LinearLayout parent, String tag, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        r.setMinimumHeight(dp(56));
        r.setBackground(Ui.focusable(this, Color.TRANSPARENT, 12, 0));
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(tag);
        r.setOnClickListener(l);
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private LinearLayout titleBlock(String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, title, 15, Ui.TEXT, true));
        if (sub != null) {
            TextView s = Ui.text(this, sub, 13, Ui.SUB, false);
            s.setPadding(0, dp(2), dp(12), 0);
            t.addView(s);
        }
        return t;
    }

    private void divider(LinearLayout parent) {
        if (parent.getChildCount() == 0) return;
        View d = new View(this);
        d.setBackgroundColor(Ui.STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(dp(12), dp(2), dp(12), dp(2));
        parent.addView(d, lp);
    }

    // ── generic views ──

    private void header(String title, String sub) {
        content.addView(Ui.text(this, title, 30, Ui.TEXT, true));
        TextView s = Ui.text(this, sub, 15, Ui.SUB, false);
        s.setPadding(0, dp(6), 0, dp(14));
        content.addView(s);
    }

    private void section(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), 12, Ui.MUTED, true);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(4), dp(24), 0, dp(8));
        content.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setBackground(Ui.round(Ui.SURFACE, dp(18), Ui.STROKE, dp(1)));
        content.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private View button(String label, boolean primary, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 14, primary ? Color.WHITE : Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setSingleLine(true);
        b.setFocusable(true);
        b.setClickable(true);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        int base = primary ? Ui.ACCENT : Ui.SURFACE2;
        s.addState(new int[]{android.R.attr.state_focused}, Ui.round(primary ? 0xFFF0575C : 0xFF263042, dp(12), Color.WHITE, dp(2)));
        s.addState(new int[]{android.R.attr.state_pressed}, Ui.round(primary ? 0xFFC93C41 : Ui.STROKE, dp(12), 0, 0));
        s.addState(new int[]{}, Ui.round(base, dp(12), 0, 0));
        b.setBackground(s);
        b.setOnClickListener(l);
        return b;
    }

    private void problemCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Ui.round(Ui.ACCENT_SOFT, dp(18), Ui.ACCENT, dp(1)));
        content.addView(c, new LinearLayout.LayoutParams(-1, -2));
        c.addView(Ui.text(this, "Tablet may be stuck in TV mode", 16, Ui.TEXT, true));
        String msg = DockService.lastError.isEmpty() ? "TV settings are still applied." : DockService.lastError;
        TextView t = Ui.text(this, msg, 14, Ui.SUB, false);
        t.setPadding(0, dp(4), 0, dp(10));
        c.addView(t);
        LinearLayout b = new LinearLayout(this);
        b.addView(warnButton("Restore tablet now", v -> DockService.send(this, DockService.A_EXIT_ALL)));
        if (Shell.backend() == null) {
            View s = button("Open Shizuku", false, v -> fix(ShizukuBridge.isRunning() ? "shz_allow" : isInstalled(ShizukuBridge.MANAGER_PKG) ? "shz_open" : "shz_get"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            b.addView(s, lp);
        }
        c.addView(b);
    }

    private void confirmExitAll() {
        info("Restore tablet?",
                "Undoes everything LegionDock changed: resolution, UI size, tablet screen, refresh rate, audio, Do Not Disturb, home screen and any game profile. "
                        + "Auto-docking pauses until you unplug the TV.",
                "Restore", () -> DockService.send(this, DockService.A_EXIT_ALL),
                "Cancel", () -> {});
    }

    private View warnButton(String label, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 14, Ui.ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setSingleLine(true);
        b.setFocusable(true);
        b.setClickable(true);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.round(Ui.ACCENT_SOFT, dp(12), Color.WHITE, dp(2)));
        s.addState(new int[]{android.R.attr.state_pressed}, Ui.round(0xFF4A2026, dp(12), 0, 0));
        s.addState(new int[]{}, Ui.round(Ui.ACCENT_SOFT, dp(12), Ui.ACCENT, dp(1)));
        b.setBackground(s);
        b.setOnClickListener(l);
        return b;
    }

    private TextView pill(String text, int fg, int bg) {
        TextView t = Ui.text(this, text, 11, fg, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setBackground(Ui.round(bg, dp(8), 0, 0));
        return t;
    }

    private android.graphics.drawable.StateListDrawable selBg(boolean sel) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.round(sel ? Ui.ACCENT_SOFT : Ui.SURFACE2, dp(18), Ui.ACCENT, dp(2)));
        s.addState(new int[]{}, Ui.round(sel ? Ui.ACCENT_SOFT : Ui.SURFACE, dp(18), sel ? Ui.ACCENT : Ui.STROKE, dp(sel ? 2 : 1)));
        return s;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private ImageView icon(String pkg) {
        ImageView iv = new ImageView(this);
        try { iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Throwable ignored) {}
        return iv;
    }

    // ── dialogs ──

    private Dialog sheet(String title, LinearLayout body, boolean scrollable) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        panel.setBackground(Ui.round(Ui.SURFACE, dp(22), Ui.STROKE, dp(1)));
        TextView t = Ui.text(this, title, 20, Ui.TEXT, true);
        t.setPadding(dp(4), 0, 0, dp(12));
        panel.addView(t);
        if (scrollable) {
            ScrollView sv = new ScrollView(this);
            sv.addView(body);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
            panel.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(maxH, dp(64) * Math.max(1, body.getChildCount()))));
        } else panel.addView(body);
        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    private void pickApp(String title, List<ResolveInfo> apps, String none, StrCb cb) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] d = new Dialog[1];
        if (none != null) {
            LinearLayout r = row(body, null, v -> { d[0].dismiss(); cb.on(""); });
            View sp = new View(this);
            r.addView(sp, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView t = Ui.text(this, none, 15, Ui.SUB, false);
            t.setPadding(dp(14), 0, 0, 0);
            r.addView(t);
        }
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : apps) {
            final String comp = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToShortString();
            LinearLayout r = row(body, null, v -> { d[0].dismiss(); cb.on(comp); });
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ri.loadIcon(pm));
            r.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView t = Ui.text(this, ri.loadLabel(pm), 15, Ui.TEXT, false);
            t.setPadding(dp(14), 0, 0, 0);
            r.addView(t);
        }
        d[0] = sheet(title, body, true);
        d[0].show();
    }

    private void pickList(String title, List<String> opts, IntCb cb) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] d = new Dialog[1];
        for (int i = 0; i < opts.size(); i++) {
            final int idx = i;
            LinearLayout r = row(body, null, v -> { d[0].dismiss(); cb.on(idx); });
            r.addView(Ui.text(this, opts.get(i), 15, Ui.TEXT, false));
        }
        d[0] = sheet(title, body, true);
        d[0].show();
    }

    private Set<String> cleanList() {
        Set<String> s = new LinkedHashSet<>();
        for (String p : Modes.prefs(this).getString("clean_list", "").split("\\s+")) if (!p.isEmpty()) s.add(p);
        return s;
    }

    private void pickMulti() {
        final Set<String> sel = cleanList();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : launchables()) {
            final String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg) || pkg.equals(getPackageName()) || pkg.equals(ShizukuBridge.MANAGER_PKG)) continue;
            final LinearLayout r = row(body, null, null);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ri.loadIcon(pm));
            r.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView t = Ui.text(this, ri.loadLabel(pm), 15, Ui.TEXT, false);
            t.setPadding(dp(14), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView mark = Ui.text(this, sel.contains(pkg) ? "✓" : "", 18, Ui.ACCENT, true);
            r.addView(mark);
            r.setOnClickListener(v -> {
                if (sel.contains(pkg)) sel.remove(pkg); else sel.add(pkg);
                mark.setText(sel.contains(pkg) ? "✓" : "");
            });
        }
        Dialog d = sheet("Apps to close during cleanup", body, true);
        d.setOnDismissListener(x -> {
            Modes.prefs(this).edit().putString("clean_list", TextUtils.join(" ", sel)).apply();
            rerender("clist");
        });
        d.show();
    }

    private void info(String title, String msg, String a, Runnable ra, String b, Runnable rb) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView m = Ui.text(this, msg, 15, Ui.SUB, false);
        m.setPadding(dp(4), 0, dp(4), dp(16));
        body.addView(m);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        final Dialog[] d = new Dialog[1];
        if (b != null) btns.addView(button(b, false, v -> { d[0].dismiss(); rb.run(); }), weight());
        btns.addView(button(a, true, v -> { d[0].dismiss(); ra.run(); }), weight());
        body.addView(btns);
        d[0] = sheet(title, body, false);
        d[0].show();
    }

    private void runDiag() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        final TextView out = Ui.text(this, "Running…", 12, Ui.SUB, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        body.addView(sv, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.5f)));
        final String[] text = {""};
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(12), 0, 0);
        final Dialog[] d = new Dialog[1];
        btns.addView(button("Close", false, v -> d[0].dismiss()), weight());
        btns.addView(button("Copy", true, v -> getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("LegionDock diagnostics", text[0]))), weight());
        body.addView(btns);
        d[0] = sheet("Diagnostics", body, false);
        d[0].show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("app: LegionDock ").append(BuildInfo.VERSION).append("  backend: ").append(Shell.backend())
                    .append("  shizuku v").append(ShizukuBridge.version()).append('\n');
            sb.append("android: ").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")  ")
                    .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            sb.append("mode: ").append(Modes.active(this)).append("  docked: ").append(Modes.docked(this))
                    .append("  handheld: ").append(Modes.handheld(this)).append('\n');
            sb.append("controller service: ").append(GuideService.running).append("  profile: ").append(DockService.appliedProfile)
                    .append("  fg: ").append(DockService.foreground).append('\n');
            sb.append("pads: ").append(DockService.connectedPads()).append('\n');
            sb.append("--- app displays\n");
            for (android.view.Display dd : getSystemService(android.hardware.display.DisplayManager.class).getDisplays())
                sb.append(dd.getDisplayId()).append(": ").append(dd.getName()).append(" flags=0x")
                        .append(Integer.toHexString(dd.getFlags())).append(' ').append(dd.getMode().getPhysicalWidth())
                        .append('x').append(dd.getMode().getPhysicalHeight()).append('\n');
            sb.append(Shell.out(this, "diag"));
            text[0] = sb.toString();
            main.post(() -> out.setText(text[0]));
        }).start();
    }

    private void openQuickMenu() {
        GuideService g = GuideService.inst;
        if (g == null) { fix("a11y"); return; }
        g.main.post(() -> g.menu.show());
    }

    // ── app lists / misc ──

    private List<ResolveInfo> launchers() {
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)) {
            if (r.activityInfo.packageName.equals(getPackageName()) || r.activityInfo.name.endsWith("FallbackHome")) continue;
            out.add(r);
        }
        sort(out);
        return out;
    }

    private List<ResolveInfo> launchables() {
        List<ResolveInfo> out = new ArrayList<>(getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        sort(out);
        return out;
    }

    private void sort(List<ResolveInfo> l) {
        final PackageManager pm = getPackageManager();
        Collections.sort(l, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
    }

    private String label(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Throwable t) { return pkg; }
    }

    private String compLabel(String comp) {
        ComponentName cn = ComponentName.unflattenFromString(comp);
        return cn == null ? comp : label(cn.getPackageName());
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Throwable t) { return false; }
    }

    private boolean accessibilityEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private int dp(float v) { return Ui.dp(this, v); }
}
