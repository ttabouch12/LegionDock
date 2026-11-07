package io.legiondock;

import android.content.Context;
import android.content.SharedPreferences;

/** Mode presets + per-mode settings stored in SharedPreferences. "handheld" uses the same storage. */
public final class Modes {
    public static final String CONSOLE = "console", MEDIA = "media", MIRROR = "mirror", DESKTOP = "desktop",
            OFF = "off", HANDHELD = "handheld";
    public static final String[] ALL = {CONSOLE, DESKTOP, MEDIA, MIRROR, OFF};
    public static final String[] DOCK_MODES = {CONSOLE, DESKTOP, MEDIA, MIRROR};

    /** Desktop needs Lenovo's PC mode (ZUI/ZUXOS); hidden on tablets that don't have it. */
    static boolean hasPcMode(Context c) { return prefs(c).getBoolean("cap_pcmode", true); }
    static boolean hasBypass(Context c) { return prefs(c).getBoolean("cap_bypass", true); }

    static String[] all(Context c) { return hasPcMode(c) ? ALL : new String[]{CONSOLE, MEDIA, MIRROR, OFF}; }
    static String[] dockModes(Context c) { return hasPcMode(c) ? DOCK_MODES : new String[]{CONSOLE, MEDIA, MIRROR}; }

    public static String title(String m) {
        switch (m) {
            case CONSOLE: return "Console";
            case MEDIA: return "Media";
            case MIRROR: return "Mirror";
            case DESKTOP: return "Desktop";
            case HANDHELD: return "Handheld";
            default: return "Off";
        }
    }

    public static String subtitle(String m) {
        switch (m) {
            case CONSOLE: return "Games on the big screen";
            case DESKTOP: return "Lenovo PC mode";
            case MEDIA: return "Movies and shows";
            case MIRROR: return "Same screen, panel off";
            default: return "Leave the TV alone";
        }
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("legiondock", Context.MODE_PRIVATE);
    }

    public static String active(Context c) { return prefs(c).getString("active_mode", CONSOLE); }

    // ── per-mode settings ──
    public static final String K_HOME = "home", K_LAUNCH = "launch", K_RES = "res", K_REFRESH = "refresh",
            K_UI = "ui", K_PANEL = "panel", K_AWAKE = "awake", K_LAND = "landscape", K_ANIM = "anim",
            K_DND = "dnd", K_GUIDE = "guide", K_AUDIO = "audio", K_CLEAN = "clean", K_OVERLAY = "overlay",
            K_MOUSE = "mouse", K_BT = "bt", K_BYPASS = "bypass", K_SCALE = "scale", K_HAPTIC = "nohaptic";

    static String key(String mode, String k) { return "m." + mode + "." + k; }

    public static int getInt(Context c, String mode, String k) {
        return prefs(c).getInt(key(mode, k), defInt(mode, k));
    }

    public static boolean getBool(Context c, String mode, String k) {
        return prefs(c).getBoolean(key(mode, k), defBool(mode, k));
    }

    public static String getStr(Context c, String mode, String k) {
        return prefs(c).getString(key(mode, k), "");
    }

