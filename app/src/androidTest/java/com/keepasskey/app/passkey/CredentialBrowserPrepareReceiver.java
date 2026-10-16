package com.keepasskey.app.passkey;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.credentials.CredentialManager;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.PrepareGetCredentialResponse;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** Background test caller that prepares origin B without dismissing the already visible A selector. */
public class CredentialBrowserPrepareReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.keepasskey.debug.test.P2_620_PREPARE_ORIGIN_B";
    public static final String EXTRA_ORIGIN = "p2_620_prepare_origin";
    public static final String STATUS_FILE = "p2-620-prepare-receiver.status";

    private static final String PASSWORD_TYPE = "android.credentials.TYPE_PASSWORD_CREDENTIAL";
    private static final String ALLOWED_USER_IDS = "androidx.credentials.BUNDLE_KEY_ALLOWED_USER_IDS";
    private static final String AUTO_SELECT = "androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED";
    private static final String TYPE_PRIORITY = "androidx.credentials.BUNDLE_KEY_TYPE_PRIORITY_VALUE";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        String origin = intent.getStringExtra(EXTRA_ORIGIN);
        try {
            CredentialManager manager = context.getSystemService(CredentialManager.class);
            if (manager == null || origin == null) {
                writeStatus(context, "error:missing-manager-or-origin");
                pending.finish();
                return;
            }
            CredentialOption option = passwordOption();
            GetCredentialRequest request = new GetCredentialRequest.Builder(new Bundle())
                    .addCredentialOption(option)
                    .setOrigin(origin)
                    .build();
            manager.prepareGetCredential(request, new CancellationSignal(), context.getMainExecutor(),
                    new OutcomeReceiver<PrepareGetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(PrepareGetCredentialResponse response) {
                            writeStatus(context, "prepared:password=" +
                                    response.hasCredentialResults(PASSWORD_TYPE));
                            pending.finish();
                        }

                        @Override
                        public void onError(GetCredentialException error) {
                            writeStatus(context, "error:" + error.getType());
                            pending.finish();
                        }
                    });
        } catch (Throwable failure) {
            writeStatus(context, "error:" + failure.getClass().getSimpleName());
            pending.finish();
        }
    }

    private static CredentialOption passwordOption() {
        Bundle data = new Bundle();
        data.putStringArrayList(ALLOWED_USER_IDS, new ArrayList<>());
        data.putBoolean(AUTO_SELECT, false);
        data.putInt(TYPE_PRIORITY, 1000);
        return new CredentialOption.Builder(PASSWORD_TYPE, new Bundle(data), new Bundle(data)).build();
    }

    private static void writeStatus(Context context, String status) {
        File file = new File(context.getFilesDir(), STATUS_FILE);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(("status=" + status + "\n").getBytes("UTF-8"));
            output.flush();
            output.getFD().sync();
        } catch (Exception failure) {
            Log.e("P2_620_PrepareReceiver", "status receipt write failed: " + failure.getClass().getSimpleName());
        }
    }
}
