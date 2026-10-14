package com.keepasskey.app.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.CharBuffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P1-613：由宿主 force-stop 后启动产品主界面，完整操作恢复副本。 */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryProductUiProcessRestartDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun forceStop后从产品UI预览确认并恢复副本() = runBlocking {
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)) { "缺少 p1_613_run_id" }
        val vaultFile = File(context.filesDir, "p1-613-recovery-$runId.kdbx")
        val vaultId = vaultFile.absolutePath
        val vaultTitle = "P1-613 Recovery $runId"
        val password = RECOVERY_PASSWORD.toCharArray()
        val store = VaultRecoveryStore(context, DebugLogBuffer())

        try {
            when (arguments.getString(ARG_PHASE)) {
                PHASE_PREPARE -> prepareRecovery(runId, vaultFile, vaultId, vaultTitle, password, store)
                PHASE_RESUME -> consumeThroughProductUi(runId, vaultFile, vaultId, vaultTitle, password, store)
                else -> error("p1_613_phase 只接受 prepare 或 resume")
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun prepareRecovery(
        runId: String,
        vaultFile: File,
        vaultId: String,
        vaultTitle: String,
        password: CharArray,
        store: VaultRecoveryStore
    ) {
        store.list().filter { it.vaultId.startsWith(File(context.filesDir, "p1-613-recovery-").absolutePath) }
            .forEach { previous ->
                store.deleteByVaultId(previous.vaultId)
                File(previous.pathIdentifier).delete()
                File(previous.pathIdentifier + ".bak").delete()
            }
        store.deleteByVaultId(vaultId)
        val seed = DatabaseSession()
        val dirty = DatabaseSession()
        try {
            assertTrue("恢复测试合成库创建失败", seed.create(vaultFile, vaultTitle, password, useArgon2 = false) is KdbxResult.Success)
            seed.lock()
            assertTrue(
                "注入保存失败的会话打开失败",
                dirty.openStream(
                    pathIdentifier = vaultId,
                    inputStreamProvider = { FileInputStream(vaultFile) },
                    saveWriter = { throw IOException("synthetic-p1-613-save-failure") },
                    passwordChars = password.clone()
                ) is KdbxResult.Success
            )
            dirty.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString(RECOVERY_ENTRY_TITLE),
                        KdbxConstants.Fields.USER_NAME to ProtectedString("synthetic-user"),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("Synthetic-Recovery-Secret", isProtected = true)
                    )
                )
            )
            val log = DebugLogBuffer()
            val alertStore = RecoveryAlertStore(context)
            val recovery = SessionRecoveryCoordinator(dirty, store, alertStore, log)
            val settings = RealSettingsRepository(context)
            val old = settings.getSettings().first().lockWhenScreenOff
            try {
                settings.setLockWhenScreenOff(true)
                val guard = AutoLockSessionGuard(dirty, settings, log, recovery = recovery)
                guard.lockOnScreenOff()
                assertEquals("熄屏锁定必须销毁活动会话", DatabaseSession.SessionState.LOCKED, dirty.state.value)
                assertTrue("正式恢复副本必须已落盘", store.list().any { it.vaultId == vaultId })
            } finally {
                settings.setLockWhenScreenOff(old)
            }
            context.getSharedPreferences(PREFS, 0).edit()
                .putInt(KEY_PREPARE_PID, android.os.Process.myPid())
                .putString(KEY_RUN_ID, runId)
                .commit()
        } finally {
            dirty.close()
            seed.close()
        }
    }

    private suspend fun consumeThroughProductUi(
        runId: String,
        vaultFile: File,
        vaultId: String,
        vaultTitle: String,
        password: CharArray,
        store: VaultRecoveryStore
    ) {
        val state = context.getSharedPreferences(PREFS, 0)
        assertEquals("恢复阶段必须对应同一运行", runId, state.getString(KEY_RUN_ID, null))
        assertNotEquals("OS force-stop 后必须由新进程运行", state.getInt(KEY_PREPARE_PID, -1), android.os.Process.myPid())
        assertTrue("杀进程后的恢复副本仍应存在", store.list().any { it.vaultId == vaultId })

        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("找不到产品主界面的 Launcher Intent")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)

        val recoveryTitle = findTextAny(RECOVERY_DIALOG_TITLE_ZH, RECOVERY_DIALOG_TITLE_EN, timeoutMs = 30_000)
        assertTrue("真实产品恢复对话框未显示", recoveryTitle != null)
        assertTrue("恢复条目未出现在产品 UI", clickTextContaining(vaultTitle, 10_000))
        val passwordField = waitForNode(10_000) { it.isEditable }
            ?: error("产品恢复 UI 未显示可编辑的主密码输入框")
        val wrongPassword = "Wrong-P1-613-Credential".toCharArray()
        val wrongText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, CharBuffer.wrap(wrongPassword))
        }
        assertTrue("无法输入无效的合成凭据", passwordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, wrongText))
        wrongPassword.fill('\u0000')
        assertTrue("错误凭据必须被产品预览拒绝", clickTextAny(PREVIEW_ZH, PREVIEW_EN, 10_000))
        assertTrue("错误凭据必须显示解锁失败", waitForTextContainingAny("凭据不匹配", "wrong credentials", 15_000))
        val restoreLabel = waitForNode(5_000) {
            it.text?.toString() == RESTORE_ZH || it.text?.toString() == RESTORE_EN
        } ?: error("错误凭据后产品 UI 缺少恢复操作")
        val restoreButton = clickableAncestor(restoreLabel)
            ?: error("错误凭据后恢复操作未绑定可点击容器")
        assertTrue("未成功预览时不得允许覆盖恢复", !restoreButton.isEnabled)

        val setText = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, CharBuffer.wrap(password)) }
        assertTrue("无法通过产品 UI 输入合成主密码", passwordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText))
        val previewButtonClicked = clickTextAny(PREVIEW_ZH, PREVIEW_EN, 10_000)
        assertTrue("产品 UI 缺少预览按钮；当前无障碍树=${accessibilityTreeText()}", previewButtonClicked)
        val previewVisible = waitForTextContainingAny("副本 1 项", "Copy 1", 15_000)
        assertTrue("产品 UI 未显示差异预览摘要；当前无障碍树=${accessibilityTreeText()}", previewVisible)
        assertTrue("产品 UI 缺少恢复按钮", clickTextAny(RESTORE_ZH, RESTORE_EN, 10_000))
        assertTrue("已有正式库时必须显示确认覆盖对话框", clickTextAny(CONFIRM_OVERWRITE_ZH, CONFIRM_OVERWRITE_EN, 10_000))
        assertTrue("恢复成功后产品恢复对话框应关闭", waitForNode(15_000) { node ->
            val text = node.text?.toString()
            text == RECOVERY_DIALOG_TITLE_ZH || text == RECOVERY_DIALOG_TITLE_EN
        } == null)

        assertTrue("成功恢复后恢复副本必须删除", store.list().none { it.vaultId == vaultId })
        val bytes = vaultFile.readBytes()
        val copy = password.clone()
        val restored = try { KdbxFile.load(ByteArrayInputStream(bytes), copy) } finally { copy.fill('\u0000'); bytes.fill(0) }
        try {
            assertEquals("正式库必须包含失败补存的条目", RECOVERY_ENTRY_TITLE, restored.rootGroup.allEntries().single().title)
        } finally {
            restored.clearSensitiveData()
        }
        state.edit().remove(KEY_PREPARE_PID).remove(KEY_RUN_ID).commit()
        vaultFile.delete()
        File(vaultFile.absolutePath + ".bak").delete()
    }

    private fun clickTextContaining(part: String, timeoutMs: Long): Boolean = clickWhen(timeoutMs) { node ->
        node.text?.toString()?.contains(part, ignoreCase = true) == true
    }

    private fun clickTextAny(first: String, second: String, timeoutMs: Long): Boolean = clickWhen(timeoutMs) { node ->
        node.text?.toString() == first || node.text?.toString() == second
    }

    private fun clickWhen(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var node = waitForNode(timeoutMs, predicate) ?: return false
        return clickAncestor(node)
    }

    private fun clickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node = start
        repeat(MAX_CLICK_ANCESTORS) {
            if (node.isClickable) return node
            node = node.parent ?: return null
        }
        return null
    }

    private fun clickAncestor(start: AccessibilityNodeInfo): Boolean {
        var node = start
        repeat(MAX_CLICK_ANCESTORS) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent ?: return false
        }
        return false
    }

    private fun findTextAny(first: String, second: String, timeoutMs: Long): AccessibilityNodeInfo? =
        waitForNode(timeoutMs) { it.text?.toString() == first || it.text?.toString() == second }

    private fun waitForTextContainingAny(first: String, second: String, timeoutMs: Long): Boolean =
        waitForNode(timeoutMs) { node ->
            val text = node.text?.toString().orEmpty()
            text.contains(first) || text.contains(second, ignoreCase = true)
        } != null

    private fun waitForNode(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(automation.rootInActiveWindow, predicate)?.let { return it }
            automation.windows.orEmpty().forEach { window ->
                findNode(window.root, predicate)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            findNode(root.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun accessibilityTreeText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH) return
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || description.isNotBlank() || node.isEditable) {
                append(" ".repeat(depth)).append(node.className).append(" text=[").append(text)
                    .append("] desc=[").append(description).append("] editable=").append(node.isEditable).append('\n')
            }
            for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
        }
        appendNode(automation.rootInActiveWindow, 0)
        automation.windows.orEmpty().forEach { appendNode(it.root, 0) }
    }

    private companion object {
        const val ARG_PHASE = "p1_613_phase"
        const val ARG_RUN_ID = "p1_613_run_id"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_RESUME = "resume"
        const val PREFS = "p1_613_recovery_process"
        const val KEY_PREPARE_PID = "prepare_pid"
        const val KEY_RUN_ID = "run_id"
        const val RECOVERY_PASSWORD = "Synthetic-P1-613-Recovery-Password"
        const val MAX_CLICK_ANCESTORS = 6
        const val MAX_TREE_DEPTH = 20
        const val RECOVERY_ENTRY_TITLE = "Uncommitted P1-613 recovery entry"
        const val RECOVERY_DIALOG_TITLE_ZH = "发现未保存的恢复副本"
        const val RECOVERY_DIALOG_TITLE_EN = "Unsaved recovery copy found"
        const val PREVIEW_ZH = "解锁并比较"
        const val PREVIEW_EN = "Unlock and compare"
        const val RESTORE_ZH = "恢复为正式库"
        const val RESTORE_EN = "Restore into official vault"
        const val CONFIRM_OVERWRITE_ZH = "确认覆盖"
        const val CONFIRM_OVERWRITE_EN = "Confirm overwrite"
    }
}
