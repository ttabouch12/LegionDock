package io.legiondock;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Controller-driven overlay: hold Guide (or Select+Start). D-pad moves, A selects, B closes, L1/R1 = volume. */
final class QuickMenu {
    private final GuideService s;
    private final WindowManager wm;
    private FrameLayout root;
    private GridLayout grid;
    private TextView title, subtitle, clock, hint;
    private final List<Item> items = new ArrayList<>();
    private final List<LinearLayout> tiles = new ArrayList<>();
    private int sel;
    private boolean confirmClose, confirmRestore;
    private static final int COLS = 4;

    interface Act { void run(); }

    static final class Item {
        final String name; final Act act; String state = ""; boolean on;
        Item(String name, Act act) { this.name = name; this.act = act; }
    }

    QuickMenu(GuideService s) {
        this.s = s;
        this.wm = s.getSystemService(WindowManager.class);
    }

    boolean isShowing() { return root != null; }

    void show() {
        if (root != null) return;
        buildItems();
        sel = 0;
        confirmClose = false;
        confirmRestore = false;
        Context c = s;
        root = new FrameLayout(c);
        root.setBackgroundColor(0xB3000000);
        root.setOnClickListener(v -> hide());

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 22);
        panel.setPadding(p, p, p, Ui.dp(c, 16));
        panel.setBackground(Ui.round(0xF2141A24, Ui.dp(c, 24), Ui.STROKE, Ui.dp(c, 1)));
        panel.setClickable(true);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(c, "", 22, Ui.TEXT, true);
        subtitle = Ui.text(c, "", 13, Ui.SUB, false);
        names.addView(title);
        names.addView(subtitle);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        clock = Ui.text(c, "", 15, Ui.SUB, false);
        clock.setGravity(Gravity.END);
        head.addView(clock);
        panel.addView(head);

