package com.keepasskey.app.autofill;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ISSUE-P2-73 AC③：设备侧「真实客户端」登录表单（instrumented 用例专用，**不属于产品代码**）。
 *
 * <h2>为什么需要它</h2>
 *
 * 自动填充框架对「自动填充服务自己所在包名」不生效，驱动认证链路必须存在一个**本应用之外**
 * 的调用方。本 Activity 声明在测试 APK 清单中，组件包名即测试 APK 包名
 * （{@code com.keepasskey.test}），与填充服务所在包 {@code com.keepasskey} 不同，
 * 走的是与第三方应用完全相同的框架路径。
 *
 * <h2>为什么必须用 Java（2026-09-16 真机实测结论）</h2>
 *
 * 该 Activity 运行在**测试 APK 自己的进程**里（uid 亦不同于被测应用）。instrumented 测试
 * 代码平时寄生在被测应用进程中、借用其 classpath（含 Kotlin 运行时），而测试 APK 自身
 * **不打包 kotlin-stdlib**——用 Kotlin 写本类会以
 * {@code ClassNotFoundException: kotlin.jvm.internal.Intrinsics} 崩溃（本文件的前身即如此）。
 * 故本类刻意只用 Android 框架 API + Java，保持零 Kotlin 依赖。
 *
 * <h2>为什么自己驱动</h2>
 *
 * 客户端与测试进程不同进程，测试代码无法持有其 View。故本 Activity 自行：
 * ① 聚焦账号框并显式调用 {@link AutofillManager#requestAutofill(View)} 触发真实填充请求；
 * ② 周期性把表单状态写入界面文本与 logcat——测试侧既可读 logcat，也可读取无障碍树。
 *
 * <h2>敏感数据纪律</h2>
 *
 * 用例中的账号/口令均为虚构测试值；输出**只含口令长度与是否非空**，绝不输出口令内容。
 */
public class AutofillClientActivity extends Activity {

    public static final String TAG = "AutofillClientTest";

    /** 界面标题，同时作为测试侧在无障碍树中定位本客户端的锚点 */
    public static final String TITLE = "AC3-自动填充客户端表单";

    /** 状态文本前缀（无障碍树与 logcat 双通道证据锚点） */
    public static final String STATUS_PREFIX = "AC3-STATUS";

    /** 重新触发填充的按钮文本（测试侧必要时可点击） */
    public static final String REQUEST_BUTTON_TEXT = "重新请求自动填充";

    private static final long FIRST_REQUEST_DELAY_MS = 1500L;
    private static final long TICK_INTERVAL_MS = 1000L;
    private static final int TICK_LIMIT = 150;

    private EditText usernameField;
    private EditText passwordField;
    private TextView statusView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tick = 0;

    /**
     * 本实例是否已主动请求过填充。
     *
     * **取证纪律（AC③）**：认证完成/失败后，客户端会因任务栈切换多次 resume。若每次 resume 都
     * 主动 requestAutofill，就会把「框架在认证结果后自行重发 onFillRequest」与「客户端自己又请求了一次」
     * 混为一谈——实测因此无法归因。故本实例只在**首次** resume 时请求一次；
     * 需要再次请求时由测试点击「重新请求自动填充」按钮，使归因始终明确。
     */
    private boolean requestedOnce = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick++;
            String state = describeState();
            statusView.setText(state);
            Log.i(TAG, "客户端状态 #" + tick + " " + state);
            if (tick <= TICK_LIMIT) {
                handler.postDelayed(this, TICK_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int top = (int) (64 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, top, pad, pad);

        TextView title = new TextView(this);
        title.setText(TITLE);
        title.setTextSize(16f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("（本页面由 instrumented 用例拉起，模拟第三方登录表单）");
        hint.setTextSize(12f);
        root.addView(hint);

        usernameField = new EditText(this);
        usernameField.setHint("用户名");
        usernameField.setInputType(InputType.TYPE_CLASS_TEXT);
        usernameField.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        usernameField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        root.addView(usernameField);

        passwordField = new EditText(this);
        passwordField.setHint("密码");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        passwordField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        root.addView(passwordField);

        Button requestButton = new Button(this);
        requestButton.setText(REQUEST_BUTTON_TEXT);
        requestButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestAutofillNow("用户点击按钮");
            }
        });
        root.addView(requestButton);

        statusView = new TextView(this);
        statusView.setText(STATUS_PREFIX);
        statusView.setTextSize(12f);
        root.addView(statusView);

        setContentView(root);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // 等首帧布局完成后再请求（autofillId 需视图已附着到窗口）；每个实例只请求一次，见 requestedOnce
        if (!requestedOnce) {
            requestedOnce = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestAutofillNow("onPostResume-首次");
                }
            }, FIRST_REQUEST_DELAY_MS);
        }
        handler.removeCallbacks(ticker);
        tick = 0;
        handler.postDelayed(ticker, TICK_INTERVAL_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestAutofillNow(String reason) {
        AutofillManager manager = getSystemService(AutofillManager.class);
        if (manager == null) {
            Log.w(TAG, "AutofillManager 不可用，无法请求填充");
            return;
        }
        Log.i(TAG, "requestAutofill(" + reason + ") 调用");
        try {
            manager.requestAutofill(usernameField);
        } catch (Throwable t) {
            Log.w(TAG, "requestAutofill 抛出 " + t.getClass().getSimpleName());
        }
    }

    /** 当前表单状态摘要（口令只出长度） */
    private String describeState() {
        return STATUS_PREFIX
                + " username=[" + usernameField.getText() + "]"
                + " passwordLength=" + passwordField.getText().length()
                + " usernameFilled=" + (usernameField.getText().length() > 0)
                + " passwordFilled=" + (passwordField.getText().length() > 0);
    }
}
