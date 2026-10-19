package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `ISSUE-P1-592` 步骤 5 / 6：恢复副本处置编排单测（`VaultRecoveryCoordinator`）。
 *
 * 覆盖：发现 → 解锁比较差异（含「正式库外改」读数）→ 覆盖保护 → 恢复 / 另存 → 成功后删除副本。
 */
class VaultRecoveryCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var official: File
    private lateinit var store: VaultRecoveryStore

    private fun newCoordinator(): VaultRecoveryCoordinator {
        store = VaultRecoveryStore(null, DebugLogBuffer()).apply { baseDirOverride = tempFolder.root }
        return VaultRecoveryCoordinator(
            context = null,
            store = store,
            alertStore = RecoveryAlertStore(null),
            keyFileCopyStore = KeyFileVaultCopyStore(null, null, DebugLogBuffer()),
            debugLog = DebugLogBuffer()
        ).apply { filesDirOverride = tempFolder.root }
    }

    /** 建一个含 [entryTitles] 的库文件（正式库），返回会话导出的 KDBX 字节（供作快照载荷）。 */
    private fun seedSnapshot(entryTitles: List<String>): ByteArray {
        official = File(tempFolder.root, "official.kdbx")
        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(official, "Official", PASSWORD, useArgon2 = false).isSuccess)
            session.saveEntry(entry(entryTitles.first()))
            assertTrue(session.save().isSuccess)
            entryTitles.drop(1).forEach { session.saveEntry(entry(it)) }
        }
        val bytes = runBlocking { (session.exportToBytes() as KdbxResult.Success).data }
        runBlocking { session.lock() }
        return bytes
    }

    private fun entry(title: String) = KdbxEntry(
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false))
    )

    private fun previewFor(snapshotEntry: KdbxEntry, officialEntry: KdbxEntry): VaultRecoveryCoordinator.Preview {
        official = File(tempFolder.root, "compare-${System.nanoTime()}.kdbx")
        val snapshotSession = DatabaseSession()
        runBlocking {
            val create = snapshotSession.create(official, "Compare", PASSWORD.clone(), useArgon2 = false)
            assertTrue("恢复预览快照库创建失败: ${(create as? KdbxResult.Failure)?.code}", create.isSuccess)
            snapshotSession.saveEntry(snapshotEntry)
            val saved = snapshotSession.save()
            assertTrue("恢复预览快照写入失败: ${(saved as? KdbxResult.Failure)?.code}", saved.isSuccess)
        }
        val snapshotBytes = runBlocking { (snapshotSession.exportToBytes() as KdbxResult.Success).data }

        val officialSession = DatabaseSession()
        runBlocking {
            assertTrue(officialSession.open(official, PASSWORD.clone()).isSuccess)
            officialSession.saveEntry(officialEntry)
            assertTrue(officialSession.save().isSuccess)
            officialSession.lock()
        }
        runBlocking { snapshotSession.lock() }

        val coordinator = newCoordinator()
        assertTrue(runBlocking {
            store.write("recovery-preview-test", "Compare", official.absolutePath, snapshotBytes)
        })
        snapshotBytes.fill(0)
        coordinator.refresh()
        val result = runBlocking { coordinator.preview(coordinator.pending.value.single(), PASSWORD.clone()) }
        assertTrue("预览应成功: ${(result as? KdbxResult.Failure)?.code}", result.isSuccess)
        return (result as KdbxResult.Success).data
    }

    private fun titlesOf(bytes: ByteArray): List<String> {
        val db = KdbxFile.load(ByteArrayInputStream(bytes), PASSWORD.clone())
        val titles = db.rootGroup.allEntries().map { it.title }
        db.clearSensitiveData()
        return titles
    }

    @Test
    fun `预览差异并识别正式库外改条目`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A", "B"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()

        val pending = coordinator.pending.value.single()
        assertTrue("正式库存在", pending.officialFileExists)

        val result = coordinator.preview(pending, PASSWORD.clone())
        assertTrue(result.isSuccess)
        val preview = (result as KdbxResult.Success).data
        assertEquals(2, preview.snapshotEntryCount)
        assertEquals(1, preview.officialEntryCount)
        assertEquals(1, preview.addedEntries)
        assertTrue(preview.differs)
    }

    @Test
    fun `正式库存在时未确认覆盖被拒绝`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A", "B"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()
        val pending = coordinator.pending.value.single()

        val result = coordinator.restore(pending, forceOverwrite = false)

        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.RECOVERY_OVERWRITE_BLOCKED, (result as KdbxResult.Failure).code)
        assertTrue("未确认时不得改动正式库", titlesOf(official.readBytes()) == listOf("A"))
        assertTrue("副本保留", store.hasAny())
    }

    @Test
    fun `确认覆盖后恢复正式库并删除副本`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A", "B"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()
        val pending = coordinator.pending.value.single()

        val result = coordinator.restore(pending, forceOverwrite = true)

        assertTrue(result.isSuccess)
        assertTrue(titlesOf(official.readBytes()).containsAll(listOf("A", "B")))
        assertFalse("恢复成功后副本必须删除", store.hasAny())
    }

    @Test
    fun `另存为新库后删除副本`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A", "B"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()
        val pending = coordinator.pending.value.single()

        val result = coordinator.saveAs(pending)

        assertTrue(result.isSuccess)
        val name = (result as KdbxResult.Success).data
        val saved = File(tempFolder.root, name)
        assertTrue(saved.exists())
        assertTrue(titlesOf(saved.readBytes()).containsAll(listOf("A", "B")))
        assertFalse("另存成功后副本必须删除", store.hasAny())
    }

    @Test
    fun `损坏副本预览如实失败`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A", "B"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()
        val pending = coordinator.pending.value.single()

        // 篡改载荷，破坏完整性
        tempFolder.root.listFiles { f -> f.name.endsWith(".kre") }!!.single().writeBytes(ByteArray(8))

        val result = coordinator.preview(pending, PASSWORD.clone())
        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.RECOVERY_PAYLOAD_CORRUPT, (result as KdbxResult.Failure).code)
    }

    @Test
    fun `密码OTP受保护和Passkey字段附件及历史单独变化均被计数`() {
        fun baseline() = KdbxEntry(
            id = com.keepasskey.core.model.KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Entry", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("fake-password", isProtected = true)
            ),
            customFields = listOf(
                KdbxCustomField("otp", ProtectedString("fake-otp", isProtected = true)),
                KdbxCustomField("private-note", ProtectedString("old-secret", isProtected = true)),
                KdbxCustomField("KPEX_PASSKEY_PRIVATE_KEY", ProtectedString("fake-passkey-key", isProtected = true))
            ),
            attachments = listOf(KdbxAttachment("file.bin", data = byteArrayOf(1, 2, 3))),
            history = listOf(entry("History old"))
        )
        val cases: List<Pair<String, (KdbxEntry) -> KdbxEntry>> = listOf(
            "password" to { value -> value.copy(fields = value.fields + (KdbxConstants.Fields.PASSWORD to ProtectedString("changed-password", isProtected = true))) },
            "OTP" to { value -> value.copy(customFields = value.customFields.map { field -> if (field.key == "otp") field.copy(value = ProtectedString("changed-otp", isProtected = true)) else field }) },
            "受保护自定义字段" to { value -> value.copy(customFields = value.customFields.map { field -> if (field.key == "private-note") field.copy(value = ProtectedString("new-secret", isProtected = true)) else field }) },
            "Passkey 材料" to { value -> value.copy(customFields = value.customFields.map { field -> if (field.key == "KPEX_PASSKEY_PRIVATE_KEY") field.copy(value = ProtectedString("changed-passkey-key", isProtected = true)) else field }) },
            "同数量附件字节" to { value -> value.copy(attachments = listOf(KdbxAttachment("file.bin", data = byteArrayOf(1, 2, 4)))) },
            "同数量历史内容" to { value -> value.copy(history = listOf(entry("History new"))) }
        )

        cases.forEach { (label, change) ->
            val base = baseline()
            assertEquals(label, 1, previewFor(base, change(base)).modifiedEntries)
        }
    }

    @Test
    fun `相同用户内容为负对照`() {
        val base = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Entry", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("fake-password", isProtected = true)
            ),
            attachments = listOf(KdbxAttachment("file.bin", data = byteArrayOf(8, 9)))
        )

        val preview = previewFor(base, base.copy(id = base.id))

        assertEquals(0, preview.modifiedEntries)
        assertFalse(preview.differs)
    }

    @Test
    fun `大附件按固定预算比较且内容变化被识别`() {
        val id = com.keepasskey.core.model.KdbxUuid.random()
        val baseBytes = ByteArray(LARGE_ATTACHMENT_SIZE) { (it % 251).toByte() }
        val changedBytes = baseBytes.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val base = KdbxEntry(
            id = id,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Large", isProtected = false)),
            attachments = listOf(KdbxAttachment("large.bin", data = baseBytes))
        )
        val changed = base.copy(attachments = listOf(KdbxAttachment("large.bin", data = changedBytes)))

        val preview = previewFor(base, changed)

        assertEquals(1, preview.modifiedEntries)
        baseBytes.fill(0)
        changedBytes.fill(0)
    }

    @Test
    fun `历史条目数量相同但内容不同仍计为修改`() {
        val id = com.keepasskey.core.model.KdbxUuid.random()
        val base = KdbxEntry(
            id = id,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Entry", isProtected = false)),
            history = listOf(entry("History old"))
        )
        val changed = base.copy(history = listOf(entry("History new")))

        val preview = previewFor(base, changed)

        assertEquals(1, preview.modifiedEntries)
    }

    @Test
    fun `不可读正式库不报告零差异`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()
        official.writeBytes(byteArrayOf(1, 2, 3))

        val result = coordinator.preview(coordinator.pending.value.single(), PASSWORD.clone())

        assertTrue(result.isSuccess)
        val preview = (result as KdbxResult.Success).data
        assertTrue(preview.officialUnreadable)
        assertEquals(null, preview.modifiedEntries)
    }

    @Test
    fun `删除副本后不再待处置`() = runBlocking {
        val snapshot = seedSnapshot(listOf("A"))
        val coordinator = newCoordinator()
        assertTrue(store.write(official.absolutePath, "Official", official.absolutePath, snapshot))
        snapshot.fill(0)
        coordinator.refresh()
        assertNotNull(coordinator.pending.value.singleOrNull())

        coordinator.discard(coordinator.pending.value.single())

        assertTrue(coordinator.pending.value.isEmpty())
    }

    private companion object {
        val PASSWORD = "VaultPass!42".toCharArray()
        const val LARGE_ATTACHMENT_SIZE = 1024 * 1024
    }
}
