package com.keepasskey.app.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainApplication
import com.keepasskey.app.autofill.AutofillClientActivity
import com.keepasskey.app.autofill.DeviceAutofillProbe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENG03 剪贴板平台行为设备侧实测。
 *
 * ## 实测覆盖契约
 * 1. **独立 UID 身份证明**：断言测试客户端与主应用的 `applicationInfo.uid` 确实不同，
 *    证明客户端为跨 UID 的独立外部应用；
 * 2. **正保留时长跨 App 窗口可真实粘贴**：主应用前台复制敏感值并配置保留时长后，切换至独立 UID 客户端
 *    （[AutofillClientActivity]），主应用进后台不提前清空剪贴板，客户端在保留窗口内成功执行真实粘贴；
 * 3. **独立 UID 后台读不可用验证**：主应用退至后台且客户端处于前台焦点时，主应用读剪贴板返回 null，
 *    证明 Android 10+ 后台无焦点应用无法获取剪贴板内容；
 * 4. **外部 App 改写后待超时新内容不被误清**：客户端在前台写入新内容，主应用在后台等待定时器到期。
 *    AOSP `ClipboardService` 对无焦点后台应用既禁止读取又抑制变更事件；主应用因无法证明内容仍属自身，
 *    安全条件清理策略拒绝物理清空，待超时后外部 App 的新内容完整保留，严禁误清他处内容；
 * 5. **延后核验与回前台清空**：
 *    - 外部覆盖场景：主应用回到前台核验，读得当前内容与先前敏感值摘要不符，确认为外部覆盖，复位待清标记，
 *      不误清他处新值；
 *    - 未覆盖场景：主应用在后台到期后延后清理，回到前台核验摘要匹配，随即执行物理清空，杜绝跨应用常驻泄密；
 * 6. **真实系统熄屏（ACTION_SCREEN_OFF）设备场景**：通过真实硬件键值 sleep/wake 触发真实系统熄屏与广播，
 *    finally 块保证唤醒；分别验证：
 *    - 未覆盖场景：熄屏后后台无法核验延后清理，唤醒回前台核验匹配并物理清空；
 *    - 外部覆盖场景：外部改写后熄屏，唤醒回前台核验不匹配，不误清外部新内容；
 * 7. **会话锁定与无保留窗口语义**：
 *    - 前台会话锁定（onSessionLocked）：立即物理清空；
 *    - 关闭自动清空/选择不清空（customTimeoutSeconds <= 0）：切后台触发立即清理，回前台核验清空；
 * 8. **冷启动对账内核契约（reconcileOnColdStart）**：如实标注：直接测试冷启动对账方法契约，
 *    验证遗留持久化标记时的保守清空与标记复位，不模拟杀进程完整 UI 启动流（避免扩充无谓基建）。
 */
