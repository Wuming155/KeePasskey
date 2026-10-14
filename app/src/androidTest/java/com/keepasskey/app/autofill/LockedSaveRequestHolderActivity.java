package com.keepasskey.app.autofill;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;

/** 以前台测试客户端发送独立 holder 进程保留的系统 PendingIntent。 */
public final class LockedSaveRequestHolderActivity extends Activity {

    public static final String EXTRA_REQUEST = "holder_request";
    private String request;
    private boolean sent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        request = getIntent().getStringExtra(EXTRA_REQUEST);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sent) return;
        sent = true;
        String method = "B".equals(request)
                ? LockedSaveRequestHolderProvider.GET_B : LockedSaveRequestHolderProvider.GET_A;
        try {
            Bundle result = getContentResolver().call(holderUri(), method, null, null);
            PendingIntent pending = result == null ? null : (PendingIntent) result.getParcelable("pending");
            if (pending == null) throw new IllegalStateException("system token missing from holder");
            // 测试客户端当前可见，显式声明此用户操作允许启动保存 Activity。
            Bundle options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    )
                    .toBundle();
            pending.send(this, 0, null, null, null, null, options);
            report(request + ":OK");
        } catch (Exception error) {
            report(request + ":" + error.getClass().getSimpleName());
        }
        finish();
    }

    private Uri holderUri() { return Uri.parse("content://" + getPackageName() + ".p2_603_holder"); }

    private void report(String result) {
        Bundle extras = new Bundle();
        extras.putString("result", result);
        getContentResolver().call(holderUri(), LockedSaveRequestHolderProvider.REPORT_RESULT, null, extras);
    }
}
