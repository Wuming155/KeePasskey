package com.keepasskey.app.passkey;

import android.app.Activity;
import android.content.Context;
import android.credentials.Credential;
import android.credentials.CredentialManager;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.credentials.PrepareGetCredentialResponse;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** Test-only browser-origin client. Calls the real framework CredentialManager with two approved web origins. */
public class CredentialBrowserGetClientActivity extends Activity {
    public static final String EXTRA_COMMAND = "p2_620_browser_command";
    public static final String EXTRA_ORIGIN = "p2_620_browser_origin";
    public static final String ACTION_PREPARE_A = "prepare-a";
    public static final String ACTION_PREPARE_B = "prepare-b";
    public static final String ACTION_GET_A = "get-a";
    public static final String ACTION_GET_B = "get-b";
    public static final String ACTION_FINISH = "finish";
    public static final String STATE_PREFS = "p2_620_browser_client";
    public static final String STATUS_KEY = "status";
    public static final String PID_KEY = "browser_pid";
    public static final String STATUS_FILE = "p2-620-browser-client.status";
    public static final String TAG = "P2_620_BrowserClient";

    private static final String PASSWORD_TYPE = "android.credentials.TYPE_PASSWORD_CREDENTIAL";
    private static final String ALLOWED_USER_IDS = "androidx.credentials.BUNDLE_KEY_ALLOWED_USER_IDS";
    private static final String AUTO_SELECT = "androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED";
    private static final String TYPE_PRIORITY = "androidx.credentials.BUNDLE_KEY_TYPE_PRIORITY_VALUE";
    private static final String DATA_ID = "androidx.credentials.BUNDLE_KEY_ID";
    private static final String DATA_PASSWORD = "androidx.credentials.BUNDLE_KEY_PASSWORD";
    private static final String USER_A = "cm-p2-620-a-user";
    private static final String USER_B = "cm-p2-620-b-user";
    private static final String PASSWORD_A = "Synthetic-P2-620-A-Password";
    private static final String PASSWORD_B = "Synthetic-P2-620-B-Password";

    private static PrepareGetCredentialResponse.PendingGetCredentialHandle handleA;
    private static PrepareGetCredentialResponse.PendingGetCredentialHandle handleB;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(PID_KEY, android.os.Process.myPid()).commit();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        statusView = new TextView(this);
        statusView.setText("P2-620 Android Credential Manager browser-origin caller");
        layout.addView(statusView);
        setContentView(layout);
        handleCommand(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    private void handleCommand(android.content.Intent intent) {
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (ACTION_FINISH.equals(command)) {
            handleA = null;
            handleB = null;
            status("finished");
            finish();
        } else if (ACTION_PREPARE_A.equals(command) || ACTION_PREPARE_B.equals(command)) {
            prepare(command, intent.getStringExtra(EXTRA_ORIGIN));
        } else if (ACTION_GET_A.equals(command)) {
            complete(ACTION_GET_A, handleA);
        } else if (ACTION_GET_B.equals(command)) {
            complete(ACTION_GET_B, handleB);
        }
    }

    private void prepare(String slot, String origin) {
        if (origin == null || !origin.startsWith("https://cm-p2-620-")) {
            status("prepare-error:" + slot + ":origin-invalid");
            return;
        }
        CredentialManager manager = getSystemService(CredentialManager.class);
        if (manager == null) {
            status("prepare-error:" + slot + ":manager-missing");
            return;
        }
        try {
            GetCredentialRequest request = new GetCredentialRequest.Builder(new Bundle())
                    .addCredentialOption(passwordOption())
                    .setOrigin(origin)
                    .build();
            status("preparing:" + slot);
            manager.prepareGetCredential(request, new CancellationSignal(), getMainExecutor(),
                    new OutcomeReceiver<PrepareGetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(PrepareGetCredentialResponse response) {
                            if (ACTION_PREPARE_A.equals(slot)) handleA = response.getPendingGetCredentialHandle();
                            else handleB = response.getPendingGetCredentialHandle();
                            boolean hasPassword = response.hasCredentialResults(PASSWORD_TYPE);
                            status("prepared:" + slot + ":password=" + hasPassword);
                        }

                        @Override
                        public void onError(GetCredentialException error) {
                            status("prepare-error:" + slot + ":" + error.getType());
                        }
                    });
        } catch (Throwable failure) {
            status("prepare-error:" + slot + ":" + failure.getClass().getSimpleName());
        }
    }

    private CredentialOption passwordOption() {
        Bundle data = new Bundle();
        data.putStringArrayList(ALLOWED_USER_IDS, new ArrayList<>());
        data.putBoolean(AUTO_SELECT, false);
        data.putInt(TYPE_PRIORITY, 1000);
        return new CredentialOption.Builder(PASSWORD_TYPE, new Bundle(data), new Bundle(data)).build();
    }

    private void complete(String slot, PrepareGetCredentialResponse.PendingGetCredentialHandle handle) {
        if (handle == null) {
            status("result:" + slot + ":handle-missing");
            return;
        }
        CredentialManager manager = getSystemService(CredentialManager.class);
        if (manager == null) {
            status("result:" + slot + ":manager-missing");
            return;
        }
        status("selecting:" + slot);
        manager.getCredential(this, handle, new CancellationSignal(), getMainExecutor(),
                new OutcomeReceiver<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        Bundle data = credential.getData();
                        String id = data.getString(DATA_ID, "");
                        String password = data.getString(DATA_PASSWORD, "");
                        String result = USER_A.equals(id) && PASSWORD_A.equals(password) ? "A"
                                : USER_B.equals(id) && PASSWORD_B.equals(password) ? "B" : "other";
                        status("result:" + slot + ":" + result);
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        status("result:" + slot + ":cancelled:" + error.getType());
                    }
                });
    }

    private void status(String value) {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putString(STATUS_KEY, value).commit();
        File statusFile = new File(getFilesDir(), STATUS_FILE);
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(("P2-620-CM-STATUS:" + value + "\npid=" + android.os.Process.myPid() + "\n").getBytes("UTF-8"));
            output.flush();
            output.getFD().sync();
        } catch (Exception failure) {
            Log.e(TAG, "test status receipt write failed: " + failure.getClass().getSimpleName());
        }
        if (statusView != null) {
            statusView.setText("P2-620-CM-STATUS:" + value + " pid=" + android.os.Process.myPid());
        }
        Log.i(TAG, "P2-620-CM-STATUS:" + value);
    }
}
