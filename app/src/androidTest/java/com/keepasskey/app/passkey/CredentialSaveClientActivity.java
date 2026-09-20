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

    /** 运行模式选择 extra：缺省 = 密码保存；[MODE_PASSKEY] = 通行密钥注册 */
    public static final String EXTRA_MODE = "com.keepasskey.test.extra.MODE";

    /** 通行密钥注册模式取值 */
    public static final String MODE_PASSKEY = "passkey";

    /**
     * 「载荷残缺的公钥创建请求」模式。
     *
     * 刻意为复现 2026-09-21 真机踩到的形态：类型字符串是公钥类型，但载荷缺
     * `BUNDLE_KEY_REQUEST_JSON` ⇒ androidx 的
     * `BeginCreatePublicKeyCredentialRequest.createFrom$credentials` 抛
     * `FrameworkClassParsingException` 并被兜底成 `BeginCreateCustomCredentialRequest`
     * （见 `BeginCreateCredentialRequest$Companion.createFrom` 的异常表）。
     * 该形态用于验证 provider 侧「未识别请求」留痕与空响应的 fail-closed 行为。
     */
    public static final String MODE_PASSKEY_MALFORMED = "passkey-malformed";

    /** 框架侧公钥凭据类型（androidx {@code PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL}） */
    private static final String TYPE_PUBLIC_KEY_CREDENTIAL = "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL";

    /** androidx {@code BUNDLE_KEY_SUBTYPE} 与公钥创建请求子类型（取自已发布 AAR 的字节码常量） */
    private static final String KEY_SUBTYPE = "androidx.credentials.BUNDLE_KEY_SUBTYPE";
    private static final String SUBTYPE_CREATE_PUBLIC_KEY =
            "androidx.credentials.BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST";

    /** androidx {@code BUNDLE_KEY_REQUEST_JSON} */
    private static final String KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON";

    /** androidx {@code BUNDLE_KEY_CLIENT_DATA_HASH} */
    private static final String KEY_CLIENT_DATA_HASH = "androidx.credentials.BUNDLE_KEY_CLIENT_DATA_HASH";

    /** 虚构 clientDataHash 长度（SHA-256 摘要 32 字节；内容为全零，仅用于形状对齐） */
    private static final int CLIENT_DATA_HASH_BYTES = 32;

    /**
     * WebAuthn 注册选项 JSON（虚构测试值）。
     *
     * `rp.id` 取一个**可注册域**：`DomainMatcher.isRpIdTrustedForCreation` 要求
     * 「rp.id 是调用方 origin 的可注册后缀或为有效可注册域」，本探针的调用方 origin 是
     * `android:apk-key-hash:…`（非浏览器），只有走「有效可注册域」这一条才可能放行。
     */
    private static final String PASSKEY_CREATE_REQUEST_JSON = "{"
            + "\"challenge\":\"cm-probe-challenge-bytes\","
            + "\"rp\":{\"name\":\"CM Probe\",\"id\":\"example.com\"},"
            + "\"user\":{\"id\":\"Y20tcHJvYmUtdXNlci1pZA\",\"name\":\"cm-probe-user\","
            + "\"displayName\":\"CM Probe User\"},"
            + "\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7}],"
            + "\"timeout\":60000,"
            + "\"attestation\":\"none\""
            + "}";

    private static final long REQUEST_DELAY_MS = 1200L;

    private TextView statusView;

    /** 本次运行模式（缺省密码保存；[MODE_PASSKEY] 为通行密钥注册） */
    private String mode = "password";

    /** 防止任务栈来回切换导致重复发起（重复发起会让「系统是否自动重发」无法归因） */
    private boolean requested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null) {
            String requested = getIntent().getStringExtra(EXTRA_MODE);
            if (requested != null && !requested.isEmpty()) {
                mode = requested;
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(TITLE + "[" + mode + "]");
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

            CreateCredentialRequest request;
            if (MODE_PASSKEY.equals(mode)) {
                request = buildPasskeyCreateRequest();
            } else if (MODE_PASSKEY_MALFORMED.equals(mode)) {
                request = buildMalformedPasskeyCreateRequest();
            } else {
                request = buildPasswordCreateRequest();
            }

            status("requesting:" + mode);
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

    /**
     * 「载荷残缺」的公钥创建请求：类型正确但载荷**缺 `BUNDLE_KEY_REQUEST_JSON`**
     * （复现 androidx 静默降级为 `BeginCreateCustomCredentialRequest` 的形态）。
     */
    private CreateCredentialRequest buildMalformedPasskeyCreateRequest() {
        Bundle payload = new Bundle();
        payload.putString(KEY_SUBTYPE, SUBTYPE_CREATE_PUBLIC_KEY);
        return new CreateCredentialRequest.Builder(
                TYPE_PUBLIC_KEY_CREDENTIAL, payload, new Bundle(payload)).build();
    }

    /** 传统密码保存请求（类型 `TYPE_PASSWORD_CREDENTIAL` + `{id, password}`） */
    private CreateCredentialRequest buildPasswordCreateRequest() {
        Bundle credentialData = new Bundle();
        credentialData.putString(KEY_ID, TEST_USERNAME);
        credentialData.putString(KEY_PASSWORD, TEST_PASSWORD);
        return new CreateCredentialRequest.Builder(
                TYPE_PASSWORD_CREDENTIAL, credentialData, new Bundle()).build();
    }

    /**
     * 通行密钥注册请求。
     *
     * 形状逐字对齐 androidx `CreatePublicKeyCredentialRequest` 的
     * `toCredentialDataBundle$credentials` **与** `toCandidateDataBundle$credentials`
     * （2026-09-21 经 `javap -c` 核对已发布的 `credentials:1.6.0` AAR）：
     * 两者内容**完全相同**，均为
     * `{BUNDLE_KEY_SUBTYPE = BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST,
     *   BUNDLE_KEY_REQUEST_JSON = <注册选项 JSON>,
     *   BUNDLE_KEY_CLIENT_DATA_HASH = <SHA-256 摘要>}`，
     * 且 `type = TYPE_PUBLIC_KEY_CREDENTIAL`。
     *
     * **为什么必须两处都写**（本探针首版只写了 `credentialData`，实测踩坑）：
     * provider 侧收到的是**框架的单个 `data` Bundle**，androidx 把它当作
     * `createFrom(type, data, callingAppInfo)` 的第二参数；该参数缺 `BUNDLE_KEY_REQUEST_JSON` 时，
     * `BeginCreatePublicKeyCredentialRequest.createFrom$credentials` 会以
     * `FrameworkClassParsingException` 被兜底成 **`BeginCreateCustomCredentialRequest`**
     * （类型字符串仍是公钥类型），于是落到 provider 的 `else` 分支 ⇒ 空响应。
     */
    private CreateCredentialRequest buildPasskeyCreateRequest() {
        Bundle payload = new Bundle();
        payload.putString(KEY_SUBTYPE, SUBTYPE_CREATE_PUBLIC_KEY);
        payload.putString(KEY_REQUEST_JSON, PASSKEY_CREATE_REQUEST_JSON);
        payload.putByteArray(KEY_CLIENT_DATA_HASH, new byte[CLIENT_DATA_HASH_BYTES]);
        return new CreateCredentialRequest.Builder(
                TYPE_PUBLIC_KEY_CREDENTIAL, payload, new Bundle(payload)).build();
    }

    private void status(String value) {
        Log.i(TAG, STATUS_PREFIX + ":" + value);
        if (statusView != null) {
            statusView.setText(STATUS_PREFIX + ":" + value);
        }
    }
}
