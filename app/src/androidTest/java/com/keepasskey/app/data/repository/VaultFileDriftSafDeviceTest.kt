package com.keepasskey.app.data.repository

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.di.DriftDeviceGraph
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * `ISSUE-P1-631`：**SAF（`content://`）端到端验收**——真实 `DocumentsProvider`（594 夹具，
 * 真 Binder / 真文件描述符）、真实 SAF Uri、真实生产单例图（`RealVaultRepository` +
 * 生产 `DatabaseSession` + 生产 `VaultFileDriftCoordinator`）。
 *
 * 流程与断言（§4 矩阵第 1/2/3 行的 SAF 部分）：
 * - 打开基线库（`importExternalDatabase` 登记 → `unlockActiveDatabase` 经 `content://` 打开）→
 *   本地新增条目 → 外部改写文档 → 保存被中止且提示置起 → `MERGE_AND_SAVE` → 保存；
 * - 以 **provider 侧真实文档字节**重解析核对（不是看内存、不是看返回值）；
 * - 同字段双改 ⇒ 进入裁决且裁决前不写盘；`KEEP_LOCAL` / `KEEP_DISK` / `KEEP_BOTH` 三种裁决
 *   各自落盘并重解析；
 * - 写失败对照（`OPEN_FAIL` / `PARTIAL_WRITE`）⇒ 如实失败、脏树保留、基线不前移、正式文档
 *   可重读；失败后再保存可修复文档（非原子通道的既有边界见限界 §24）；
 * - 收尾以 `lockDatabase` + 重新 `unlockActiveDatabase` 重开正式库核对（不是只看会话内存）。
 */
