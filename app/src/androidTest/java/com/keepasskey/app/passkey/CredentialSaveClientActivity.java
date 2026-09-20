package com.keepasskey.app.passkey;

import android.app.Activity;
import android.content.ComponentName;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CreateCredentialResponse;
import android.credentials.CredentialManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设备侧「真实客户端」的**凭据保存请求发起方**（instrumented 用例专用，**不属于产品代码**）。
 *
 * <h2>为什么需要它</h2>
 *
 * 「用户在登录界面提交后由系统弹出保存提示」这一链路，其**起点必然是本应用之外的调用方**
 * 调用 {@link CredentialManager#createCredential}。既有用例（宿主单测、
 * {@code CredentialProviderRequestContractDeviceTest}）只覆盖「provider 侧收到请求之后」的半段，
 * **没有任何用例覆盖系统是否真的把创建请求路由到了本 provider**。本 Activity 用来补上这半段。
 *
 * <h2>为什么必须用 Java</h2>
 *
 * 同 {@code AutofillClientActivity}（2026-09-16 真机实测结论）：本类运行在**测试 APK 自己的
 * 进程**里（uid 亦不同于被测应用），而测试 APK 自身**不打包 kotlin-stdlib**——用 Kotlin 写
 * 本类会以 {@code ClassNotFoundException: kotlin.jvm.internal.Intrinsics} 崩溃。
 * 故本类刻意只用 Android **框架** API + Java，保持零 Kotlin / 零 androidx.credentials 依赖。
 *
 * <h2>为何手写类型与 Bundle 键字面量</h2>
 *
 * 具体凭据类型（{@code CreatePasswordRequest} 等）只存在于 androidx.credentials，框架侧只有
 * 通用的 {@code CreateCredentialRequest(type, credentialData)}。本类直接按官方契约装配
 * ——类型取 {@code android.credentials.TYPE_PASSWORD_CREDENTIAL}，数据键取 androidx 的
 * {@code BUNDLE_KEY_ID} / {@code BUNDLE_KEY_PASSWORD}（两值经 dex 字面量核对）。
 *
 * <h2>为什么自己驱动</h2>
 *
 * 客户端与测试进程不同进程，测试代码无法持有其 View。故本 Activity 在 resume 后**自行**
 * 发起 {@code createCredential}，并把结果（含异常 type）同时写入界面文本与 logcat，
 * 使测试侧既可读 logcat、也可读无障碍树；真机排查时亦可直接由 adb 拉起后人工观察。
 *
 * <h2>敏感数据纪律</h2>
 *
 * 账号/口令均为虚构测试值；输出**只含异常 type / 布尔探测结果**，绝不输出口令内容。
 */
public class CredentialSaveClientActivity extends Activity {

    public static final String TAG = "CmSaveClientTest";

    /** 界面标题，同时作为测试侧在无障碍树中定位本客户端的锚点 */
    public static final String TITLE = "CM-保存客户端表单";

    /** 状态文本前缀（无障碍树与 logcat 双通道证据锚点） */
    public static final String STATUS_PREFIX = "CM-SAVE-STATUS";

    /** 虚构测试账号（非真实用户数据） */
    public static final String TEST_USERNAME = "cm-probe-user";

    /** 虚构测试口令（非真实用户数据；界面与日志均不输出其内容） */
    public static final String TEST_PASSWORD = "Cm-Probe-Passw0rd!";

    /** 框架侧密码凭据类型（androidx {@code PasswordCredential.TYPE_PASSWORD_CREDENTIAL} 的取值） */
    private static final String TYPE_PASSWORD_CREDENTIAL = "android.credentials.TYPE_PASSWORD_CREDENTIAL";

    /** androidx {@code BUNDLE_KEY_ID} */
    private static final String KEY_ID = "androidx.credentials.BUNDLE_KEY_ID";

    /** androidx {@code BUNDLE_KEY_PASSWORD} */
    private static final String KEY_PASSWORD = "androidx.credentials.BUNDLE_KEY_PASSWORD";

    /** 被测应用的 applicationId 与其 provider 组件类名（用于启用态探测） */
    private static final String APP_PACKAGE = "com.keepasskey";
    private static final String PROVIDER_CLASS =
            "com.keepasskey.app.passkey.KeePasskeyCredentialProviderService";

    private static final long REQUEST_DELAY_MS = 1200L;

    private TextView statusView;

    /** 防止任务栈来回切换导致重复发起（重复发起会让「系统是否自动重发」无法归因） */
    private boolean requested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(TITLE);
        title.setTextSize(18f);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText(STATUS_PREFIX + ":created");
        root.addView(statusView);

        setContentView(root);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (requested) {
            return;
        }
        requested = true;
        statusView.postDelayed(this::requestSave, REQUEST_DELAY_MS);
    }

    /** 发起一次与真实登录表单等价的「保存密码」请求 */
    private void requestSave() {
        try {
            CredentialManager credentialManager = getSystemService(CredentialManager.class);
            if (credentialManager == null) {
                status("no-credential-manager");
                return;
            }

            // 启用态探测：系统是否把本应用的 provider 视为「已启用」（只记布尔值）
            try {
                boolean enabled = credentialManager.isEnabledCredentialProviderService(
                        new ComponentName(APP_PACKAGE, PROVIDER_CLASS));
                status("providerEnabled=" + enabled);
            } catch (Throwable t) {
                status("enabledProbeThrew=" + t.getClass().getSimpleName());
            }

            Bundle credentialData = new Bundle();
            credentialData.putString(KEY_ID, TEST_USERNAME);
            credentialData.putString(KEY_PASSWORD, TEST_PASSWORD);
            CreateCredentialRequest request = new CreateCredentialRequest.Builder(
                    TYPE_PASSWORD_CREDENTIAL, credentialData, new Bundle()).build();

            status("requesting");
            credentialManager.createCredential(
                    this,
                    request,
                    new CancellationSignal(),
                    getMainExecutor(),
                    new OutcomeReceiver<CreateCredentialResponse, CreateCredentialException>() {
                        @Override
                        public void onResult(CreateCredentialResponse response) {
                            status("onResult");
                            Log.i(TAG, STATUS_PREFIX + ":onResult");
                        }

                        @Override
                        public void onError(CreateCredentialException error) {
                            status("onError:" + error.getType());
                            Log.w(TAG, STATUS_PREFIX + ":onError type=" + error.getType());
                        }
                    });
        } catch (Throwable t) {
            // 只记录异常类名（不透传 message，避免任何敏感内容落日志）
            status("threw:" + t.getClass().getSimpleName());
            Log.e(TAG, STATUS_PREFIX + ":threw " + t.getClass().getSimpleName());
        }
    }

    private void status(String value) {
        Log.i(TAG, STATUS_PREFIX + ":" + value);
        if (statusView != null) {
            statusView.setText(STATUS_PREFIX + ":" + value);
        }
    }
}
