package io.legiondock;

import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Small always-on-top stats strip: FPS, CPU/GPU temp + load, battery watts. */
final class PerfOverlay {
    private final GuideService s;
    private final WindowManager wm;
    private TextView view;
    private boolean enabled;
    private Shell.Proc proc;
    private long lastFlip = -1, lastAt;

    PerfOverlay(GuideService s) {
        this.s = s;
        this.wm = s.getSystemService(WindowManager.class);
    }

    void setEnabled(boolean on) {
        if (on == enabled) { if (on) reposition(); return; }
        enabled = on;
        if (on) start(); else stop();
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        int pos = Modes.prefs(s).getInt("overlay_pos", 0);
        lp.gravity = (pos == 0 || pos == 2 ? Gravity.LEFT : Gravity.RIGHT) | (pos < 2 ? Gravity.TOP : Gravity.BOTTOM);
        lp.x = Ui.dp(s, 12);
        lp.y = Ui.dp(s, 12);
        return lp;
    }

    private void reposition() {
        if (view != null) try { wm.updateViewLayout(view, params()); } catch (Throwable ignored) {}
    }

    private void start() {
        view = Ui.text(s, "…", 12, Ui.TEXT, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(Ui.dp(s, 10), Ui.dp(s, 5), Ui.dp(s, 10), Ui.dp(s, 5));
        view.setBackground(Ui.round(0xB0000000, Ui.dp(s, 10), 0, 0));
        try { wm.addView(view, params()); } catch (Throwable t) { view = null; return; }
        lastFlip = -1;
        new Thread(this::stream, "perf").start();
    }

    private void stop() {
        if (proc != null) proc.stop();
        proc = null;
        if (view != null) try { wm.removeView(view); } catch (Throwable ignored) {}
        view = null;
    }

    private void stream() {
        try {
            if (!Shell.ready()) { post("Performance overlay needs Shizuku"); return; }
            Shell.Proc p = Shell.spawn(s, "perf");
            proc = p;
            BufferedReader r = new BufferedReader(new InputStreamReader(p.stream));
            String line;
            while (enabled && (line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 6 || !"S".equals(f[0])) continue;
                long now = SystemClock.elapsedRealtime();
                String fps = "--";
                try {
                    long flips = Long.parseLong(f[1], 16);
                    if (lastFlip >= 0 && now > lastAt) {
                        long n = Math.round((flips - lastFlip) * 1000.0 / (now - lastAt));
                        if (n >= 0 && n < 1000) fps = String.valueOf(n);
                    }
                    lastFlip = flips;
                } catch (Throwable ignored) {}
                lastAt = now;
                render(fps, temp(f[2]), temp(f[3]), f[4], f[5]);
            }
        } catch (Throwable t) {
            post("overlay: " + t.getMessage());
        }
    }

    private static int temp(String v) {
        try {
            long t = Long.parseLong(v);
            if (t > 1000) t /= 1000; else if (t > 200) t /= 10;
            return (int) t;
        } catch (Throwable e) { return 0; }
    }

    private void render(String fps, int cpuT, int gpuT, String gpuBusy, String cpuFreq) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int fc = Ui.TEXT;
        try { int n = Integer.parseInt(fps); fc = n >= 55 ? Ui.GOOD : n >= 28 ? Ui.WARN : Ui.ACCENT; } catch (Throwable ignored) {}
        append(sb, fps + " FPS", fc);
        String freq = "";
        try { freq = String.format(java.util.Locale.US, " %.1fGHz", Long.parseLong(cpuFreq) / 1e6); } catch (Throwable ignored) {}
        append(sb, "  CPU " + (cpuT > 0 ? cpuT + "°" : "--") + freq, tempColor(cpuT));
        String busy = gpuBusy.equals("x") ? "" : " " + gpuBusy + "%";
        append(sb, "  GPU " + (gpuT > 0 ? gpuT + "°" : "--") + busy, tempColor(gpuT));
        append(sb, "  " + battery(), Ui.SUB);
        final CharSequence text = sb;
        s.main.post(() -> { if (view != null) view.setText(text); });
    }

    private static int tempColor(int t) { return t >= 80 ? Ui.ACCENT : t >= 65 ? Ui.WARN : Ui.TEXT; }

    private static void append(SpannableStringBuilder sb, String t, int color) {
        int st = sb.length();
        sb.append(t);
        sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String battery() {
        Intent b = s.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return "";
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int bt = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
        boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        long cur = Math.abs((long) s.getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        double watts = cur > 20000 ? cur * mv / 1e9 : cur * mv / 1e6;
        String w = watts > 0.05 ? String.format(java.util.Locale.US, "%s%.1fW ", plugged ? "+" : "−", watts) : "";
        return w + level + "% " + bt + "°";
    }

    private void post(String msg) { s.main.post(() -> { if (view != null) view.setText(msg); }); }
}