@RunWith(AndroidJUnit4::class)
class VaultFileDriftSafDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "P1-631-Saf-Device-2026".toCharArray()

    private val repository get() =
        EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)
            .vaultRepository()
    private val session get() =
        EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)
            .databaseSession()
    private val coordinator get() =
        EntryPointAccessors.fromApplication(context.applicationContext, DriftDeviceGraph::class.java)
            .vaultFileDriftCoordinator()

    private fun uuidOf(hex: String): KdbxUuid =
        KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private fun entry(id: KdbxUuid, title: String, value: String): KdbxEntry = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(value, true)
        )
    )

    /** 真实 KDBX 字节（可被生产解析链读取） */
    private fun kdbxOf(vararg entries: KdbxEntry): ByteArray {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = entries.toList())
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
    }

    private fun writeDoc(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "rwt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
        } ?: error("无法写入 SAF 文档")
    }

    private fun docBytes(client: SafCreationProbeClient, uri: Uri): ByteArray =
        requireNotNull(client.state(uri).getByteArray("bytes")) { "SAF 文档字节不可读" }

    private fun parse(bytes: ByteArray): KdbxDatabase =
        KdbxFile.load(ByteArrayInputStream(bytes), password.clone())

    private fun passwordsOf(db: KdbxDatabase): List<String> =
        db.rootGroup.allEntries().mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }

    private class Fixture(
        val client: SafCreationProbeClient,
        val uri: Uri
    ) {
        fun cleanup(context: android.content.Context) {
            try {
                val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                if (permission != null) {
                    var flags = 0
                    if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.releasePersistableUriPermission(uri, flags)
                }
            } finally {
                client.cleanupAndEnd()
            }
        }
    }

    /** 登记 SAF 库为产品「活动库」（走真实登记路径：目录 + 活动标记 + 列表刷新） */
    private suspend fun registerAndUnlock(name: String, uri: Uri) {
        val imported = repository.importExternalDatabase(name, uri.toString())
        assertTrue("SAF 库登记失败: $imported", imported is KdbxResult.Success)
        val unlocked = repository.unlockActiveDatabase(password.clone(), null, readOnly = false)
        assertTrue("SAF 库打开失败: $unlocked", unlocked is KdbxResult.Success)
        assertEquals(
            "打开必须走真实 SAF 通道（会话路径标识＝content:// Uri）",
            uri.toString(),
            session.currentPathIdentifier
        )
    }

    /** 收尾：按 **Uri** 注销测试库登记（避免污染产品目录；名称经过 sanizite 故不能按名匹配） */
    private suspend fun unregisterCatalog(uri: Uri) {
        val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
        val remaining = catalog.loadKnownDatabases().filterNot { it.path == uri.toString() }
        catalog.saveKnownDatabases(remaining)
    }

    private fun newUiEntry(hex: String, title: String, url: String? = null) = UiVaultEntry(
        id = hex,
        title = title,
        username = "device-user",
        url = url ?: "https://device.invalid"
    )

    @Test
    fun `SAF端到端_合并保留本地新增并重开可见`() = runBlocking {
        val client = SafCreationProbeClient()
        val name = "p1-631-saf-merge-${System.nanoTime()}"
        val uri = client.create("$name.kdbx")
        val commonId = uuidOf("00000000000000000000000000000021")
        val diskOnlyId = uuidOf("00000000000000000000000000000022")
        val localOnlyHex = "00000000000000000000000000000023"
        try {
            // 基线库 V1（含共有条目 C）写入真实 SAF 文档
            val v1 = kdbxOf(entry(commonId, "Common", "Base#1"))
            writeDoc(uri, v1)
            registerAndUnlock(name, uri)

            // 外部改写：V2 = C + 磁盘独有条目 D（大小变化 ⇒ 漂移可判）
            writeDoc(uri, kdbxOf(entry(commonId, "Common", "Base#1"), entry(diskOnlyId, "Disk", "Disk#1")))
            val v2Bytes = docBytes(client, uri)

            // 本地新增 E（产品编辑入口）⇒ 保存被漂移中止且提示置起（不得写盘）
            val saveResult = repository.saveEntry(
                newUiEntry(localOnlyHex, "Local E"),
                passwordChars = "Local#1".toCharArray()
            )
            assertTrue("漂移下保存必须如实失败（不得判成功）: $saveResult", saveResult is KdbxResult.Failure)
            assertNotNull("漂移必须置起三选提示", coordinator.pending.value)
            assertTrue(
                "漂移中止时不得写盘（SAF 文档仍为外部版本）",
                docBytes(client, uri).contentEquals(v2Bytes)
            )

            // 合并后保存（真实 SAF 通道写回）
            val merged = repository.applyExternalModificationChoice(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("合并必须成功: $merged", merged is KdbxResult.Success)
            assertNull("无同字段分歧时不得要求裁决", coordinator.mergeConflict.value)

            // 以 provider 侧真实文档字节重解析核对
            val reparsed = parse(docBytes(client, uri))
            val passwords = passwordsOf(reparsed)
            assertTrue("本地新增 E 必须保留（旧实现会推断为远端删除）", "Local#1" in passwords)
            assertTrue("磁盘独有条目 D 必须并入", "Disk#1" in passwords)
            assertTrue("共有条目 C 不受影响", "Base#1" in passwords)

            // 重新打开正式库核对（不是只看内存）
            repository.lockDatabase()
            val reopened = repository.unlockActiveDatabase(password.clone(), null, readOnly = false)
            assertTrue("重开正式 SAF 库必须成功: $reopened", reopened is KdbxResult.Success)
            val reopenedPasswords = session.databaseFlow.value?.rootGroup?.allEntries()
                ?.mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
                .orEmpty()
            assertTrue("重开后 E 仍在: $reopenedPasswords", "Local#1" in reopenedPasswords)
            assertTrue("重开后 D 仍在", "Disk#1" in reopenedPasswords)
        } finally {
            runCatching { session.lock() }
            runCatching { unregisterCatalog(uri) }
            Fixture(client, uri).cleanup(context)
        }
    }

    @Test
    fun `SAF端到端_同字段双改进入裁决且三种裁决各自落盘`() = runBlocking {
        val editedHex = "00000000000000000000000000000031"
        val editedId = uuidOf(editedHex)
        // 磁盘独有条目：为外部版本制造**尺寸差异**——本探针不暴露 last_modified（mtime 恒 0），
        // 漂移判据退化为 size（与 `VaultFileMetadataProbe` 的「缺列按 0」口径一致，限界 §38/§39）。
        val diskExtraId = uuidOf("00000000000000000000000000000032")
        for (policy in listOf(
            ExternalModificationChoice.MERGE_KEEP_LOCAL,
            ExternalModificationChoice.MERGE_KEEP_DISK,
            ExternalModificationChoice.MERGE_KEEP_BOTH
        )) {
            val client = SafCreationProbeClient()
            val name = "p1-631-saf-$policy-${System.nanoTime()}"
            val uri = client.create("$name.kdbx")
            try {
                writeDoc(uri, kdbxOf(entry(editedId, "Edited", "A")))
                registerAndUnlock(name, uri)
                // 外部：同一条目 pwd=C + 磁盘独有条目（尺寸差异保证漂移可判）
                writeDoc(uri, kdbxOf(entry(editedId, "Edited", "C"), entry(diskExtraId, "Disk Extra", "Disk#1")))
                val beforeDecision = docBytes(client, uri)
                // 本地：同一条目 pwd=B
                val localEdit = repository.saveEntry(
                    newUiEntry(editedHex, "Edited"),
                    passwordChars = "B".toCharArray()
                )
                assertTrue(
                    "漂移下本地保存必须被中止（policy=$policy）: $localEdit",
                    localEdit is KdbxResult.Failure
                )

                val mergeAttempt = repository.applyExternalModificationChoice(ExternalModificationChoice.MERGE_AND_SAVE)
                assertTrue("同字段双改不得静默提交: $mergeAttempt", mergeAttempt is KdbxResult.Failure)
                val conflict = coordinator.mergeConflict.value
                assertNotNull("必须置起待裁决提示", conflict)
                assertTrue("待裁决计数必须为 1", conflict!!.conflictCount == 1)
                assertTrue(
                    "裁决前不得写盘（SAF 文档仍为外部版本）",
                    docBytes(client, uri).contentEquals(beforeDecision)
                )

                val decided = repository.applyExternalModificationChoice(policy)
                assertTrue("裁决 $policy 必须落盘成功: $decided", decided is KdbxResult.Success)
                assertNull("裁决完成必须关闭待裁决提示", coordinator.mergeConflict.value)

                val reparsed = parse(docBytes(client, uri))
                val values = passwordsOf(reparsed)
                when (policy) {
                    ExternalModificationChoice.MERGE_KEEP_LOCAL -> {
                        assertTrue("以本地为准 ⇒ B（实测=$values）", "B" in values)
                        assertTrue("不得同时保留磁盘值", "C" !in values)
                    }
                    ExternalModificationChoice.MERGE_KEEP_DISK -> {
                        assertTrue("以磁盘为准 ⇒ C（实测=$values）", "C" in values)
                        assertTrue("不得同时保留本地值", "B" !in values)
                    }
                    else -> {
                        assertTrue("双方保留 ⇒ B 与 C 同时在库（实测=$values）", "B" in values && "C" in values)
                        val duplicateTitle = reparsed.rootGroup.allEntries()
                            .first { it.fields.getValue(KdbxConstants.Fields.PASSWORD).readString() == "C" }
                            .fields.getValue(KdbxConstants.Fields.TITLE).readString()
                        assertTrue(
                            "磁盘侧副本标题须带后缀（实测=$duplicateTitle）",
                            duplicateTitle.contains("磁盘冲突副本")
                        )
                    }
                }
            } finally {
                runCatching { session.lock() }
                runCatching { unregisterCatalog(uri) }
                Fixture(client, uri).cleanup(context)
            }
        }
    }

    @Test
    fun `SAF端到端_写故障如实失败且文档可重读并可再保存修复`() = runBlocking {
        val client = SafCreationProbeClient()
        val name = "p1-631-saf-fault-${System.nanoTime()}"
        val uri = client.create("$name.kdbx")
        val commonId = uuidOf("00000000000000000000000000000041")
        val localOnlyHex = "00000000000000000000000000000042"
        try {
            val v1 = kdbxOf(entry(commonId, "Common", "Base#1"))
            writeDoc(uri, v1)
            registerAndUnlock(name, uri)

            // ① OPEN_FAIL：正式文件必须逐字节原样、保存如实失败、基线不前移
            val baselineBefore = session.persistedBaselineSnapshot() ?: error("基线必须已登记")
            val revisionBefore = baselineBefore.revision
            baselineBefore.wipe()
            client.setFault(uri, "OPEN_FAIL")
            val failed = repository.saveEntry(
                newUiEntry(localOnlyHex, "Local Fault"),
                passwordChars = "Fault#1".toCharArray()
            )
            assertTrue("打开失败注入下保存必须如实失败: $failed", failed is KdbxResult.Failure)
            assertTrue("正式文件必须原样保全（未被半写覆盖）", docBytes(client, uri).contentEquals(v1))
            val baselineAfterFail = session.persistedBaselineSnapshot() ?: error("旧基线必须保持")
            try {
                assertEquals("写失败不得前移持久化基线修订", revisionBefore, baselineAfterFail.revision)
            } finally {
                baselineAfterFail.wipe()
            }
            assertTrue(
                "失败后脏树必须保留（本地新增仍在内存待处置）",
                session.databaseFlow.value?.rootGroup?.allEntries()
                    ?.any { it.fields[KdbxConstants.Fields.TITLE]?.readString() == "Local Fault" } == true
            )

            // ①b 恢复：故障清除后重试保存必须成功（干净失败可自重试修复），且文档可重读
            client.setFault(uri, "NONE")
            val retried = repository.saveEntry(
                newUiEntry(localOnlyHex, "Local Fault"),
                passwordChars = "Fault#1".toCharArray()
            )
            assertTrue("故障清除后重试保存必须成功: $retried", retried is KdbxResult.Success)
            val healed = parse(docBytes(client, uri))
            val healedValues = passwordsOf(healed)
            assertTrue(
                "修复后的正式文档必须可解析且含全部内容（实测=$healedValues）",
                "Base#1" in healedValues && "Fault#1" in healedValues
            )

            // ② PARTIAL_WRITE：非原子通道的部分写可能破坏文档（既有边界 §24）——应用侧只保证如实失败
            val sizeBeforePartial = client.state(uri).getLong("size")
            client.setFault(uri, "PARTIAL_WRITE")
            val partial = repository.saveEntry(
                newUiEntry(localOnlyHex, "Local Fault"),
                passwordChars = "Fault#1".toCharArray()
            )
            assertTrue("部分写故障下保存必须如实失败（不得谎报成功）: $partial", partial is KdbxResult.Failure)
            val damaged = client.state(uri).getLong("size")
            assertTrue(
                "如实记录：部分写故障已截断正式文档（size=$damaged < $sizeBeforePartial）——SAF 无原子写、无 .bak（限界 §24）",
                damaged < sizeBeforePartial
            )
            assertTrue(
                "损坏文档必须不可被真实解析器重读（如实记录）",
                runCatching { parse(docBytes(client, uri)) }.isFailure
            )

            // ③ 损坏后不得静默覆盖：普通保存被漂移防护接手，合并也无法把损坏文档当版本合并
            val blocked = repository.saveEntry(
                newUiEntry(localOnlyHex, "Local Fault"),
                passwordChars = "Fault#1".toCharArray()
            )
            assertTrue("损坏状态必须被漂移防护接手（不得静默覆盖）: $blocked", blocked is KdbxResult.Failure)
            val blockedFailure = blocked as KdbxResult.Failure
            assertEquals(
                "损坏后的保存必须以类型化漂移码中止",
                KdbxError.DRIFT_SAVE_ABORTED,
                blockedFailure.code
            )
            assertNotNull("损坏状态必须置起漂移提示", coordinator.pending.value)
            val cannotMerge = repository.applyExternalModificationChoice(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue(
                "损坏的 SAF 文档不得被静默合并（如实失败，用户可见）: $cannotMerge",
                cannotMerge is KdbxResult.Failure
            )
            assertEquals(
                "失败合并不得改写损坏文档（仍为截断态）",
                damaged,
                client.state(uri).getLong("size")
            )
        } finally {
            runCatching { session.lock() }
            runCatching { unregisterCatalog(uri) }
            Fixture(client, uri).cleanup(context)
        }
    }
}
