package com.keepasskey.app.autofill;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** 在独立 test APK 进程持有 PendingIntent，模拟系统保存 UI 对 IntentSender 的强引用。 */
public final class LockedSaveRequestHolderProvider extends ContentProvider {
    public static final String HOLD = "hold";
    public static final String GET_A = "get_a";
    public static final String GET_B = "get_b";
    public static final String REPORT_RESULT = "report_result";
    public static final String CLEAR_RESULT = "clear_result";
    public static final String READ_RESULT = "read_result";
    public static final String RELEASE = "release";
    public static final String EXTRA_A = "pending_a";
    public static final String EXTRA_B = "pending_b";

    private PendingIntent pendingA;
    private PendingIntent pendingB;
    private String lastResult;

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        try {
            if (HOLD.equals(method)) {
                pendingA = extras == null ? null : extras.getParcelable(EXTRA_A);
                pendingB = extras == null ? null : extras.getParcelable(EXTRA_B);
                result.putBoolean("ready", pendingA != null && pendingB != null);
            } else if (GET_A.equals(method) || GET_B.equals(method)) {
                PendingIntent pending = GET_A.equals(method) ? pendingA : pendingB;
                if (pending == null) throw new IllegalStateException("holder has no requested PendingIntent");
                result.putParcelable("pending", pending);
            } else if (REPORT_RESULT.equals(method)) {
                lastResult = extras == null ? null : extras.getString("result");
                result.putBoolean("reported", lastResult != null);
            } else if (CLEAR_RESULT.equals(method)) {
                lastResult = null;
                result.putBoolean("cleared", true);
            } else if (READ_RESULT.equals(method)) {
                result.putString("result", lastResult);
            } else if (RELEASE.equals(method)) {
                if (pendingA != null) pendingA.cancel();
                if (pendingB != null) pendingB.cancel();
                pendingA = null;
                pendingB = null;
                lastResult = null;
                result.putBoolean("released", true);
            } else {
                throw new IllegalArgumentException("unknown holder operation");
            }
        } catch (Exception error) {
            result.putString("error", error.getClass().getSimpleName());
        }
        return result;
    }
}
