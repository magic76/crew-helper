package com.crewpocket.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Exposes only the last manually exported ZIP, read-only via temporary URI grant. */
public final class CrewDebugShareProvider extends ContentProvider {
    static final String AUTHORITY = "com.crewpocket.helper.debugbundle";
    static final Uri SHARE_URI =
            Uri.parse("content://" + AUTHORITY + "/latest");

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return isValid(uri) ? "application/zip" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!isValid(uri) || !"r".equals(mode)) {
            throw new FileNotFoundException("Read-only debug bundle URL required");
        }
        File file = bundleFile();
        if (!file.isFile()) throw new FileNotFoundException("No debug bundle");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        if (!isValid(uri)) return null;
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor result = new MatrixCursor(columns);
        File file = bundleFile();
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                values[i] = "crew-debug-lab.zip";
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                values[i] = file.isFile() ? file.length() : 0L;
            }
        }
        result.addRow(values);
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int update(Uri uri, ContentValues values,
            String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override public int delete(Uri uri, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    static File targetFile(android.content.Context context) {
        return new File(new File(context.getCacheDir(), "crew_debug_lab"), "latest.zip");
    }

    private File bundleFile() {
        return targetFile(getContext());
    }

    private static boolean isValid(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && "/latest".equals(uri.getPath());
    }
}
