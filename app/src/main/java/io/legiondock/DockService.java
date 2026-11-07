package io.legiondock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground watcher and layer engine.
 *   base layer : dock (TV connected, selected mode)  or  handheld (chosen controller attached)
 *   top layer  : per-app profile of the foreground app
 * The top layer is always popped before the base changes, then re-applied.
 */
public class DockService extends Service {
    public static final String A_DOCK = "io.legiondock.DOCK", A_UNDOCK = "io.legiondock.UNDOCK",
            A_REAPPLY = "io.legiondock.REAPPLY", A_PANEL_ON = "io.legiondock.PANEL_ON", A_PANEL_OFF = "io.legiondock.PANEL_OFF",
            A_CLEAN = "io.legiondock.CLEAN", A_PROFILES = "io.legiondock.PROFILES", A_FEATURES = "io.legiondock.FEATURES",
            A_EXIT_ALL = "io.legiondock.EXIT_ALL", A_RESET = "io.legiondock.RESET", A_SCALE = "io.legiondock.SCALE",
            A_RESET_UI = "io.legiondock.RESET_UI", A_SYNC = "io.legiondock.SYNC";
    static final String EDEN = "dev.eden.eden_emulator", CITRON = "org.citron.citron_emu";
    private static final String CH = "dock";
    private static final int NID = 1;

    static volatile DockService instance;
    static final StringBuilder LOG = new StringBuilder();
    static final CopyOnWriteArrayList<Runnable> uiListeners = new CopyOnWriteArrayList<>();
    static volatile String externalInfo = "";

    // effective feature state, read by GuideService
    static volatile boolean fGuide, fMouse, fOverlay;
    static volatile String foreground = "", appliedProfile = "";
    static volatile Boolean manualMouse, manualOverlay;
    static volatile boolean panelOff;
    /** TV settings are still applied on the system although LegionDock thinks it's in tablet mode. */
    static volatile boolean stray;
    static volatile String lastError = "";

    private HandlerThread thread;
    Handler h;
    private DisplayManager dm;
    private InputManager im;
    private boolean manualOverride; // user pressed Undock while TV connected
    private boolean shellSaysExternal;
    private int profilesVersion, appliedVersion = -1;
    private boolean repairChecked, hhSuppressed;

