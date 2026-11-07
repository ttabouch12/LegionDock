package io.legiondock;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Forces where media audio goes (TV / Bluetooth / tablet speaker) via the audio policy's
 * preferred-device-for-strategy. Runs as shell:
 *   CLASSPATH=<apk> app_process /system/bin io.legiondock.AudioRoute tv|bt|speaker|clear|list
 */
public final class AudioRoute {
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
        String target = args.length > 0 ? args[0] : "list";
        try {
            try { Looper.prepareMainLooper(); } catch (Throwable ignored) {}
            AudioDeviceInfo[] devs = (AudioDeviceInfo[]) AudioManager.class
                    .getMethod("getDevicesStatic", int.class).invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
            if ("list".equals(target)) {
                for (AudioDeviceInfo d : devs) System.out.println("  type " + d.getType() + "  " + d.getProductName());
                return;
            }
            Object svc = audioService();
            int strategy = mediaStrategy();
            if (strategy < 0) throw new IllegalStateException("no media strategy");

            if ("clear".equals(target)) {
                Method rm = find(svc, "removePreferredDevicesForStrategy", 1);
                if (rm == null) rm = find(svc, "removePreferredDeviceForStrategy", 1);
                rm.invoke(svc, strategy);
                System.out.println("AUDIO_OK automatic");
                return;
            }

            AudioDeviceInfo pick = null;
            int[] wanted = "tv".equals(target) ? new int[]{AudioDeviceInfo.TYPE_HDMI, 10 /*HDMI_ARC*/, 29 /*HDMI_EARC*/,
                    AudioDeviceInfo.TYPE_DOCK, 31 /*DOCK_ANALOG*/, AudioDeviceInfo.TYPE_USB_DEVICE}
                    : "bt".equals(target) ? new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /*BLE_HEADSET*/, 27 /*BLE_SPEAKER*/, 30 /*BLE_BROADCAST*/}
                    : new int[]{AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};
            outer:
            for (int t : wanted) for (AudioDeviceInfo d : devs) if (d.getType() == t) { pick = d; break outer; }
            if (pick == null) { System.out.println("AUDIO_FAIL no " + target + " output connected"); return; }

            Class<?> adaC = Class.forName("android.media.AudioDeviceAttributes");
            Object ada = adaC.getConstructor(AudioDeviceInfo.class).newInstance(pick);
            Method set = find(svc, "setPreferredDevicesForStrategy", 2);
            if (set != null) set.invoke(svc, strategy, Collections.singletonList(ada));
            else find(svc, "setPreferredDeviceForStrategy", 2).invoke(svc, strategy, ada);
            System.out.println("AUDIO_OK " + target + " (" + pick.getProductName() + ")");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("AUDIO_FAIL " + c);
        }
    }

    private static Object audioService() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
        return Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static int mediaStrategy() throws Exception {
        AudioAttributes media = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
        Class<?> ps = Class.forName("android.media.audiopolicy.AudioProductStrategy");
        List<?> list = (List<?>) ps.getMethod("getAudioProductStrategies").invoke(null);
        for (Object s : list) {
            if ((Boolean) ps.getMethod("supportsAudioAttributes", AudioAttributes.class).invoke(s, media))
                return (Integer) ps.getMethod("getId").invoke(s);
        }
        return -1;
    }

    private static Method find(Object o, String name, int params) {
        for (Method m : o.getClass().getMethods())
            if (m.getName().equals(name) && m.getParameterTypes().length == params) return m;
        return null;
    }
}
