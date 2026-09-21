package com.keepasskey.app.security

import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
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
        // 宿主窗口是否携带 FLAG_SECURE：**必须由测试在被启动前设定**
        // （见 `DialogWindowProbe.hostWindowSecureOnCreate` 的为什么）。
        if (DialogWindowProbe.hostWindowSecureOnCreate) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContent {
            if (DialogWindowProbe.visible) {
                Dialog(onDismissRequest = {}) {
                    DialogWindowProbe.Content()
                }
            }
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

    lateinit var window: CompletableDeferred<Window>
        private set

    lateinit var decorView: CompletableDeferred<View>
        private set

    /** 每个用例开始前复位（instrumentation 的静态状态跨用例存活） */
    fun reset(hostWindowSecure: Boolean) {
        visible = true
        hostWindowSecureOnCreate = hostWindowSecure
        window = CompletableDeferred()
        decorView = CompletableDeferred()
    }

    @Composable
    fun Content() {
        SecureDialogWindowEffect()
        val view = LocalView.current
        SideEffect {
            val resolved = view.dialogWindowOrNull() ?: return@SideEffect
            window.complete(resolved)
            decorView.complete(resolved.decorView)
        }
    }
}
