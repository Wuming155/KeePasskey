package com.keepasskey.app.security

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import kotlinx.coroutines.CompletableDeferred

/**
 * 设备侧用例 `DialogWindowHardeningDeviceTest`（`ISSUE-P2-245`）的最小宿主。
 *
 * ## 为什么宿主在 `src/debug/` 而不是 `src/androidTest/`
 *
 * `Instrumentation.startActivitySync` **拒绝**启动属于**另一个包/进程**的组件：
 * 若把本 Activity 声明在 `app/src/androidTest/AndroidManifest.xml`（测试 APK 包
 * `com.keepasskey.app.test`），`ActivityScenario.launch` 会在真机上抛
 * `RuntimeException: Intent in process com.keepasskey resolved to different process
 * com.keepasskey.test`——2026-09-21 首轮真机实测即此形态（如实留痕），且用例在**另一进程**里
 * 组合出的对话框窗口，测试进程根本读不到。
 *
 * 故宿主放在 **debug 变体**（`app/src/debug/`，随 debug 清单声明）：它与 instrumented 测试同在
 * 被测应用进程 `com.keepasskey` 内，`ActivityScenario` 可直接启动；`release` 变体**不含**本文件与
 * 该清单声明（本仓 `buildTypes` 只自定义 `release`，debug 默认包含 `src/debug`）⇒ 生产包零改动。
 *
 * ## 它做什么
 *
 * 只做一件事——把 Compose [Dialog] 推到**真实对话框窗口**上。`Dialog` 内部经 `DialogLayout`
 * （`AbstractComposeView` 子类，实现 `DialogWindowProvider`）创建独立窗口，与生产 `AlertDialog`
 * 及四个敏感对话框**同一条窗口创建路径**；本 Activity 不接 Hilt、不含业务状态，避免把建库 / 解锁
 * 等无关链路拉进用例。
 */
class DialogWindowHardeningHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (applicationContext as? com.keepasskey.app.MainApplication)?.autoLockManager?.interactionTracker?.let { tracker ->
            tracker.onWindowAttached = { attachedWindow ->
                if (attachedWindow !== window) {
                    val origCallback = attachedWindow.callback
                    if (origCallback != null) {
                        attachedWindow.callback = object : Window.Callback by origCallback {
                            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    DialogWindowProbe.lastWindowCallbackEventFlags = event.flags
                                }
                                return origCallback.dispatchTouchEvent(event)
                            }
                        }
                    }
                    DialogWindowProbe.captureWindow(attachedWindow)
                }
            }
            tracker.onWindowDetached = { detachedWindow ->
                if (detachedWindow !== window) {
                    DialogWindowProbe.notifyWindowDetached(detachedWindow)
                }
            }
        }
        // 宿主窗口是否携带 FLAG_SECURE：**必须由测试在被启动前设定**
        // （见 `DialogWindowProbe.hostWindowSecureOnCreate` 的为什么）。
        if (DialogWindowProbe.hostWindowSecureOnCreate) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        if (DialogWindowProbe.hostHideOverlayWindows) {
            window.setHideOverlayWindows(true)
        }
        setContent {
            val bg = DialogWindowProbe.hostBackgroundColor
            KeePasskeyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bg ?: androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    if (DialogWindowProbe.visible) {
                        val custom = DialogWindowProbe.customContent
                        if (custom != null) {
                            custom()
                        } else {
                            Dialog(
                                onDismissRequest = {},
                                // 本探针由测试切换策略，用于证明「SecureOn 强制并保持该 flag」这一机制本身。
                                properties = DialogProperties(
                                    securePolicy = if (DialogWindowProbe.secureOn) {
                                        SecureFlagPolicy.SecureOn
                                    } else {
                                        SecureFlagPolicy.Inherit
                                    }
                                )
                            ) {
                                DialogWindowProbe.Content()
                            }
                        }
                    }
                    DialogWindowProbe.HostContent()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (applicationContext as? com.keepasskey.app.MainApplication)?.autoLockManager?.interactionTracker?.let { tracker ->
            tracker.onWindowAttached = null
            tracker.onWindowDetached = null
        }
    }
}

/**
 * 对话框窗口与 `decorView` 的交接点：组合期捕获（`SideEffect`）、测试线程断言
 * （同处被测应用进程，故静态字段即可交接）。
 *
 * 用**生产同一解析口径**（[dialogWindowOrNull]）取窗口——若测试自行另写一套父链查找，
 * 断言的就只是「另一种取窗口方式的结果」，无法证成生产路径的接线。
 */
internal object DialogWindowProbe {

    /** 组合中 `Dialog` 的显隐（由测试经 `ActivityScenario.onActivity` 在主线程切换） */
    var visible by mutableStateOf(true)