        grid = new GridLayout(c);
        grid.setColumnCount(COLS);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = Ui.dp(c, 16);
        panel.addView(grid, glp);
        tiles.clear();
        int tw = Ui.dp(c, 150), th = Ui.dp(c, 84), m = Ui.dp(c, 5);
        for (int i = 0; i < items.size(); i++) {
            LinearLayout t = new LinearLayout(c);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 12), 0);
            t.addView(Ui.text(c, items.get(i).name, 15, Ui.TEXT, true));
            t.addView(Ui.text(c, "", 12, Ui.SUB, false));
            final int idx = i;
            t.setOnClickListener(v -> { sel = idx; activate(); });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = tw; lp.height = th;
            lp.setMargins(m, m, m, m);
            grid.addView(t, lp);
            tiles.add(t);
        }
        hint = Ui.text(c, "D-pad / stick move · ✕ select · ○ close · L1 / R1 volume", 12, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 12);
        panel.addView(hint, hlp);

        root.addView(panel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Focusable: many pads (PlayStation, generic) send the D-pad as HAT axes. Only a focused
        // window gets those, and Android then turns D-pad/left stick into DPAD keys for it.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, code, ev) -> onKey(ev));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try { wm.addView(root, lp); } catch (Throwable t) { root = null; DockService.log("quick menu failed: " + t.getMessage()); return; }
        root.requestFocus();
        refresh();
    }

    void hide() {
        if (root == null) return;
        try { wm.removeView(root); } catch (Throwable ignored) {}
        root = null;
    }

    private void buildItems() {
        items.clear();
        final DockService d = DockService.instance;
        items.add(new Item("Close app", () -> {
            if (!confirmClose) { confirmClose = true; refresh(); return; }
            hide(); s.closeForegroundApp();
        }));
        items.add(new Item("Home", () -> { hide(); s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }));
        items.add(new Item("Recents", () -> { hide(); s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); }));
        items.add(new Item("Screenshot", () -> {
            hide();
            s.main.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT), 350);
        }));
        items.add(new Item("Virtual mouse", () -> { DockService.setManual(!DockService.fMouse, null); later(); }));
        items.add(new Item("Performance", () -> { DockService.setManual(null, !DockService.fOverlay); later(); }));
        items.add(new Item("Tablet screen", () -> {
            if (!DockService.panelOff && DockService.externalInfo.isEmpty()) { flash("Only while a TV is connected"); return; }
            DockService.send(s, DockService.panelOff ? DockService.A_PANEL_ON : DockService.A_PANEL_OFF);
            DockService.panelOff = !DockService.panelOff;
            refresh();
        }));
        items.add(new Item("Free memory", () -> { DockService.send(s, DockService.A_CLEAN); flash("Cleaning…"); }));
        items.add(new Item("Volume −", () -> { volume(-1); }));
        items.add(new Item("Volume +", () -> { volume(1); }));
        items.add(new Item("Mode", () -> {
            String cur = Modes.active(s);
            String[] m = Modes.dockModes(s);
            int i = 0;
            for (int k = 0; k < m.length; k++) if (m[k].equals(cur)) i = k;
            Modes.prefs(s).edit().putString("active_mode", m[(i + 1) % m.length]).apply();
            DockService.start(s);
            refresh();
        }));
        items.add(new Item("Restore tablet", () -> {
            if (!confirmRestore) { confirmRestore = true; refresh(); return; }
            hide(); DockService.send(s, DockService.A_EXIT_ALL);
        }));
        items.add(new Item(Modes.docked(s) ? "Undock" : "Dock now", () -> {
            hide();
            DockService.send(s, Modes.docked(s) ? DockService.A_UNDOCK : DockService.A_DOCK);
        }));
    }

    private void later() { s.main.postDelayed(this::refresh, 150); }

    private void flash(String msg) { if (hint != null) hint.setText(msg); s.main.postDelayed(() -> {
        if (hint != null) hint.setText("D-pad / stick move · ✕ select · ○ close · L1 / R1 volume"); }, 1500); }

    private void volume(int dir) {
        AudioManager am = s.getSystemService(AudioManager.class);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        refresh();
    }

    void refresh() {
        if (root == null) return;
        Context c = s;
        DockService d = DockService.instance;
        String fg = DockService.foreground;
        String app = (d != null && !fg.isEmpty()) ? d.label(fg) : "LegionDock";
        title.setText(app);
        String ctx = Modes.docked(c) ? "Docked · " + Modes.title(Modes.dockedMode(c))
                : Modes.handheld(c) ? "Handheld mode" : "Tablet mode";
        if (!DockService.appliedProfile.isEmpty()) ctx += " · profile active";
        subtitle.setText(ctx);

        AudioManager am = c.getSystemService(AudioManager.class);
        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        boolean plugged = b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date())
                + "\n" + (level >= 0 ? level + "%" + (plugged ? " ⚡" : "") : ""));

        for (Item it : items) {
            switch (it.name) {
                case "Close app": it.state = confirmClose ? "Press A again" : (fg.isEmpty() ? "" : app); it.on = confirmClose; break;
                case "Virtual mouse": it.on = DockService.fMouse; it.state = it.on ? "On" : "Off"; break;
                case "Performance": it.on = DockService.fOverlay; it.state = it.on ? "Overlay on" : "Overlay off"; break;
                case "Tablet screen": it.on = !DockService.panelOff; it.state = DockService.panelOff ? "Off" : "On"; break;
                case "Volume −": case "Volume +": it.state = vol + " / " + max; break;
                case "Mode": it.state = Modes.title(Modes.active(c)); break;
                case "Free memory": it.state = "Close background apps"; break;
                case "Restore tablet": it.state = confirmRestore ? "Press A again" : "Exit all"; it.on = confirmRestore; break;
                default: it.state = "";
            }
        }
        for (int i = 0; i < tiles.size(); i++) {
            LinearLayout t = tiles.get(i);
            Item it = items.get(i);
            boolean focused = i == sel;
            int bg = it.on ? Ui.ACCENT_SOFT : Ui.SURFACE2;
            t.setBackground(Ui.round(bg, Ui.dp(c, 16), focused ? Ui.ACCENT : 0, focused ? Ui.dp(c, 3) : 0));
            ((TextView) t.getChildAt(0)).setTextColor(it.on ? Ui.ACCENT : Ui.TEXT);
            TextView st = (TextView) t.getChildAt(1);
            st.setText(it.state);
            st.setVisibility(it.state.isEmpty() ? View.GONE : View.VISIBLE);
            t.setScaleX(focused ? 1.04f : 1f);
            t.setScaleY(focused ? 1.04f : 1f);
        }
    }

    private void activate() {
        Item it = items.get(sel);
        if (!"Close app".equals(it.name)) confirmClose = false;
        if (!"Restore tablet".equals(it.name)) confirmRestore = false;
        it.act.run();
    }

    private void move(int d) {
        int n = items.size();
        int nx = sel + d;
        if (d == 1 && sel % COLS == COLS - 1) nx = sel - (COLS - 1);
        if (d == -1 && sel % COLS == 0) nx = Math.min(n - 1, sel + COLS - 1);
        if (d == COLS && nx >= n) nx = sel % COLS;
        if (d == -COLS && nx < 0) { nx = sel + ((n - 1 - sel) / COLS) * COLS; }
        sel = Math.max(0, Math.min(n - 1, nx));
        if (!"Close app".equals(items.get(sel).name)) confirmClose = false;
        if (!"Restore tablet".equals(items.get(sel).name)) confirmRestore = false;
        refresh();
    }

    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) move(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) move(-COLS); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) move(COLS); return true;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) activate(); return true;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK:
                if (!down) hide(); return true;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                if (down && e.getRepeatCount() == 0) hide(); return true;
            case KeyEvent.KEYCODE_BUTTON_L1: if (down) volume(-1); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: if (down) volume(1); return true;
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_POWER:
                return false;
            default:
                return KeyEvent.isGamepadButton(k);
        }
    }
}
