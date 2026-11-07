package io.legiondock;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal Shizuku client (protocol v13) written against raw binder transactions,
 * so the app needs no external library.
 */
public final class ShizukuBridge {
    private static final String TAG = "LegionDock/Shizuku";
    static final String SVC = "moe.shizuku.server.IShizukuService";
    static final String APP = "moe.shizuku.server.IShizukuApplication";
    static final String PROC = "moe.shizuku.server.IRemoteProcess";
    static final String MANAGER_PKG = "moe.shizuku.privileged.api";

    private static final int FIRST = IBinder.FIRST_CALL_TRANSACTION;
    // IShizukuService (explicit AIDL ids, code = FIRST + id)
    private static final int T_GET_VERSION = FIRST + 2;
    private static final int T_NEW_PROCESS = FIRST + 7;
    private static final int T_REQUEST_PERMISSION = FIRST + 14;
    private static final int T_CHECK_SELF_PERMISSION = FIRST + 15;
    private static final int T_ATTACH_APPLICATION = FIRST + 17;
    // IRemoteProcess (implicit ids)
    private static final int P_GET_OUTPUT = FIRST;
    private static final int P_GET_INPUT = FIRST + 1;
    private static final int P_WAIT_FOR = FIRST + 3;
    private static final int P_DESTROY = FIRST + 5;

