package com.crewpocket.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * Shell-only handoff entry point. Android enforces the provider permission;
 * the nonce + UID/PID + exact installed APK + live Binder identity are checked
 * independently before any authority is passed into the App process.
 */
public final class BackgroundShellHandoffProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!BackgroundShellHandoff.METHOD_ATTACH.equals(method) || getContext() == null)
            return null;
        return BackgroundShellHandoff.offer(getContext().getApplicationContext(),
                extras, Binder.getCallingUid(), Binder.getCallingPid());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] selectionArgs, String sortOrder) {
        return null;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }
}
