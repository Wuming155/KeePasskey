package com.keepasskey.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real EntryDetailScreen clicks validate history row identity before and after a repository head insert. */
@RunWith(AndroidJUnit4::class)
class HistoryRevisionUiDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "History-UI-AVD-Fake-Master".toCharArray()
    private val entryId = "0000000000000000000000000000a632"
    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).vaultRepository()

    private var activeFile: File? = null

    @Before
    fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    @After
    fun cleanup() {
        runCatching { runBlocking { repository.lockDatabase() } }
        activeFile?.let { file ->
            runCatching {
                val catalog = VaultDatabaseCatalog(context, { _, _ -> "" },
                    EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        AutofillDeviceGraph::class.java
                    ).databaseSession()
                )
                catalog.saveKnownDatabases(catalog.loadKnownDatabases().filterNot { it.path == file.absolutePath })
            }
            runCatching { file.delete() }
            runCatching { File(file.absolutePath + ".bak").delete() }
            runCatching { file.parentFile?.delete() }
        }
        password.fill('\u0000')
    }

    @Test
    fun `真实详情页第二行对比回滚且头插后仍能选择原历史`() {
        val file = createAndOpenTestVault()
        val initialPasswords = listOf("P0", "P1", "P2", "P3")
        initialPasswords.forEach { savePassword(file, it) }

        val intent = Intent(context, EntryDetailDeviceHostActivity::class.java)
            .putExtra(EntryDetailDeviceHostActivity.EXTRA_ENTRY_ID, entryId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<EntryDetailDeviceHostActivity>(intent).use {
            assertTrue("详情页必须显示三条历史行", waitForExactTextCount("对比差异", 3))

            // Old history row 2 is P1 while current value is P3.
            clickNthText("对比差异", 1)
            assertTrue("第二条历史的对比弹窗必须出现", waitForExactText("版本差异对比"))
            assertTrue("第二条历史必须对应 P1，当前必须是 P3", waitUntil {
                val text = accessibilityText()
                "P1" in text && "P3" in text
            })
            clickText("取消")
            assertTrue("对比弹窗关闭后详情页必须可见", waitForExactText("凭据详情"))

            // Keep the real detail screen mounted while the production repository inserts a head revision.
            savePassword(file, "P4")
            assertTrue("头插后详情投影应出现四条历史行", waitForExactTextCount("对比差异", 4))

            // After head insertion P1 moves from row 2 to row 3. Tapping that row must still resolve P1.
            clickNthText("对比差异", 2)
            assertTrue("头插后的 P1 历史对比弹窗必须出现", waitForExactText("版本差异对比"))
            assertTrue("头插后第 3 行仍须对应 P1，当前为 P4", waitUntil {
                val text = accessibilityText()
                "P1" in text && "P4" in text
            })
            clickText("回滚至该历史版本")
            assertTrue("真实 UI 回滚必须把正式库提交为 P1", waitUntil { currentPassword(file) == "P1" })
            assertEquals("回滚后活动会话也必须为 P1", "P1", currentSessionPassword())
        }
    }

    private fun createAndOpenTestVault(): File {
        val folder = File(context.cacheDir, "history-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, "vault.kdbx")
        activeFile = file
        val session = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).databaseSession()
        assertTrue("合成库创建失败", runBlocking {
            session.create(file, "History UI Device", password.clone(), useArgon2 = false) is KdbxResult.Success
        })
        assertTrue("测试库登记失败", runBlocking {
            repository.importExternalDatabase("history-ui-${folder.name}", file.absolutePath) is KdbxResult.Success
        })
        runBlocking { repository.selectDatabase(file.absolutePath) }
        assertTrue("目标库必须成为活动库", repository.databaseInfoForPath(file.absolutePath)?.isActive == true)
        assertTrue("测试库解锁失败", runBlocking {
            repository.unlockActiveDatabase(password.clone(), null, readOnly = false) is KdbxResult.Success
        })
        return file
    }

    private fun savePassword(file: File, value: String) {
        val entry = UiVaultEntry(
            id = entryId,
            title = "History UI target",
            username = "ui-user",
            url = "https://history-ui.invalid"
        )
        val saved = runBlocking { repository.saveEntry(entry, value.toCharArray()) }
        assertTrue("生产仓库保存 $value 失败: $saved", saved is KdbxResult.Success)
        assertEquals("每次保存后正式文件必须可解析为当前值", value, currentPassword(file))
    }

    private fun currentPassword(file: File): String {
        val credential = password.clone()
        val database = try {
            KdbxFile.load(file.inputStream(), credential)
        } finally {
            credential.fill('\u0000')
        }
        return try {
            database.rootGroup.findEntry(KdbxUuid.fromHexString(entryId))
                ?.fields?.get(KdbxConstants.Fields.PASSWORD)?.readString().orEmpty()
        } finally {
            database.clearSensitiveData()
        }
    }

    private fun currentSessionPassword(): String = runBlocking {
        val entry = repository.getKdbxEntry(entryId) ?: error("活动会话缺少历史测试条目")
        entry.password?.readString().orEmpty()
    }

    private fun exactTextNodes(label: String): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == label) found += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { collect(it.root) }
        return found.distinctBy { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "${node.text}|$bounds"
        }.sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
    }

    private fun clickNthText(label: String, index: Int) {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var node: AccessibilityNodeInfo? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            node = exactTextNodes(label).getOrNull(index)
            if (node != null) break
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        val target = requireNotNull(node) { "无障碍树未找到第 ${index + 1} 个 '$label'：${accessibilityText()}" }
        clickNode(target)
    }

    private fun clickText(label: String) {
        val node = waitForNode(label) ?: error("无障碍树未找到 '$label'：${accessibilityText()}")
        clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(bounds.width() > 0 && bounds.height() > 0) { "目标无可点击边界" }
        automation.executeShellCommand("input tap ${bounds.exactCenterX().toInt()} ${bounds.exactCenterY().toInt()}").use { it.close() }
    }

    private fun waitForNode(label: String): AccessibilityNodeInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            exactTextNodes(label).firstOrNull()?.let { return it }
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun waitForExactText(label: String): Boolean = waitForNode(label) != null

    private fun waitForExactTextCount(label: String, expected: Int): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (exactTextNodes(label).size == expected) return true
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun accessibilityText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            for (index in 0 until node.childCount) appendNode(node.getChild(index))
        }
        appendNode(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { appendNode(it.root) }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_CLICK_ANCESTORS = 8
    }
}
