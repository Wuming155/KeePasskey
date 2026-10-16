package com.keepasskey.app.data.repository

import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant

private const val PWD = KdbxConstants.Fields.PASSWORD
private const val TITLE = KdbxConstants.Fields.TITLE

private fun uuidOf(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val UUID_ROOT = uuidOf("00000000000000000000000000000001")
private val UUID_COMMON = uuidOf("00000000000000000000000000000002")
private val UUID_LOCAL_ONLY = uuidOf("00000000000000000000000000000003")
private val UUID_DISK_ONLY = uuidOf("00000000000000000000000000000004")
private val UUID_EDITED = uuidOf("00000000000000000000000000000005")

private val STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P1-631` 的 app 侧验收：漂移合并必须使用**真实共同基线**（最后确认持久化版本），
 * 且**不得静默做删除裁决 / 不得静默取一侧**。
 *
 * 缺陷形态（本用例的反向反校锚点）：`mergeAndSave` 曾把当前 localDb 同时传作 base 与 local，
 * 合并器据此认为本地从未修改——本地新增条目在磁盘缺失时被推断为远端删除、同 ID 本地编辑
 * 让磁盘版本成为唯一修改，随后采用并保存该结果（静默丢失本地修改）。
 *
 * 验收矩阵（方案 §4）：
 * 1. 基线无 E、本地有 E、磁盘无 E ⇒ 合并后 E 仍在；
 * 2. 本地 B / 磁盘 A 与本地 B / 磁盘 C ⇒ 按真实修改来源保留或要求冲突，不静默回 A；
 * 3. 基线缺失 ⇒ 禁止静默删除裁决（并集语义 + 冲突要求裁决）；
 * 4. 提交失败如实失败、基线不前移、正式文件原样保全；
 * 每例以**重解析正式库**核对（不接受只看内存）。
 */
class VaultFileDriftMergeBaselineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterPassword = "DriftBaseline#2026".toCharArray()

    private fun entry(id: KdbxUuid, title: String, password: String) = KdbxEntry(
        id = id,
        fields = mapOf(
            TITLE to ProtectedString(title, false),
            PWD to ProtectedString(password, true)
        )
    )

    private fun rootOf(vararg entries: KdbxEntry) =
        KdbxGroup(id = UUID_ROOT, name = "Root", entries = entries.toList())

    private suspend fun createdSession(): Pair<DatabaseSession, File> {
        val vaultFile = File(tempFolder.newFolder(), "drift-baseline.kdbx")
        val session = DatabaseSession()
        assertTrue(
            "前提：建库必须成功",
            session.create(vaultFile, "Drift", masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        return session to vaultFile
    }

    private fun coordinatorOf(session: DatabaseSession): VaultFileDriftCoordinator =
        VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)

    private fun resolverOf(session: DatabaseSession, coordinator: VaultFileDriftCoordinator) =
        VaultFileDriftResolve(session, STRINGS, coordinator)

    /** 以当前活动库为底版构造磁盘漂移版本并写入正式文件（模拟外部程序改写） */
    private suspend fun writeDiskVersion(vaultFile: File, session: DatabaseSession, root: KdbxGroup) {
        val active = session.databaseFlow.value ?: error("活动库不得为空")
        val bytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, active.copy(rootGroup = root), masterPassword) }
            .toByteArray()
        vaultFile.writeBytes(bytes)
    }

    private fun reparse(vaultFile: File): KdbxDatabase =
        KdbxFile.load(FileInputStream(vaultFile), masterPassword)

    private fun passwordsOf(db: KdbxDatabase): List<String> =
        db.rootGroup.allEntries().map { it.fields.getValue(PWD).readString() }

    private fun passwordOf(db: KdbxDatabase, id: KdbxUuid): String =
        db.rootGroup.allEntries().first { it.id == id }.fields.getValue(PWD).readString()

    @Test
    fun `验收1 基线无E本地有E磁盘无E 合并后E仍在`() = runBlocking {
        val (session, vaultFile) = createdSession()
        // 先落盘 V1（含 C）建立真实基线
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_COMMON, "Common", "Base#1"))) }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        val v1 = session.databaseFlow.value!!
        val common = v1.rootGroup.entries.first()
        // 磁盘被外部改写：V2 = V1 + D
        writeDiskVersion(vaultFile, session, rootOf(common, entry(UUID_DISK_ONLY, "Disk", "Disk#1")))
        // 本地新增 E（未写盘）
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(common, entry(UUID_LOCAL_ONLY, "Local", "Local#1")))
        }

        val coordinator = coordinatorOf(session)
        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("验收1：合并必须成功: $result", result is KdbxResult.Success)
        assertNull("无同字段分歧时不得要求裁决", coordinator.mergeConflict.value)
        assertEquals(
            "验收1：本地独有条目 E 必须保留（旧实现推断为远端删除而丢弃）",
            "Local#1",
            passwordOf(session.databaseFlow.value!!, UUID_LOCAL_ONLY)
        )
        // 重解析正式库核对（验收要求：以落盘产物为准，不只看内存）
        val reparsed = reparse(vaultFile)
        assertEquals("验收1：重解析后 E 仍在", "Local#1", passwordOf(reparsed, UUID_LOCAL_ONLY))
        assertEquals("验收1：磁盘独有 D 并入", "Disk#1", passwordOf(reparsed, UUID_DISK_ONLY))
        assertEquals("验收1：共有条目 C 不受影响", "Base#1", passwordOf(reparsed, UUID_COMMON))
        session.close()
    }

    @Test
    fun `验收2 本地B磁盘A base为A时保留B不静默回A`() = runBlocking {
        val (session, vaultFile) = createdSession()
        // V1：X(pwd=A) + C
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "A"), entry(UUID_COMMON, "Common", "Base#1")))
        }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        val v1 = session.databaseFlow.value!!
        val common = v1.rootGroup.entries.first { it.id == UUID_COMMON }
        val edited = v1.rootGroup.entries.first { it.id == UUID_EDITED }
        // 磁盘被外部改写：V2 = V1 + D（X 的 pwd 保持 A 未变）
        writeDiskVersion(vaultFile, session, rootOf(edited, common, entry(UUID_DISK_ONLY, "Disk", "Disk#1")))
        // 本地把 X 的 pwd 改为 B（未写盘）
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    edited.withField(PWD, ProtectedString("B", true)),
                    common,
                    entry(UUID_LOCAL_ONLY, "Local", "Local#1")
                )
            )
        }

        val coordinator = coordinatorOf(session)
        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("验收2：本地侧修改、磁盘侧未改 ⇒ 无冲突直接合并成功: $result", result is KdbxResult.Success)
        assertEquals(
            "验收2：本地修改 B 必须保留（旧实现把磁盘旧值 A 判为唯一修改而静默覆盖）",
            "B",
            passwordOf(reparse(vaultFile), UUID_EDITED)
        )
        assertEquals("验收2：磁盘新增 D 照常并入", "Disk#1", passwordOf(reparse(vaultFile), UUID_DISK_ONLY))
        session.close()
    }

    @Test
    fun `验收2 本地B磁盘C 同字段双改要求裁决并以裁决结果提交`() = runBlocking {
        val (session, vaultFile) = createdSession()
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "A"))) }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        // 磁盘：X.pwd = C
        writeDiskVersion(vaultFile, session, rootOf(entry(UUID_EDITED, "Edited", "C")))
        // 本地：X.pwd = B
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "B")))
        }
        val bytesBeforeMerge = vaultFile.readBytes()

        val coordinator = coordinatorOf(session)
        val resolver = resolverOf(session, coordinator)
        val mergeResult = resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)

        assertTrue("验收2：同字段双改不得静默提交（必须要求裁决）", mergeResult is KdbxResult.Failure)
        val conflict = coordinator.mergeConflict.value
        assertNotNull("验收2：必须置起待裁决提示", conflict)
        assertEquals("验收2：待裁决冲突计数", 1, conflict!!.conflictCount)
        assertTrue("验收2：待裁决条目应可辨识（标题前缀）", conflict.entryTitles.any { it.startsWith("Edited") })
        assertArrayEquals("验收2：裁决前不得写盘（正式文件原样）", bytesBeforeMerge, vaultFile.readBytes())

        val keepLocal = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
        assertTrue("验收2：裁决后提交必须成功: $keepLocal", keepLocal is KdbxResult.Success)
        assertNull("验收2：裁决完成必须关闭待裁决提示", coordinator.mergeConflict.value)
        assertEquals("验收2：以本地为准 ⇒ B", "B", passwordOf(reparse(vaultFile), UUID_EDITED))
        session.close()
    }

    @Test
    fun `验收2 同字段双改可以磁盘为准或双方保留`() = runBlocking {
        // 以磁盘为准
        run {
            val (session, vaultFile) = createdSession()
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "A"))) }
            assertTrue(session.save() is KdbxResult.Success)
            writeDiskVersion(vaultFile, session, rootOf(entry(UUID_EDITED, "Edited", "C")))
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "B"))) }
            val resolver = resolverOf(session, coordinatorOf(session))
            assertTrue(resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure)
            val result = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_DISK)
            assertTrue("以磁盘为准必须成功: $result", result is KdbxResult.Success)
            assertEquals("以磁盘为准 ⇒ C", "C", passwordOf(reparse(vaultFile), UUID_EDITED))
            session.close()
        }
        // 双方保留（磁盘侧生成副本，本地原条目保留）
        run {
            val (session, vaultFile) = createdSession()
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "A"))) }
            assertTrue(session.save() is KdbxResult.Success)
            writeDiskVersion(vaultFile, session, rootOf(entry(UUID_EDITED, "Edited", "C")))
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "B"))) }
            val resolver = resolverOf(session, coordinatorOf(session))
            assertTrue(resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure)
            val result = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_BOTH)
            assertTrue("双方保留必须成功: $result", result is KdbxResult.Success)
            val reparsed = reparse(vaultFile)
            val passwords = passwordsOf(reparsed)
            assertTrue("双方保留：本地 B 保留", passwords.contains("B"))
            assertTrue("双方保留：磁盘 C 以副本保留", passwords.contains("C"))
            val duplicateTitle = reparsed.rootGroup.allEntries()
                .first { it.fields.getValue(PWD).readString() == "C" }
                .displayTitle()
            assertTrue("双方保留：磁盘副本应有独立标题（含后缀），实测=$duplicateTitle", duplicateTitle.startsWith("Edited ("))
            session.close()
        }
    }

    @Test
    fun `验收3 基线缺失时并集语义不推断删除`() = runBlocking {
        val (session, vaultFile) = createdSession()
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_COMMON, "Common", "Base#1"))) }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        val v1 = session.databaseFlow.value!!
        val common = v1.rootGroup.entries.first()
        // 模拟「无可信基线」（如打开链未登记 / 已被清除）
        session.clearPersistedBaselineForTesting()
        // 磁盘：V1 + D；本地：V1 + E
        writeDiskVersion(vaultFile, session, rootOf(common, entry(UUID_DISK_ONLY, "Disk", "Disk#1")))
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(common, entry(UUID_LOCAL_ONLY, "Local", "Local#1")))
        }

        val coordinator = coordinatorOf(session)
        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("验收3：无重叠修改时并集合并应直接成功: $result", result is KdbxResult.Success)
        val reparsed = reparse(vaultFile)
        assertEquals("验收3：本地新增 E 不得被推断为远端删除", "Local#1", passwordOf(reparsed, UUID_LOCAL_ONLY))
        assertEquals("验收3：磁盘新增 D 保留", "Disk#1", passwordOf(reparsed, UUID_DISK_ONLY))
        session.close()
    }

    @Test
    fun `验收3 基线缺失且同字段分叉时要求裁决`() = runBlocking {
        val (session, vaultFile) = createdSession()
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "A"))) }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        session.clearPersistedBaselineForTesting()
        writeDiskVersion(vaultFile, session, rootOf(entry(UUID_EDITED, "Edited", "C")))
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "B"))) }
        val bytesBeforeMerge = vaultFile.readBytes()

        val coordinator = coordinatorOf(session)
        val resolver = resolverOf(session, coordinator)
        assertTrue(
            "验收3：无可信基线且同字段分叉 ⇒ 必须要求裁决",
            resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure
        )
        assertNotNull("验收3：待裁决提示必须置起", coordinator.mergeConflict.value)
        assertArrayEquals("验收3：裁决前不得写盘", bytesBeforeMerge, vaultFile.readBytes())
        assertTrue(
            "验收3：裁决后可提交",
            resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL) is KdbxResult.Success
        )
        assertEquals("验收3：以本地为准 ⇒ B", "B", passwordOf(reparse(vaultFile), UUID_EDITED))
        session.close()
    }

    @Test
    fun `验收4 提交失败不判成功 基线不前移 正式文件保全`() = runBlocking {
        val vaultFile = File(tempFolder.newFolder(), "drift-write-fail.kdbx")
        val seed = DatabaseSession()
        assertTrue(seed.create(vaultFile, "Drift", masterPassword, useArgon2 = false) is KdbxResult.Success)
        seed.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_COMMON, "Common", "Base#1"))) }
        assertTrue(seed.save() is KdbxResult.Success)
        val v1 = seed.databaseFlow.value!!
        val common = v1.rootGroup.entries.first()
        // 磁盘上放 V2（外部改写）——必须在 seed 关闭（树被清零）之前序列化
        val diskBytes = ByteArrayOutputStream()
            .also {
                KdbxFile.save(
                    it,
                    v1.copy(rootGroup = rootOf(common, entry(UUID_DISK_ONLY, "Disk", "Disk#1"))),
                    masterPassword
                )
            }
            .toByteArray()
        seed.close()
        vaultFile.writeBytes(diskBytes)
        val session = DatabaseSession()
        assertTrue(
            "前提：打开必须成功",
            session.openStream(
                pathIdentifier = vaultFile.absolutePath,
                inputStreamProvider = { FileInputStream(vaultFile) },
                saveWriter = { throw IOException("disk full") },
                passwordChars = masterPassword,
                associatedFile = vaultFile
            ) is KdbxResult.Success
        )
        val baselineBefore = session.persistedBaselineSnapshot()!!.also { it.wipe() }
        // 本地新增 E（注意：seed 会话已关闭，其树实例已清零，不得复用 `common` 实例）
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    entry(UUID_COMMON, "Common", "Base#1"),
                    entry(UUID_LOCAL_ONLY, "Local", "Local#1")
                )
            )
        }

        val coordinator = coordinatorOf(session)
        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)

        assertTrue("验收4：落盘失败必须如实失败（不得判成功）", result is KdbxResult.Failure)
        assertNotNull("验收4：失败必须重新唤起漂移提示（保留脏树）", coordinator.pending.value)
        val baselineAfter = session.persistedBaselineSnapshot()!!.also { it.wipe() }
        assertEquals("验收4：提交失败基线不得前移", baselineBefore.revision, baselineAfter.revision)
        assertArrayEquals("验收4：正式文件必须原样保全（磁盘版本未被覆盖）", diskBytes, vaultFile.readBytes())
        session.close()
    }

    @Test
    fun `删除与修改组合 磁盘墓碑但本地修改更晚时本地版本复活`() = runBlocking {
        val (session, vaultFile) = createdSession()
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "A"))) }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        // 磁盘：X 被删除（带墓碑）+ D
        val deletionTime = Instant.now().minusSeconds(60)
        val diskRoot = rootOf(entry(UUID_DISK_ONLY, "Disk", "Disk#1"))
        val diskBytes = ByteArrayOutputStream().also {
            KdbxFile.save(
                it,
                session.databaseFlow.value!!.copy(
                    rootGroup = diskRoot,
                    deletedObjects = listOf(DeletedObject(UUID_EDITED, deletionTime))
                ),
                masterPassword
            )
        }.toByteArray()
        vaultFile.writeBytes(diskBytes)
        // 本地：X 修改为 B（修改时间晚于墓碑删除时刻）
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(entry(UUID_EDITED, "Edited", "B")))
        }

        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("删除/修改组合必须合并成功: $result", result is KdbxResult.Success)
        val reparsed = reparse(vaultFile)
        assertEquals(
            "删除后重建口径：本地修改晚于墓碑 ⇒ 本地版本复活",
            "B",
            passwordOf(reparsed, UUID_EDITED)
        )
        assertTrue(
            "复活条目的墓碑必须被清除",
            reparsed.deletedObjects.none { it.id == UUID_EDITED }
        )
        assertEquals("磁盘新增 D 保留", "Disk#1", passwordOf(reparsed, UUID_DISK_ONLY))
        session.close()
    }
}