    /**
     * 宿主 Activity 窗口是否在 `onCreate` 即携带 `FLAG_SECURE`——**必须在 `ActivityScenario.launch`
     * 之前设定**（`onCreate` 读取它）。
     *
     * 为什么需要这个开关：Compose 的 `Dialog` 默认 `DialogProperties.securePolicy =
     * SecureFlagPolicy.Inherit`，其「继承源」是**调用方（宿主）composition 的窗口**
     * （`AndroidDialog.android.kt:251/263` 取 `LocalView.current` 作 `composeView`，
     * `:675` 以 `composeView.isFlagSecureEnabled()` 即**宿主窗口**的 flag 位求值，
     * `isFlagSecureEnabled()` 实现见 `AndroidPopup.android.kt:1110-1116`）。
     * 故宿主窗口有无该 flag，直接决定对话框窗口最终是否被 Compose 置位 / 清除——
     * 2026-09-21 设备侧实测正是靠这一点揭出 `SecureDialogWindowEffect` 的 `addFlags`
     * 会被 Compose 清除（见 `DialogWindowHardeningDeviceTest` 与 `ISSUE-P2-246`）。
     */
    var hostWindowSecureOnCreate = false

    /**
     * 探针 `Dialog` 的策略是否为 `SecureFlagPolicy.SecureOn`——**必须在 `ActivityScenario.launch`
     * 之前设定**。
     *
     * 生产侧凭据类对话框统一显式指定 `SecureOn`（按 ENG-20261028-33 由各调用点明确策略），
     * 本开关让设备用例既能验证 `SecureOn` 的**保持**效果，也能在同一宿主机上复现
     * `Inherit`（＝调用点漏传）时的缺口，从而证明该策略参数是**承重**的。
     */
    var secureOn = true

    /** 是否在 Dialog 内施加 SecureDialogWindowEffect（false 时仅施加普通对话框接线 TrackDialogInteractionEffect） */
    var useSecureEffect = true

    /** 是否呈现宿主界面的真实文本输入框 */
    var showTextField by mutableStateOf(false)

    /** 宿主 Activity 窗口是否在 onCreate 开启 setHideOverlayWindows(true) */
    var hostHideOverlayWindows = false

    /** 宿主 Activity 背景纯色覆盖（供截图像素对比正负对照使用） */
    var hostBackgroundColor by mutableStateOf<Color?>(null)

    /** 最近一次经真实 Window.Callback 收到的 ACTION_DOWN 事件 flags（由 OS 平台生成设置，非测试自填） */
    var lastWindowCallbackEventFlags: Int? = null

    /** 宿主文本框的当前文本内容 */
    var textInput by mutableStateOf("")

    /** 自定义测试对话框内容（如真实生产对话框 Composable） */
    var customContent by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** 探针及生产对话框内部按钮点击计数器（用于验证非遮挡正对照触发点击，遮挡负对照不交付点击） */
    var clickCount by mutableStateOf(0)

    var focusRequester: FocusRequester? = null

    lateinit var window: CompletableDeferred<Window>
        private set

    lateinit var decorView: CompletableDeferred<View>
        private set

    lateinit var detachedWindow: CompletableDeferred<Window>
        private set

    fun captureWindow(resolved: Window) {
        if (!window.isCompleted) window.complete(resolved)
        if (!decorView.isCompleted) decorView.complete(resolved.decorView)
    }

    fun notifyWindowDetached(resolved: Window) {
        if (!detachedWindow.isCompleted) detachedWindow.complete(resolved)
    }

    /** 每个用例开始前复位（instrumentation 的静态状态跨用例存活） */
    fun reset(
        hostWindowSecure: Boolean,
        secure: Boolean = true,
        useSecureEffect: Boolean = true,
        showTextField: Boolean = false,
        hostHideOverlayWindows: Boolean = false,
        hostBackgroundColor: Color? = null,
        customContent: (@Composable () -> Unit)? = null
    ) {
        visible = !showTextField
        hostWindowSecureOnCreate = hostWindowSecure
        this.hostHideOverlayWindows = hostHideOverlayWindows
        this.hostBackgroundColor = hostBackgroundColor
        this.lastWindowCallbackEventFlags = null
        secureOn = secure
        this.useSecureEffect = useSecureEffect
        this.showTextField = showTextField
        this.customContent = customContent
        textInput = ""
        clickCount = 0
        focusRequester = FocusRequester()
        window = CompletableDeferred()
        decorView = CompletableDeferred()
        detachedWindow = CompletableDeferred()
    }

    @Composable
    fun Content() {
        if (useSecureEffect) {
            SecureDialogWindowEffect()
        } else {
            TrackDialogInteractionEffect()
        }
        val view = LocalView.current
        SideEffect {
            val resolved = view.dialogWindowOrNull() ?: return@SideEffect
            captureWindow(resolved)
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(240.dp, 120.dp)
                    .padding(16.dp)
            ) {
                Button(
                    onClick = { clickCount++ }
                ) {
                    Text("Probe Confirm")
                }
            }
        }
    }

    @Composable
    fun HostContent() {
        if (showTextField) {
            val requester = focusRequester ?: remember { FocusRequester().also { focusRequester = it } }
            BasicTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.focusRequester(requester)
            )
            androidx.compose.runtime.LaunchedEffect(requester) {
                requester.requestFocus()
            }
        }
    }
}