    private static volatile IBinder binder;
    private static volatile boolean granted;
    private static volatile int version = -1;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Runnable r) { listeners.add(r); }
    public static void removeListener(Runnable r) { listeners.remove(r); }
    private static void notifyListeners() { for (Runnable r : listeners) try { r.run(); } catch (Throwable ignored) {} }

    public static boolean isRunning() { IBinder b = binder; return b != null && b.pingBinder(); }
    public static boolean isGranted() { return isRunning() && granted; }
    public static int version() { return version; }

    /** Our side of IShizukuApplication: the server calls back here. */
    private static final Binder APP_BINDER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(APP); return true; }
            if (code == FIRST + 1) { // bindApplication(Bundle)
                data.enforceInterface(APP);
                Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                if (b != null) {
                    granted = b.getBoolean("shizuku:attach-reply-permission-granted", granted);
                    version = b.getInt("shizuku:attach-reply-version", version);
                }
                notifyListeners();
                return true;
            }
            if (code == FIRST + 2) { // dispatchRequestPermissionResult(int, Bundle)
                data.enforceInterface(APP);
                data.readInt();
                Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                granted = b != null && b.getBoolean("shizuku:request-permission-reply-allowed", false);
                notifyListeners();
                return true;
            }
            if (code == FIRST + 10000) { // showPermissionConfirmation (Sui only)
                data.enforceInterface(APP);
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private static final IBinder.DeathRecipient DEATH = new IBinder.DeathRecipient() {
        @Override public void binderDied() { binder = null; granted = false; notifyListeners(); }
    };

    static synchronized void onBinderReceived(IBinder b, String packageName) {
        if (b == null || b == binder) return;
        if (binder != null) try { binder.unlinkToDeath(DEATH, 0); } catch (Throwable ignored) {}
        binder = b;
        try { b.linkToDeath(DEATH, 0); } catch (Throwable ignored) {}
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            Bundle args = new Bundle();
            args.putInt("shizuku:attach-api-version", 13);
            args.putString("shizuku:attach-package-name", packageName);
            data.writeInterfaceToken(SVC);
            data.writeStrongBinder(APP_BINDER);
            data.writeInt(1);
            args.writeToParcel(data, 0);
            b.transact(T_ATTACH_APPLICATION, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "attachApplication failed", t);
        } finally { data.recycle(); reply.recycle(); }
        refreshPermission();
        Log.i(TAG, "binder received, granted=" + granted);
        notifyListeners();
    }

    static boolean refreshPermission() {
        IBinder b = binder;
        if (b == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC);
            b.transact(T_CHECK_SELF_PERMISSION, data, reply, 0);
            reply.readException();
            granted = reply.readInt() != 0;
            data.recycle(); reply.recycle();
            data = Parcel.obtain(); reply = Parcel.obtain();
            data.writeInterfaceToken(SVC);
            b.transact(T_GET_VERSION, data, reply, 0);
            reply.readException();
            version = reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "checkSelfPermission failed", t);
        } finally { data.recycle(); reply.recycle(); }
        return granted;
    }

    public static void requestPermission() {
        IBinder b = binder;
        if (b == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC);
            data.writeInt(42);
            b.transact(T_REQUEST_PERMISSION, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        } finally { data.recycle(); reply.recycle(); }
    }

    /** A long-running remote process whose stdout can be streamed. */
    public static final class Stream {
        public final java.io.InputStream out;
        public final java.io.OutputStream in;   // process stdin (null unless requested)
        private final IBinder proc;
        Stream(java.io.InputStream out, java.io.OutputStream in, IBinder proc) { this.out = out; this.in = in; this.proc = proc; }
        public void destroy() {
            try { out.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            procInt(proc, P_DESTROY);
        }
    }

    static Stream spawn(String[] cmd) throws Exception { return spawn(cmd, false); }

    static Stream spawn(String[] cmd, boolean keepStdin) throws Exception {
        IBinder b = binder;
        if (b == null) throw new IllegalStateException("Shizuku not running");
        IBinder proc;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC);
            data.writeStringArray(cmd);
            data.writeStringArray(null);
            data.writeString(null);
            b.transact(T_NEW_PROCESS, data, reply, 0);
            reply.readException();
            proc = reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
        if (proc == null) throw new IllegalStateException("newProcess returned null");
        ParcelFileDescriptor stdin = procPfd(proc, P_GET_OUTPUT);
        java.io.OutputStream inStream = null;
        if (stdin != null) {
            if (keepStdin) inStream = new ParcelFileDescriptor.AutoCloseOutputStream(stdin);
            else stdin.close();
        }
        ParcelFileDescriptor stdout = procPfd(proc, P_GET_INPUT);
        return new Stream(new ParcelFileDescriptor.AutoCloseInputStream(stdout), inStream, proc);
    }

    /** Runs a command as the Shizuku (shell) user; returns {exitCode, output}. */
    static Object[] exec(String[] cmd) throws Exception {
        IBinder b = binder;
        if (b == null) throw new IllegalStateException("Shizuku not running");
        IBinder proc;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC);
            data.writeStringArray(cmd);
            data.writeStringArray(null);
            data.writeString(null);
            b.transact(T_NEW_PROCESS, data, reply, 0);
            reply.readException();
            proc = reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
        if (proc == null) throw new IllegalStateException("newProcess returned null");

        ParcelFileDescriptor stdin = procPfd(proc, P_GET_OUTPUT);
        if (stdin != null) stdin.close();
        // watchdog: never let one stuck command block the app forever
        final IBinder fp = proc;
        final boolean[] done = {false};
        Thread wd = new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
            if (!done[0]) { Log.w(TAG, "command timed out, killing it"); procInt(fp, P_DESTROY); }
        }, "shizuku-watchdog");
        wd.setDaemon(true);
        wd.start();
        StringBuilder out = new StringBuilder();
        ParcelFileDescriptor stdout = procPfd(proc, P_GET_INPUT);
        if (stdout != null) {
            java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(stdout);
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n, "UTF-8"));
            in.close();
        }
        done[0] = true;
        wd.interrupt();
        int code = procInt(proc, P_WAIT_FOR);
        procInt(proc, P_DESTROY);
        return new Object[]{code, out.toString()};
    }

    private static ParcelFileDescriptor procPfd(IBinder proc, int code) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROC);
            proc.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(reply) : null;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static int procInt(IBinder proc, int code) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROC);
            proc.transact(code, data, reply, 0);
            reply.readException();
            return reply.dataAvail() >= 4 ? reply.readInt() : 0;
        } catch (Throwable t) {
            return -1;
        } finally { data.recycle(); reply.recycle(); }
    }
}
