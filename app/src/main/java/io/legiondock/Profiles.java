package io.legiondock;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Per-app profiles (stored as JSON in prefs). Values: 0 = inherit unless noted. */
public final class Profiles {
    // keys
    public static final String REFRESH = "refresh";   // Hz, 0 inherit
    public static final String FPS = "fps";           // FPS limit via Android game mode, 0 off
    public static final String DND = "dnd";           // 0 inherit, 1 on, 2 off
    public static final String MOUSE = "mouse";       // 0 inherit, 1 on, 2 off
    public static final String OVERLAY = "overlay";   // 0 inherit, 1 on, 2 off
    public static final String AUDIO = "audio";       // 0 inherit, 1 TV, 2 bluetooth, 3 speaker
    public static final String CLEAN = "clean";       // 1 = clean memory on launch
    public static final String SCOPE = "scope";       // 0 always, 1 only docked/handheld

    private static JSONObject all(Context c) {
        try { return new JSONObject(Modes.prefs(c).getString("profiles", "{}")); }
        catch (Throwable t) { return new JSONObject(); }
    }

    private static void save(Context c, JSONObject o) {
        Modes.prefs(c).edit().putString("profiles", o.toString()).apply();
    }

    public static List<String> packages(Context c) {
        List<String> l = new ArrayList<>();
        for (Iterator<String> it = all(c).keys(); it.hasNext(); ) l.add(it.next());
        return l;
    }

    public static boolean has(Context c, String pkg) { return pkg != null && all(c).has(pkg); }

    public static JSONObject get(Context c, String pkg) {
        JSONObject o = all(c).optJSONObject(pkg);
        return o == null ? new JSONObject() : o;
    }

    public static int getInt(Context c, String pkg, String key) { return get(c, pkg).optInt(key, 0); }

    public static void put(Context c, String pkg, String key, int v) {
        JSONObject a = all(c);
        try {
            JSONObject o = a.optJSONObject(pkg);
            if (o == null) o = new JSONObject();
            o.put(key, v);
            a.put(pkg, o);
        } catch (Throwable ignored) {}
        save(c, a);
    }

    public static void add(Context c, String pkg) {
        JSONObject a = all(c);
        if (!a.has(pkg)) {
            try {
                JSONObject o = new JSONObject();
                o.put(CLEAN, 1);
                a.put(pkg, o);
            } catch (Throwable ignored) {}
            save(c, a);
        }
    }

    public static void remove(Context c, String pkg) {
        JSONObject a = all(c);
        a.remove(pkg);
        save(c, a);
    }

    static String audioArg(int v) {
        switch (v) { case 1: return "tv"; case 2: return "bt"; case 3: return "speaker"; default: return ""; }
    }
}
