package com.keepasskey.app.data.repository;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** 仅测试APK的系统CreateDocument客户端，回执保留系统实际URI与授权flags。 */
public final class SafCreationProbeActivity extends Activity {
    public static final String EXTRA_BOOTSTRAP = "saf594.bootstrap";
    public static final String CONTROL_PATH = "control594";
    private static final int CONTROL_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int REQUEST = 594;
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

    private String providerAuthority;
    private String targetPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        providerAuthority = findProviderAuthority();
        targetPackage = requireDeclaredInstrumentationTarget(getIntent().getStringExtra("target"));
        if (getIntent().getBooleanExtra(EXTRA_BOOTSTRAP, false)) {
            grantUriPermission(targetPackage,
                    Uri.parse("content://" + providerAuthority + "/" + CONTROL_PATH),
                    CONTROL_GRANT_FLAGS);
            finish();
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/octet-stream");
        create.putExtra(Intent.EXTRA_TITLE, getIntent().getStringExtra("name"));
        create.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse(requireExtra(getIntent().getStringExtra("initial"), "initial")));
        startActivityForResult(create, REQUEST);
    }

    /** 测试客户端保留平台结果回执路径。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST) return;
        Bundle report = new Bundle();
        report.putInt("code", resultCode);
        report.putString("uri", data == null || data.getData() == null ? null : data.getData().toString());
        report.putInt("flags", data == null ? 0 : data.getFlags());
        Uri resultUri = data == null ? null : data.getData();
        if (resultCode == RESULT_OK && resultUri != null) {
            grantUriPermission(targetPackage, resultUri, data.getFlags() & GRANT_FLAGS);
        }
        getContentResolver().call(Uri.parse("content://" + providerAuthority), "594:report", null, report);
        finish();
    }

    private String findProviderAuthority() {
        PackageInfo packageInfo;
        try {
            packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PROVIDERS);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
        ProviderInfo[] providers = packageInfo.providers;
        int matches = 0;
        String authority = null;
        if (providers != null) {
            for (ProviderInfo provider : providers) {
                if (SafCreationProbeDocumentsProvider.class.getName().equals(provider.name)) {
                    matches++;
                    if (!getPackageName().equals(provider.packageName)) {
                        throw new IllegalStateException("594 provider must belong to the test APK");
                    }
                    authority = provider.authority;
                }
            }
        }
        if (matches != 1 || authority == null) {
            throw new IllegalStateException("test APK must declare exactly one 594 DocumentsProvider");
        }
        return authority;
    }

    private String requireDeclaredInstrumentationTarget(String target) {
        PackageInfo packageInfo;
        try {
            packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_INSTRUMENTATION);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
        if (packageInfo.instrumentation == null) {
            throw new IllegalStateException("test APK has no instrumentation declaration");
        }
        int matches = 0;
        String declaredTarget = null;
        for (android.content.pm.InstrumentationInfo instrumentation : packageInfo.instrumentation) {
            if (instrumentation.targetPackage != null && instrumentation.targetPackage.equals(target)) {
                matches++;
                declaredTarget = instrumentation.targetPackage;
            }
        }
        if (matches != 1) throw new IllegalArgumentException("target is not the declared instrumentation target");
        return declaredTarget;
    }

    private static String requireExtra(String value, String key) {
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }
}