    public static void start(Context c) {
        if (android.os.Process.myUserHandle().hashCode() != 0) return;
        if (instance != null) { if (instance.h != null) instance.h.post(instance.evaluate); return; }
        try {
            Intent i = new Intent(c, DockService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) { log("could not start watcher: " + t.getMessage()); }
    }

    public static void send(Context c, String action) {
        Intent i = new Intent(c, DockService.class).setAction(action);
        try {
            if (instance != null) c.startService(i); else c.startForegroundService(i);
        } catch (Throwable t) { log("send failed: " + t.getMessage()); }
    }

    /** Called by GuideService whenever a real app comes to the front. */
    static void onForeground(final String pkg) {
        final DockService s = instance;
        if (s == null || pkg == null || pkg.equals(foreground)) return;
        s.h.post(() -> {
            if (pkg.equals(foreground)) return;
            foreground = pkg;
            manualMouse = null; manualOverlay = null;
            s.reapplyApp();
        });
    }

    static volatile String lastSync = "";

    void syncSaves(boolean manual) {
        String out = Shell.out(this, "sync_saves");
        android.util.Log.i("LegionDock", "sync_saves: " + out.replace("\n", " | "));
        String line = "";
        for (String l : out.split("\n")) if (l.startsWith("sync:")) line = l.substring(5).trim();
        if (line.isEmpty()) line = "sync failed";
        lastSync = new SimpleDateFormat("HH:mm", Locale.US).format(new Date()) + " · " + line;
        log("Switch saves: " + line);
        if (manual || !line.startsWith("saves already")) {
            final GuideService g = GuideService.inst;
            final String msg = "Switch saves: " + line;
            if (g != null && g.main != null) g.main.post(() -> g.flash(msg));
        }
        notifyUi();
    }

    private static String lastWindowPkg = "";

    /** Every window change: check which app is really on top (Lenovo's game overlay hides emulators). */
    static void onAnyWindow(final String pkg) {
        final DockService s = instance;
        if (s == null || pkg == null || !Modes.prefs(s).getBoolean("save_sync", true)) return;
        s.h.removeCallbacks(s.checkTop);
        s.h.postDelayed(s.checkTop, 700);
    }

    /** What this tablet supports (Lenovo PC mode, bypass charging). Checked once per start. */
    private void detectCaps() {
        String out = Shell.out(this, "caps");
        android.content.SharedPreferences.Editor e = Modes.prefs(this).edit();
        if (out.contains("pcmode=")) e.putBoolean("cap_pcmode", out.contains("pcmode=1"));
        if (out.contains("bypass=")) e.putBoolean("cap_bypass", out.contains("bypass=1"));
        e.apply();
        log("device: " + out.trim().replace('\n', ' '));
    }

    private final Runnable autoSync = () -> syncSaves(false);

    private final Runnable checkTop = () -> {
        if (!Shell.ready()) return;
        String top = Shell.out(this, "top_app").trim();
        if (top.isEmpty() || top.contains(" ")) return;
        String prev = lastWindowPkg;
        if (top.equals(prev)) return;
        lastWindowPkg = top;
        boolean wasEmu = EDEN.equals(prev) || CITRON.equals(prev);
        boolean isEmu = EDEN.equals(top) || CITRON.equals(top);
        if (wasEmu && !isEmu) { h.removeCallbacks(autoSync); h.postDelayed(autoSync, 3000); }
    };


    static void setManual(final Boolean mouse, final Boolean overlay) {
        if (mouse != null) manualMouse = mouse;
        if (overlay != null) manualOverlay = overlay;
        DockService s = instance;
        if (s != null) s.h.post(s::recomputeFeatures);
    }

    static void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s.trim() + "\n";
        synchronized (LOG) {
            LOG.append(line);
            if (LOG.length() > 16000) LOG.delete(0, LOG.length() - 12000);
        }
        notifyUi();
    }

