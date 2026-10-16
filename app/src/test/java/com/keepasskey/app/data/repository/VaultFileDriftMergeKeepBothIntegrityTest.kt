package com.keepasskey.app.data.repository

import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val KEEP_PWD_FIELD = KdbxConstants.Fields.PASSWORD
private const val KEEP_REF_FIELD = "{REF:@T@KeepMe#1}"

private fun keepUuid(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val KEEP_ROOT = keepUuid("00000000000000000000000000000001")
private val KEEP_GROUP = keepUuid("00000000000000000000000000000002")
private val KEEP_ENTRY = keepUuid("00000000000000000000000000000003")
private val KEEP_DEAD_ENTRY = keepUuid("00000000000000000000000000000004")
private val KEEP_ICON = keepUuid("00000000000000000000000000000005")

private val KEEP_STRINGS = StringsProvider { _, _ -> "" }

/** 历史快照的固定时间（使三方 history 并集在 `distinctBy(lastModificationTime)` 后收敛为一条） */
private val FIXED_SNAPSHOT_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** 「磁盘删除 / 本地未改动」场景里被删条目的固定时间戳（使 isModified 判定确定） */
private val FIXED_DEAD_AT: Instant = Instant.parse("2026-02-01T00:00:00Z")

/**
 * `ISSUE-P1-631`：**`KEEP_BOTH`（双方保留）引用完整性**专项验证（方案第 9 项）。
 *
 * 只检查「标题带后缀」远远不够：复制一条条目会牵动 UUID、父子归属、history、附件 / 二进制、
 * 自定义图标引用、AutoType、字段引用文本与墓碑。本件以**真实落盘 + 重解析**逐项核对，
 * 判据是「重开后的库内容正确」，不是「内存对象看着对」。
 *
 * 覆盖：Entry UUID 唯一 / 同一父组 / history 随父条目换 UUID / 附件字节随行 /
 * customIconId 不悬空 / AutoType 保留 / `{REF:…}` 文本逐字保留（并**明确声明不重映射**）/
 * 不给复制体造墓碑 / 两侧新增同 UUID 异内容必须各自换 UUID 且内容不互相污染。
 */
class VaultFileDriftMergeKeepBothIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterPassword = "DriftKeepBoth#2026".toCharArray()
    private val attachmentBytes = "ATTACHMENT-PAYLOAD-#2026".toByteArray()

    private fun entry(
        id: KdbxUuid,
        title: String,
        password: String,
        parent: KdbxUuid = KEEP_GROUP,
        withExoticPayload: Boolean = false,
        /**
         * 固定时间戳。默认 `KdbxTimes()` 每次构造取不同「当前时刻」，会让「本地未改动」判定
         * （`KdbxEntryMerger.isModified` 含 `lastModificationTime` 比较）变成 true，
         * 从而把「磁盘删除 / 本地未改动」的墓碑语义测成「修改方胜的复活」——属装置噪声，必须固定。
         */
        at: Instant? = null
    ) = KdbxEntry(
        id = id,
        parentGroupId = parent,
        times = if (at != null) KdbxTimes(creationTime = at, lastModificationTime = at) else KdbxTimes(),
        customIconId = if (withExoticPayload) KEEP_ICON else null,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KEEP_PWD_FIELD to ProtectedString(password, true)
        ),
        customFields = if (withExoticPayload) {
            listOf(KdbxCustomField("Reference", ProtectedString(KEEP_REF_FIELD, isProtected = false)))
        } else {
            emptyList()
        },
        history = if (withExoticPayload) {
            listOf(
                KdbxEntry(
                    id = id,
                    parentGroupId = parent,
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString(title, false),
                        KEEP_PWD_FIELD to ProtectedString("HistorySnapshot#1", true)
                    ),
                    // 固定时间：合并器按 `lastModificationTime` 去重三方 history
                    // （`local + remote + base` 并集后 distinctBy），默认 `KdbxTimes()` 每次构造
                    // 取不同「当前时刻」⇒ 同一条快照会因时间不同而并成多条，掩盖断言意图
                    times = KdbxTimes(creationTime = FIXED_SNAPSHOT_AT, lastModificationTime = FIXED_SNAPSHOT_AT)
                )
            )
        } else {
            emptyList()
        },
        attachments = if (withExoticPayload) {
            listOf(KdbxAttachment(name = "payload.bin", refIndex = 0, data = attachmentBytes))
        } else {
            emptyList()
        },
        autoType = if (withExoticPayload) {
            KdbxAutoType(
                enabled = true,
                defaultSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}",
                associations = listOf(KdbxAutoType.AutoTypeAssociation("KeepMe*", "{PASSWORD}{ENTER}"))
            )
        } else {
            null
        }
    )

    private fun root(entries: List<KdbxEntry>) = KdbxGroup(
        id = KEEP_ROOT,
        name = "Root",
        subgroups = listOf(KdbxGroup(id = KEEP_GROUP, name = "Group", parentGroupId = KEEP_ROOT, entries = entries))
    )

    private suspend fun seed(): Pair<DatabaseSession, File> {
        val file = File(tempFolder.newFolder(), "keepboth.kdbx")
        val session = DatabaseSession()
        assertTrue(
            "前提：建库成功",
            session.create(file, "KeepBoth", masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = root(
                    listOf(
                        entry(KEEP_ENTRY, "KeepMe", "Base#1", withExoticPayload = true),
                        entry(KEEP_DEAD_ENTRY, "Dead", "Dead#1", at = FIXED_DEAD_AT)
                    )
                ),
                customIcons = listOf(CustomIcon(uuid = KEEP_ICON, data = byteArrayOf(1, 2, 3)))
            )
        }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        return session to file
    }

    private suspend fun writeDisk(file: File, session: DatabaseSession, db: KdbxDatabase) {
        val active = session.databaseFlow.value ?: error("活动库不得为空")
        val bytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, db.copy(header = active.header), masterPassword) }
            .toByteArray()
        file.writeBytes(bytes)
    }

    private fun reparse(file: File): KdbxDatabase = KdbxFile.load(FileInputStream(file), masterPassword)

    private fun resolve(session: DatabaseSession): VaultFileDriftResolve {
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        return VaultFileDriftResolve(session, KEEP_STRINGS, coordinator)
    }

    private fun pwdOf(entry: KdbxEntry): String =
        entry.fields.getValue(KEEP_PWD_FIELD).readString()

    @Test
    fun `双方保留后条目UUID history 附件 图标 AutoType 引用 父子关系与墓碑全部正确`() = runBlocking {
        val (session, file) = seed()
        // 磁盘：共享条目改口令（外部改写）+ **删除** Dead 条目（留墓碑、树中不再存在）
        writeDisk(
            file,
            session,
            (session.databaseFlow.value!!).copy(
                rootGroup = root(listOf(entry(KEEP_ENTRY, "KeepMe", "Disk#1", withExoticPayload = true))),
                deletedObjects = listOf(DeletedObject(id = KEEP_DEAD_ENTRY, deletionTime = Instant.now()))
            )
        )
        // 本地：共享条目改成另一口令（同字段双侧异值 ⇒ 必须裁决）；Dead 条目本地未改动
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = root(
                    listOf(
                        entry(KEEP_ENTRY, "KeepMe", "Local#1", withExoticPayload = true),
                        entry(KEEP_DEAD_ENTRY, "Dead", "Dead#1", at = FIXED_DEAD_AT)
                    )
                )
            )
        }

        val blocked = resolve(session).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("前提：同字段双侧异值必须先阻断待裁决: $blocked", blocked is KdbxResult.Failure)

        val result = resolve(session).resolve(ExternalModificationChoice.MERGE_KEEP_BOTH)
        assertTrue("双方保留必须成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(file)
        val all = reparsed.rootGroup.allEntries()
        val keepMe = all.filter { it.title == "KeepMe" || it.title.startsWith("KeepMe (") }
        assertEquals("必须保留两条（本地版本 + 磁盘冲突副本）", 2, keepMe.size)

        val localSide = keepMe.single { it.title == "KeepMe" }
        val duplicate = keepMe.single { it.title.startsWith("KeepMe (") }
        assertEquals("本地侧必须是本地口令", "Local#1", pwdOf(localSide))
        assertEquals("副本必须是磁盘口令", "Disk#1", pwdOf(duplicate))

        // ① Entry UUID：副本必须换新，且全库无重复
        assertTrue("副本 UUID 必须与原件不同", duplicate.id != localSide.id)
        val entryIds = all.map { it.id.toHexString() }
        assertEquals("全库不得出现重复条目 UUID（实测=$entryIds）", entryIds.size, entryIds.toSet().size)

        // ② 父子关系：两侧必须同属原分组
        assertEquals("副本必须留在原分组", KEEP_GROUP, duplicate.parentGroupId)
        assertEquals("本地侧必须留在原分组", KEEP_GROUP, localSide.parentGroupId)

        // ③ history：副本的 history 必须保留，且其 UUID 跟随新条目（KDBX 语义）
        assertTrue("副本 history 必须保留（实测=${duplicate.history.size} 条）", duplicate.history.isNotEmpty())
        assertTrue(
            "**每条** history 的 UUID 必须等于父条目 UUID（KDBX 规范；换 UUID 时 history 必须同批跟随）",
            duplicate.history.all { it.id.toHexString() == duplicate.id.toHexString() }
        )
        assertTrue(
            "history 快照内容必须原样保留（并集去重后仍包含原始快照）",
            duplicate.history.any {
                it.fields.getValue(KEEP_PWD_FIELD).readString() == "HistorySnapshot#1"
            }
        )

        // ④ 附件 / 二进制：副本必须带着同一份字节，重解析后仍可读回
        assertEquals("副本必须保留附件", 1, duplicate.attachments.size)
        assertArrayEquals(
            "副本附件字节必须与原件一致（重解析后逐字节核对）",
            attachmentBytes,
            duplicate.attachments.single().data
        )
        assertArrayEquals(
            "本地侧附件字节必须未被污染",
            attachmentBytes,
            localSide.attachments.single().data
        )

        // ⑤ 自定义图标：引用不得悬空（池里必须有该图标）
        assertEquals("副本必须保留 customIconId", KEEP_ICON, duplicate.customIconId)
        assertNotNull(
            "customIconId 不得悬空——图标池必须仍含该图标",
            reparsed.customIcons.firstOrNull { it.uuid == KEEP_ICON }
        )

        // ⑥ AutoType：必须随副本保留
        assertNotNull("副本必须保留 AutoType", duplicate.autoType)
        assertEquals(
            "AutoType 关联必须逐项保留",
            listOf(KdbxAutoType.AutoTypeAssociation("KeepMe*", "{PASSWORD}{ENTER}")),
            duplicate.autoType!!.associations
        )

        // ⑦ 字段引用文本：逐字保留；**如实声明不重映射**（引用文本是用户数据，不是结构引用）
        assertEquals(
            "字段引用文本必须逐字保留（本项不重映射引用目标，见 KDoc）",
            KEEP_REF_FIELD,
            duplicate.customFields.single { it.key == "Reference" }.value.readString()
        )

        // ⑧ 墓碑：不得为复制体（新 UUID）造墓碑；磁盘删除→本地未改动的删除必须确认且墓碑保留
        val tombstones = reparsed.deletedObjects.map { it.id.toHexString() }
        assertTrue("不得为复制体新 UUID 造墓碑", duplicate.id.toHexString() !in tombstones)
        assertTrue(
            "磁盘删除、本地未改动的条目必须确认删除并保留墓碑（实测=$tombstones）",
            KEEP_DEAD_ENTRY.toHexString() in tombstones
        )
        assertTrue(
            "被墓碑标记的条目不得作为存活对象出现（不得复活）",
            all.none { it.id.toHexString() == KEEP_DEAD_ENTRY.toHexString() }
        )
        session.close()
    }

    @Test
    fun `两侧新增同UUID异内容 不得共享UUID也不得互相污染`() = runBlocking {
        val (session, file) = seed()
        // 磁盘侧新增（base 无）：UUID_SIDE 内容 = Disk
        writeDisk(
            file,
            session,
            (session.databaseFlow.value!!).copy(
                rootGroup = root(
                    listOf(
                        entry(KEEP_ENTRY, "KeepMe", "Disk#1", withExoticPayload = true),
                        entry(KEEP_DEAD_ENTRY, "SameUuid", "DiskSide")
                    )
                )
            )
        )
        // 本地侧新增（base 亦无）：**同一 UUID** 内容 = Local
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = root(
                    listOf(
                        entry(KEEP_ENTRY, "KeepMe", "Local#1", withExoticPayload = true),
                        entry(KEEP_DEAD_ENTRY, "SameUuid", "LocalSide")
                    )
                )
            )
        }

        val result = resolve(session).resolve(ExternalModificationChoice.MERGE_KEEP_BOTH)
        assertTrue("同 UUID 异内容在双方保留下必须成功: $result", result is KdbxResult.Success)

        val all = reparse(file).rootGroup.allEntries()
        val sameUuid = all.filter { it.title.startsWith("SameUuid") }
        assertEquals("两个版本都必须保留", 2, sameUuid.size)
        assertEquals(
            "内容必须各自原样（不得互相污染）",
            setOf("DiskSide", "LocalSide"),
            sameUuid.map { pwdOf(it) }.toSet()
        )
        val ids = all.map { it.id.toHexString() }
        assertEquals("两条必须使用不同 UUID（实测=$ids）", ids.size, ids.toSet().size)
        assertTrue(
            "至少一条必须换新 UUID（原件 UUID 不可被两条共用）",
            sameUuid.map { it.id.toHexString() }.toSet().size == 2
        )
        session.close()
    }
}
