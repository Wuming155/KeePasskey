package com.keepasskey.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P3-643` 的**真实 UI 回归**：重复选择图标后离页必须仍弹丢弃确认。
 *
 * 与 `EntryEditDirtyConfirmationConsistencyTest`（JVM）的分工：那件锁「编辑页脏位 / 全局登记表 /
 * 离页确认谓词三者同源」；本件在真机 / 隔离 AVD 上驱动**生产的编辑页**（debug 宿主
 * `EntryEditDirtyConfirmDeviceHostActivity` 挂的就是 `EntryEditScreen` + 生产 `EntryEditViewModel`）：
 * 经无障碍节点真实输入标题、真实点顶栏返回、真实点丢弃确认里的按钮；
 * 「连续两次选择当前图标」走生产 ViewModel 的 `onCustomIconSelected`——它正是图标选择器
 * `onSelectIcon` / `onSelectCustomIcon` 在选中时调用的同一个方法（图标 tile 无
 * `contentDescription`，无障碍树里点不到；该缺口另行登记）。
 *
 * 用例序列（整改方案 §4 矩阵）：
 * 1. 改标题 → 连续两次选当前图标 → 返回 → **「丢弃未保存的更改？」必须出现**；
 *    点「继续编辑」仍留在编辑页，且未保存的标题**不落盘**（整改前此处直接离页、静默丢改动）。
 * 2. 负对照：纯未改动（含选一次当前图标）→ 返回**不得**弹确认，且直接离页。
 *
 * 全部判定读真实无障碍树与真实落盘文件，不用固定 sleep 断言结果。
 */
@RunWith(AndroidJUnit4::class)
class EntryEditDirtyConfirmationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "P3-643-Dirty-Confirm-Device".toCharArray()

    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).vaultRepository()
    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).databaseSession()

    private var file: File? = null

    @After
    fun cleanup() {
        runCatching { runBlocking { repository.lockDatabase() } }
        val owned = file
        if (owned != null) {
            runCatching {
                val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
                catalog.saveKnownDatabases(
                    catalog.loadKnownDatabases().filterNot { it.path == owned.absolutePath }
                )
            }
            runCatching { owned.delete() }
            runCatching { File(owned.absolutePath + ".bak").delete() }
            runCatching { owned.parentFile?.delete() }
        }
        password.fill('\u0000')
    }

    @Test
    fun `改标题后连续两次选当前图标_返回必须弹丢弃确认且继续编辑不丢改动`() {
        val id = createVaultWithEntry()
        enableAccessibilityRetrieval()

        ActivityScenario.launch<EntryEditDirtyConfirmDeviceHostActivity>(
            Intent(context, EntryEditDirtyConfirmDeviceHostActivity::class.java)
                .putExtra(EntryEditDeviceHostActivity.EXTRA_ENTRY_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).use {
            assertTrue("编辑页必须载入既有条目", waitForText(ENTRY_TITLE))
            setTitleText(EDITED_TITLE)

            // 连续两次选择「当前」图标：走生产的 ViewModel 入口（图标选择器选中时调用的同一方法）。
            // 整改前第二次会把 isDirty 覆盖为 false（ISSUE-P3-643 的复现序列）。
            repeat(2) { index ->
                val viewModel = requireNotNull(
                    EntryEditDirtyConfirmDeviceHostActivity.currentViewModel
                ) { "宿主未暴露生产 ViewModel" }
                val currentIcon = viewModel.uiState.value.customIconId
                viewModel.onCustomIconSelected(currentIcon)
                assertTrue(
                    "第 ${index + 1} 次重复选择当前图标后脏位必须保持（页面=${accessibilityText()}）",
                    viewModel.uiState.value.isDirty
                )
            }

            // 返回：必须弹丢弃确认（整改前 isDirty 已被覆盖为 false ⇒ 直接离页）
            clickContentDescription(text(R.string.cd_back))
            assertTrue(
                "重复选择图标后返回必须弹丢弃确认（ISSUE-P3-643）；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.edit_discard_title))
            )

            // 「继续编辑」不得离页、不得丢改动
            clickText(text(R.string.btn_continue_edit))
            assertTrue("继续编辑后仍应在编辑页", waitForText(EDITED_TITLE))
        }

        // 正式文件不得被这次未保存的编辑改动
        val persisted = readVault(requireNotNull(file))
        try {
            val entry = persisted.rootGroup.allEntries().single { it.id.toHexString() == id }
            assertTrue(
                "未保存的标题不得落盘（实测=${entry.title}）",
                entry.title != EDITED_TITLE
            )
        } finally {
            persisted.clearSensitiveData()
        }
    }

    @Test
    fun `纯未改动负对照_返回不弹丢弃确认`() {
        val id = createVaultWithEntry()
        enableAccessibilityRetrieval()

        ActivityScenario.launch<EntryEditDirtyConfirmDeviceHostActivity>(
            Intent(context, EntryEditDirtyConfirmDeviceHostActivity::class.java)
                .putExtra(EntryEditDeviceHostActivity.EXTRA_ENTRY_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).use { scenario ->
            assertTrue("编辑页必须载入既有条目", waitForText(ENTRY_TITLE))
            // 未改动就选一次当前图标（同一条复现路径的无变化分支）
            val viewModel = requireNotNull(
                EntryEditDirtyConfirmDeviceHostActivity.currentViewModel
            ) { "宿主未暴露生产 ViewModel" }
            viewModel.onCustomIconSelected(viewModel.uiState.value.customIconId)
            assertFalse("未改动时选当前图标不得凭空变脏", viewModel.uiState.value.isDirty)

            clickContentDescription(text(R.string.cd_back))
            assertFalse(
                "未改动不得弹丢弃确认；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.edit_discard_title), timeoutMs = 1_500L)
            )
            // 已离页（宿主 onBackClick 即 finish）
            assertTrue("未改动返回必须直接离页", awaitScenarioDestroyed(scenario))
        }
    }

    // ------------------------------------------------------------------ 装置

    private fun text(resId: Int): String = context.getString(resId)

    private fun createVaultWithEntry(): String {
        val dir = File(context.cacheDir, "p3643-${System.nanoTime()}").apply { mkdirs() }
        val vault = File(dir, "vault.kdbx")
        file = vault
        val entry = KdbxEntry(
            id = ENTRY_UUID,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(ENTRY_TITLE, false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("fake-entry-pw", true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "P3-643 dirty confirmation",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        vault.outputStream().use { KdbxFile.save(it, db, password) }
        runBlocking {
            assertTrue(
                "登记活动库必须成功",
                repository.importExternalDatabase("P3643-${dir.name}", vault.absolutePath)
                    is KdbxResult.Success
            )
            var selected = false
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                repository.selectDatabase(vault.absolutePath)
                if (repository.databaseInfoForPath(vault.absolutePath)?.isActive == true) {
                    selected = true
                    break
                }
                android.os.SystemClock.sleep(50)
            }
            assertTrue("目标库必须成为活动库", selected)
            assertTrue(
                "打开活动库必须成功",
                repository.unlockActiveDatabase(password.clone(), null, readOnly = false) is KdbxResult.Success
            )
        }
        return ENTRY_UUID.toHexString()
    }

    private fun readVault(vault: File): KdbxDatabase =
        KdbxFile.load(ByteArrayInputStream(vault.readBytes()), password.clone())

    // --------------------------------------------------- 无障碍驱动辅助

    private fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    /** 以无障碍 `ACTION_SET_TEXT` 写入标题（与既有设备用例同一手法）。 */
    private fun setTitleText(value: String) {
        // 标题框当前值即既有条目标题：按「可编辑 + 文本等于原值」定位，不依赖 hintText
        // （Compose 的 label 是独立文本节点，不是节点的 hintText）
        val field = waitForNode { node -> node.isEditable && node.text?.toString() == ENTRY_TITLE }
            ?: error("找不到标题输入框（页面=${accessibilityText()}）")
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        assertTrue(
            "标题输入必须成功（页面=${accessibilityText()}）",
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        )
        assertTrue("标题必须写入编辑态", waitForText(value))
    }

    private fun clickText(label: String) {
        val node = waitForNode { it.text?.toString() == label }
            ?: error("找不到可点击文案：$label（页面=${accessibilityText()}）")
        clickNodeOrClickableAncestor(node, "文案 $label")
    }

    private fun clickContentDescription(description: String) {
        val node = waitForNode { it.contentDescription?.toString() == description }
            ?: error("找不到可点击描述：$description（页面=${accessibilityText()}）")
        clickNodeOrClickableAncestor(node, "描述 $description")
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo, label: String) {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        error("$label 不可点击（页面=${accessibilityText()}）")
    }

    private fun waitForNode(
        timeoutMs: Long = UI_TIMEOUT_MS,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            automation.rootInActiveWindow?.let { root ->
                findNode(root, predicate)?.let { return it }
            }
            automation.windows.orEmpty().forEach { window ->
                findNode(window.root, predicate)?.let { return it }
            }
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            findNode(root.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean =
        waitForNode(timeoutMs) { it.text?.toString() == text } != null

    private fun awaitScenarioDestroyed(
        scenario: ActivityScenario<EntryEditDirtyConfirmDeviceHostActivity>
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) return true
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
    }

    private fun accessibilityText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH) return
            val label = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (label.isNotBlank() || description.isNotBlank()) {
                append(" ".repeat(depth)).append(node.className).append(" text=[").append(label)
                    .append("] desc=[").append(description).append("]\n")
            }
            for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
        }
        appendNode(automation.rootInActiveWindow, 0)
        automation.windows.orEmpty().forEach { appendNode(it.root, 0) }
    }

    private companion object {
        const val ENTRY_TITLE = "P3643-Entry"
        const val EDITED_TITLE = "P3643-Edited"

        val ENTRY_UUID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x64, 0x43)
        )

        const val UI_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_CLICK_ANCESTORS = 8
        const val MAX_TREE_DEPTH = 24
    }
}
