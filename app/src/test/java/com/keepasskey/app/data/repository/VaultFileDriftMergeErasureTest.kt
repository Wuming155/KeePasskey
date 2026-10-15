package com.keepasskey.app.data.repository

import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

private const val PWD = KdbxConstants.Fields.PASSWORD
private const val TITLE = KdbxConstants.Fields.TITLE

private fun uuidOf(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val UUID_ROOT = uuidOf("00000000000000000000000000000001")
private val UUID_COMMON = uuidOf("00000000000000000000000000000002")
private val UUID_LOCAL_ONLY = uuidOf("00000000000000000000000000000003")
private val UUID_REMOTE_ONLY = uuidOf("00000000000000000000000000000004")

private val STRINGS = StringsProvider { _, _ -> "" }

private class DriftAttachmentStore : BinaryStore {
    val entries = LinkedHashMap<String, ByteArray>()
    var afterStore: (() -> Unit)? = null
    private var counter = 0

    override fun store(bytes: ByteArray): String = "drift-${counter++}".also {
        entries[it] = bytes.copyOf()
    }

    override fun storeFromStream(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        DataInputStream(input).readFully(bytes)
        return store(bytes).also { afterStore?.invoke() }
    }

    override fun load(key: String): ByteArray = entries[key]?.copyOf() ?: ByteArray(0)
    override fun openStream(key: String): InputStream = ByteArrayInputStream(load(key))
    override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()
    override fun clear() = entries.clear()
    override fun delete(key: String) { entries.remove(key) }
}

/**
 * `ISSUE-P3-471` 的 app 侧回归：**漂移合并的丢弃擦除必须走身份集合判定**。
 *
 * 缺陷形态（本次一并修正的历史遗留）：`VaultFileDriftResolve.mergeAndSave` 曾直接调
 * `diskDb.clearSensitiveData()`。但 `KdbxMerger` 对「磁盘独有 / 磁盘胜出的条目」与
 * 「磁盘独有的自定义图标」**复用原实例**（`KdbxEntryMerger.mergeSurvivingEntries` 的
 * `add(re)` 分支、`KdbxMerger.mergeCustomIcons`），故合并采用后这些实例同时可达于活动会话树
 * ——裸擦会把**活动库仍在使用的口令明文字节与图标字节**清零
 * （`SECURITY_RECHECK_2026-09.md` §9.6 #19 同型；图标面为 `ISSUE-P3-471` 新增）。
 *
 * 本用例把该调用点退回裸 `clearSensitiveData()` 即必红（反向反校锚点）。
 */
class VaultFileDriftMergeErasureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterPassword = "DriftMerge#2026".toCharArray()

    private fun entry(id: KdbxUuid, title: String, password: String) = KdbxEntry(
        id = id,
        fields = mapOf(
            TITLE to ProtectedString(title, false),
            PWD to ProtectedString(password, true)
        )
    )

    @Test
    fun `漂移合并采用磁盘独有条目与图标后，丢弃磁盘解析树不得清零活动库`() = runBlocking {
        val workDir = tempFolder.newFolder()
        val vaultFile = File(workDir, "drift.kdbx")
        val attachmentStore = DriftAttachmentStore()
        val session = DatabaseSession(attachmentStore)
        val created = session.create(
            file = vaultFile,
            name = "Drift",
            passwordChars = masterPassword,
            useArgon2 = false
        )
        assertTrue("前提：建库必须成功: $created", created is KdbxResult.Success)

        // 活动库（本地侧）：共有条目 + 本地独有条目 + 本地独有图标
        val localIcon = CustomIcon(uuid = uuidOf("0000000000000000000000000000000A"), data = byteArrayOf(0x11))
        val commonEntry = entry(UUID_COMMON, "Common", "Base#1")
        val localOnlyEntry = entry(UUID_LOCAL_ONLY, "LocalOnly", "LocalOnly#pwd")
        val localRoot = KdbxGroup(
            id = UUID_ROOT,
            name = "Root",
            entries = listOf(commonEntry, localOnlyEntry)
        )
        session.updateDatabaseMeta { it.copy(rootGroup = localRoot, customIcons = listOf(localIcon)) }
        assertTrue("前提：本地落盘成功", session.save() is KdbxResult.Success)
        val localDb = session.databaseFlow.value!!

        // 磁盘被外部改写：在磁盘版本基础上**追加**磁盘独有条目与磁盘独有图标（模拟他端写入）
        val diskIcon = CustomIcon(uuid = uuidOf("0000000000000000000000000000000B"), data = byteArrayOf(0x22))
        val remoteAttachment = ByteArray(1_200_000) { 0x42 }
        val remoteOnlyEntry = entry(UUID_REMOTE_ONLY, "RemoteOnly", "RemoteOnly#pwd").copy(
            attachments = listOf(KdbxAttachment("remote.bin", data = remoteAttachment))
        )
        val diskRoot = KdbxGroup(
            id = UUID_ROOT,
            name = "Root",
            entries = listOf(commonEntry, localOnlyEntry, remoteOnlyEntry)
        )
        val diskBytes = ByteArrayOutputStream()
            .also {
                KdbxFile.save(
                    it,
                    localDb.copy(rootGroup = diskRoot, customIcons = listOf(diskIcon)),
                    masterPassword
                )
            }
            .toByteArray()
        vaultFile.writeBytes(diskBytes)

        // 用户选择「合并」
        val resolver = VaultFileDriftResolve(
            databaseSession = session,
            strings = STRINGS,
            driftCoordinator = null
        )
        val result = resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("前提：合并必须成功: $result", result is KdbxResult.Success)

        val adopted = session.databaseFlow.value ?: error("活动库不得丢失")
        assertEquals(
            "P0 护栏：磁盘独有条目经原实例复用进活动库，其口令绝不可被丢弃树擦除清零",
            "RemoteOnly#pwd",
            adopted.rootGroup.allEntries().first { it.id == UUID_REMOTE_ONLY }.fields.getValue(PWD).readString()
        )
        assertEquals(
            "共有条目口令不得被误擦",
            "Base#1",
            adopted.rootGroup.allEntries().first { it.id == UUID_COMMON }.fields.getValue(PWD).readString()
        )
        assertEquals(
            "本地独有条目口令不得被误擦",
            "LocalOnly#pwd",
            adopted.rootGroup.allEntries().first { it.id == UUID_LOCAL_ONLY }.fields.getValue(PWD).readString()
        )
        assertArrayEquals(
            "P0 护栏：磁盘独有图标经原实例复用进活动库，其字节绝不可被丢弃树擦除清零",
            byteArrayOf(0x22),
            adopted.customIcons.first { it.uuid == diskIcon.uuid }.data
        )

        val adoptedAttachment = adopted.rootGroup.allEntries()
            .first { it.id == UUID_REMOTE_ONLY }.attachments.single()
        assertTrue("远端 spill key 必须在 mergedRoot 存活时保留", attachmentStore.entries.isNotEmpty())
        assertTrue("合并采用的 spill source 仍可读", adoptedAttachment.data.contentEquals(remoteAttachment))

        session.close()
        assertTrue("活动根库关闭后应按 source owner 清理远端 spill", attachmentStore.entries.isEmpty())

        masterPassword.fill('0')
    }

    @Test
    fun `磁盘解析后活动库消失的早退也清理全部临时spill`() = runBlocking {
        val workDir = tempFolder.newFolder()
        val vaultFile = File(workDir, "drift-close-during-parse.kdbx")
        val attachmentStore = DriftAttachmentStore()
        val session = DatabaseSession(attachmentStore)
        assertTrue(
            "前提：建库必须成功",
            session.create(vaultFile, "Drift", masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        val active = session.databaseFlow.value!!
        val remoteWithAttachment = active.copy(
            rootGroup = KdbxGroup(
                id = UUID_ROOT,
                name = "Root",
                entries = listOf(
                    entry(UUID_REMOTE_ONLY, "RemoteOnly", "RemoteOnly#pwd").copy(
                        attachments = listOf(
                            KdbxAttachment("remote.bin", data = ByteArray(1_200_000) { 0x51 })
                        )
                    )
                )
            )
        )
        val diskBytes = ByteArrayOutputStream().also {
            KdbxFile.save(it, remoteWithAttachment, masterPassword)
        }.toByteArray()
        vaultFile.writeBytes(diskBytes)
        attachmentStore.afterStore = { runBlocking { session.close() } }

        val resolver = VaultFileDriftResolve(session, STRINGS, driftCoordinator = null)
        val result = resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)

        assertTrue("活动库在解析期间关闭后，合并应走无活动库失败分支", result is KdbxResult.Failure)
        assertTrue("finally 必须回收已解析但未采用的附件 spill", attachmentStore.entries.isEmpty())
        masterPassword.fill('0')
    }
}
