package com.keepasskey.app.data.repository

import com.keepasskey.app.R
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

private const val SCOPE_PWD = KdbxConstants.Fields.PASSWORD
private const val SCOPE_URL = KdbxConstants.Fields.URL
private const val SCOPE_NOTES = KdbxConstants.Fields.NOTES
private const val SCOPE_USER = KdbxConstants.Fields.USER_NAME
private const val SCOPE_TITLE = KdbxConstants.Fields.TITLE

private fun scopeUuid(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val SCOPE_ROOT = scopeUuid("00000000000000000000000000000001")
private val SCOPE_EDITED = scopeUuid("00000000000000000000000000000002")

private val SCOPE_STRINGS = StringsProvider { id, _ ->
    if (id == R.string.ext_mod_merge_duplicate_suffix) "磁盘冲突副本" else ""
}

/**
 * `ISSUE-P1-631`（裁决范围）：同字段双改的裁决**只重裁差异字段**，冲突条目上**非冲突字段的
 * 合并产物必须保持不变**。
 *
 * 缺陷形态（整改前的整条目二选一）：合并器在双方均有修改时已做字段级合并——若冲突条目上
 * 磁盘只改了 `pwd`、本地只改了 `url`，那条目的产物本应同时带上二者；整条目「以本地为准」会把
 * 磁盘对 `url` 的修改一并丢弃（用户并未对该字段做任何取舍）。
 *
 * 覆盖：① 以本地为准不丢磁盘对其余字段的修改；② 以磁盘为准不丢本地对其余字段的修改；
 * ③ 双方保留 = 两份都建在合并产物之上（各自带自己一侧的差异字段版本、副本换新 UUID）；
 * ④ 「删除 vs 修改」形态的差异字段：以本地为准时字段必须删除、以磁盘为准时取磁盘值。
 */
class VaultFileDriftMergePolicyScopeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterPassword = "DriftScope#2026".toCharArray()

    private fun editedEntry(
        password: String?,
        url: String? = null,
        userName: String? = null,
        notes: String? = null
    ): KdbxEntry {
        val fields = linkedMapOf(SCOPE_TITLE to ProtectedString("Edited", false))
        password?.let { fields[SCOPE_PWD] = ProtectedString(it, true) }
        url?.let { fields[SCOPE_URL] = ProtectedString(it, false) }
        userName?.let { fields[SCOPE_USER] = ProtectedString(it, false) }
        notes?.let { fields[SCOPE_NOTES] = ProtectedString(it, false) }
        return KdbxEntry(id = SCOPE_EDITED, fields = fields)
    }

    private fun rootOf(entry: KdbxEntry) = KdbxGroup(id = SCOPE_ROOT, name = "Root", entries = listOf(entry))

    private suspend fun createdSession(): Pair<DatabaseSession, File> {
        val vaultFile = File(tempFolder.newFolder(), "drift-scope.kdbx")
        val session = DatabaseSession()
        assertTrue(
            "前提：建库必须成功",
            session.create(vaultFile, "DriftScope", masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        return session to vaultFile
    }

    private suspend fun persist(session: DatabaseSession, entry: KdbxEntry) {
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry)) }
        assertTrue("前提：落盘必须成功", session.save() is KdbxResult.Success)
    }

    private suspend fun writeDisk(vaultFile: File, session: DatabaseSession, entry: KdbxEntry) {
        val active = session.databaseFlow.value ?: error("活动库不得为空")
        val bytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, active.copy(rootGroup = rootOf(entry)), masterPassword) }
            .toByteArray()
        vaultFile.writeBytes(bytes)
    }

    private fun reparse(vaultFile: File): KdbxDatabase =
        KdbxFile.load(FileInputStream(vaultFile), masterPassword)

    private fun entryOf(db: KdbxDatabase, id: KdbxUuid): KdbxEntry? =
        db.rootGroup.allEntries().firstOrNull { it.id == id }

    private fun coordinatorOf(session: DatabaseSession) =
        VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)

    private fun resolverOf(session: DatabaseSession, coordinator: VaultFileDriftCoordinator) =
        VaultFileDriftResolve(session, SCOPE_STRINGS, coordinator)

    /** 公共前置：base pwd=A/url=U1；磁盘 pwd=C/url=U2；本地 pwd=B/url=U1 */
    private suspend fun conflictingPair(): Triple<DatabaseSession, File, VaultFileDriftCoordinator> {
        val (session, vaultFile) = createdSession()
        persist(session, editedEntry(password = "A", url = "U1"))
        writeDisk(vaultFile, session, editedEntry(password = "C", url = "U2"))
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(editedEntry(password = "B", url = "U1"))) }
        val coordinator = coordinatorOf(session)
        val pending = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("前置：同字段双改必须要求裁决: $pending", pending is KdbxResult.Failure)
        assertNotNull("前置：待裁决提示必须置起", coordinator.mergeConflict.value)
        return Triple(session, vaultFile, coordinator)
    }

    @Test
    fun `以本地为准不得丢掉磁盘对其它字段的就近修改`() = runBlocking {
        val (session, vaultFile, coordinator) = conflictingPair()

        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
        assertTrue("裁决后提交必须成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(vaultFile)
        val entry = entryOf(reparsed, SCOPE_EDITED) ?: error("条目必须存在")
        assertEquals("冲突字段（pwd）取本地版本", "B", entry.fields.getValue(SCOPE_PWD).readString())
        assertEquals(
            "非冲突字段（url）必须保留磁盘修改——不得被整条目替换抹掉",
            "U2",
            entry.fields.getValue(SCOPE_URL).readString()
        )
        session.close()
    }

    @Test
    fun `以磁盘为准不得丢掉本地对其它字段的就近修改`() = runBlocking {
        val (session, vaultFile) = createdSession()
        persist(session, editedEntry(password = "A", url = "U1", userName = "base-user"))
        // 磁盘：pwd=C（冲突字段）+ url=U2（单侧修改）
        writeDisk(vaultFile, session, editedEntry(password = "C", url = "U2", userName = "base-user"))
        // 本地：pwd=B（冲突字段）+ userName=local-user（单侧修改）
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(editedEntry(password = "B", url = "U1", userName = "local-user")))
        }
        val coordinator = coordinatorOf(session)
        val resolver = resolverOf(session, coordinator)
        assertTrue(resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure)

        val result = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_DISK)
        assertTrue("裁决后提交必须成功: $result", result is KdbxResult.Success)

        val entry = entryOf(reparse(vaultFile), SCOPE_EDITED) ?: error("条目必须存在")
        assertEquals("冲突字段（pwd）取磁盘版本", "C", entry.fields.getValue(SCOPE_PWD).readString())
        assertEquals(
            "非冲突字段（userName）必须保留本地修改——不得被整条目替换抹掉",
            "local-user",
            entry.fields.getValue(SCOPE_USER).readString()
        )
        assertEquals("磁盘对 url 的修改照常并入", "U2", entry.fields.getValue(SCOPE_URL).readString())
        session.close()
    }

    @Test
    fun `双方保留 两侧各自带差异字段版本且副本换新UUID`() = runBlocking {
        val (session, vaultFile, coordinator) = conflictingPair()

        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_KEEP_BOTH)
        assertTrue("双方保留必须成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(vaultFile)
        val entries = reparsed.rootGroup.allEntries()
        assertEquals("双方保留必须产出两条条目", 2, entries.size)
        val originals = entries.filter { it.id == SCOPE_EDITED }
        val duplicates = entries.filter { it.id != SCOPE_EDITED }
        assertEquals("原条目不换 UUID", 1, originals.size)
        assertEquals("副本必须换新 UUID（KDBX 要求 UUID 唯一）", 1, duplicates.size)
        val original = originals.single()
        val duplicate = duplicates.single()
        assertEquals("原条目保留本地版本", "B", original.fields.getValue(SCOPE_PWD).readString())
        assertEquals("副本保留磁盘版本", "C", duplicate.fields.getValue(SCOPE_PWD).readString())
        assertEquals(
            "副本标题必须带「磁盘冲突副本」后缀（不得沿用同步侧「云端」口径）",
            "Edited (磁盘冲突副本)",
            duplicate.fields.getValue(SCOPE_TITLE).readString()
        )
        assertEquals(
            "两侧都必须带上非冲突字段的合并产物（url=U2）",
            "U2",
            original.fields.getValue(SCOPE_URL).readString()
        )
        assertEquals("副本同样带非冲突字段合并产物", "U2", duplicate.fields.getValue(SCOPE_URL).readString())
        assertEquals(
            "两份必须落在同一分组内（可对照重解析树直接读取）",
            original.parentGroupId,
            duplicate.parentGroupId
        )
        session.close()
    }

    @Test
    fun `删除与修改形态的差异字段按裁决方向落定`() = runBlocking {
        // 本地：删除 notes；磁盘：把 notes 改为 N2 ⇒ notes 进冲突清单
        run {
            val (session, vaultFile) = createdSession()
            persist(session, editedEntry(password = "A", notes = "N1"))
            writeDisk(vaultFile, session, editedEntry(password = "A", notes = "N2"))
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(editedEntry(password = "A"))) }
            val coordinator = coordinatorOf(session)
            val resolver = resolverOf(session, coordinator)
            assertTrue(
                "前置：删除 vs 修改必须要求裁决",
                resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure
            )
            val result = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
            assertTrue("以本地为准提交必须成功: $result", result is KdbxResult.Success)
            val entry = entryOf(reparse(vaultFile), SCOPE_EDITED) ?: error("条目必须存在")
            assertTrue(
                "以本地为准 = 本地删除了该字段 ⇒ 字段必须删除（不得留下磁盘值）",
                SCOPE_NOTES !in entry.fields
            )
            session.close()
        }
        // 反向：以磁盘为准 ⇒ 字段取磁盘值
        run {
            val (session, vaultFile) = createdSession()
            persist(session, editedEntry(password = "A", notes = "N1"))
            writeDisk(vaultFile, session, editedEntry(password = "A", notes = "N2"))
            session.updateDatabaseMeta { it.copy(rootGroup = rootOf(editedEntry(password = "A"))) }
            val resolver = resolverOf(session, coordinatorOf(session))
            assertTrue(resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure)
            val result = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_DISK)
            assertTrue("以磁盘为准提交必须成功: $result", result is KdbxResult.Success)
            val entry = entryOf(reparse(vaultFile), SCOPE_EDITED) ?: error("条目必须存在")
            assertEquals(
                "以磁盘为准 ⇒ 字段取磁盘值",
                "N2",
                entry.fields.getValue(SCOPE_NOTES).readString()
            )
            session.close()
        }
    }
}
