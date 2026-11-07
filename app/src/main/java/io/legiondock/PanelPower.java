package io.legiondock;

import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns ONLY the tablet panel on/off at the SurfaceFlinger level (same technique as scrcpy).
 * Android still believes the display is ON, so every app keeps rendering to the TV mirror.
 *
 * Runs as shell via:  CLASSPATH=<apk> app_process /system/bin io.legiondock.PanelPower off|on|list
 */
public final class PanelPower {
    private static final int POWER_MODE_OFF = 0, POWER_MODE_NORMAL = 2;

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { System.out.println((args.length > 0 ? "FAIL " : "") + t); }
        finally {
            System.out.flush();
            // SIGKILL ourselves: skips native exit handlers that crash on this firmware and
            // never waits for leftover binder/audio threads
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private static void run(String[] args) {
        String cmd = args.length > 0 ? args[0] : "list";
        try {
            try { Looper.prepareMainLooper(); } catch (Throwable ignored) {}
            List<long[]> ids = new ArrayList<>();
            List<IBinder> tokens = new ArrayList<>();
            collect(ids, tokens);
            long internal = internalPhysicalId();
            System.out.println("physical displays: " + ids.size() + ", internal id: " + internal);

            if ("list".equals(cmd)) {
                for (long[] id : ids) System.out.println("  id " + id[0] + (id[0] == internal ? "  (internal)" : ""));
                return;
            }
            int mode = "on".equals(cmd) ? POWER_MODE_NORMAL : POWER_MODE_OFF;
            int target = 0; // fallback: first physical display is the built-in panel
            for (int i = 0; i < ids.size(); i++) if (ids.get(i)[0] == internal) target = i;
            if (tokens.isEmpty()) throw new IllegalStateException("no display tokens");

            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method set = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            set.invoke(null, tokens.get(target), mode);
            System.out.println("PANEL_OK " + cmd + " id " + ids.get(target)[0]);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("PANEL_FAIL " + c);
        }
    }

    private static void collect(List<long[]> ids, List<IBinder> tokens) throws Exception {
        Class<?> src;
        try {
            // Android 10-13
            src = Class.forName("android.view.SurfaceControl");
            src.getMethod("getPhysicalDisplayIds");
        } catch (Throwable notThere) {
            // Android 14+: moved into services.jar (DisplayControl)
            src = displayControl();
        }
        long[] all = (long[]) src.getMethod("getPhysicalDisplayIds").invoke(null);
        Method tok = src.getMethod("getPhysicalDisplayToken", long.class);
        for (long id : all) {
            ids.add(new long[]{id});
            tokens.add((IBinder) tok.invoke(null, id));
        }
    }

    private static Class<?> displayControl() throws Exception {
        Class<?> f = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method create = f.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                ClassLoader.class, int.class, boolean.class, String.class);
        ClassLoader cl = (ClassLoader) create.invoke(null, "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), dc, "android_servers");
        return dc;
    }

    /** Physical id behind logical display 0 (the tablet's own panel), or -1. */
    private static long internalPhysicalId() {
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = g.getMethod("getInstance").invoke(null);
            Object info = g.getMethod("getDisplayInfo", int.class).invoke(dmg, 0);
            Object addr = info.getClass().getField("address").get(info);
            return (Long) addr.getClass().getMethod("getPhysicalDisplayId").invoke(addr);
        } catch (Throwable t) {
            return -1;
        }
    }
}
