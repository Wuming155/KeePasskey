package com.keepasskey.app.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.passkey.ImportedPasskey
import com.keepasskey.app.passkey.PasskeyCxfNotes
import com.keepasskey.app.passkey.PasskeyCxfSourceShape
import com.keepasskey.app.passkey.PasskeyImportDraft
import com.keepasskey.app.ui.screens.database.OpenExistingVaultDialog
import com.keepasskey.app.ui.screens.vault.PasskeyImportConfirmDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-245` 与 `ENG-20261028-33` 的设备侧实证：**生产对话框窗口**是否真正施加了防截屏
 * （`FLAG_SECURE`）与遮挡触摸过滤（`filterTouchesWhenObscured`）。
 *
 * ## 为什么必须有这一层
 *
 * `SecureDialogFlagPolicyTest` 仅覆盖纯裁决内核与无环境状态转换；宿主 JVM 构造不出真实的
 * Compose 对话框窗口与 `WindowManager` 交互。本用例以真实 `DialogWindowHardeningHostActivity`
 * 挂接真实生产组合件 [PasskeyImportConfirmDialog] 与 [OpenExistingVaultDialog]，
 * 在真实 Window 与 `WindowManager` 上读回 `Window.attributes.flags` 与 `decorView.filterTouchesWhenObscured`。
 *
 * ## 本用例证明什么 / 不证明什么（如实边界，勿外推）
 *
 * - **证明**：
 *   ① **生产组合件挂接与策略矩阵**：
 *      - [PasskeyImportConfirmDialog]（跟随用户防截屏开关）：宿主开/关 × 导入设置开/关 4 组状态下，
 *        对话框窗口的 `FLAG_SECURE` 严格由传入的设置开关决定，宿主开而设置关时不被继承（`SecureOff` 生效），
 *        宿主关而设置开时显式置位（`SecureOn` 生效），且遮挡触摸过滤恒常生效；
 *      - [OpenExistingVaultDialog]（云端库凭据对话框）：无论宿主窗口是否开启防截屏，该窗口均**无条件强制携带 `FLAG_SECURE`**
 *        与遮挡触摸过滤（`SecureOn` 强制施加，不随宿主放宽）；
 *      - 对话框关闭后，该 flag 均随组合离开由包装撤销并清除。
 *   ② **触摸过滤安全契约**：
 *      - 对话框窗口的 `decorView` 均已开启 `filterTouchesWhenObscured == true`；
 *      - 非遮挡事件（正对照）正常通过 `onFilterTouchEventForSecurity` 过滤且触发真实子控件/确认按钮的点击回调；
 *      - 携带 `MotionEvent.FLAG_WINDOW_IS_OBSCURED` 的遮挡态事件被 `onFilterTouchEventForSecurity`
 *        拦截，同位置派发的真实点击绝不交付给目标控件。
 *   ③ **真实外部 UID Overlay 探测、绘制正对照与抑制/点击不交付基建**：
 *      - 接入测试 APK（`com.keepasskey.app.test` 独立 UID/进程）的最小悬浮窗服务 [TestOverlayService]，
 *        运行时动态校验目标包与测试包 UID 不同，利用 shell `appops` 临时授予并在 finally 中恢复原始状态；
 *      - 正对照验证：普通未保护窗口下外部 overlay 确实可绘制，经 `UiAutomation.takeScreenshot()` 像素比对验证品红区域存在（>1000 像素）；
 *      - 保护窗口验证：宿主既有 `setHideOverlayWindows(true)` 策略下，截图品红像素严格为 0，证实系统非系统悬浮窗抑制；
 *      - 事件路由验证：由 `UiAutomation.injectInputEvent` 注入原始无标志触摸，经 OS InputDispatcher 真实路由，
 *        overlay 存在时由于生产 `filterTouchesWhenObscured` 拦截，点击不交付；关闭后点击恢复。
 * - **不证明**：「截屏确实被拦截」——需真机实际截屏像素比对，属手工冒烟项。
 */
@RunWith(AndroidJUnit4::class)
class DialogWindowHardeningDeviceTest {

    // =============================================================================================
    // 第一部分：机制留证（探针 Dialog 行为验证）
    // =============================================================================================

    /**
     * 宿主窗口携带 `FLAG_SECURE` 态下，探针对话框窗口获得遮挡触摸过滤并携带遮罩，关闭后撤销。
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

            assertTrue(
                "对话框窗口的 decorView 必须已开启遮挡触摸过滤（filterTouchesWhenObscured == true）——" +
                    "FLAG_SECURE 只防截屏，不防点击劫持；本次读到 ${decorView.filterTouchesWhenObscured}（ISSUE-P2-245）",
                decorView.filterTouchesWhenObscured
            )

            val flagsAtOpen = dialogWindow.attributes.flags
            assertTrue(
                "对话框窗口必须已携带 FLAG_SECURE——Compose Dialog 是独立窗口；" +
                    "本次读到 flags=0x${Integer.toHexString(flagsAtOpen)}" +
                    "（FLAG_SECURE=0x${Integer.toHexString(FLAG_SECURE)}，宿主窗口已带该 flag）",
                flagsAtOpen and FLAG_SECURE == FLAG_SECURE
            )

            assertTouchFilterBehavior(scenario, decorView)

            scenario.onActivity { DialogWindowProbe.visible = false }
            val flagsAfterClose = awaitFlagsCleared(dialogWindow)
            assertEquals(
                "关闭对话框后 FLAG_SECURE 必须已被清除（clearFlags 生效，不得泄漏到窗口生命周期之外）——" +
                    "本次读到 flags=0x${Integer.toHexString(flagsAfterClose)}",
                0,
                flagsAfterClose and FLAG_SECURE
            )
        }
    }

    /**
     * 宿主窗口未携带 `FLAG_SECURE` 时，探针对话框指定 `SecureOn` 仍强制遮罩。
     */
    @Test
    fun `宿主窗口未携带FLAG_SECURE时对话框仍强制遮罩`() {
        DialogWindowProbe.reset(hostWindowSecure = false)

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            assertTrue(
                "遮挡触摸过滤与宿主窗口的 FLAG_SECURE 无关，必须照样施加到对话框 decorView 上" +
                    "（本次读到 ${decorView.filterTouchesWhenObscured}，ISSUE-P2-245）",
                decorView.filterTouchesWhenObscured
            )

            val flags = dialogWindow.attributes.flags
            val hostFlags = hostWindowFlags(scenario)
            assertTrue(
                "宿主窗口未带 FLAG_SECURE 时，调用点显式指定 SecureOn 的对话框窗口**仍必须**携带该 flag；" +
                    "本次读到 hostFlags=0x${Integer.toHexString(hostFlags)}、" +
                    "dialogFlags=0x${Integer.toHexString(flags)}" +
                    "（FLAG_SECURE=0x${Integer.toHexString(FLAG_SECURE)}）",
                flags and FLAG_SECURE == FLAG_SECURE
            )

            assertTouchFilterBehavior(scenario, decorView)
        }
    }

    /**
     * 对照留证：宿主窗口未带 `FLAG_SECURE` 且策略为 `Inherit` 时，Compose 会清除对话框窗口的 `FLAG_SECURE`。
     */
    @Test
    fun `宿主窗口未携带FLAG_SECURE且策略为Inherit时对话框不遮罩`() {
        DialogWindowProbe.reset(hostWindowSecure = false, secure = false)

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            assertTrue(
                "遮挡触摸过滤由本包装独立施加、与 SecureFlagPolicy 无关，必须照样成立" +
                    "（本次读到 ${decorView.filterTouchesWhenObscured}，ISSUE-P2-245）",
                decorView.filterTouchesWhenObscured
            )

            val flags = dialogWindow.attributes.flags
            val hostFlags = hostWindowFlags(scenario)
            assertEquals(
                "对照留证：宿主窗口未带 FLAG_SECURE 且 DialogProperties 策略为默认 Inherit 时，" +
                    "Compose 会清除对话框窗口的 FLAG_SECURE；证明显式 securePolicy 参数是承重的。" +
                    "本次读到 hostFlags=0x${Integer.toHexString(hostFlags)}、" +
                    "dialogFlags=0x${Integer.toHexString(flags)}",
                0,
                flags and FLAG_SECURE
            )

            assertTouchFilterBehavior(scenario, decorView)
        }
    }

    // =============================================================================================
    // 第二部分：生产组合件 PasskeyImportConfirmDialog 矩阵验证 (ENG-20261028-33)
    // =============================================================================================

    /**
     * 生产 [PasskeyImportConfirmDialog]：宿主带 FLAG_SECURE × 导入设置开启。
     * 对话框窗口必须携带 FLAG_SECURE 与触摸过滤，关闭后清除 FLAG_SECURE。
     */
    @Test
    fun `生产通行密钥导入确认对话框在宿主带遮罩且开关开启时携带遮罩与过滤并在关闭后撤销`() {
        val draft = createSamplePasskeyDraft()
        DialogWindowProbe.reset(
            hostWindowSecure = true,
            customContent = {
                PasskeyImportConfirmDialog(
                    draft = draft,
                    flagSecureEnabled = true,
                    onConfirm = { DialogWindowProbe.clickCount++ },
                    onDismiss = { DialogWindowProbe.clickCount++ }
                )
            }
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("生产导入确认对话框未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("生产导入确认对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            scenario.onActivity { hostActivity ->
                assertTrue(
                    "生产对话框窗口必须为独立窗口，不得冒充宿主 Activity 窗口",
                    dialogWindow !== hostActivity.window
                )
            }

            val flags = dialogWindow.attributes.flags
            assertTrue(
                "导入确认对话框在开关开启时必须携带 FLAG_SECURE（读到 0x${Integer.toHexString(flags)}）",
                flags and FLAG_SECURE == FLAG_SECURE
            )
            assertTrue(
                "导入确认对话框 decorView 必须开启遮挡触摸过滤",
                decorView.filterTouchesWhenObscured
            )

            assertTouchFilterBehavior(scenario, decorView)

            scenario.onActivity { DialogWindowProbe.visible = false }
            val flagsAfterClose = awaitFlagsCleared(dialogWindow)
            assertEquals(
                "关闭生产导入确认对话框后 FLAG_SECURE 必须被清除",
                0,
                flagsAfterClose and FLAG_SECURE
            )
        }
    }

    /**
     * 生产 [PasskeyImportConfirmDialog]：宿主带 FLAG_SECURE × 导入设置关闭。
     * 对话框必须因 `SecureOff` 显式不遮罩（不继承宿主的 FLAG_SECURE），但仍必须保持触摸过滤！
     */
    @Test
    fun `生产通行密钥导入确认对话框在宿主带遮罩但开关关闭时显式不遮罩但保持触摸过滤`() {
        val draft = createSamplePasskeyDraft()
        DialogWindowProbe.reset(
            hostWindowSecure = true,
            customContent = {
                PasskeyImportConfirmDialog(
                    draft = draft,
                    flagSecureEnabled = false,
                    onConfirm = { DialogWindowProbe.clickCount++ },
                    onDismiss = { DialogWindowProbe.clickCount++ }
                )
            }
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("生产导入确认对话框未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("生产导入确认对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            scenario.onActivity { hostActivity ->
                assertTrue(
                    "生产对话框窗口必须为独立窗口，不得冒充宿主 Activity 窗口",
                    dialogWindow !== hostActivity.window
                )
                assertTrue(
                    "宿主窗口此时必须确实携带 FLAG_SECURE，以证成对话框未误继承",
                    hostActivity.window.attributes.flags and FLAG_SECURE == FLAG_SECURE
                )
            }

            val flags = dialogWindow.attributes.flags
            assertEquals(
                "用户防截屏开关关闭时，导入确认对话框必须显式不遮罩（SecureOff 清除宿主 flag，不得继承；读到 0x${Integer.toHexString(flags)}）",
                0,
                flags and FLAG_SECURE
            )
            assertTrue(
                "即便防截屏开关关闭，反点击劫持的遮挡触摸过滤仍必须恒常施加",
                decorView.filterTouchesWhenObscured
            )

            assertTouchFilterBehavior(scenario, decorView)
        }
    }

    /**
     * 生产 [PasskeyImportConfirmDialog]：宿主无 FLAG_SECURE × 导入设置开启。
     * 对话框必须因 `SecureOn` 强制遮罩（不继承宿主的无遮罩状态），且施加触摸过滤。
     */
    @Test
    fun `生产通行密钥导入确认对话框在宿主无遮罩且开关开启时强制遮罩与过滤`() {
        val draft = createSamplePasskeyDraft()
        DialogWindowProbe.reset(
            hostWindowSecure = false,
            customContent = {
                PasskeyImportConfirmDialog(
                    draft = draft,
                    flagSecureEnabled = true,
                    onConfirm = { DialogWindowProbe.clickCount++ },
                    onDismiss = { DialogWindowProbe.clickCount++ }
                )
            }
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("生产导入确认对话框未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("生产导入确认对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            scenario.onActivity { hostActivity ->
                assertTrue(
                    "生产对话框窗口必须为独立窗口",
                    dialogWindow !== hostActivity.window
                )
                assertEquals(
                    "宿主窗口此时未携带 FLAG_SECURE",
                    0,
                    hostActivity.window.attributes.flags and FLAG_SECURE
                )
            }

            val flags = dialogWindow.attributes.flags
            assertTrue(
                "用户防截屏开关开启时，导入确认对话框必须通过 SecureOn 强制携带 FLAG_SECURE（读到 0x${Integer.toHexString(flags)}）",
                flags and FLAG_SECURE == FLAG_SECURE
            )
            assertTrue(
                "导入确认对话框 decorView 必须开启遮挡触摸过滤",
                decorView.filterTouchesWhenObscured
            )

            assertTouchFilterBehavior(scenario, decorView)
        }
    }

    /**
     * 生产 [PasskeyImportConfirmDialog]：宿主无 FLAG_SECURE × 导入设置关闭。
     * 对话框不遮罩，但仍必须保持触摸过滤。
     */
    @Test
    fun `生产通行密钥导入确认对话框在宿主无遮罩且开关关闭时不遮罩但保持触摸过滤`() {
        val draft = createSamplePasskeyDraft()
        DialogWindowProbe.reset(
            hostWindowSecure = false,
            customContent = {
                PasskeyImportConfirmDialog(
                    draft = draft,
                    flagSecureEnabled = false,
                    onConfirm = { DialogWindowProbe.clickCount++ },
                    onDismiss = { DialogWindowProbe.clickCount++ }
                )
            }
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("生产导入确认对话框未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("生产导入确认对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            val flags = dialogWindow.attributes.flags
            assertEquals(
                "宿主无遮罩且开关关闭时，导入确认对话框不得携带 FLAG_SECURE",
                0,
                flags and FLAG_SECURE
            )
            assertTrue(
                "遮挡触摸过滤仍必须恒常施加",
                decorView.filterTouchesWhenObscured
            )

            assertTouchFilterBehavior(scenario, decorView)
        }
    }

    // =============================================================================================
    // 第三部分：生产组合件 OpenExistingVaultDialog 强制策略验证 (ENG-20261028-33)
    // =============================================================================================

    /**
     * 生产 [OpenExistingVaultDialog]（云端库凭据对话框）：宿主带遮罩时强制遮罩与过滤，关闭后撤销。
     */
    @Test
    fun `生产云端凭据对话框在宿主带遮罩时始终强制遮罩与过滤并在关闭后撤销`() {
        DialogWindowProbe.reset(
            hostWindowSecure = true,
            customContent = {
                OpenExistingVaultDialog(
                    onDismiss = { DialogWindowProbe.clickCount++ },
                    onConfirm = { DialogWindowProbe.clickCount++ }
                )
            }
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("生产云凭据对话框未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("生产云凭据对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            scenario.onActivity { hostActivity ->
                assertTrue(
                    "云凭据对话框必须为独立窗口，不得冒充宿主 Activity 窗口",
                    dialogWindow !== hostActivity.window
                )
            }

            val flags = dialogWindow.attributes.flags
            assertTrue(
                "云凭据对话框承载 WebDAV/S3 敏感凭据，必须始终强制携带 FLAG_SECURE（读到 0x${Integer.toHexString(flags)}）",
                flags and FLAG_SECURE == FLAG_SECURE
            )
            assertTrue(
                "云凭据对话框 decorView 必须开启遮挡触摸过滤",
                decorView.filterTouchesWhenObscured
            )

            assertTouchFilterBehavior(scenario, decorView)

            scenario.onActivity { DialogWindowProbe.visible = false }
            val flagsAfterClose = awaitFlagsCleared(dialogWindow)
            assertEquals(
                "关闭云凭据对话框后 FLAG_SECURE 必须被清除",
                0,
                flagsAfterClose and FLAG_SECURE
            )
        }
    }

    /**
     * 生产 [OpenExistingVaultDialog]：宿主无遮罩时仍始终强制遮罩与过滤（不随宿主放宽）。
     */
    @Test
    fun `生产云端凭据对话框在宿主无遮罩时仍始终强制遮罩与过滤`() {
        DialogWindowProbe.reset(
            hostWindowSecure = false,
            customContent = {
                OpenExistingVaultDialog(
                    onDismiss = { DialogWindowProbe.clickCount++ },
                    onConfirm = { DialogWindowProbe.clickCount++ }
                )
            }
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val dialogWindow = awaitProbe("生产云凭据对话框未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("生产云凭据对话框 decorView 未在超时内解析到") {
                DialogWindowProbe.decorView
            }

            scenario.onActivity { hostActivity ->
                assertTrue(
                    "云凭据对话框必须为独立窗口",
                    dialogWindow !== hostActivity.window
                )
                assertEquals(
                    "宿主窗口未携带 FLAG_SECURE",
                    0,
                    hostActivity.window.attributes.flags and FLAG_SECURE
                )
            }

            val flags = dialogWindow.attributes.flags
            assertTrue(
                "宿主无遮罩时，云端凭据对话框仍必须强制携带 FLAG_SECURE（SecureOn 强制保护，不得继承宿主；读到 0x${Integer.toHexString(flags)}）",
                flags and FLAG_SECURE == FLAG_SECURE
            )
            assertTrue(
                "云凭据对话框 decorView 必须开启遮挡触摸过滤",
                decorView.filterTouchesWhenObscured
            )

            assertTouchFilterBehavior(scenario, decorView)
        }
    }

    // =============================================================================================
    // 第四部分：真实外部 UID Overlay 探测、绘制正对照与抑制/点击不交付基建
    // =============================================================================================

    /**
     * 真实独立 UID 悬浮窗正对照验证：
     * 普通未保护窗口下（未开启 hideOverlay），外部 overlay 确实可绘制；
     * 通过 UiAutomation.takeScreenshot() 进行屏幕像素比对，验证品红色覆盖区域真实存在；
     * 并在 finally 中撤销权限并停止服务。
     */
    @Test
    fun `真实独立UID悬浮窗在普通未保护窗口正对照验证可绘制`() {
        DialogWindowProbe.reset(
            hostWindowSecure = false,
            hostHideOverlayWindows = false,
            hostBackgroundColor = ComposeColor.Blue
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()

            val controller = RealExternalOverlayController(instrumentation)
            controller.use {
                val attached = it.showOverlay(
                    color = TestOverlayService.OVERLAY_COLOR_MAGENTA,
                    alpha = 1.0f,
                    height = 400,
                    notTouchable = true
                )
                assertTrue("普通未保护窗口下，独立 UID 悬浮窗必须成功添加到 WindowManager 并完成挂载（正对照）", attached)

                instrumentation.waitForIdleSync()
                SystemClock.sleep(600) // 等待 WindowManager 与 SurfaceFlinger 帧渲染就绪

                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull("屏幕截图必须非空", screenshot)

                // 统计覆盖区域 (y: 200..380, x: 50..(width-50)) 内的品红色像素
                val magentaPixels = countPixelsMatching(
                    screenshot,
                    xRange = 50 until (screenshot.width - 50),
                    yRange = 200 until 380
                ) { pixel ->
                    AndroidColor.red(pixel) > 200 &&
                        AndroidColor.green(pixel) < 60 &&
                        AndroidColor.blue(pixel) > 200
                }

                println("[Overlay绘制正对照] 截图像素统计: 发现品红色像素 $magentaPixels 个 (screenshot ${screenshot.width}x${screenshot.height})")
                assertTrue(
                    "普通未保护窗口下，真实外部 UID overlay 必须在屏幕截图中清晰可见（品红色像素数必须 > 1000，本次读到 $magentaPixels）",
                    magentaPixels > 1000
                )
            }
        }
    }

    /**
     * 真实独立 UID 悬浮窗在宿主开启 setHideOverlayWindows 保护窗口下的抑制验证：
     * 宿主 Activity 模拟生产 MainActivity / BaseCredentialActivity 策略开启 setHideOverlayWindows(true)，
     * 相同 overlay 与背景下，通过 UiAutomation.takeScreenshot() 像素差验证 overlay 确实不再可见（品红像素归零）。
     */
    @Test
    fun `真实独立UID悬浮窗在宿主hideOverlay保护窗口下验证绘制抑制`() {
        DialogWindowProbe.reset(
            hostWindowSecure = false,
            hostHideOverlayWindows = true, // 对齐生产 MainActivity / BaseCredentialActivity 策略
            hostBackgroundColor = ComposeColor.Blue
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()

            val controller = RealExternalOverlayController(instrumentation)
            controller.use {
                val attached = it.showOverlay(
                    color = TestOverlayService.OVERLAY_COLOR_MAGENTA,
                    alpha = 1.0f,
                    height = 400,
                    notTouchable = true
                )
                assertTrue("独立 UID 悬浮窗服务正常启动并挂载", attached)

                instrumentation.waitForIdleSync()
                SystemClock.sleep(600) // 等待硬件合成帧就绪

                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull("屏幕截图必须非空", screenshot)

                // 宿主开启 setHideOverlayWindows(true) 时，非系统 overlay 被 OS 抑制绘制！
                // 相同区域内的品红色像素必须为 0，而应呈现宿主背景纯蓝色
                val magentaPixels = countPixelsMatching(
                    screenshot,
                    xRange = 50 until (screenshot.width - 50),
                    yRange = 200 until 380
                ) { pixel ->
                    AndroidColor.red(pixel) > 200 &&
                        AndroidColor.green(pixel) < 60 &&
                        AndroidColor.blue(pixel) > 200
                }

                val bluePixels = countPixelsMatching(
                    screenshot,
                    xRange = 50 until (screenshot.width - 50),
                    yRange = 200 until 380
                ) { pixel ->
                    AndroidColor.red(pixel) < 50 &&
                        AndroidColor.green(pixel) < 50 &&
                        AndroidColor.blue(pixel) > 50
                }

                println("[Overlay抑制验证] 截图像素统计: 品红色像素=$magentaPixels, 宿主背景蓝色像素=$bluePixels")
                assertEquals(
                    "宿主应用 setHideOverlayWindows(true) 时，真实外部 UID overlay 必须被系统抑制绘制（品红色像素必须为 0，本次读到 $magentaPixels）",
                    0,
                    magentaPixels
                )
                assertTrue(
                    "被抑制区域应展示宿主自身的背景纯蓝色（蓝色像素应 > 1000，本次读到 $bluePixels）",
                    bluePixels > 1000
                )
            }
        }
    }

    /**
     * 真实独立 UID 悬浮窗与平台 InputDispatcher 遮挡事件及触摸过滤验证：
     * 事件由 UiAutomation 注入原始无 OBSCURED 标志的 MotionEvent，经 OS InputDispatcher 真实路由。
     * 当外部 UID 悬浮窗覆盖按钮区域时（FLAG_NOT_TOUCHABLE + alpha 0.5），
     * 验证：
     * 1. 无 overlay 时正对照：点击能够正常交付（clickCount 递增），OS 未置位遮挡标志；
     * 2. overlay 覆盖时：事件路由穿透 overlay，OS InputDispatcher 自动注入遮挡状态标志（通过窗口回调观察记录），
     *    由于 filterTouchesWhenObscured 开启，点击绝不交付目标按钮（clickCount 不增加）；
     * 3. 关闭 overlay 后恢复：点击再次能够正常交付。
     */
    @Test
    fun `真实独立UID悬浮窗与平台InputDispatcher遮挡事件及触摸过滤验证`() {
        DialogWindowProbe.reset(
            hostWindowSecure = false,
            hostHideOverlayWindows = false
        )

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val dialogWindow = awaitProbe("对话框窗口未在超时内解析到") { DialogWindowProbe.window }
            val decorView = awaitProbe("对话框 decorView 未在超时内解析到") { DialogWindowProbe.decorView }

            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)

            // 计算确认按钮在屏幕上的绝对中心坐标
            val loc = IntArray(2)
            decorView.getLocationOnScreen(loc)
            val btnX = loc[0] + decorView.width / 2f
            val btnY = loc[1] + decorView.height / 2f

            // ── 阶段 1：无 overlay 正对照，注入原始无标志触摸，点击必须能够交付 ──
            val initialClicks = DialogWindowProbe.clickCount
            injectScreenTouch(instrumentation, btnX, btnY)
            val clicksAfterFirst = awaitClickCount(initialClicks + 1)
            assertEquals("无 overlay 时原始触摸必须正常交付触发点击回调", initialClicks + 1, clicksAfterFirst)

            val flagsWithoutOverlay = DialogWindowProbe.lastWindowCallbackEventFlags ?: 0
            assertEquals(
                "无 overlay 时 OS 派发事件不得携带 FLAG_WINDOW_IS_OBSCURED (flags=0x${Integer.toHexString(flagsWithoutOverlay)})",
                0,
                flagsWithoutOverlay and MotionEvent.FLAG_WINDOW_IS_OBSCURED
            )

            // ── 阶段 2：启动真实外部 UID overlay（高度覆盖按钮，alpha 0.5，NOT_TOUCHABLE 允许穿透） ──
            val controller = RealExternalOverlayController(instrumentation)
            controller.use {
                val attached = it.showOverlay(
                    color = TestOverlayService.OVERLAY_COLOR_MAGENTA,
                    alpha = 0.5f,
                    height = (btnY + 200).toInt(),
                    notTouchable = true
                )
                assertTrue("外部 UID 悬浮窗必须挂载成功", attached)

                instrumentation.waitForIdleSync()
                SystemClock.sleep(600) // 等待系统 WindowManager / InputDispatcher 识别新窗口

                // 注入同样无任何自填标志的原始触摸事件
                DialogWindowProbe.lastWindowCallbackEventFlags = null
                injectScreenTouch(instrumentation, btnX, btnY)
                SystemClock.sleep(200)

                val osDeliveredFlags = DialogWindowProbe.lastWindowCallbackEventFlags
                println("[InputDispatcher遮挡路由] OS 实际投递到对话框窗口的 ACTION_DOWN flags: 0x${Integer.toHexString(osDeliveredFlags ?: 0)}")

                // 核心安全契约断言：由于生产 filterTouchesWhenObscured 生效，点击绝不得交付给确认按钮
                assertEquals(
                    "真实外部 UID overlay 遮挡状态下，即便事件穿透，生产 filterTouchesWhenObscured 必须丢弃该触摸，点击绝不交付目标按钮",
                    clicksAfterFirst,
                    DialogWindowProbe.clickCount
                )
            }

            // ── 阶段 3：关闭 overlay 后恢复，验证点击重新可交付 ──
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            injectScreenTouch(instrumentation, btnX, btnY)
            val clicksAfterRestore = awaitClickCount(clicksAfterFirst + 1)
            assertEquals("关闭 overlay 后，原始触摸必须恢复正常交付", clicksAfterFirst + 1, clicksAfterRestore)
        }
    }

    // =============================================================================================
    // 辅助工具与测试断言方法
    // =============================================================================================

    /** 统计 Bitmap 中满足条件的像素数 */
    private fun countPixelsMatching(
        bitmap: Bitmap,
        xRange: IntRange,
        yRange: IntRange,
        predicate: (Int) -> Boolean
    ): Int {
        var count = 0
        val safeX = xRange.coerceIn(0 until bitmap.width)
        val safeY = yRange.coerceIn(0 until bitmap.height)
        for (y in safeY) {
            for (x in safeX) {
                if (predicate(bitmap.getPixel(x, y))) {
                    count++
                }
            }
        }
        return count
    }

    private fun IntRange.coerceIn(bounds: IntRange): IntRange {
        val start = maxOf(this.first, bounds.first)
        val end = minOf(this.last, bounds.last)
        return if (start <= end) start..end else IntRange.EMPTY
    }

    /** 构造虚拟样本通行密钥导入草案 */
    private fun createSamplePasskeyDraft(): PasskeyImportDraft {
        val credential = ImportedPasskey(
            relyingPartyId = "example.com",
            credentialId = "test-cred-id-c2FtcGxl",
            userHandle = "test-user-handle",
            userName = "user@example.com",
            userDisplayName = "Test User",
            privateKeyPemChars = charArrayOf('t', 'e', 's', 't'),
            algorithmId = -7,
            prfWithUv = null,
            prfWithoutUv = null
        )
        val notes = PasskeyCxfNotes(
            shape = PasskeyCxfSourceShape.BarePasskeyObject,
            dropped = emptyList(),
            missingDisplayFields = emptyList(),
            additionalPasskeyCount = 0,
            skippedCredentialCount = 0
        )
        return PasskeyImportDraft(credential, notes)
    }

    /** 读宿主 Activity 窗口的 flags（仅供留痕打印与断言） */
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

    /** 构造指定坐标与标志位的 MotionEvent */
    private fun obtainMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        flags: Int
    ): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = 1.0f
                this.size = 1.0f
            }
        )
        return MotionEvent.obtain(
            now,
            now,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            flags
        )
    }

    /** 注入原始屏幕触摸事件（无任何 obscured 标志，经 OS InputDispatcher 真实路由） */
    private fun injectScreenTouch(instrumentation: Instrumentation, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50L, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
            SystemClock.sleep(50)
            instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** 动态寻找对话框内的可交互按钮坐标 */
    private fun findClickableCoordinates(
        instrumentation: Instrumentation,
        decorView: View
    ): Pair<Float, Float> {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val uiAutomation = runCatching { instrumentation.uiAutomation }.getOrNull()
        if (uiAutomation != null) {
            val serviceInfo = uiAutomation.serviceInfo ?: AccessibilityServiceInfo()
            serviceInfo.flags = serviceInfo.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            uiAutomation.serviceInfo = serviceInfo

            val roots = mutableListOf<AccessibilityNodeInfo>()
            uiAutomation.rootInActiveWindow?.let { roots.add(it) }
            for (w in runCatching { uiAutomation.windows }.getOrDefault(emptyList())) {
                w.root?.let { roots.add(it) }
            }
            val rect = Rect()
            val keywords = listOf("Probe Confirm", "导入", "Import", "替换", "Replace", "取消", "Cancel")

            for (root in roots) {
                var foundNode: AccessibilityNodeInfo? = null
                fun search(n: AccessibilityNodeInfo?) {
                    if (n == null || foundNode != null) return
                    val text = n.text?.toString()?.trim() ?: ""
                    for (kw in keywords) {
                        if (text.equals(kw, ignoreCase = true)) {
                            foundNode = n
                            return
                        }
                    }
                    for (i in 0 until n.childCount) {
                        search(n.getChild(i))
                    }
                }
                search(root)
                if (foundNode != null) {
                    foundNode.getBoundsInScreen(rect)
                    val loc = IntArray(2)
                    decorView.getLocationOnScreen(loc)
                    val x = (rect.centerX() - loc[0]).toFloat()
                    val y = (rect.centerY() - loc[1]).toFloat()
                    if (x > 0 && y > 0 && x < decorView.width && y < decorView.height) {
                        return Pair(x, y)
                    }
                }
            }
        }
        return Pair(decorView.width / 2f, decorView.height / 2f)
    }

    /** 向目标 View 派发指定标志的触摸事件序列（DOWN + UP），返回安全过滤判断结果 */
    private fun dispatchTouchSequenceOnView(
        scenario: ActivityScenario<DialogWindowHardeningHostActivity>,
        decorView: View,
        flags: Int,
        coords: Pair<Float, Float>
    ): Boolean {
        var filterResult = false
        scenario.onActivity {
            val (x, y) = coords
            val down = obtainMotionEvent(MotionEvent.ACTION_DOWN, x, y, flags)
            val up = obtainMotionEvent(MotionEvent.ACTION_UP, x, y, flags)
            try {
                filterResult = decorView.onFilterTouchEventForSecurity(down)
                decorView.dispatchTouchEvent(down)
                decorView.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }
        return filterResult
    }

    /**
     * 校验遮挡触摸过滤行为：
     * 1. 保持 onFilterTouchEventForSecurity 安全过滤判据（非遮挡放行，遮挡拦截）；
     * 2. 真实子 View / Compose 确认按钮的点击回调计数：
     *    - 未遮挡真实 DOWN/UP 正对照能够触发点击回调；
     *    - 带 FLAG_WINDOW_IS_OBSCURED 在同位置派发，绝不触发点击回调（点击不交付目标控件）。
     */
    private fun assertTouchFilterBehavior(
        scenario: ActivityScenario<DialogWindowHardeningHostActivity>,
        decorView: View
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val coords = findClickableCoordinates(instrumentation, decorView)
        val initialClicks = DialogWindowProbe.clickCount

        // 1. 正对照：非遮挡正常触摸（flags=0），必须通过安全过滤且真实触发目标控件点击回调
        val filterNormal = dispatchTouchSequenceOnView(scenario, decorView, flags = 0, coords = coords)
        assertTrue(
            "非遮挡触摸事件（flags=0）必须通过 onFilterTouchEventForSecurity 过滤（正对照）",
            filterNormal
        )
        val clicksAfterNormal = awaitClickCount(initialClicks + 1)
        assertEquals(
            "非遮挡真实 DOWN/UP 触摸正对照必须能够触发目标子控件/确认按钮的点击回调",
            initialClicks + 1,
            clicksAfterNormal
        )

        // 2. 负对照：携带 FLAG_WINDOW_IS_OBSCURED 的遮挡态事件，安全过滤必须拦截，且同位置点击绝不交付！
        val filterObscured = dispatchTouchSequenceOnView(
            scenario,
            decorView,
            flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED,
            coords = coords
        )
        assertFalse(
            "携带 FLAG_WINDOW_IS_OBSCURED 的遮挡态事件必须被 onFilterTouchEventForSecurity 拦截（返回 false）",
            filterObscured
        )
        SystemClock.sleep(100)
        assertEquals(
            "携带 FLAG_WINDOW_IS_OBSCURED 的遮挡事件被安全过滤拦截后，同位置的点击回调绝不得被触发（点击未交付目标控件）",
            clicksAfterNormal,
            DialogWindowProbe.clickCount
        )
    }

    private fun awaitClickCount(expected: Int): Int {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (DialogWindowProbe.clickCount < expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return DialogWindowProbe.clickCount
    }

    /**
     * 真实外部独立 UID 悬浮窗（com.keepasskey.app.test）控制器。
     *
     * 负责：
     * 1. 运行时动态校验测试包与被测包 UID 不同（确认独立外部 UID）；
     * 2. 读取当前原始 appops 状态并经 shell appops 授予 SYSTEM_ALERT_WINDOW；
     * 3. 驱动 TestOverlayService 挂载悬浮窗；
     * 4. 在 close()（finally）中停止服务并恢复原始 appops 权限。
     */
    private class RealExternalOverlayController(
        private val instrumentation: Instrumentation
    ) : AutoCloseable {
        val testContext: Context = instrumentation.context
        val targetContext: Context = instrumentation.targetContext
        val testPackageName: String = testContext.packageName
        val targetPackageName: String = targetContext.packageName

        private val originalAppopsMode: String
        private var serviceStarted = false

        init {
            val pm = targetContext.packageManager
            val testUid = pm.getPackageUid(testPackageName, 0)
            val targetUid = pm.getPackageUid(targetPackageName, 0)
            assertNotEquals(
                "测试包 ($testPackageName, uid=$testUid) 与目标包 ($targetPackageName, uid=$targetUid) UID 必须不同",
                targetUid,
                testUid
            )

            // 读取原始 appops 状态
            originalAppopsMode = readAppopsMode(testPackageName)

            // 临时授予 SYSTEM_ALERT_WINDOW
            executeShell("cmd appops set $testPackageName SYSTEM_ALERT_WINDOW allow")
            val modeAfter = readAppopsMode(testPackageName)
            assertEquals(
                "执行 appops set $testPackageName SYSTEM_ALERT_WINDOW allow 后状态必须为 allow",
                "allow",
                modeAfter
            )
        }

        fun showOverlay(
            color: Int = TestOverlayService.OVERLAY_COLOR_MAGENTA,
            alpha: Float = 1.0f,
            height: Int = 400,
            notTouchable: Boolean = true
        ): Boolean {
            val latch = CountDownLatch(1)
            var attached = false
            var canDrawInService = false
            var serviceUid = -1

            val filter = IntentFilter(TestOverlayService.ACTION_STATUS)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    attached = intent?.getBooleanExtra("attached", false) ?: false
                    canDrawInService = intent?.getBooleanExtra("canDrawOverlays", false) ?: false
                    serviceUid = intent?.getIntExtra("uid", -1) ?: -1
                    latch.countDown()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                targetContext.registerReceiver(receiver, filter)
            }

            try {
                val cmd = "am start-foreground-service -a ${TestOverlayService.ACTION_SHOW} " +
                    "-n $testPackageName/com.keepasskey.app.security.TestOverlayService " +
                    "--ei ${TestOverlayService.EXTRA_COLOR} $color " +
                    "--ef ${TestOverlayService.EXTRA_ALPHA} $alpha " +
                    "--ei ${TestOverlayService.EXTRA_HEIGHT} $height " +
                    "--ez ${TestOverlayService.EXTRA_NOT_TOUCHABLE} $notTouchable"
                executeShell(cmd)
                serviceStarted = true

                val received = latch.await(5, TimeUnit.SECONDS)
                assertTrue("TestOverlayService 必须在超时内返回启动并挂载广播", received)
                assertTrue("TestOverlayService 进程中 Settings.canDrawOverlays 必须为 true", canDrawInService)
                val expectedUid = targetContext.packageManager.getPackageUid(testPackageName, 0)
                assertEquals("TestOverlayService 运行 UID 必须为测试包 UID", expectedUid, serviceUid)
                return attached
            } finally {
                targetContext.unregisterReceiver(receiver)
            }
        }

        override fun close() {
            try {
                if (serviceStarted) {
                    val cmd = "am start-foreground-service -a ${TestOverlayService.ACTION_HIDE} " +
                        "-n $testPackageName/com.keepasskey.app.security.TestOverlayService"
                    executeShell(cmd)
                }
            } finally {
                val restoreCmd = if (originalAppopsMode.isNotEmpty() && originalAppopsMode != "default") {
                    "cmd appops set $testPackageName SYSTEM_ALERT_WINDOW $originalAppopsMode"
                } else {
                    "cmd appops set $testPackageName SYSTEM_ALERT_WINDOW default"
                }
                executeShell(restoreCmd)
            }
        }

        private fun readAppopsMode(packageName: String): String {
            val out = executeShell("cmd appops get $packageName SYSTEM_ALERT_WINDOW")
            return when {
                out.contains("allow") -> "allow"
                out.contains("ignore") -> "ignore"
                out.contains("deny") -> "deny"
                else -> "default"
            }
        }

        private fun executeShell(command: String): String {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
    }

    private companion object {
        const val FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE
        const val PROBE_TIMEOUT_MS = 10_000L
        const val CLEAR_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
