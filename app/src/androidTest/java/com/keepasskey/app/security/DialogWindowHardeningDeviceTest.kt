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
 *   ② 对话框窗口**在宿主窗口带与不带 `FLAG_SECURE` 的两种态下都携带该 flag**（后者是 `ISSUE-P2-246`
 *   的修复面，由调用点 `SecureFlagPolicy.SecureOn` 强制并保持）；③ 关闭对话框后该 flag **确实被清除**。
 * - **不证明**：「截屏确实被拦截」——需在真机上实际截图比对（本仓无该自动化基建），属手工冒烟项。
 * - **不证明**：「遮挡窗口的触摸确实被丢弃」——需要真机上存在真实遮挡窗口以产生带
 *   `MotionEvent.FLAG_WINDOW_IS_OBSCURED` 的事件，属手工冒烟项，本仓无该场景自动化基建。
 * - **不证明**：「对话框窗口的 `FLAG_SECURE` 由 `SecureDialogWindowEffect` 的 `addFlags` 保证」——
 *   §252 实测已证成**不成立**（Compose 的 `SecureFlagPolicy.Inherit` 会按宿主窗口状态清除它）；
 *   §254 的修复是**让调用点显式要求 `SecureOn`**，而不是加强本包装的 `addFlags`。
 * - **不覆盖**：**生产 7 处调用点本身**。本用例的组合里，承载对话框的 `Dialog` 由测试宿主提供
 *   （只有测试自己的 composition 能插入探针去解析对话框窗口），故它验证的是
 *   「本包装 + `DialogProperties(securePolicy = …)` 在**真实对话框窗口**上的行为」，
 *   调用点的接线由宿主静态守卫 `SecureDialogFlagPolicyTest` 覆盖（两条互为补充）。
 */
@RunWith(AndroidJUnit4::class)
class DialogWindowHardeningDeviceTest {

