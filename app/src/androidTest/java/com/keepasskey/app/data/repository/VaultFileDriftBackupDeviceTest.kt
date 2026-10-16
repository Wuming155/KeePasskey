package com.keepasskey.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * `ISSUE-P1-631`：**File 通道的提交失败与备份重解析**设备侧验收（方案 §4 第 3 行的 File 部分）。
 *
 * 与 SAF 用例（`VaultFileDriftSafDeviceTest`）分工：本条走**内部存储 `File` 通道**——
 * 该通道的写盘是原子替换 + 滚动备份（`.bak`），因此「失败保全」多一项可验证材料：
 * **`.bak` 必须仍是上一成功版本且可被真实解析器重读**。
 *
 * 三条证据链：
 * ① 成功路径：连续两次保存后 `.bak` ＝上一版本（真实 `KdbxFile` 重解析核对，不接受只比大小 / hash）；
 * ② 失败路径：把库所在目录置为不可写（真实 IO 失败，非注入替身），漂移合并必须如实失败——
 *    正式文件逐字节不变、`.bak` 不变且可重读、持久化基线修订不前移、脏树保留；
 * ③ 失败后恢复：目录恢复可写后重试合并保存必须成功，且正式文件与 `.bak` 均可重读。
 */
@RunWith(AndroidJUnit4::class)
class VaultFileDriftBackupDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "P1-631-File-Device-2026".toCharArray()
    private val strings = StringsProvider { id, args -> context.getString(id, *args) }

    private fun uuidOf(hex: String): KdbxUuid =
        KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private val commonId = uuidOf("00000000000000000000000000000051")
    private val diskOnlyId = uuidOf("00000000000000000000000000000052")

    private fun entry(id: KdbxUuid, title: String, value: String): KdbxEntry = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(value, true)
        )
    )

    private fun rootOf(vararg entries: KdbxEntry) =
        KdbxGroup(id = uuidOf("00000000000000000000000000000050"), name = "Root", entries = entries.toList())

    private fun markerOf(bytes: ByteArray): String {
        val db = KdbxFile.load(ByteArrayInputStream(bytes), password.clone())
        return db.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
            .sorted()
            .joinToString(",")
    }

    private fun parseOrNull(file: File): KdbxDatabase? =
        runCatching { KdbxFile.load(FileInputStream(file), password.clone()) }.getOrNull()

    @Test
    fun `File通道_成功路径滚动备份可重解析且失败路径保全`() = runBlocking {
        val dir = File(context.filesDir, "p1-631-file-${System.nanoTime()}").apply { mkdirs() }
        val vaultFile = File(dir, "vault.kdbx")
        val backup = File(dir, "vault.kdbx.bak")
        val session = DatabaseSession()
        try {
            // ① 建库（V1：Common=Base#1）→ 再保存（V2：追加 Disk#1）⇒ .bak 必须＝V1
            assertTrue(
                "建库必须成功",
                session.create(vaultFile, "P1-631 File", password.clone(), useArgon2 = false) is KdbxResult.Success
            )
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(commonId, "Common", "Base#1"))) }
            assertTrue("V1 落盘必须成功", session.save() is KdbxResult.Success)
            val v1Bytes = vaultFile.readBytes()
            session.updateDatabaseMeta {
                it.copy(rootGroup = rootOf(entry(commonId, "Common", "Base#1"), entry(diskOnlyId, "Disk", "Disk#1")))
            }
            assertTrue("V2 落盘必须成功", session.save() is KdbxResult.Success)
            val v2Bytes = vaultFile.readBytes()
            assertTrue("成功保存后必须存在滚动备份", backup.isFile)
            assertTrue("备份与正式文件必须内容不同（备份＝上一版本）", !backup.readBytes().contentEquals(v2Bytes))
            assertEquals("备份必须以真实解析器重读为上一版本（V1）", "Base#1", markerOf(backup.readBytes()))
            assertEquals("正式文件必须以真实解析器重读为当前版本（V2）", "Base#1,Disk#1", markerOf(v2Bytes))

            // ② 漂移 + 写失败：把目录置为不可写（真实 IO 失败）
            val driftDisk = ByteArrayOutputStream().also {
                KdbxFile.save(
                    it,
                    session.databaseFlow.value!!.copy(rootGroup = rootOf(entry(commonId, "Common", "Disk-Rewritten"))),
                    password.clone()
                )
            }.toByteArray()
            vaultFile.writeBytes(driftDisk)
            val revisionBefore = session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
            val backupBefore = backup.readBytes()

            assertTrue("目录必须可置为不可写（设备文件系统前提）", dir.setWritable(false))
            val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
            val resolver = VaultFileDriftResolve(session, strings, coordinator)
            val result = try {
                resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            } finally {
                dir.setWritable(true)
            }

            assertTrue("写失败必须如实失败（不得判成功）: $result", result is KdbxResult.Failure)
            assertArrayEquals("失败路径正式文件必须逐字节不变（外部版本未被覆盖）", driftDisk, vaultFile.readBytes())
            assertArrayEquals("失败路径 .bak 必须保持原样", backupBefore, backup.readBytes())
            assertNotNull("失败路径 .bak 必须仍可被真实解析器重读", parseOrNull(backup))
            assertEquals("失败路径 .bak 内容必须仍是 V1（重解析核对）", "Base#1", markerOf(backup.readBytes()))
            assertArrayEquals("失败路径正式文件必须仍可被真实解析器重读", driftDisk, vaultFile.readBytes())
            assertEquals(
                "失败不得前移持久化基线修订",
                revisionBefore,
                session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
            )
            assertNotNull("失败必须重新唤起漂移提示（脏树保留）", coordinator.pending.value)

            // ③ 恢复可写后重试：必须成功，且正式文件与 .bak 均可重读
            val retried = resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("目录恢复可写后重试必须成功: $retried", retried is KdbxResult.Success)
            val finalMarkers = markerOf(vaultFile.readBytes())
            assertTrue("重试产物必须含外部版本与既有内容（实测=$finalMarkers）", "Disk-Rewritten" in finalMarkers)
            assertNotNull("重试后 .bak 必须仍可重读", parseOrNull(backup))
        } finally {
            runCatching { session.lock() }
            runCatching { session.close() }
            runCatching { dir.setWritable(true) }
            runCatching { dir.deleteRecursively() }
        }
    }
}
