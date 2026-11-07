package io.legiondock;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** Runs the core script with shell (Shizuku) or root privileges. */
public final class Shell {
    private static String core;
    private static Boolean rootAvailable;

    public static boolean hasRoot() {
        if (rootAvailable == null) {
            boolean found = false;
            for (String p : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"})
                if (new File(p).exists()) { found = true; break; }
            rootAvailable = found;
        }
        return rootAvailable;
    }

    public static boolean ready() { return ShizukuBridge.isGranted() || hasRoot(); }

    public static String backend() {
        if (ShizukuBridge.isGranted()) return "Shizuku";
        if (hasRoot()) return "root";
        return null;
    }

    private static synchronized String core(Context c) throws Exception {
        if (core == null) {
            InputStream in = c.getAssets().open("core.sh");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            core = bo.toString("UTF-8");
        }
        return core;
    }

    static String q(String s) { return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'"; }

    private static String script(Context c, String action, String... vars) throws Exception {
        StringBuilder sb = new StringBuilder("ACTION=").append(q(action)).append('\n');
        sb.append("APK=").append(q(c.getApplicationInfo().sourceDir)).append('\n');
        sb.append("PANEL_METHOD=").append(q(Modes.prefs(c).getString("panel_method", "sf"))).append('\n');
        sb.append("CLEAN_LIST=").append(q(Modes.prefs(c).getString("clean_list", ""))).append('\n');
        sb.append("BT_OFF_AFTER=").append(Modes.prefs(c).getBoolean("bt_off_after", false) ? "1" : "0").append('\n');
        for (int i = 0; i + 1 < vars.length; i += 2)
            sb.append(vars[i]).append('=').append(q(vars[i + 1])).append('\n');
        sb.append(core(c));
        return sb.toString();
    }

    /** vars: alternating name,value pairs. Returns {exitCode, output}. */
    public static Object[] run(Context c, String action, String... vars) {
        try {
            String s = script(c, action, vars);
            if (ShizukuBridge.isGranted())
                return ShizukuBridge.exec(new String[]{"sh", "-c", s});
            if (hasRoot()) {
                final Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", s});
                p.getOutputStream().close();
                Thread wd = new Thread(() -> {
                    try { Thread.sleep(60000); p.destroy(); } catch (InterruptedException ignored) {}
                });
                wd.setDaemon(true);
                wd.start();
                InputStream in = p.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return new Object[]{p.waitFor(), bo.toString("UTF-8")};
            }
            return new Object[]{-1, "No Shizuku permission (and no root)."};
        } catch (Throwable t) {
            return new Object[]{-1, "error: " + t};
        }
    }

    public static String out(Context c, String action, String... vars) { return (String) run(c, action, vars)[1]; }

    /** Long-running privileged command; caller reads .stream and calls .stop(). */
    public static final class Proc {
        public final InputStream stream;
        private final ShizukuBridge.Stream sh;
        private final Process p;
        Proc(InputStream s, ShizukuBridge.Stream sh, Process p) { this.stream = s; this.sh = sh; this.p = p; }
        public void stop() {
            try { if (sh != null) sh.destroy(); } catch (Throwable ignored) {}
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
        }
    }

    public static Proc spawn(Context c, String action) throws Exception {
        String s = script(c, action);
        if (ShizukuBridge.isGranted()) {
            ShizukuBridge.Stream st = ShizukuBridge.spawn(new String[]{"sh", "-c", s});
            return new Proc(st.out, st, null);
        }
        if (hasRoot()) {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", s});
            p.getOutputStream().close();
            return new Proc(p.getInputStream(), null, p);
        }
        throw new IllegalStateException("no Shizuku permission");
    }

    public static Proc spawnRaw(String... cmd) throws Exception {
        if (ShizukuBridge.isGranted()) {
            ShizukuBridge.Stream st = ShizukuBridge.spawn(cmd);
            return new Proc(st.out, st, null);
        }
        if (hasRoot()) {
            StringBuilder sb = new StringBuilder();
            for (String x : cmd) sb.append(q(x)).append(' ');
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", sb.toString()});
            p.getOutputStream().close();
            return new Proc(p.getInputStream(), null, p);
        }
        throw new IllegalStateException("no Shizuku permission");
    }
}
