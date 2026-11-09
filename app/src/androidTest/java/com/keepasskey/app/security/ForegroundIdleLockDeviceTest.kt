package com.keepasskey.app.security

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.MainApplication
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.ui.components.MasterPasswordWeakConfirmDialog
import com.keepasskey.app.ui.screens.vault.VaultCreateTypeDialog
import com.keepasskey.app.ui.screens.vault.VaultSortDialog
import com.keepasskey.app.ui.screens.vault.VaultSortOption
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 前台闲置自动锁定的设备侧行为。
 *
 * - ISSUE-P2-637：真实前台计时入口锁库后，已落盘的合成库仍可重新解密；
 * - ENG-20261028-04：Activity 窗口受理的真实触摸 / 按键经生产接线刷新计时——持续交互不锁、
 *   停止交互到点锁。输入派发到 `window.decorView`（系统输入阶段对窗口根视图的同一调用），
 *   再经 `DecorView` → `Window.Callback` 进入生产转发器；不使用屏幕坐标注入，避免命中
 *   无关控件或被系统窗口抢占焦点。
 */
@RunWith(AndroidJUnit4::class)
class ForegroundIdleLockDeviceTest {

    @Test
    fun foregroundIdleDeadlineLocksSessionAndPreservesPersistedVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = RealSettingsRepository(context)
        val previousSettings = settings.getSettings().first()
        val file = File(context.cacheDir, "p2-637-${System.nanoTime()}.kdbx")
        val password = "P2-637-device-password".toCharArray()
        val session = DatabaseSession()
        val reopened = DatabaseSession()
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(ForegroundIdleLockPolicy.IMMEDIATE)

