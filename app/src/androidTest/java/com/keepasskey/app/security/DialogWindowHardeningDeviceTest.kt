package com.keepasskey.app.security

import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-245` 的设备侧实证：**对话框窗口**是否真的被施加了防截屏（`FLAG_SECURE`）
 * 与遮挡触摸过滤（`filterTouchesWhenObscured`）。
 *
 * ## 为什么必须有这一层
 *
 * `SecureDialogFlagPolicyTest` 只覆盖纯裁决内核与其静态接线，其 KDoc 自陈「真实
 * `addFlags` / `clearFlags` 对对话框窗口的生效……需要 window token 与真实 WindowManager 服务」；
 * `ObscuredTouchWiringTest` 同理（宿主 JVM 构造不出 Compose 对话框窗口）。本用例用真实
 * `Dialog` 窗口 + 真实 `WindowManager` 读回 `decorView.filterTouchesWhenObscured` 与
 * `Window.attributes.flags`。
 *
 * ## 本用例证明什么 / 不证明什么（如实边界，勿外推）
 *
 * - **证明**：① 遮挡触摸过滤**真的施加到了对话框窗口的 `decorView`**（这是 `ISSUE-P2-245` 的接线结果）；
 *   ② 该对话框窗口在宿主窗口携带 `FLAG_SECURE` 时**确实带该 flag**、关闭后**确实被清除**。
 * - **不证明**：「遮挡窗口的触摸确实被丢弃」——需要真机上存在真实遮挡窗口以产生带
 *   `MotionEvent.FLAG_WINDOW_IS_OBSCURED` 的事件，属手工冒烟项，本仓无该场景自动化基建。
 * - **不证明**：「对话框窗口的 `FLAG_SECURE` 由 `SecureDialogWindowEffect` 的 `addFlags` 保证」——
 *   下一条用例（`宿主窗口未携带FLAG_SECURE时对话框遮罩被Compose清除`）实测表明**不成立**：
 *   Compose 的 `Dialog` 默认 `SecureFlagPolicy.Inherit`，以**宿主窗口**的 flag 位为准，
 *   在宿主窗口无该 flag 时会 `clearFlags` 掉本包装刚施加的那一次。该缺口归 `ISSUE-P2-246`
 *   （2026-09-21 §252 设备侧揭出），**本批不改** FLAG_SECURE 语义。
 */
@RunWith(AndroidJUnit4::class)
class DialogWindowHardeningDeviceTest {

    /**
     * 宿主窗口（等价生产 `MainActivity` 在「会话锁定」或「防截屏开关开启」态）= `FLAG_SECURE`
     * 存在 ⇒ 对话框窗口也应带该 flag（由 Compose 的 `Inherit` 保持），关闭后由本包装清除。
     */
    @Test
    fun `对话框窗口获得遮挡触摸过滤并携带遮罩且关闭后撤销遮罩`() {
        DialogWindowProbe.reset(hostWindowSecure = true)

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到（Dialog 是否真的被组合出来？）") {
                DialogWindowProbe.window
            }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            // ── 断言 1：遮挡触摸过滤已施加到对话框窗口的 decorView ──────────────────
            assertTrue(
                "对话框窗口的 decorView 必须已开启遮挡触摸过滤（filterTouchesWhenObscured == true）——" +
                    "FLAG_SECURE 只防截屏，不防点击劫持；两者是同一形态的窗口级缺口，" +
                    "本次读到 ${decorView.filterTouchesWhenObscured}（ISSUE-P2-245）",
                decorView.filterTouchesWhenObscured
            )

            // ── 断言 2：对话框窗口的 FLAG_SECURE 位已置 ────────────────────────────
            val flagsAtOpen = dialogWindow.attributes.flags
            assertTrue(
                "对话框窗口必须已携带 FLAG_SECURE——Compose Dialog 是独立窗口，不继承 Activity 窗口的 flag；" +
                    "本次读到 flags=0x${Integer.toHexString(flagsAtOpen)}" +
                    "（FLAG_SECURE=0x${Integer.toHexString(FLAG_SECURE)}，宿主窗口已带该 flag）",
                flagsAtOpen and FLAG_SECURE == FLAG_SECURE
            )

            println(
                "[对话框窗口加固设备侧实测·宿主窗口带 FLAG_SECURE] filterTouchesWhenObscured=true, " +
                    "flags=0x${Integer.toHexString(flagsAtOpen)}, decorView=${decorView.javaClass.name}"
            )

            // ── 断言 3：关闭对话框后 FLAG_SECURE 已清（clearFlags 生效） ────────────
            scenario.onActivity { DialogWindowProbe.visible = false }
            val flagsAfterClose = awaitFlagsCleared(dialogWindow)
            assertEquals(
                "关闭对话框后 FLAG_SECURE 必须已被清除（clearFlags 生效，不得泄漏到窗口生命周期之外）——" +
                    "本次读到 flags=0x${Integer.toHexString(flagsAfterClose)}",
                0,
                flagsAfterClose and FLAG_SECURE
            )
            println(
                "[对话框窗口加固设备侧实测] 关闭后 flags=0x${Integer.toHexString(flagsAfterClose)}" +
                    "（FLAG_SECURE 已清）"
            )
        }
    }

    /**
     * **`ISSUE-P2-246`（2026-09-21 §252 设备侧揭出）的锁定与证据**：宿主窗口**未**携带
     * `FLAG_SECURE`（等价生产「会话已解锁 + 用户在设置页关闭防截屏开关」态）时，
     * `SecureDialogWindowEffect` 的 `addFlags(FLAG_SECURE)` **会被 Compose 清除**——
     * Compose `Dialog` 默认 `SecureFlagPolicy.Inherit`，以宿主窗口的 flag 位为准
     * （`AndroidDialog.android.kt` :675 `composeView.isFlagSecureEnabled()`，`composeView` 取的是
     * **调用方** composition 的 `LocalView`，见 `:251/263`），宿主窗口无该 flag 时执行
     * `window.setFlags(FLAG_SECURE.inv(), FLAG_SECURE)` 即**清除**。
     *
     * ⇒ `SecureDialog.kt` KDoc 自陈的「无条件施加（对 `flagSecureEnabled` 开关的**有意 fail-closed 偏离**）」
     * **在宿主窗口无该 flag 时不成立**，须改判 KDoc 或改在各对话框调用点传
     * `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`。**本批不改** FLAG_SECURE 语义
     * （上级约束「既有施加/撤销语义一行不得改」），缺口如实登记为 `ISSUE-P2-246`。
     *
     * 本用例同时**正面锁定** `ISSUE-P2-245` 的成果：**过滤**与该 COMPOSE 策略无关，
     * 在宿主窗口无 flag 时**仍然被施加**（同一个 `DisposableEffect` 内、同一 `decorView`）。
     */
    @Test
    fun `宿主窗口未携带FLAG_SECURE时对话框遮罩被Compose清除`() {
        DialogWindowProbe.reset(hostWindowSecure = false)

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            // 正面锁定本批成果：遮挡触摸过滤不依赖宿主窗口状态，照样已施加
            assertTrue(
                "遮挡触摸过滤与宿主窗口的 FLAG_SECURE 无关，必须照样施加到对话框 decorView 上" +
                    "（本次读到 ${decorView.filterTouchesWhenObscured}，ISSUE-P2-245）",
                decorView.filterTouchesWhenObscured
            )

            val flags = dialogWindow.attributes.flags
            val hostFlags = hostWindowFlags(scenario)
            println(
                "[对话框窗口加固设备侧实测·宿主窗口无 FLAG_SECURE] " +
                    "hostFlags=0x${Integer.toHexString(hostFlags)}, " +
                    "dialogFlags=0x${Integer.toHexString(flags)}, " +
                    "filterTouchesWhenObscured=true"
            )
            assertEquals(
                "既有缺口锁定（ISSUE-P2-246，2026-09-21 §252 设备侧实测）：宿主窗口未带 FLAG_SECURE 时，" +
                    "Compose 的 SecureFlagPolicy.Inherit 会清除 SecureDialogWindowEffect 刚施加的 " +
                    "FLAG_SECURE ⇒ 对话框窗口实测**不带**该 flag。修掉该缺口（如在调用点传 " +
                    "SecurePolicy.SecureOn）后本断言即红，届时须同步更新 ISSUE-P2-246 与本断言。”",
                0,
                flags and FLAG_SECURE
            )
        }
    }

    /** 读宿主 Activity 窗口的 flags（仅供留痕打印） */
    private fun hostWindowFlags(scenario: ActivityScenario<DialogWindowHardeningHostActivity>): Int {
        var flags = 0
        scenario.onActivity { flags = it.window.attributes.flags }
        return flags
    }

    /** 等待组合期捕获的句柄（[DialogWindowProbe.window] / [DialogWindowProbe.decorView]） */
    private fun <T> awaitProbe(message: String, handle: () -> CompletableDeferred<T>): T = runBlocking {
        try {
            withTimeout(PROBE_TIMEOUT_MS) { handle().await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(message, timeout)
        }
    }

    /** 轮询等待 `FLAG_SECURE` 位被清（撤销发生在下一帧的组合处置中，非 `onActivity` 返回即完成） */
    private fun awaitFlagsCleared(window: Window): Int {
        val deadline = SystemClock.uptimeMillis() + CLEAR_TIMEOUT_MS
        var flags = window.attributes.flags
        while (flags and FLAG_SECURE != 0 && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            flags = window.attributes.flags
        }
        return flags
    }

    private companion object {
        const val FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE
        const val PROBE_TIMEOUT_MS = 10_000L
        const val CLEAR_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