    static void notifyUi() { for (Runnable r : uiListeners) try { r.run(); } catch (Throwable ignored) {} }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (android.os.Process.myUserHandle().hashCode() != 0) {   // App Clone / work profile copy
            stopSelf();
            return;
        }
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "TV watcher", NotificationManager.IMPORTANCE_MIN));
        goForeground();

        thread = new HandlerThread("dock");
        thread.start();
        h = new Handler(thread.getLooper());
        dm = getSystemService(DisplayManager.class);
        dm.registerDisplayListener(displayListener, h);
        im = getSystemService(InputManager.class);
        im.registerInputDeviceListener(inputListener, h);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(screenOn, f);
        ShizukuBridge.addListener(shizukuListener);
        log("watcher started");
        h.post(evaluate);
        h.postDelayed(poll, 4000);
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent == null ? null : intent.getAction();
        if (a != null) {
            final String action = a;
            if (A_EXIT_ALL.equals(action) || A_RESET.equals(action)) {
                // run immediately on its own thread, even if the worker is stuck on something
                new Thread(() -> handleAction(action), "rescue").start();
            } else {
                h.post(() -> handleAction(action));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance != this) { super.onDestroy(); return; }
        instance = null;
        try { dm.unregisterDisplayListener(displayListener); } catch (Throwable ignored) {}
        try { im.unregisterInputDeviceListener(inputListener); } catch (Throwable ignored) {}
        try { unregisterReceiver(screenOn); } catch (Throwable ignored) {}
        ShizukuBridge.removeListener(shizukuListener);
        thread.quitSafely();
        super.onDestroy();
    }

    // ── TV detection ──

    static Display findExternal(DisplayManager dm) {
        Method getType = null;
        try { getType = Display.class.getMethod("getType"); } catch (Throwable ignored) {}
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            int type = -1;
            if (getType != null) try { type = (Integer) getType.invoke(d); } catch (Throwable ignored) {}
            if (type == 2 /* EXTERNAL */ || type == 3 /* WIFI */) return d;
            if (type == -1 && (d.getFlags() & Display.FLAG_PRESENTATION) != 0
                    && !d.getName().toLowerCase(Locale.US).contains("virtual")) return d;
        }
        return null;
    }

    /** EXT_ID / EXT_UID / SCALE for the core script (Desktop mode scaling). */
    String[] extVars(String mode) {
        Display d = findExternal(dm);
        String id = d == null ? "" : String.valueOf(d.getDisplayId()), uid = "";
        if (d != null) try { uid = (String) Display.class.getMethod("getUniqueId").invoke(d); } catch (Throwable ignored) {}
        return new String[]{"EXT_ID", id, "EXT_UID", uid == null ? "" : uid,
                "SCALE", String.valueOf(Modes.getInt(this, mode, Modes.K_SCALE))};
    }

    static String[] concat(String[] a, String[] b) {
        String[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private boolean externalPresent() {
        Display d = findExternal(dm);
        if (d != null) {
            Display.Mode m = d.getMode();
            externalInfo = d.getName() + " · " + m.getPhysicalWidth() + "×" + m.getPhysicalHeight()
                    + " @ " + Math.round(m.getRefreshRate()) + " Hz";
            return true;
        }
        if (shellSaysExternal) { externalInfo = "External display"; return true; }
        externalInfo = "";
        return false;
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { schedule(); }
        @Override public void onDisplayRemoved(int id) { schedule(); }
        @Override public void onDisplayChanged(int id) { if (id != Display.DEFAULT_DISPLAY) schedule(); }
    };

    private void schedule() {
        h.removeCallbacks(evaluate);
        h.postDelayed(evaluate, 1500);
    }

    private final InputManager.InputDeviceListener inputListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { padsChanged(); }
        @Override public void onInputDeviceRemoved(int id) { padsChanged(); }
        @Override public void onInputDeviceChanged(int id) {}
    };

    private void padsChanged() {
        hhSuppressed = false;
        rememberPads();
        GuideService.padsChanged();
        schedule();
    }

    private final Runnable shizukuListener = () -> {
        if (h != null) { if (!Shell.ready()) repairChecked = false; notifyUi(); h.post(this.evaluate); }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (Modes.prefs(DockService.this).getBoolean("compat_detect", true) && Shell.ready()
                    && findExternal(dm) == null) {
                String out = Shell.out(DockService.this, "detect");
                boolean ext = false;
                int i = out.indexOf("displays=");
                if (i >= 0) try {
                    // compare against the lowest count ever seen: some firmwares list extra built-in displays
                    int n = Integer.parseInt(out.substring(i + 9).trim().split("\\s")[0]);
                    int base = Modes.prefs(DockService.this).getInt("sf_baseline", 99);
                    if (n > 0 && n < base) { base = n; Modes.prefs(DockService.this).edit().putInt("sf_baseline", n).apply(); }
                    ext = n > base;
                } catch (Throwable ignored) {}
                if (ext != shellSaysExternal) { shellSaysExternal = ext; h.post(evaluate); }
            } else if (shellSaysExternal) { shellSaysExternal = false; h.post(evaluate); }
            enforceRefresh();
            h.postDelayed(this, 4000);
        }
    };

    // ── controllers ──

    static List<String> connectedPads() {
        List<String> l = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                if (!l.contains(d.getName())) l.add(d.getName());
            }
        }
        return l;
    }

    private void rememberPads() {
        Set<String> known = new HashSet<>(Modes.prefs(this).getStringSet("known_pads", new HashSet<>()));
        if (known.addAll(connectedPads())) Modes.prefs(this).edit().putStringSet("known_pads", known).apply();
    }

    private boolean handheldWanted(boolean ext) {
        String want = Modes.prefs(this).getString("hh_controller", "");
        if (want.isEmpty() || ext || hhSuppressed) return false;
        List<String> pads = connectedPads();
        return "*".equals(want) ? !pads.isEmpty() : pads.contains(want);
    }

    // ── main evaluation ──

    final Runnable evaluate = new Runnable() {
        @Override public void run() {
            Context c = DockService.this;
            boolean ext = externalPresent();
            if (!repairChecked && Shell.ready()) { repairChecked = true; detectCaps(); repair(ext); }
            String mode = Modes.active(c);
            boolean docked = Modes.docked(c);
            if (!ext) manualOverride = false;
            if (docked && !ext && !Shell.ready()) {
                stray = true;
                lastError = "TV settings are still applied. Start Shizuku to restore the tablet.";
            }

            if (docked && (!ext || Modes.OFF.equals(mode))) {
                undock();
            } else if (!docked && ext && !manualOverride && !Modes.OFF.equals(mode)) {
                if (!Shell.ready()) log("TV connected, but Shizuku isn't ready: open LegionDock");
                else dock(mode);
            } else if (docked && ext && !mode.equals(Modes.dockedMode(c))) {
                undock(); dock(mode);
            }

            boolean hhWant = handheldWanted(ext) && !Modes.docked(c);
            if (hhWant != Modes.handheld(c) && Shell.ready()) {
                popApp();
                if (hhWant) {
                    log("controller attached: handheld mode");
                    logOut(Shell.out(c, "hh_on", Modes.handheldVars(c)));
                    Modes.setHandheld(c, true);
                } else {
                    log("handheld mode off");
                    logOut(Shell.out(c, "hh_off"));
                    Modes.setHandheld(c, false);
                }
                reapplyApp();
            }
            recomputeFeatures();
            updateNotification();
            notifyUi();
        }
    };

    private void handleAction(String a) {
        Context c = this;
        switch (a) {
            case A_DOCK:
                manualOverride = false;
                if (Modes.docked(c)) undock();
                String m = Modes.active(c);
                dock(Modes.OFF.equals(m) ? Modes.CONSOLE : m);
                break;
            case A_UNDOCK:
                manualOverride = externalPresent();
                undock();
                break;
            case A_REAPPLY:
                if (Modes.docked(c)) { undock(); dock(Modes.active(c)); }
                if (Modes.handheld(c)) {
                    popApp();
                    logOut(Shell.out(c, "hh_off"));
                    logOut(Shell.out(c, "hh_on", Modes.handheldVars(c)));
                    reapplyApp();
                }
                break;
            case A_PANEL_ON: logOut(Shell.out(c, "panel_on")); panelOff = false; break;
            case A_PANEL_OFF: logOut(Shell.out(c, "panel_off")); panelOff = true; break;
            case A_CLEAN: logOut(Shell.out(c, "clean", "KEEP", foreground)); break;
            case A_PROFILES: profilesVersion++; reapplyApp(); break;
            case A_EXIT_ALL: exitAll(); break;
            case A_SCALE:
                if (Modes.docked(c) && Modes.DESKTOP.equals(Modes.dockedMode(c)))
                {
                    logOut(Shell.out(c, "ext_scale", extVars(Modes.DESKTOP)));
                    if (panelOff) logOut(Shell.out(c, "panel_off"));   // re-entering PC mode can wake the panel
                }
                break;
            case A_SYNC: syncSaves(true); break;
            case A_RESET_UI:
                if (!Modes.docked(c)) logOut(Shell.out(c, "reset_ui"));
                else log("undock first to reset the display");
                break;
            case A_RESET:
                exitAll();
                Modes.prefs(c).edit().clear().commit();
                manualMouse = null; manualOverlay = null;
                profilesVersion++;
                log("all settings reset to default");
                break;
            case A_FEATURES: break;
        }
        evaluate.run();
    }

    /** Undo everything LegionDock changed and stay in tablet mode until the TV / controller is reconnected. */
    synchronized void exitAll() {
        if (!Shell.ready()) {
            lastError = "Can't restore: Shizuku isn't connected. Start Shizuku, then press Restore tablet again.";
            log(lastError);
            return;
        }
        log("restoring tablet: undoing all LegionDock changes");
        String out = Shell.out(this, "restore_all", extVars(Modes.CONSOLE));
        logOut(out);
        if (!out.contains("RESTORED")) { lastError = "Restore failed, see the activity log."; return; }
        manualOverride = externalPresent();
        hhSuppressed = Modes.handheld(this) || !connectedPads().isEmpty();
        Modes.setDocked(this, false, null);
        Modes.setHandheld(this, false);
        appliedProfile = "";
        appliedVersion = profilesVersion;
        manualMouse = false; manualOverlay = false;
        panelOff = false;
        stray = false;
        lastError = "";
        log("tablet restored" + (manualOverride ? " (auto-dock paused until the TV is unplugged)" : ""));
    }

    /** Fix leftovers from a crash / reboot / lost state: layers on disk that the app doesn't know about. */
    private void repair(boolean ext) {
        Shell.out(this, "kill_helpers");
        String out = Shell.out(this, "status");
        int i = out.indexOf("LAYERS");
        if (i < 0) return;
        String st = out.substring(i);
        boolean dockFile = st.contains("dock=1"), hhFile = st.contains("hh=1"), appFile = st.contains("app=1");
        boolean docked = Modes.docked(this);
        if (appFile && appliedProfile.isEmpty()) { logOut(Shell.out(this, "app_exit", "BASE_AUDIO", "clear")); log("cleaned up a leftover game profile"); }
        if (hhFile && !Modes.handheld(this)) { logOut(Shell.out(this, "hh_off")); log("cleaned up leftover handheld settings"); }
        if (dockFile && !docked && !ext) {
            log("found leftover TV settings: restoring tablet");
            logOut(Shell.out(this, "undock"));
        } else if (dockFile && !docked && ext) {
            // TV still plugged in: adopt the existing dock state instead of saving TV values as "original"
            Modes.setDocked(this, true, Modes.active(this));
        } else if (!dockFile && docked && !ext) {
            Modes.setDocked(this, false, null);
        }
        stray = false;
        lastError = "";
    }

    private void dock(String mode) {
        popApp();
        if (Modes.handheld(this)) { logOut(Shell.out(this, "hh_off")); Modes.setHandheld(this, false); }
        log("TV connected: docking in " + Modes.title(mode) + " mode");
        GuideService.transition(true, Modes.DESKTOP.equals(mode) ? "Desktop mode" : Modes.title(mode) + " mode");
        String out = Shell.out(this, "dock", concat(Modes.dockVars(this, mode), extVars(mode)));
        GuideService.transition(false, null);
        logOut(out);
        if (out.contains("DOCKED")) {
            Modes.setDocked(this, true, mode);
            panelOff = Modes.getBool(this, mode, Modes.K_PANEL);
        } else log("dock failed");
        reapplyApp();
    }

    private void undock() {
        popApp();
        log("restoring tablet mode");
        GuideService.transition(true, "Back to tablet");
        String out = Shell.out(this, "undock", extVars(Modes.CONSOLE));
        GuideService.transition(false, null);
        logOut(out);
        if (out.contains("UNDOCKED")) { Modes.setDocked(this, false, null); panelOff = false; stray = false; }
        else { stray = true; lastError = "Couldn't restore tablet mode (is Shizuku running?)."; }
        reapplyApp();
    }

    // ── per-app layer ──

    private String baseAudio() {
        String ctx = Modes.context(this);
        if (ctx.isEmpty() || Modes.HANDHELD.equals(ctx)) return "clear";
        String a = Profiles.audioArg(Modes.getInt(this, ctx, Modes.K_AUDIO));
        return a.isEmpty() ? "clear" : a;
    }

    private void popApp() {
        if (appliedProfile.isEmpty()) return;
        logOut(Shell.out(this, "app_exit", "BASE_AUDIO", baseAudio()));
        log("profile off: " + label(appliedProfile));
        appliedProfile = "";
    }

    void reapplyApp() {
        String pkg = foreground;
        boolean want = Profiles.has(this, pkg)
                && (Profiles.getInt(this, pkg, Profiles.SCOPE) == 0 || !Modes.context(this).isEmpty());
        String target = want ? pkg : "";
        if (target.equals(appliedProfile) && appliedVersion == profilesVersion) { recomputeFeatures(); return; }
        popApp();
        if (!target.isEmpty() && Shell.ready()) {
            int dnd = Profiles.getInt(this, pkg, Profiles.DND);
            log("profile on: " + label(pkg));
            logOut(Shell.out(this, "app_enter",
                    "PKG", pkg,
                    "REFRESH", String.valueOf(Profiles.getInt(this, pkg, Profiles.REFRESH)),
                    "FPS", String.valueOf(Profiles.getInt(this, pkg, Profiles.FPS)),
                    "DNDX", dnd == 1 ? "on" : dnd == 2 ? "off" : "",
                    "AUDIO", Profiles.audioArg(Profiles.getInt(this, pkg, Profiles.AUDIO)),
                    "CLEAN", String.valueOf(Profiles.getInt(this, pkg, Profiles.CLEAN))));
            appliedProfile = pkg;
        }
        appliedVersion = profilesVersion;
        recomputeFeatures();
    }

    void recomputeFeatures() {
        String ctx = Modes.context(this);
        boolean inCtx = !ctx.isEmpty();
        String pkg = appliedProfile;
        boolean guide = inCtx ? Modes.getBool(this, ctx, Modes.K_GUIDE) : Modes.prefs(this).getBoolean("guide_always", false);

        boolean mouse = inCtx && Modes.getBool(this, ctx, Modes.K_MOUSE);
        boolean overlay = inCtx ? Modes.getBool(this, ctx, Modes.K_OVERLAY) : Modes.prefs(this).getBoolean("overlay_always", false);
        if (!pkg.isEmpty()) {
            int pm = Profiles.getInt(this, pkg, Profiles.MOUSE), po = Profiles.getInt(this, pkg, Profiles.OVERLAY);
            if (pm != 0) mouse = pm == 1;
            if (po != 0) overlay = po == 1;
        }
        if (manualMouse != null) mouse = manualMouse;
        if (manualOverlay != null) overlay = manualOverlay;
        boolean changed = guide != fGuide || mouse != fMouse || overlay != fOverlay;
        fGuide = guide; fMouse = mouse; fOverlay = overlay;
        if (changed) GuideService.featuresChanged();
        notifyUi();
    }

    /** Lenovo's display service rewrites the refresh cap on every app switch; keep ours while docked. */
    private void enforceRefresh() {
        String ctx = Modes.context(this);
        if (ctx.isEmpty() || !Shell.ready()) return;
        int want = Modes.getInt(this, ctx, Modes.K_REFRESH);
        if (!appliedProfile.isEmpty()) {
            int pr = Profiles.getInt(this, appliedProfile, Profiles.REFRESH);
            if (pr > 0) want = pr;
        }
        if (want <= 0) return;
        String out = Shell.out(this, "refresh_check", "REFRESH", String.valueOf(want)).trim();
        if (!out.isEmpty() && out.contains("re-applied")) Log.i("LegionDock", out);
    }

    String label(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Throwable t) { return pkg; }
    }

    private static void logOut(String out) {
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.equals("DOCKED") || t.equals("UNDOCKED") || t.startsWith("PROFILE_")
                    || t.startsWith("HANDHELD_")) continue;
            log("  " + t);
        }
    }

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Modes.docked(c) && panelOff) {
                h.postDelayed(() -> Shell.out(DockService.this, "panel_off"), 900);
            }
        }
    };

    // ── notification ──

    private Notification buildNotification() {
        String ctx = Modes.context(this);
        String text = Modes.docked(this) ? "Docked · " + Modes.title(Modes.dockedMode(this))
                : Modes.handheld(this) ? "Handheld mode"
                : "Waiting for TV · " + Modes.title(Modes.active(this));
        if (!appliedProfile.isEmpty()) text += " · " + label(appliedProfile);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("LegionDock")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true);
        if (Modes.docked(this) || Modes.handheld(this) || stray || !appliedProfile.isEmpty()) {
            PendingIntent ex = PendingIntent.getService(this, 2,
                    new Intent(this, DockService.class).setAction(A_EXIT_ALL), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Restore tablet", ex).build());
        }
        if (Modes.docked(this)) {
            PendingIntent un = PendingIntent.getService(this, 1,
                    new Intent(this, DockService.class).setAction(A_UNDOCK), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Undock", un).build());
        }
        if (stray) b.setContentText("TV settings still applied · start Shizuku, then Restore");
        return b.build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NID, buildNotification());
    }
}
