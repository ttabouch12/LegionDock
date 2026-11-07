package io.legiondock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import moe.shizuku.api.BinderContainer;

/** Shizuku pushes its binder to "<package>.shizuku" via call("sendBinder"). */
public class ShizukuProvider extends ContentProvider {
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override public boolean onCreate() { return true; }

    @Override
    @SuppressWarnings("deprecation")
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        if ("sendBinder".equals(method)) {
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer c = Build.VERSION.SDK_INT >= 33
                    ? extras.getParcelable(EXTRA_BINDER, BinderContainer.class)
                    : (BinderContainer) extras.getParcelable(EXTRA_BINDER);
            if (c != null && c.binder != null) {
                ShizukuBridge.onBinderReceived(c.binder, getContext().getPackageName());
                // make sure the watcher is alive once Shizuku is available
                DockService.start(getContext());
            }
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