    /**
     * 宿主窗口（等价生产 `MainActivity` 在「会话锁定」或「防截屏开关开启」态）= `FLAG_SECURE`
     * 存在 ⇒ 对话框窗口也应带该 flag（本态下调用点的 `SecureOn` 与 Compose 的 `Inherit` 都会保持它），
     * 关闭后由本包装的 `clearFlags` 清除。
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
     * **`ISSUE-P2-246` 修复的锁定（2026-09-21 §254）**：宿主窗口**未**携带 `FLAG_SECURE`
     * （等价生产「会话已解锁 + 用户在设置页关闭『防截屏』开关」态）时，本类对话框窗口**仍必须**带
     * `FLAG_SECURE`——即**宿主的开关状态不影响本类对话框**（该包装自陈意图的实现：
     * 这四类对话框含主密钥 / 子密钥 / 条目明文差异，一律强制遮罩）。
     *
     * 判据为什么是这一条：Compose `Dialog` 默认 `SecureFlagPolicy.Inherit`，其继承源是**宿主窗口**
     * 的该 flag 位（`AndroidDialog.android.kt` 取调用方 `LocalView` 作 `composeView`，
     * 以 `composeView.isFlagSecureEnabled()` 求值，实现见 `AndroidPopup.android.kt`），
     * 宿主不带时会执行 `setFlags(FLAG_SECURE.inv(), FLAG_SECURE)` **清除**本窗的 flag——
     * 这正是 §252 实测到的缺口（当时读数 `hostFlags=0x81810100, dialogFlags=0x1800002`）。
     * §254 的修复＝各调用点传 `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`
     * （Compose 官方 API，强制并**保持**该 flag），故本用例在本态下反而**必须**读到该 flag
     * ——**它判的是「修复」，不是「缺口」**。
     *
     * 本用例同时**正面锁定** `ISSUE-P2-245` 的成果：**过滤**与该 Compose 策略无关，
     * 在宿主窗口无 flag 时**仍然被施加**（同一个 `DisposableEffect` 内、同一 `decorView`）。
     */
    @Test
    fun `宿主窗口未携带FLAG_SECURE时对话框仍强制遮罩`() {
        DialogWindowProbe.reset(hostWindowSecure = false)

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            // 正面锁定 ISSUE-P2-245 成果：遮挡触摸过滤不依赖宿主窗口状态，照样已施加
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
            assertTrue(
                "宿主窗口未带 FLAG_SECURE 时，本类对话框窗口**仍必须**携带该 flag（ISSUE-P2-246，" +
                    "2026-09-21 §254 修复）：三个含主密钥 / 子密钥 / 条目明文差异的对话框不随用户的" +
                    "「防截屏」开关解除遮罩，故调用点已传 " +
                    "DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)；" +
                    "本次读到 hostFlags=0x${Integer.toHexString(hostFlags)}、" +
                    "dialogFlags=0x${Integer.toHexString(flags)}" +
                    "（FLAG_SECURE=0x${Integer.toHexString(FLAG_SECURE)}）" +
                    "——若此处又读不到该位，说明某调用点的 SecureOn 被移除（宿主侧守卫 " +
                    "`SecureDialogFlagPolicyTest` 应同时报红），不得放宽本断言",
                flags and FLAG_SECURE == FLAG_SECURE
            )
        }
    }

    /**
     * **机制留证（Compose 行为，非本仓缺陷）**：同一宿主（窗口**不带** `FLAG_SECURE`）下，
     * 若探针 `Dialog` 的策略保持默认 `Inherit`（＝等价于「某调用点漏传 `SecureOn`」），
     * 对话框窗口**读不到** `FLAG_SECURE`——这正是 §252 实测到的缺口形态（`ISSUE-P2-246`）。
     *
     * 为什么要有这一条：上一条用例断言「`SecureOn` 下必须带 flag」，若该断言因某种原因恒真
     * （例如平台/版本变化后 `Inherit` 也会保留 flag），它就失去了判别力。本条以**同一宿主、
     * 仅改策略参数**作对照，证明上一条**真的在判策略**（两组读数只差 `securePolicy`），
     * 也即证明生产 7 处调用点的 `SecureOn` 是**承重**的。
     */
    @Test
    fun `宿主窗口未携带FLAG_SECURE且策略为Inherit时对话框不遮罩`() {
        DialogWindowProbe.reset(hostWindowSecure = false, secure = false)

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            // 过滤与本策略无关：即便 Inherit 清掉了 flag，遮挡触摸过滤照样已施加（ISSUE-P2-245）
            assertTrue(
                "遮挡触摸过滤由本包装独立施加、与 SecureFlagPolicy 无关，必须照样成立" +
                    "（本次读到 ${decorView.filterTouchesWhenObscured}，ISSUE-P2-245）",
                decorView.filterTouchesWhenObscured
            )

            val flags = dialogWindow.attributes.flags
            val hostFlags = hostWindowFlags(scenario)
            println(
                "[对话框窗口加固设备侧实测·宿主无 FLAG_SECURE + 策略 Inherit] " +
                    "hostFlags=0x${Integer.toHexString(hostFlags)}, " +
                    "dialogFlags=0x${Integer.toHexString(flags)}, " +
                    "filterTouchesWhenObscured=true"
            )
            assertEquals(
                "对照留证：宿主窗口未带 FLAG_SECURE 且 DialogProperties 策略为默认 Inherit 时，" +
                    "Compose 会清除对话框窗口的 FLAG_SECURE（ISSUE-P2-246 的缺口形态，2026-09-21 §252 实测）；" +
                    "本条锁定的是**框架行为**（本仓未改），用于证明上一条用例真的在判 securePolicy 参数" +
                    "——即生产调用点的 SecureOn 是承重的。本次读到 " +
                    "hostFlags=0x${Integer.toHexString(hostFlags)}、" +
                    "dialogFlags=0x${Integer.toHexString(flags)}",
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
