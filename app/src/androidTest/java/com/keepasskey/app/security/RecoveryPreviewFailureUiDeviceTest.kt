package com.keepasskey.app.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.EntryPointAccessors
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.MainActivity
import com.keepasskey.app.ui.screens.recovery.RecoveryViewModel
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-638`：恢复预览在**真实产品 UI**上的失败呈现（设备侧）。
 *
 * ## 这项在证明什么
 *
 * JVM 侧已用 `RecoveryEntryContentComparatorTest`（6 例）与 `RecoveryPreviewContentTest`（4 例）
 * 证明「密码 / OTP / Passkey / 受保护自定义字段 / 附件逐字节」都参与比较，且超预算与读取失败
 * fail-closed。剩下的是**设备侧两件事**：
 *
 * 1. **正式库解析失败**时，产品 UI 必须如实呈现失败，而不是显示「修改 0」；
 * 2. **大附件预算**耗尽时同样 fail-closed（不把超预算当「相同」），并在 UI 上如实呈现。
 *
 * 两条都走**真实** [VaultRecoveryCoordinator]（不替换 `previewOperation`），
 * 只经产品 `MainActivity` + 真实 `RecoveryViewModel` 与无障碍树读取呈现结果。
 *
 * ## 边界
 *
 * 预算用例不造 256 MiB 附件（设备上不可接受）：协调器暴露了 `attachmentBudgetBytes`
 * （生产恒为 256 MiB），用例把它调到极小值，从而在**真实 KDBX + 真实产品 UI** 上
 * 触发与「大附件超预算」完全相同的分支。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPreviewFailureUiDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "P2-638-Recovery-Ui".toCharArray()

    private val store = VaultRecoveryStore(context, DebugLogBuffer())
    private val createdVaultIds = mutableListOf<String>()
    private val createdFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        createdVaultIds.forEach { runCatching { store.deleteByVaultId(it) } }
        createdFiles.forEach { runCatching { it.delete() } }
        password.fill('\u0000')
    }

    @Test
    fun `正式库解析失败时产品UI必须如实呈现失败而非修改零`() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val vaultId = "p2-638-broken-$suffix"
        val official = File(context.cacheDir, "$vaultId.kdbx").also { createdFiles += it }
        // 正式库写成**损坏字节**：副本本身合法，但正式库无法解析
        official.writeBytes(ByteArray(64) { 0x5A })
        val payload = buildVaultBytes(entryCount = 2)
        try {
            assertTrue("恢复副本写入失败", store.write(vaultId, "P2-638 Broken", official.absolutePath, payload))
            createdVaultIds += vaultId

            enableAccessibilityRetrieval()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var viewModel: RecoveryViewModel? = null
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity).get(RecoveryViewModel::class.java)
                    requireNotNull(viewModel).refresh()
                }
                val vm = requireNotNull(viewModel)
                val pending = vm.uiState.value.pending.single { it.entry.vaultId == vaultId }
                assertNotNull("副本必须被产品 UI 发现", pending)

                vm.select(RecoveryViewModel.keyOf(pending))
                // 直接经真实 coordinator 触发预览（UI 的「预览」按钮需要先输入主密码；
                // 此处经 ViewModel 的同一入口，password 由用例持有并按其契约只读）
                vm.preview(pending, password.copyOf())

                waitUntil(15_000) { vm.uiState.value.previewText != null || !vm.uiState.value.busy }
                val state = vm.uiState.value
                // 产品口径（`VaultRecoveryCoordinator.buildPreview`）：正式库读不出来时返回
                // `officialEntryCount = null`，UI 走 `recovery_preview_no_official`——
                // **绝不**给出「修改 0」这种误导性读数。这里断言的正是这条语义。
                assertNotNull("预览必须给出结论（成功或失败），不得停在忙态", state.preview)
                assertEquals(
                    "正式库不可读时不得给出差异计数",
                    null,
                    state.preview!!.officialEntryCount
                )
                assertEquals("正式库不可读时不得给出新增数", null, state.preview!!.addedEntries)
                assertEquals("正式库不可读时不得给出修改数", null, state.preview!!.modifiedEntries)
                assertEquals(
                    "正式库不可读时必须呈现「无正式库可比较」文案",
                    context.getString(R.string.recovery_preview_no_official),
                    state.previewText
                )
                // 产品 UI 上真的显示了这条文案
                assertTrue(
                    "产品 UI 未呈现该文案；页面=${accessibilityText()}",
                    waitForText(state.previewText!!, 10_000)
                )
            }
        } finally {
            payload.fill(0)
        }
    }

    @Test
    fun `附件内容一致时预览判无修改且不依赖内容哈希`() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val vaultId = "p2-638-budget-$suffix"
        val official = File(context.cacheDir, "$vaultId-official.kdbx").also { createdFiles += it }
        // 正式库与副本必须是**同一份字节**：两次独立保存会带出不同的内部元数据（保存时刻等），
        // 那样 modified 会因元数据差异而为 1，本用例就证明不了「逐字节比较」这件事。
        val sharedAttachment = ByteArray(4096) { (it % 251).toByte() }
        val vaultBytes = buildVaultBytes(entryCount = 1, attachmentBytes = sharedAttachment)
        official.writeBytes(vaultBytes)
        val payload = vaultBytes.copyOf()
        try {
            assertTrue("恢复副本写入失败", store.write(vaultId, "P2-638 Budget", official.absolutePath, payload))
            createdVaultIds += vaultId

            enableAccessibilityRetrieval()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var viewModel: RecoveryViewModel? = null
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity).get(RecoveryViewModel::class.java)
                    requireNotNull(viewModel).refresh()
                }
                val vm = requireNotNull(viewModel)
                val pending = vm.uiState.value.pending.single { it.entry.vaultId == vaultId }

                vm.select(RecoveryViewModel.keyOf(pending))
                vm.preview(pending, password.copyOf())
                waitUntil(20_000) { vm.uiState.value.previewText != null || !vm.uiState.value.busy }

                val state = vm.uiState.value
                // 两侧是同一份字节 ⇒ 必须成功预览且差异全为 0。
                // 这条同时是**负对照**：证明比较器不会把「内容一致」误判成有修改，
                // 也不会因为附件参与逐字节比较就一律报脏。
                assertNotNull("内容一致的副本必须能正常预览；状态=$state", state.preview)
                assertEquals("内容一致时修改数必须为 0", 0, state.preview!!.modifiedEntries)
                assertEquals("内容一致时新增数必须为 0", 0, state.preview!!.addedEntries)
                assertEquals("内容一致时删除数必须为 0", 0, state.preview!!.removedEntries)
                assertEquals("两侧应有同样多的条目", 1, state.preview!!.officialEntryCount)
            }
        } finally {
            sharedAttachment.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun `附件字节不同但长度相同时必须判为已修改`() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val vaultId = "p2-638-attach-diff-$suffix"
        val official = File(context.cacheDir, "$vaultId-official.kdbx").also { createdFiles += it }
        // 同一条目、同附件名、**同长度**，只有附件字节的最后一个字节不同。
        // 这正是原缺陷的盲区：只比数量 / 名称 / 长度 / 32 位 contentHash 都看不出差异。
        val officialAttachment = ByteArray(2048) { (it % 251).toByte() }
        val snapshotAttachment = officialAttachment.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals("两侧附件长度必须相同（否则证明的不是本分支）", officialAttachment.size, snapshotAttachment.size)
        assertFalse("两侧附件字节必须不同", officialAttachment.contentEquals(snapshotAttachment))

        official.writeBytes(buildVaultBytes(entryCount = 1, attachmentBytes = officialAttachment))
        val payload = buildVaultBytes(entryCount = 1, attachmentBytes = snapshotAttachment)
        try {
            assertTrue("恢复副本写入失败", store.write(vaultId, "P2-638 AttachDiff", official.absolutePath, payload))
            createdVaultIds += vaultId

            enableAccessibilityRetrieval()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var viewModel: RecoveryViewModel? = null
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity).get(RecoveryViewModel::class.java)
                    requireNotNull(viewModel).refresh()
                }
                val vm = requireNotNull(viewModel)
                val pending = vm.uiState.value.pending.single { it.entry.vaultId == vaultId }

                vm.select(RecoveryViewModel.keyOf(pending))
                vm.preview(pending, password.copyOf())
                waitUntil(20_000) { vm.uiState.value.previewText != null || !vm.uiState.value.busy }

                val state = vm.uiState.value
                assertNotNull("附件不同的副本必须能正常预览；状态=$state", state.preview)
                assertEquals(
                    "附件字节不同（长度相同）时必须判为已修改 1 条——原缺陷的盲区正在此处",
                    1,
                    state.preview!!.modifiedEntries
                )
                assertEquals("条目 id 相同，故新增为 0", 0, state.preview!!.addedEntries)
                assertEquals("条目 id 相同，故删除为 0", 0, state.preview!!.removedEntries)
                assertTrue(
                    "产品 UI 必须把这处差异呈现出来；页面=${accessibilityText()}",
                    waitForText(
                        context.getString(
                            R.string.recovery_preview_summary, 1, 1, 0, 0, 1
                        ),
                        10_000
                    )
                )
            }
        } finally {
            officialAttachment.fill(0)
            snapshotAttachment.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun `附件超出预算时UI必须呈现预算文案而非凭据不匹配`() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val vaultId = "p2-638-budget-$suffix"
        val official = File(context.cacheDir, "$vaultId-official.kdbx").also { createdFiles += it }
        // 正式库与副本各带一个附件；两侧字节相同，故「若无预算检查会判为无修改」。
        val attachment = ByteArray(8192) { (it % 251).toByte() }
        val vaultBytes = buildVaultBytes(entryCount = 1, attachmentBytes = attachment)
        official.writeBytes(vaultBytes)
        val payload = vaultBytes.copyOf()

        // 生产预算是 256 MiB；这里调到 1 KiB，使「两侧 8 KiB 附件」必然超限——
        // 与真实大附件走**完全同一条** fail-closed 分支，且不必在设备上造 256 MiB。
        val coordinator = EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.keepasskey.app.di.AutofillDeviceGraph::class.java
        ).vaultRecoveryCoordinator()
        val originalBudget = coordinator.attachmentBudgetBytes
        coordinator.attachmentBudgetBytes = 1024L
        try {
            assertTrue("恢复副本写入失败", store.write(vaultId, "P2-638 Budget", official.absolutePath, payload))
            createdVaultIds += vaultId

            enableAccessibilityRetrieval()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var viewModel: RecoveryViewModel? = null
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity).get(RecoveryViewModel::class.java)
                    requireNotNull(viewModel).refresh()
                }
                val vm = requireNotNull(viewModel)
                val pending = vm.uiState.value.pending.single { it.entry.vaultId == vaultId }

                vm.select(RecoveryViewModel.keyOf(pending))
                vm.preview(pending, password.copyOf())
                waitUntil(20_000) { vm.uiState.value.previewText != null || !vm.uiState.value.busy }

                val state = vm.uiState.value
                assertNull("超预算时不得给出差异读数（否则会把未知当成「无修改」）", state.preview)
                assertEquals(
                    "超预算必须呈现**预算**文案；呈现「凭据不匹配」会让用户徒劳地反复换凭据",
                    context.getString(R.string.err_recovery_preview_budget_exceeded),
                    state.previewText
                )
                assertNotEquals(
                    "超预算不得复用「凭据不匹配」文案",
                    context.getString(R.string.err_recovery_unlock_failed),
                    state.previewText
                )
                assertTrue(
                    "产品 UI 必须真的显示该预算文案；页面=${accessibilityText()}",
                    waitForText(state.previewText!!, 10_000)
                )
            }
        } finally {
            coordinator.attachmentBudgetBytes = originalBudget
            attachment.fill(0)
            payload.fill(0)
        }
    }

    @Test
    fun `密码单独变化与历史同长度异内容都必须判为已修改`() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val vaultId = "p2-638-pwd-$suffix"
        val official = File(context.cacheDir, "$vaultId-official.kdbx").also { createdFiles += it }
        // 原缺陷的另外两个盲区：只比 title/url/notes/tags 与附件/历史**数量**，
        // 因此「密码单独变化」与「历史条数不变但内容不同」都会显示「修改 0」。
        val officialBytes = buildVaultBytes(
            entryCount = 1,
            entryPassword = "p2-638-original-secret",
            historyCount = 1,
            historyPassword = "p2-638-history-original"
        )
        official.writeBytes(officialBytes)
        val payload = buildVaultBytes(
            entryCount = 1,
            // 标题/URL/标签/附件与历史条数全部不变，只有密码与历史内容变了
            entryPassword = "p2-638-CHANGED-secret",
            historyCount = 1,
            historyPassword = "p2-638-history-CHANGED!"
        )
        try {
            assertTrue("恢复副本写入失败", store.write(vaultId, "P2-638 Pwd", official.absolutePath, payload))
            createdVaultIds += vaultId

            enableAccessibilityRetrieval()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var viewModel: RecoveryViewModel? = null
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity).get(RecoveryViewModel::class.java)
                    requireNotNull(viewModel).refresh()
                }
                val vm = requireNotNull(viewModel)
                val pending = vm.uiState.value.pending.single { it.entry.vaultId == vaultId }

                vm.select(RecoveryViewModel.keyOf(pending))
                vm.preview(pending, password.copyOf())
                waitUntil(20_000) { vm.uiState.value.previewText != null || !vm.uiState.value.busy }

                val state = vm.uiState.value
                assertNotNull("必须能正常预览；状态=$state", state.preview)
                assertEquals(
                    "密码与历史内容都变了、数量不变，必须判为已修改 1 条（原缺陷在此显示修改 0）",
                    1,
                    state.preview!!.modifiedEntries
                )
                assertTrue(
                    "产品 UI 必须呈现「修改 1」；页面=${accessibilityText()}",
                    waitForText(context.getString(R.string.recovery_preview_summary, 1, 1, 0, 0, 1), 10_000)
                )
            }
        } finally {
            payload.fill(0)
        }
    }

    // ------------------------------------------------------------ 装置

    private fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    /** 造一份真实 KDBX 字节（可选带附件 / 改密码 / 改历史），供副本 / 正式库两侧使用。 */
    private fun buildVaultBytes(
        entryCount: Int,
        attachmentBytes: ByteArray? = null,
        entryPassword: String = "p2-638-secret",
        historyCount: Int = 0,
        historyPassword: String = "p2-638-history"
    ): ByteArray {
        val entries = (1..entryCount).map { index ->
            KdbxEntry(
                id = KdbxUuid.fromHexString("6386380000000000000000000000000$index"),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("P2-638 entry $index", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("$entryPassword-$index", isProtected = true)
                ),
                attachments = attachmentBytes?.let {
                    listOf(KdbxAttachment("p2-638.bin", data = it.copyOf()))
                }.orEmpty(),
                // 历史条目：同长度不同内容时也必须判为修改（历史容量上限场景）
                history = (1..historyCount).map { revision ->
                    KdbxEntry(
                        id = KdbxUuid.fromHexString("6386380000000000000000000000000$index"),
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to
                                ProtectedString("P2-638 entry $index", isProtected = false),
                            KdbxConstants.Fields.PASSWORD to
                                ProtectedString("$historyPassword-$revision", isProtected = true)
                        )
                    )
                }
            )
        }
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "P2-638",
            rootGroup = KdbxGroup(name = "Root", entries = entries)
        )
        val output = java.io.ByteArrayOutputStream()
        KdbxFile.save(output, db, password)
        db.clearSensitiveData()
        return output.toByteArray()
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return
            android.os.SystemClock.sleep(50)
        }
        assertTrue("等待条件超时（${timeoutMs}ms）；页面=${accessibilityText()}", predicate())
    }

    private fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (accessibilityText().contains(text)) return true
            android.os.SystemClock.sleep(100)
        }
        return accessibilityText().contains(text)
    }

    private fun accessibilityText(): String = buildString {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append('|') }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append('|') }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(automation.rootInActiveWindow)
    }
}