            assertTrue("合成数据库创建失败", session.create(file, "P2-637", password, useArgon2 = false) is KdbxResult.Success)
            session.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Persisted foreground timer fixture"),
                        KdbxConstants.Fields.USER_NAME to ProtectedString("synthetic-user"),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("synthetic-secret", isProtected = true)
                    )
                )
            )
            assertTrue("合成数据库初始写入失败", session.save() is KdbxResult.Success)
            val persistedBytes = file.readBytes()

            val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())
            val manager = AutoLockManager(context, guard, settings)
            manager.onUserInteraction()
            withTimeout(10_000) { guard.isLocked.first { it } }

            assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
            assertArrayEquals("自动锁定不得改写正式数据库", persistedBytes, file.readBytes())
            assertTrue(
                "锁定后的正式数据库必须仍可解密",
                reopened.openStream(
                    pathIdentifier = file.absolutePath,
                    inputStreamProvider = { FileInputStream(file) },
                    passwordChars = password.clone()
                ) is KdbxResult.Success
            )
            assertTrue(
                "重新解密后必须保留已提交条目",
                reopened.databaseFlow.value?.rootGroup?.allEntries()?.any {
                    it.title == "Persisted foreground timer fixture"
                } == true
            )
        } finally {
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
            session.close()
            reopened.close()
            file.delete()
            File(file.absolutePath + ".bak").delete()
            password.fill('\u0000')
        }
    }

    /**
     * ENG-20261028-04：生产 `AutoLockManager` 的交互接线必须在真实 Activity 窗口上生效——
     * 持续触摸 / 按键期间不得锁库，停止交互后按所配置的闲置档到点锁。
     *
     * 用例走的是应用真实对象图（`MainApplication.autoLockManager` 单例 + 真实 `MainActivity`），
     * 输入派发到窗口根视图后由 `DecorView` 交给 `Window.Callback`，即转发器所在的那一跳；
     * 接线缺失时交互刷新不到计时，会在闲置档到点锁定并被循环内断言捕获。
     * 触摸与按键分两段单独持续交互，各自证明能独立刷新（不让其中一条掩盖另一条失效）。
     */
    @Test
    fun continuousWindowInputRefreshesIdleTimerAndTrueIdleLocks() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            scenario = ActivityScenario.launch(MainActivity::class.java)
            val host = requireNotNull(scenario) { "MainActivity 未能启动" }
            var decorView: View? = null
            host.onActivity { activity ->
                manager.onUnlockSuccess()
                decorView = activity.window.decorView
                dispatchTouch(activity)
            }
            val targetDecor = requireNotNull(decorView)
            assertFalse("解锁播种并交互后不得立即锁库", manager.isLocked.value)

            assertInteractionsKeepAlive(manager, "触摸") { dispatchTouch(targetDecor) }
            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "按键") { dispatchKey(targetDecor) }

            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止交互后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )
        } finally {
            scenario?.close()
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * ENG-20261028-04：独立 Compose 对话框（拥有独立的 PhoneWindow / Window.Callback）的真实触摸 / 按键交互：
     * 经 [SecureDialogWindowEffect] / [AutoLockInteractionTracker.attachWindow] 接入生产转发器——
     * 持续在对话框窗口上交互期间不得锁库，停止交互后按所配置的闲置档到点锁。
     */
    @Test
    fun continuousDialogInputRefreshesIdleTimerAndTrueIdleLocks() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<DialogWindowHardeningHostActivity>? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            DialogWindowProbe.reset(hostWindowSecure = false, secure = false, useSecureEffect = false)
            scenario = ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java)
            val decorView = withTimeout(10_000) { DialogWindowProbe.decorView.await() }

            manager.onUnlockSuccess()
            dispatchTouch(decorView)
            assertFalse("解锁播种并在普通对话框交互后不得立即锁库", manager.isLocked.value)

            assertInteractionsKeepAlive(manager, "对话框触摸") { dispatchTouch(decorView) }
            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "对话框按键") { dispatchKey(decorView) }

            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止对话框交互后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )
        } finally {
            scenario?.close()
            manager.interactionTracker.onWindowAttached = null
            manager.interactionTracker.onWindowDetached = null
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * ENG-20261028-04：代表性生产普通对话框（以真实 [VaultSortDialog] 为代表）：
     * - 生产接线：在真实 Compose 窗口内组合生产对话框组件，经 [TrackDialogInteractionEffect] 挂接；
     * - 安全策略保持：普通对话框不得机械施加 FLAG_SECURE（flags and FLAG_SECURE == 0），维持原安全边界；
     * - 交互保活：持续触摸派发刷新前台闲置计时，期间绝不锁库；
     * - 停止交互到点锁：停止派发后按闲置档到点锁库。
     */
    @Test
    fun continuousProductionNormalDialogInputRefreshesIdleTimerAndPreservesSecurityPolicy() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<DialogWindowHardeningHostActivity>? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            DialogWindowProbe.reset(
                hostWindowSecure = false,
                secure = false,
                customContent = {
                    VaultSortDialog(
                        currentOption = VaultSortOption.NAME_ASC,
                        onSelect = {},
                        onDismiss = {}
                    )
                }
            )
            scenario = ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java)
            val dialogWindow = withTimeout(10_000) { DialogWindowProbe.window.await() }
            val decorView = withTimeout(10_000) { DialogWindowProbe.decorView.await() }

            assertEquals(
                "生产普通排序对话框不得强制施加 FLAG_SECURE，必须严格保持原安全 flags 策略",
                0,
                dialogWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
            )

            manager.onUnlockSuccess()
            dispatchTouch(decorView)
            assertFalse("解锁播种并在生产普通对话框交互后不得立即锁库", manager.isLocked.value)

            assertInteractionsKeepAlive(manager, "生产排序对话框触摸") { dispatchTouch(decorView) }
            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "生产排序对话框按键") { dispatchKey(decorView) }

            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止生产普通对话框交互后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )
        } finally {
            scenario?.close()
            manager.interactionTracker.onWindowAttached = null
            manager.interactionTracker.onWindowDetached = null
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * ENG-20261028-04：多选项生产内容槽普通对话框（以真实 [VaultCreateTypeDialog] 为代表）：
     * 验证带多卡片、模板入口的普通对话框在真实触摸 / 按键交互下持续刷新闲置。
     */
    @Test
    fun continuousProductionCreateTypeDialogInputRefreshesIdleTimer() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<DialogWindowHardeningHostActivity>? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            DialogWindowProbe.reset(
                hostWindowSecure = false,
                secure = false,
                customContent = {
                    VaultCreateTypeDialog(
                        onDismiss = {},
                        onAddEntry = {},
                        onCreateFolder = {},
                        templateCount = 1,
                        onCreateFromTemplate = {}
                    )
                }
            )
            scenario = ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java)
            val dialogWindow = withTimeout(10_000) { DialogWindowProbe.window.await() }
            val decorView = withTimeout(10_000) { DialogWindowProbe.decorView.await() }

            assertEquals(
                "生产新建类型对话框不得额外添加 FLAG_SECURE",
                0,
                dialogWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
            )

            manager.onUnlockSuccess()
            dispatchTouch(decorView)
            assertFalse("解锁播种并在生产新建分类对话框交互后不得立即锁库", manager.isLocked.value)

            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "新建分类对话框触摸") { dispatchTouch(decorView) }

            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止新建分类对话框交互后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )
        } finally {
            scenario?.close()
            manager.interactionTracker.onWindowAttached = null
            manager.interactionTracker.onWindowDetached = null
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * ENG-20261028-04：生产敏感对话框（以真实 [MasterPasswordWeakConfirmDialog] 为代表）：
     * - 强制遮罩：携带 FLAG_SECURE，不随普通对话框策略放宽；
     * - 交互刷新：内容槽经统一接线刷新闲置计时，避免重复挂接 SecureEffect；
     * - 停止交互到点锁。
     */
    @Test
    fun continuousProductionSensitiveDialogInputRefreshesIdleTimerAndEnforcesSecureFlag() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<DialogWindowHardeningHostActivity>? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            DialogWindowProbe.reset(
                hostWindowSecure = false,
                secure = true,
                customContent = {
                    MasterPasswordWeakConfirmDialog(
                        strengthBits = 20,
                        onConfirm = {},
                        onCancel = {}
                    )
                }
            )
            scenario = ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java)
            val dialogWindow = withTimeout(10_000) { DialogWindowProbe.window.await() }
            val decorView = withTimeout(10_000) { DialogWindowProbe.decorView.await() }

            assertTrue(
                "生产敏感确认对话框必须强制携带 FLAG_SECURE",
                dialogWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == WindowManager.LayoutParams.FLAG_SECURE
            )

            manager.onUnlockSuccess()
            dispatchTouch(decorView)
            assertFalse("解锁播种并在敏感对话框交互后不得立即锁库", manager.isLocked.value)

            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "生产敏感对话框触摸") { dispatchTouch(decorView) }

            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止敏感对话框交互后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )
        } finally {
            scenario?.close()
            manager.interactionTracker.onWindowAttached = null
            manager.interactionTracker.onWindowDetached = null
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * ENG-20261028-04：生产平台普通对话框（以 [android.app.AlertDialog] 为代表，覆盖 OpenVaultEntryActivity 等入口）：
     * - 平台原生 AlertDialog 独立于 Compose，其独立 PhoneWindow 通过生产 attachWindow 接入；
     * - 持续触摸刷新前台闲置计时；
     * - 停止交互后到点锁库；
     * - 关闭后正确解除包装（detachWindow）。
     */
    @Test
    fun continuousProductionPlatformDialogInputRefreshesIdleTimer() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<MainActivity>? = null
        var dialog: AlertDialog? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            scenario = ActivityScenario.launch(MainActivity::class.java)
            val host = requireNotNull(scenario) { "MainActivity 未能启动" }

            var dialogDecor: View? = null
            host.onActivity { activity ->
                val created = AlertDialog.Builder(activity)
                    .setTitle("Platform Dialog Fixture")
                    .setMessage("Synthetic platform dialog message")
                    .setPositiveButton("OK", null)
                    .create()
                created.show()
                val window = created.window
                if (window != null) {
                    manager.interactionTracker.attachWindow(window)
                    dialogDecor = window.decorView
                }
                dialog = created
            }

            val targetDecor = requireNotNull(dialogDecor) { "未能获取平台 AlertDialog decorView" }
            manager.onUnlockSuccess()
            dispatchTouch(targetDecor)
            assertFalse("解锁播种并在平台对话框交互后不得立即锁库", manager.isLocked.value)

            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "平台对话框触摸") { dispatchTouch(targetDecor) }

            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止平台对话框交互后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )
        } finally {
            scenario?.onActivity {
                dialog?.window?.let { manager.interactionTracker.detachWindow(it) }
                dialog?.dismiss()
            }
            scenario?.close()
            manager.interactionTracker.onWindowAttached = null
            manager.interactionTracker.onWindowDetached = null
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * ENG-20261028-04：软键盘 [android.view.inputmethod.InputConnection] 真实输入：
     * - 生产接线：在经过 KeePasskeyTheme 的真实 Compose TextField 上建立真实输入会话，
     *   从 ComposeView.onCreateInputConnection 获取生产生成的包装连接，不自行 wrap/notify；
     * - 阅读不刷新：纯文本只读查询（getTextBeforeCursor / getSelectedText）不重排闲置计时，到点正常锁库；
     * - 编辑保活：经生产连接的真实文本编辑（commitText 等）确认 UI 状态改变，持续编辑期间刷新计时、绝不锁库；
     * - 停止编辑到点锁：停止输入后按闲置档到点锁库；
     * - 锁定态不刷新：锁定态下的输入不会触发重排或重置锁态。
     */
    @Test
    fun continuousSoftKeyboardInputRefreshesIdleTimerAndTrueIdleLocks() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        val manager = app.autoLockManager
        val settings = RealSettingsRepository(app)
        val previousSettings = settings.getSettings().first()
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        var scenario: ActivityScenario<DialogWindowHardeningHostActivity>? = null
        try {
            settings.setAutoLockBackground(false)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockForegroundEnabled(true)
            settings.setAutoLockForegroundTimeoutSeconds(INTERACTION_TIMEOUT_SECONDS)
            manager.initialize()

            DialogWindowProbe.reset(hostWindowSecure = false, secure = false, showTextField = true)
            scenario = ActivityScenario.launch(DialogWindowHardeningHostActivity::class.java)
            val host = requireNotNull(scenario) { "DialogWindowHardeningHostActivity 未能启动" }

            var productionInputConnection: InputConnection? = null
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (SystemClock.uptimeMillis() < deadline && productionInputConnection == null) {
                host.onActivity { activity ->
                    DialogWindowProbe.focusRequester?.requestFocus()
                    val composeRoot = findAndroidComposeView(activity.window.decorView)
                    if (composeRoot != null) {
                        val editorInfo = EditorInfo()
                        val ic = composeRoot.onCreateInputConnection(editorInfo)
                        if (ic != null) {
                            productionInputConnection = ic
                        }
                    }
                }
                if (productionInputConnection == null) {
                    delay(50L)
                }
            }
            val inputConnection = requireNotNull(productionInputConnection) {
                "未能从聚焦的 ComposeView 获取生产 InputConnection"
            }

            // ── 段 1：阅读不刷新（仅查询不重排定时器，到点锁库） ──────────────────
            manager.onUnlockSuccess()
            assertFalse("解锁后初始态不得锁定", manager.isLocked.value)
            val readStartedAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - readStartedAt < timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                host.onActivity {
                    inputConnection.getTextBeforeCursor(10, 0)
                    inputConnection.getSelectedText(0)
                }
                if (manager.isLocked.value) break
                delay(INTERACTION_INTERVAL_MILLIS)
            }
            assertTrue("纯读取查询不得刷新闲置计时，闲置到点必须锁库", manager.isLocked.value)

            // ── 段 2：编辑保活（持续真实编辑不锁库）与 UI 值改变确认 ─────────
            manager.onUnlockSuccess()
            assertFalse("重新解锁后初始态不得锁定", manager.isLocked.value)
            host.onActivity {
                inputConnection.commitText("k", 1)
            }
            assertEquals("软键盘输入必须真实改变 Compose TextField 的 UI 值", "k", DialogWindowProbe.textInput)

            var editCount = 0
            val lastInteractionAtMillis = assertInteractionsKeepAlive(manager, "软键盘提交文本") {
                editCount++
                host.onActivity {
                    inputConnection.commitText("a", 1)
                }
            }
            assertEquals("持续输入后 UI 字符长度必须同步增加", 1 + editCount, DialogWindowProbe.textInput.length)

            // ── 段 3：停止编辑到点锁 ──────────────────────────────────────────
            withTimeout(timeoutMillis + IDLE_LOCK_BUDGET_MILLIS) {
                manager.isLocked.first { it }
            }
            val lockedAfterMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            assertTrue(
                "停止软键盘输入后不得早于闲置档到点锁库（实测 $lockedAfterMillis ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                lockedAfterMillis >= timeoutMillis - IDLE_LOCK_SLACK_MILLIS
            )

            // ── 段 4：锁定态输入不刷新 ───────────────────────────────────────
            assertTrue("确认当前处于锁定态", manager.isLocked.value)
            host.onActivity {
                inputConnection.commitText("test-when-locked", 1)
            }
            delay(INTERACTION_INTERVAL_MILLIS)
            assertTrue("锁定态下的软键盘输入不得刷新或复位锁定态", manager.isLocked.value)
        } finally {
            scenario?.close()
            settings.setAutoLockBackground(previousSettings.autoLockBackground)
            settings.setLockWhenScreenOff(previousSettings.lockWhenScreenOff)
            settings.setAutoLockForegroundEnabled(previousSettings.autoLockForegroundEnabled)
            settings.setAutoLockForegroundTimeoutSeconds(previousSettings.autoLockForegroundTimeoutSeconds)
        }
    }

    /**
     * 在超过 2 倍闲置档的时间里持续派发 [dispatch]，期间不得锁库；返回最后一次交互时刻。
     * 交互间隔小于闲置档，接线正常时定时器会被持续重排。
     */
    private suspend fun assertInteractionsKeepAlive(
        host: ActivityScenario<MainActivity>,
        manager: AutoLockManager,
        channel: String,
        dispatch: (Activity) -> Unit
    ): Long = assertInteractionsKeepAlive(manager, channel) {
        host.onActivity { dispatch(it) }
    }

    private suspend fun assertInteractionsKeepAlive(
        manager: AutoLockManager,
        channel: String,
        dispatch: () -> Unit
    ): Long {
        val timeoutMillis = INTERACTION_TIMEOUT_SECONDS * 1_000L
        val startedAt = SystemClock.elapsedRealtime()
        var lastInteractionAtMillis = startedAt
        while (SystemClock.elapsedRealtime() - startedAt < timeoutMillis * KEEP_ALIVE_ROUNDS) {
            dispatch()
            lastInteractionAtMillis = SystemClock.elapsedRealtime()
            assertFalse(
                "持续${channel}交互期间不得因前台闲置锁库（已交互 " +
                    "${lastInteractionAtMillis - startedAt}ms，闲置档 ${INTERACTION_TIMEOUT_SECONDS}s）",
                manager.isLocked.value
            )
            delay(INTERACTION_INTERVAL_MILLIS)
        }
        return lastInteractionAtMillis
    }

    /** 触摸 DOWN→CANCEL：走窗口触摸派发，但不落成点击（避免误触界面元素）。 */
    private fun dispatchTouch(activity: Activity) {
        dispatchTouch(activity.window.decorView)
    }

    private fun dispatchTouch(decorView: View) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, INTERACTION_TOUCH_X, INTERACTION_TOUCH_Y, 0
        )
        val cancel = MotionEvent.obtain(
            downTime, downTime + 1L, MotionEvent.ACTION_CANCEL, INTERACTION_TOUCH_X, INTERACTION_TOUCH_Y, 0
        )
        try {
            decorView.dispatchTouchEvent(down)
            decorView.dispatchTouchEvent(cancel)
        } finally {
            down.recycle()
            cancel.recycle()
        }
    }

    /** 物理按键 Shift 按下 / 抬起：走窗口按键派发，不触发任何业务动作。 */
    private fun dispatchKey(activity: Activity) {
        dispatchKey(activity.window.decorView)
    }

    private fun dispatchKey(decorView: View) {
        val downTime = SystemClock.uptimeMillis()
        decorView.dispatchKeyEvent(
            KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
        )
        decorView.dispatchKeyEvent(
            KeyEvent(downTime + 1L, downTime + 1L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
        )
    }

    private fun findAndroidComposeView(view: View): View? {
        if (view.javaClass.name.endsWith("AndroidComposeView")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findAndroidComposeView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private companion object {
        /** 闲置档取值：足够短以便用例在秒级完成，足够长以免主线程抖动误判 */
        const val INTERACTION_TIMEOUT_SECONDS = 3

        /** 持续交互时长相对闲置档的倍数，须 > 1 才能真正证伪「交互不刷新」 */
        const val KEEP_ALIVE_ROUNDS = 2L

        const val INTERACTION_INTERVAL_MILLIS = 700L

        /** 到点锁定允许的调度余量 */
        const val IDLE_LOCK_BUDGET_MILLIS = 6_000L

        /** 提前锁定判定余量（采样间隔 + 协程唤醒抖动） */
        const val IDLE_LOCK_SLACK_MILLIS = 500L

        const val INTERACTION_TOUCH_X = 8f
        const val INTERACTION_TOUCH_Y = 8f
    }
}