    public static void put(Context c, String mode, String k, Object v) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (v instanceof Integer) e.putInt(key(mode, k), (Integer) v);
        else if (v instanceof Boolean) e.putBoolean(key(mode, k), (Boolean) v);
        else e.putString(key(mode, k), String.valueOf(v));
        e.apply();
    }

    static int defInt(String mode, String k) {
        switch (k) {
            case K_RES: return MIRROR.equals(mode) ? 0 : DESKTOP.equals(mode) ? 1440 : 1080;
            case K_REFRESH: return CONSOLE.equals(mode) ? 60 : HANDHELD.equals(mode) ? 0 : 0;
            case K_UI: return MIRROR.equals(mode) ? 3 : 0; // 0 auto, 1 smaller, 2 larger, 3 don't change
            case K_AUDIO: return MEDIA.equals(mode) ? 1 : 0; // 0 auto, 1 TV, 2 bluetooth, 3 speaker
            case K_SCALE: return 80;   // Desktop mode: % of the TV's native density
        }
        return 0;
    }

    static boolean defBool(String mode, String k) {
        switch (k) {
            case K_PANEL: return !HANDHELD.equals(mode);
            case K_AWAKE: return !HANDHELD.equals(mode);
            case K_LAND: return !MIRROR.equals(mode) && !HANDHELD.equals(mode);
            case K_GUIDE: return !MIRROR.equals(mode) && !DESKTOP.equals(mode);
            case K_ANIM: return CONSOLE.equals(mode);
            case K_DND: return MEDIA.equals(mode);
            case K_CLEAN: return CONSOLE.equals(mode);
            case K_BT: return !MIRROR.equals(mode) && !HANDHELD.equals(mode);
            case K_BYPASS: return !HANDHELD.equals(mode);
            case K_HAPTIC: return !HANDHELD.equals(mode);
        }
        return false;
    }

    /** Variables passed to core.sh for docking in this mode. */
    public static String[] dockVars(Context c, String m) {
        int ui = getInt(c, m, K_UI);
        String dens = ui == 0 ? "auto" : ui == 1 ? "small" : ui == 2 ? "large" : "none";
        return new String[]{
                "RENDER", String.valueOf(getInt(c, m, K_RES)),
                "DENSITY_MODE", dens,
                "REFRESH", String.valueOf(getInt(c, m, K_REFRESH)),
                "LANDSCAPE", getBool(c, m, K_LAND) ? "1" : "0",
                "PANEL_OFF", getBool(c, m, K_PANEL) ? "1" : "0",
                "STAY_AWAKE", getBool(c, m, K_AWAKE) ? "1" : "0",
                "ANIM", getBool(c, m, K_ANIM) ? "1" : "0",
                "DND", getBool(c, m, K_DND) ? "1" : "0",
                "HOME_COMP", getStr(c, m, K_HOME),
                "LAUNCH_COMP", getStr(c, m, K_LAUNCH),
                "AUDIO", Profiles.audioArg(getInt(c, m, K_AUDIO)),
                "CLEAN", getBool(c, m, K_CLEAN) ? "1" : "0",
                "FORCE_MIRROR", prefs(c).getBoolean("force_mirror", true) ? "1" : "0",
                "DESKTOP", DESKTOP.equals(m) ? "1" : "0",
                "BT", getBool(c, m, K_BT) ? "1" : "0",
                "BYPASS", getBool(c, m, K_BYPASS) ? "1" : "0",
                "NO_HAPTIC", getBool(c, m, K_HAPTIC) ? "1" : "0",
        };
    }

    public static String[] handheldVars(Context c) {
        return new String[]{
                "REFRESH", String.valueOf(getInt(c, HANDHELD, K_REFRESH)),
                "DND", getBool(c, HANDHELD, K_DND) ? "1" : "0",
                "CLEAN", getBool(c, HANDHELD, K_CLEAN) ? "1" : "0",
        };
    }

    // ── global state ──
    public static boolean docked(Context c) { return prefs(c).getBoolean("docked", false); }
    public static String dockedMode(Context c) { return prefs(c).getString("docked_mode", ""); }
    public static void setDocked(Context c, boolean d, String mode) {
        prefs(c).edit().putBoolean("docked", d).putString("docked_mode", d ? mode : "").apply();
    }
    public static boolean handheld(Context c) { return prefs(c).getBoolean("handheld_active", false); }
    public static void setHandheld(Context c, boolean on) { prefs(c).edit().putBoolean("handheld_active", on).apply(); }

    /** The mode whose settings currently apply (docked mode, handheld, or "" for plain tablet). */
    public static String context(Context c) {
        if (docked(c)) return dockedMode(c);
        if (handheld(c)) return HANDHELD;
        return "";
    }
}
