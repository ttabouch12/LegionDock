package io.legiondock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Left stick moves a cursor, A = tap (hold = long press), B = back, right stick = scroll.
 * Stick values are read with `getevent` through Shizuku (accessibility can't see joystick motion).
 */
final class VirtualMouse {
    private final GuideService s;
    private final WindowManager wm;
    private View cursor;
    private WindowManager.LayoutParams lp;
    private boolean enabled;
    private final List<Shell.Proc> procs = new ArrayList<>();
    private volatile float lx, ly, ry;
    private float x, y;
    private int w, h;
    private long lastTick, lastMove, lastScroll, lastBounds;
    private boolean longFired;

    VirtualMouse(GuideService s) {
        this.s = s;
        this.wm = s.getSystemService(WindowManager.class);
    }

    boolean isActive() { return enabled; }

    void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) start(); else stop();
    }

    void restart() { if (enabled) { stop(); start(); } }

    private void start() {
        updateBounds();
        x = w / 2f; y = h / 2f;
        cursor = new CursorView(s);
        int size = Ui.dp(s, 30);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.x = (int) x; lp.y = (int) y;
        try { wm.addView(cursor, lp); } catch (Throwable t) { cursor = null; }
        lastTick = SystemClock.uptimeMillis();
        lastMove = lastTick;
        s.main.post(tick);
        new Thread(this::openSticks, "mouse-sticks").start();
        DockService.log("virtual mouse on");
    }

    private void stop() {
        s.main.removeCallbacks(tick);
        s.main.removeCallbacks(longPress);
        synchronized (procs) { for (Shell.Proc p : procs) p.stop(); procs.clear(); }
        if (cursor != null) try { wm.removeView(cursor); } catch (Throwable ignored) {}
        cursor = null;
        lx = ly = ry = 0;
    }

    @SuppressWarnings("deprecation")
    private void updateBounds() {
        android.graphics.Point p = new android.graphics.Point();
        try {
            s.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0).getRealSize(p);
        } catch (Throwable t) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            p.set(b.width(), b.height());
        }
        if (p.x > 0 && p.y > 0) { w = p.x; h = p.y; }
        lastBounds = SystemClock.uptimeMillis();
    }

    // ── stick input via getevent ──

    private static final class Axis { float min, max, flat; }

    private void openSticks() {
        if (!Shell.ready()) { DockService.log("virtual mouse needs Shizuku"); return; }
        String dump = Shell.out(s, "gamepads");
        // parse "add device N: /dev/input/eventX" blocks
        String dev = null;
        boolean isPad = false;
        Map<String, Axis> axes = new HashMap<>();
        Map<String, Map<String, Axis>> pads = new HashMap<>();
        String lastAxis = null;
        for (String line : (dump + "\nadd device 0: /end").split("\n")) {
            String t = line.trim();
            if (t.startsWith("add device")) {
                if (dev != null && isPad && axes.containsKey("ABS_X")) pads.put(dev, axes);
                dev = t.substring(t.indexOf(':') + 1).trim();
                isPad = false;
                axes = new HashMap<>();
                continue;
            }
            if (t.contains("BTN_SOUTH") || t.contains("BTN_GAMEPAD") || t.contains("BTN_A ") || t.contains("BTN_A\t")
                    || t.endsWith("BTN_A")) isPad = true;
            int i = t.indexOf("ABS_");
            int v = t.indexOf(": value");
            if (i >= 0 && v > i) {
                String name = t.substring(i, v).trim().split("\\s")[0];
                Axis a = new Axis();
                a.min = num(t, "min ");
                a.max = num(t, "max ");
                a.flat = num(t, "flat ");
                axes.put(name, a);
                lastAxis = name;
            }
        }
        if (pads.isEmpty()) { DockService.log("virtual mouse: no controller found"); return; }
        for (Map.Entry<String, Map<String, Axis>> e : pads.entrySet()) {
            final String path = e.getKey();
            final Map<String, Axis> ax = e.getValue();
            final String scrollAxis = ax.containsKey("ABS_RY") ? "ABS_RY" : "ABS_RZ";
            try {
                final Shell.Proc p = Shell.spawnRaw("getevent", "-l", path);
                synchronized (procs) { procs.add(p); }
                new Thread(() -> read(p, ax, scrollAxis), "mouse-" + path).start();
            } catch (Throwable t) {
                DockService.log("virtual mouse: " + t.getMessage());
            }
        }
    }

    private static float num(String t, String key) {
        int i = t.indexOf(key);
        if (i < 0) return 0;
        int j = i + key.length();
        int k = j;
        while (k < t.length() && (Character.isDigit(t.charAt(k)) || t.charAt(k) == '-')) k++;
        try { return Float.parseFloat(t.substring(j, k)); } catch (Throwable x) { return 0; }
    }

    private void read(Shell.Proc p, Map<String, Axis> ax, String scrollAxis) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.stream));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 3 || !"EV_ABS".equals(f[0])) continue;
                Axis a = ax.get(f[1]);
                if (a == null) continue;
                int raw;
                try { raw = (int) Long.parseLong(f[2], 16); } catch (Throwable t) { continue; }
                float center = (a.min + a.max) / 2f, half = Math.max(1f, (a.max - a.min) / 2f);
                float n = Math.max(-1f, Math.min(1f, (raw - center) / half));
                if ("ABS_X".equals(f[1])) lx = n;
                else if ("ABS_Y".equals(f[1])) ly = n;
                else if (scrollAxis.equals(f[1])) ry = n;
            }
        } catch (Throwable ignored) {}
    }

    // ── movement loop ──

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!enabled || cursor == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastTick) / 1000f);
            lastTick = now;
            if (now - lastBounds > 1000) updateBounds();

            float mx = lx, my = ly;
            float mag = (float) Math.sqrt(mx * mx + my * my);
            float dead = 0.14f;
            if (mag > dead) {
                float k = (mag - dead) / (1 - dead);
                float speed = Modes.prefs(s).getInt("mouse_speed", 3) * 0.45f * w; // px per second at full tilt
                float v = speed * k * k / mag;
                x = Math.max(0, Math.min(w - 1, x + mx * v * dt));
                y = Math.max(0, Math.min(h - 1, y + my * v * dt));
                lp.x = (int) x; lp.y = (int) y;
                try { wm.updateViewLayout(cursor, lp); } catch (Throwable ignored) {}
                lastMove = now;
                cursor.setAlpha(1f);
            } else if (now - lastMove > 2500) {
                cursor.setAlpha(0.35f);
            }

            if (Math.abs(ry) > 0.35f && now - lastScroll > 150) {
                lastScroll = now;
                float dist = 0.22f * h * Math.abs(ry);
                float ey = Math.max(1, Math.min(h - 2, y - Math.signum(ry) * dist));
                gesture(x, y, x, ey, 140);
                lastMove = now;
                cursor.setAlpha(1f);
            }
            s.main.postDelayed(this, 16);
        }
    };

    // ── buttons ──

    private final Runnable longPress = () -> { longFired = true; gesture(x, y, x, y, 700); };

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) { longFired = false; s.main.postDelayed(longPress, 500); }
                } else {
                    s.main.removeCallbacks(longPress);
                    if (!longFired) gesture(x, y, x, y, 40);
                }
                if (cursor != null) { cursor.setAlpha(1f); lastMove = SystemClock.uptimeMillis(); }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (!down) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                return true;
        }
        return false;
    }

    private void gesture(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        try {
            s.dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null);
        } catch (Throwable t) {
            DockService.log("tap failed: " + t.getMessage());
        }
    }

    /** Classic arrow pointer, tip at (0,0). */
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        CursorView(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            edge.setColor(0xFF111111);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.dp(c, 1.6f));
            edge.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas cv) {
            float u = getWidth() / 30f;
            arrow.reset();
            arrow.moveTo(1 * u, 1 * u);
            arrow.lineTo(1 * u, 23 * u);
            arrow.lineTo(7 * u, 17.5f * u);
            arrow.lineTo(11 * u, 27 * u);
            arrow.lineTo(15 * u, 25 * u);
            arrow.lineTo(11 * u, 16 * u);
            arrow.lineTo(19 * u, 16 * u);
            arrow.close();
            cv.drawPath(arrow, fill);
            cv.drawPath(arrow, edge);
        }
    }
}