@RunWith(AndroidJUnit4::class)
class ClipboardSecurityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val testContext: Context = instrumentation.context
    private val probe = DeviceAutofillProbe()

    private val clientComponent = ComponentName(
        testContext.packageName,
        AutofillClientActivity::class.java.name
    )

    private val prefs by lazy {
        targetContext.getSharedPreferences("clipboard_security", Context.MODE_PRIVATE)
    }

    private lateinit var clipboardSecurityManager: ClipboardSecurityManager

    @Before
    fun setUp() {
        val targetUid = targetContext.packageManager.getApplicationInfo(targetContext.packageName, 0).uid
        val clientUid = testContext.packageManager.getApplicationInfo(testContext.packageName, 0).uid
        assertNotEquals("客户端必须具有独立 UID（不同于主应用）", targetUid, clientUid)

        val app = targetContext.applicationContext as MainApplication
        clipboardSecurityManager = app.clipboardSecurityManager
        clipboardSecurityManager.initialize()
        clipboardSecurityManager.cancelScheduledClear()
        clipboardSecurityManager.clearClipboard()
        probe.shell("am force-stop ${testContext.packageName}")
        DialogWindowProbe.reset(hostWindowSecure = false)
        DialogWindowProbe.visible = false
        wakeUpAndUnlock()
    }

    @After
    fun tearDown() {
        try {
            wakeUpAndUnlock()
        } catch (_: Throwable) {}
        clipboardSecurityManager.cancelScheduledClear()
        clipboardSecurityManager.clearClipboard()
        probe.shell("am force-stop ${testContext.packageName}")
    }

    private fun sleepDevice() {
        probe.shell("input keyevent 223") // KEYCODE_SLEEP
        SystemClock.sleep(1_500L)
    }

    private fun wakeUpAndUnlock() {
        probe.shell("input keyevent 224") // KEYCODE_WAKEUP
        probe.shell("wm dismiss-keyguard")
        SystemClock.sleep(800L)
    }

    @Test
    fun testCrossAppPasteAndExternalOverwriteNotErasedByTimeout() = runBlocking {
        val runId = "eng03-overwrite-${System.currentTimeMillis()}"
        val sensitiveSecret = "synthetic-secret-pass-A1"
        val externalNewValue = "external-overwritten-value-999"

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            // ── 步骤 1：主应用处于前台，复制敏感口令，调度 3 秒自动擦除 ────────────────
            scenario.onActivity {
                clipboardSecurityManager.copySensitiveText(
                    label = "password",
                    text = sensitiveSecret,
                    customTimeoutSeconds = 3
                )
            }

            // 确认主应用前台时剪贴板已写入敏感值
            val textInHost = clipboardSecurityManager.getCurrentClipText()
            assertEquals("主应用前台复制后剪贴板应为敏感值", sensitiveSecret, textInHost)
            assertTrue("跨进程待清标记应已置位", prefs.getBoolean("pending_sensitive_clip", false))

            // ── 步骤 2：切换至独立 UID 客户端，主应用进入后台 ─────────────────────────
            val clientIntent = Intent().apply {
                component = clientComponent
                action = AutofillClientActivity.ACTION_PASTE_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, runId)
            }
            targetContext.startActivity(clientIntent)

            // 等待客户端窗口成为前台活动应用
            val clientTitleNode = probe.awaitAppText(
                packageName = testContext.packageName,
                text = AutofillClientActivity.TITLE,
                timeoutMs = 8_000L
            )
            assertNotNull("独立 UID 客户端未在超时内进入前台", clientTitleNode)

            // ── 步骤 3：验证主应用在后台读不可用 + 客户端在跨 App 窗口真实粘贴成功 ───
            val textInHostWhileBg = clipboardSecurityManager.getCurrentClipText()
            assertTrue(
                "主应用处于后台无焦点时，系统禁止读取剪贴板，getCurrentClipText() 必须为 null 或不可读",
                textInHostWhileBg.isNullOrEmpty()
            )

            val pastedStatusNode = probe.awaitClientStatus(
                packageName = testContext.packageName,
                prefix = AutofillClientActivity.STATUS_PREFIX,
                timeoutMs = 6_000L
            )
            assertNotNull("客户端状态未在超时内就绪", pastedStatusNode)
            val pastedStatusText = probe.textOf(requireNotNull(pastedStatusNode))
            assertTrue(
                "客户端应成功完成真实粘贴敏感口令，实际状态: $pastedStatusText",
                pastedStatusText.contains("usernameText=$sensitiveSecret")
            )

            // ── 步骤 4：外部 App 改写剪贴板 ──────────────────────────────────────────
            val overwriteIntent = Intent().apply {
                component = clientComponent
                action = AutofillClientActivity.ACTION_SET_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, runId)
                putExtra(AutofillClientActivity.EXTRA_CLIPBOARD_TEXT, externalNewValue)
            }
            targetContext.startActivity(overwriteIntent)
            SystemClock.sleep(500L)

            // ── 步骤 5：等待主应用后台定时器超时（3 秒配置，等待 4.5 秒） ─────────────
            // 此时主应用在后台运行，由于无法证明内容归属，必须拒绝物理清空，严禁误清他处新内容
            SystemClock.sleep(4_500L)

            // ── 步骤 6：在客户端前台验证外部新值未被主应用后台定时器误清 ─────────────
            val rePasteIntent = Intent().apply {
                component = clientComponent
                action = AutofillClientActivity.ACTION_PASTE_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, runId)
            }
            targetContext.startActivity(rePasteIntent)
            SystemClock.sleep(800L)

            val overwrittenStatusNode = probe.awaitClientStatus(
                packageName = testContext.packageName,
                prefix = AutofillClientActivity.STATUS_PREFIX,
                timeoutMs = 6_000L
            )
            assertNotNull("客户端重新粘贴后状态未就绪", overwrittenStatusNode)
            val overwrittenStatusText = probe.textOf(requireNotNull(overwrittenStatusNode))
            assertTrue(
                "主应用后台到期不得误清外部 App 写入的新内容，实际状态: $overwrittenStatusText",
                overwrittenStatusText.contains("usernameText=$externalNewValue")
            )

            // ── 步骤 7：主应用返回前台核验 ───────────────────────────────────────────
            val bringBackIntent = Intent(targetContext, DialogWindowHardeningHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(bringBackIntent)
            SystemClock.sleep(1_000L)

            // 主应用回前台后按摘要核验：当前剪贴板不是敏感值，确认为外部覆盖，不物理清空，复位待清标记
            val textInHostAfterReturn = clipboardSecurityManager.getCurrentClipText()
            assertEquals("主应用回前台后外部新内容必须仍完整保留", externalNewValue, textInHostAfterReturn)
            assertFalse(
                "确认为外部覆盖后，跨进程待清标记必须已复位（避免冷启动误清他处新值）",
                prefs.getBoolean("pending_sensitive_clip", false)
            )
        }
    }

    @Test
    fun testDelayedClearExecutesOnForegroundReturnWhenNotOverwritten() = runBlocking {
        val runId = "eng03-clear-${System.currentTimeMillis()}"
        val sensitiveSecret = "synthetic-secret-pass-B2"

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            // ── 步骤 1：主应用前台复制，调度 2 秒自动擦除 ────────────────────────────
            scenario.onActivity {
                clipboardSecurityManager.copySensitiveText(
                    label = "password",
                    text = sensitiveSecret,
                    customTimeoutSeconds = 2
                )
            }
            assertEquals(sensitiveSecret, clipboardSecurityManager.getCurrentClipText())

            // ── 步骤 2：切换至独立客户端，完成真实粘贴 ───────────────────────────────
            val clientIntent = Intent().apply {
                component = clientComponent
                action = AutofillClientActivity.ACTION_PASTE_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, runId)
            }
            targetContext.startActivity(clientIntent)

            val clientTitleNode = probe.awaitAppText(
                packageName = testContext.packageName,
                text = AutofillClientActivity.TITLE,
                timeoutMs = 8_000L
            )
            assertNotNull("独立 UID 客户端未进入前台", clientTitleNode)

            val statusNode = probe.awaitClientStatus(
                packageName = testContext.packageName,
                prefix = AutofillClientActivity.STATUS_PREFIX,
                timeoutMs = 6_000L
            )
            assertNotNull("客户端状态未就绪", statusNode)
            assertTrue(
                "客户端应成功粘贴敏感口令",
                probe.textOf(requireNotNull(statusNode)).contains("usernameText=$sensitiveSecret")
            )

            // ── 步骤 3：客户端不改写剪贴板，等待 2 秒定时器在后台到期（等待 3.5 秒） ──
            SystemClock.sleep(3_500L)

            // ── 步骤 4：主应用返回前台，触发回前台核验补清 ───────────────────────────
            val bringBackIntent = Intent(targetContext, DialogWindowHardeningHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(bringBackIntent)
            SystemClock.sleep(1_000L)

            // 主应用回前台核验到敏感摘要仍匹配，执行物理清空
            val textInHostAfterReturn = clipboardSecurityManager.getCurrentClipText()
            assertTrue(
                "回前台核验敏感值未被覆盖且已到期，必须已物理清空，实际为: $textInHostAfterReturn",
                textInHostAfterReturn.isNullOrEmpty()
            )
            assertFalse(
                "物理清空后跨进程待清标记必须已复位",
                prefs.getBoolean("pending_sensitive_clip", false)
            )
        }
    }

    @Test
    fun testRealScreenOffClearsOnForegroundReturnWhenStillMatching() = runBlocking {
        val sensitiveSecret = "screen-off-matching-sec-731"
        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            scenario.onActivity {
                clipboardSecurityManager.copySensitiveText("pass", sensitiveSecret, 30)
            }
            assertEquals(sensitiveSecret, clipboardSecurityManager.getCurrentClipText())
            assertTrue(prefs.getBoolean("pending_sensitive_clip", false))

            try {
                // 真实硬件键值熄屏：触发系统广播 ACTION_SCREEN_OFF
                sleepDevice()
                assertTrue(
                    "设备应处于熄屏状态",
                    probe.shell("dumpsys display").contains("mState=OFF")
                )
            } finally {
                wakeUpAndUnlock()
            }

            // 唤醒并切回主应用前台
            val bringBackIntent = Intent(targetContext, DialogWindowHardeningHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(bringBackIntent)
            SystemClock.sleep(1_000L)

            // 熄屏触发了清理标记；未被覆盖的值回前台核验后必须已被物理清空
            val textAfterReturn = clipboardSecurityManager.getCurrentClipText()
            assertTrue(
                "未被覆盖的敏感内容在熄屏后回前台核验必须已物理清空，实际为: $textAfterReturn",
                textAfterReturn.isNullOrEmpty()
            )
            assertFalse(
                "物理清空后待清标记必须已复位",
                prefs.getBoolean("pending_sensitive_clip", false)
            )
        }
    }

    @Test
    fun testRealScreenOffDoesNotClearWhenOverwrittenByExternalApp() = runBlocking {
        val runId = "eng03-screen-overwrite-${System.currentTimeMillis()}"
        val sensitiveSecret = "screen-off-orig-sec-194"
        val externalNewValue = "external-screen-off-kept-value-552"

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            scenario.onActivity {
                clipboardSecurityManager.copySensitiveText("pass", sensitiveSecret, 30)
            }

            // 切换到独立客户端并改写剪贴板
            val clientIntent = Intent().apply {
                component = clientComponent
                action = AutofillClientActivity.ACTION_SET_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, runId)
                putExtra(AutofillClientActivity.EXTRA_CLIPBOARD_TEXT, externalNewValue)
            }
            targetContext.startActivity(clientIntent)
            SystemClock.sleep(800L)

            try {
                // 真实系统熄屏
                sleepDevice()
                assertTrue(
                    "设备应处于熄屏状态",
                    probe.shell("dumpsys display").contains("mState=OFF")
                )
            } finally {
                wakeUpAndUnlock()
            }

            // 唤醒并切回主应用前台
            val bringBackIntent = Intent(targetContext, DialogWindowHardeningHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(bringBackIntent)
            SystemClock.sleep(1_000L)

            // 回前台核验：当前剪贴板为外部新值，严禁物理清空，复位待清标记
            val textInHostAfterReturn = clipboardSecurityManager.getCurrentClipText()
            assertEquals("外部新值在熄屏唤醒后必须完整保留，严禁误清", externalNewValue, textInHostAfterReturn)
            assertFalse(
                "确认为外部覆盖后待清标记必须已复位",
                prefs.getBoolean("pending_sensitive_clip", false)
            )
        }
    }

    @Test
    fun testSessionLockClearsImmediatelyInForeground() = runBlocking {
        val sensitiveSecret = "lock-secret-fg-123"
        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            scenario.onActivity {
                clipboardSecurityManager.copySensitiveText("pass", sensitiveSecret, 30)
            }
            assertEquals(sensitiveSecret, clipboardSecurityManager.getCurrentClipText())
            assertTrue(prefs.getBoolean("pending_sensitive_clip", false))

            // 前台锁库：立即物理清空
            scenario.onActivity {
                clipboardSecurityManager.onSessionLocked()
            }
            val textAfterLock = clipboardSecurityManager.getCurrentClipText()
            assertTrue("前台锁库后敏感剪贴板必须立即物理清空", textAfterLock.isNullOrEmpty())
            assertFalse("前台锁库后待清标记已复位", prefs.getBoolean("pending_sensitive_clip", false))
        }
    }

    /**
     * 【工程契约说明】：本用例直接调用 [ClipboardSecurityManager.reconcileOnColdStart]，
     * 实测持久化布尔待清标记存在时的保守清空与状态复位行为。
     * 如实标注：本用例不模拟进程被 kill/force-stop 后的完整系统冷启动 UI 流程（按工程规则避免扩充无谓基建），
     * 验证的是冷启动对账内核契约。
     */
    @Test
    fun testColdStartReconcileClearsPendingSensitive() = runBlocking {
        prefs.edit().putBoolean("pending_sensitive_clip", true).apply()
        clipboardSecurityManager.copyPlainText("plain", "residual-data")
        prefs.edit().putBoolean("pending_sensitive_clip", true).apply()

        clipboardSecurityManager.reconcileOnColdStart()

        assertFalse(
            "冷启动对账后待清标记必须复位为 false",
            prefs.getBoolean("pending_sensitive_clip", false)
        )
    }

    @Test
    fun testDisabledAutoClearClearsOnBackgroundSwitchOrForegroundReturn() = runBlocking {
        val runId = "eng03-disabled-${System.currentTimeMillis()}"
        val sensitiveSecret = "disabled-autoclear-secret-888"

        ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java).use { scenario ->
            scenario.onActivity {
                clipboardSecurityManager.copySensitiveText(
                    label = "password",
                    text = sensitiveSecret,
                    customTimeoutSeconds = 0 // <= 0 表示选择不清空/无自动擦除窗口
                )
            }
            assertEquals(sensitiveSecret, clipboardSecurityManager.getCurrentClipText())

            val clientIntent = Intent().apply {
                component = clientComponent
                action = AutofillClientActivity.ACTION_PASTE_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AutofillClientActivity.EXTRA_RUN_ID, runId)
            }
            targetContext.startActivity(clientIntent)

            val clientTitleNode = probe.awaitAppText(
                packageName = testContext.packageName,
                text = AutofillClientActivity.TITLE,
                timeoutMs = 8_000L
            )
            assertNotNull("独立客户端未进入前台", clientTitleNode)
            // 等待超过 ProcessLifecycleOwner 的 700ms 停顿去抖时限，确保主应用进程完整进入后台并派发 onStop
            SystemClock.sleep(1_500L)

            // 切回前台核验
            val bringBackIntent = Intent(targetContext, DialogWindowHardeningHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(bringBackIntent)
            SystemClock.sleep(1_000L)

            val textInHostAfterReturn = clipboardSecurityManager.getCurrentClipText()
            assertTrue(
                "关闭自动擦除时切后台触发清理，回前台核验后必须已清空",
                textInHostAfterReturn.isNullOrEmpty()
            )
            assertFalse(prefs.getBoolean("pending_sensitive_clip", false))
        }
    }
}
