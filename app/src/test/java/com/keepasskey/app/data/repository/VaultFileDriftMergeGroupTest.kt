package com.keepasskey.app.data.repository

import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

private const val PWD_FIELD = KdbxConstants.Fields.PASSWORD

private fun uuid(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val GROUP_TEST_ROOT = uuid("00000000000000000000000000000001")
private val GROUP_TEST_SHARED = uuid("00000000000000000000000000000002")
private val GROUP_TEST_LOCAL_ONLY = uuid("00000000000000000000000000000003")
private val GROUP_TEST_DISK_ONLY = uuid("00000000000000000000000000000004")
private val GROUP_TEST_INNER_ENTRY = uuid("00000000000000000000000000000005")
private val GROUP_TEST_COMMON_ENTRY = uuid("00000000000000000000000000000006")

private val GROUP_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P1-631`：**分组（Group）场景**的等价覆盖。
 *
 * 原问题明确「分组也受相同基线错误影响」——基线与合并器分组分支的正确性必须与条目分支
 * 分别取证（条目侧见 [VaultFileDriftMergeBaselineTest]）。本件覆盖：
 * 1. 基线无 / 本地有 / 磁盘无 ⇒ 本地新增分组（及其内新条目）必须保留；
 * 2. 单侧改名 ⇒ 保留修改侧；更名是 `KdbxGroupMerger.mergeGroupsBothModified` 的表内字段；
 * 3. 删除 vs 修改 ⇒ 与条目同源的墓碑时间口径（晚于删除时刻才复活；早于则保持删除）；
 * 4. **同一分组双侧修改 ⇒ 字段级 LWW（无冲突清单）**——分组不进条目冲突清单
 *    （`MergeResult.conflicts` 仅为 `ConflictedEntryPair`），本用例**钉住该边界**并在
 *    实施记录 / 限界中登记；条目侧「同字段双改要求裁决」由条目用例负责；
 * 5. 基线缺失 ⇒ 禁止静默推断删除（单侧分组保留、双侧分组字段合并）。
 *
 * 每例以**重解析正式库**核对（不接受只看内存）。
 */
class VaultFileDriftMergeGroupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterPassword = "DriftGroup#2026".toCharArray()

    private fun entry(
        id: KdbxUuid,
        title: String,
        password: String,
        parent: KdbxUuid? = null
    ) = KdbxEntry(
        id = id,
        parentGroupId = parent,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            PWD_FIELD to ProtectedString(password, true)
        )
    )

    private fun group(
        id: KdbxUuid,
        name: String,
        parent: KdbxUuid = GROUP_TEST_ROOT,
        at: Instant? = null,
        entries: List<KdbxEntry> = emptyList()
    ) = KdbxGroup(
        id = id,
        name = name,
        parentGroupId = parent,
        entries = entries,
        times = if (at != null) {
            KdbxTimes(creationTime = at, lastModificationTime = at)
        } else {
            KdbxTimes()
        }
    )

    private fun rootOf(subgroups: List<KdbxGroup>, entries: List<KdbxEntry> = emptyList()) =
        KdbxGroup(id = GROUP_TEST_ROOT, name = "Root", entries = entries, subgroups = subgroups)

    private suspend fun createdSession(): Pair<DatabaseSession, File> {
        val vaultFile = File(tempFolder.newFolder(), "drift-group.kdbx")
        val session = DatabaseSession()
        assertTrue(
            "前提：建库必须成功",
            session.create(vaultFile, "DriftGroup", masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        return session to vaultFile
    }

    private suspend fun persistRoot(
        session: DatabaseSession,
        root: KdbxGroup,
        deletedObjects: List<DeletedObject> = emptyList()
    ) {
        session.updateDatabaseMeta { it.copy(rootGroup = root, deletedObjects = deletedObjects) }
        assertTrue("前提：落盘必须成功", session.save() is KdbxResult.Success)
    }

    /** 以当前活动库为外壳构造磁盘漂移版本并写入正式文件（模拟外部程序改写） */
    private suspend fun writeDisk(
        vaultFile: File,
        session: DatabaseSession,
        root: KdbxGroup,
        deletedObjects: List<DeletedObject> = emptyList()
    ) {
        val active = session.databaseFlow.value ?: error("活动库不得为空")
        val bytes = ByteArrayOutputStream()
            .also {
                KdbxFile.save(
                    it,
                    active.copy(rootGroup = root, deletedObjects = deletedObjects),
                    masterPassword
                )
            }
            .toByteArray()
        vaultFile.writeBytes(bytes)
    }

    private fun reparse(vaultFile: File): KdbxDatabase =
        KdbxFile.load(FileInputStream(vaultFile), masterPassword)

    private fun coordinatorOf(session: DatabaseSession): VaultFileDriftCoordinator =
        VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)

    private fun resolverOf(session: DatabaseSession, coordinator: VaultFileDriftCoordinator) =
        VaultFileDriftResolve(session, GROUP_TEST_STRINGS, coordinator)

    @Test
    fun `分组1 基线无本地有磁盘无 本地新增分组及其内条目必须保留`() = runBlocking {
        val (session, vaultFile) = createdSession()
        persistRoot(session, rootOf(subgroups = emptyList(), entries = listOf(entry(GROUP_TEST_COMMON_ENTRY, "Common", "Base#1"))))
        // 磁盘改写：加入一个磁盘独有分组（不改其它）
        writeDisk(
            vaultFile,
            session,
            rootOf(subgroups = listOf(group(GROUP_TEST_DISK_ONLY, "Disk Group")), entries = listOf(entry(GROUP_TEST_COMMON_ENTRY, "Common", "Base#1")))
        )
        // 本地新增：新分组 + 组内新条目
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    subgroups = listOf(
                        group(
                            GROUP_TEST_LOCAL_ONLY,
                            "Local Group",
                            entries = listOf(
                                entry(GROUP_TEST_INNER_ENTRY, "Inner", "Inner#1", parent = GROUP_TEST_LOCAL_ONLY)
                            )
                        )
                    ),
                    entries = listOf(entry(GROUP_TEST_COMMON_ENTRY, "Common", "Base#1"))
                )
            )
        }

        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("分组1：合并必须成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(vaultFile)
        val localGroup = reparsed.rootGroup.findGroup(GROUP_TEST_LOCAL_ONLY)
        assertNotNull("分组1：本地新增分组不得被推断为远端删除", localGroup)
        assertNotNull(
            "分组1：新分组内的新条目必须保留且仍归属该组",
            reparsed.rootGroup.findEntry(GROUP_TEST_INNER_ENTRY)?.also {
                assertEquals("分组1：组内条目归属", GROUP_TEST_LOCAL_ONLY, it.parentGroupId)
            }
        )
        assertNotNull("分组1：磁盘独有分组照常并入", reparsed.rootGroup.findGroup(GROUP_TEST_DISK_ONLY))
        session.close()
    }

    @Test
    fun `分组2 单侧改名必须保留修改侧`() = runBlocking {
        val (session, vaultFile) = createdSession()
        persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Original"))))
        // 磁盘：分组未改名，但加入磁盘独有条目（以证明磁盘确有改写、组名保持不变）
        writeDisk(
            vaultFile,
            session,
            rootOf(
                subgroups = listOf(group(GROUP_TEST_SHARED, "Original")),
                entries = listOf(entry(GROUP_TEST_COMMON_ENTRY, "Disk Extra", "Disk#1"))
            )
        )
        // 本地：改名
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Renamed Local"))))
        }

        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("分组2：合并必须成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(vaultFile)
        assertEquals(
            "分组2：本地改名必须保留（不得回退为磁盘旧名）",
            "Renamed Local",
            reparsed.rootGroup.findGroup(GROUP_TEST_SHARED)?.name
        )
        assertNotNull("分组2：磁盘新增条目照常并入", reparsed.rootGroup.findEntry(GROUP_TEST_COMMON_ENTRY))
        session.close()
    }

    @Test
    fun `分组3 删除与修改组合按墓碑时刻裁决双向`() = runBlocking {
        // 方向一：本地删除（带墓碑）晚于磁盘改名 ⇒ 删除生效
        run {
            val (session, vaultFile) = createdSession()
            val deletionMoment = Instant.now()
            val diskModifiedAt = deletionMoment.minusSeconds(120)
            persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Original"))))
            writeDisk(
                vaultFile,
                session,
                rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Disk Renamed", at = diskModifiedAt)))
            )
            session.updateDatabaseMeta {
                it.copy(
                    rootGroup = rootOf(subgroups = emptyList()),
                    deletedObjects = listOf(DeletedObject(GROUP_TEST_SHARED, deletionMoment))
                )
            }

            val result = resolverOf(session, coordinatorOf(session))
                .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("分组3：合并必须成功: $result", result is KdbxResult.Success)
            assertNull(
                "分组3：磁盘修改早于本地删除时刻 ⇒ 分组保持删除（不得静默复活旧分组）",
                reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)
            )
            session.close()
        }
        // 方向二：本地删除（带墓碑）早于磁盘改名 ⇒ 磁盘版本复活
        run {
            val (session, vaultFile) = createdSession()
            val deletionMoment = Instant.now().minusSeconds(300)
            val diskModifiedAt = Instant.now()
            persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Original"))))
            writeDisk(
                vaultFile,
                session,
                rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Disk Renamed", at = diskModifiedAt)))
            )
            session.updateDatabaseMeta {
                it.copy(
                    rootGroup = rootOf(subgroups = emptyList()),
                    deletedObjects = listOf(DeletedObject(GROUP_TEST_SHARED, deletionMoment))
                )
            }

            val result = resolverOf(session, coordinatorOf(session))
                .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("分组3：合并必须成功: $result", result is KdbxResult.Success)
            assertEquals(
                "分组3：磁盘修改晚于本地删除时刻 ⇒ 以磁盘版本复活（P2-284 同源口径）",
                "Disk Renamed",
                reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)?.name
            )
            session.close()
        }
    }

    private companion object {
        /** 分组名冲突场景（base=A / local=B / disk=C）——两处以上用例共用，避免各写一份装置 */
        const val BASE_NAME = "Base Name"
        const val LOCAL_NAME = "Local Name"
        const val DISK_NAME = "Disk Name"
    }

    /**
     * `ISSUE-P1-631`：分组名分叉场景（base=A / 本地=B / 磁盘=C，共享分组内含一个本地新增条目）。
     * 时间戳刻意双向错开（本地更新 / 磁盘更新两种都可由调用方构造），以证明裁决结果**不依赖**时间。
     */
    private suspend fun nameDivergenceScenario(
        diskNewer: Boolean = false,
        withInnerEntry: Boolean = true
    ): Pair<DatabaseSession, File> {
        val (session, vaultFile) = createdSession()
        val baseAt = Instant.now().minusSeconds(900)
        val localAt = if (diskNewer) Instant.now().minusSeconds(300) else Instant.now().minusSeconds(60)
        val diskAt = if (diskNewer) Instant.now().minusSeconds(60) else Instant.now().minusSeconds(300)
        persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, BASE_NAME, at = baseAt))))
        writeDisk(
            vaultFile,
            session,
            rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, DISK_NAME, at = diskAt)))
        )
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    subgroups = listOf(
                        group(
                            GROUP_TEST_SHARED,
                            LOCAL_NAME,
                            at = localAt,
                            entries = if (withInnerEntry) {
                                listOf(entry(GROUP_TEST_INNER_ENTRY, "Inner", "Inner#1"))
                            } else {
                                emptyList()
                            }
                        )
                    )
                )
            )
        }
        return session to vaultFile
    }

    @Test
    fun `分组4 同一分组同名双侧异值必须进入裁决且裁决前不写盘`() = runBlocking {
        val (session, vaultFile) = nameDivergenceScenario()
        val bytesBefore = vaultFile.readBytes()
        val coordinator = coordinatorOf(session)
        val blocked = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)

        assertTrue(
            "分组4：同字段双侧异值必须阻断（不得静默 LWW 定案）: $blocked",
            blocked is KdbxResult.Failure
        )
        val conflict = coordinator.mergeConflict.value
        assertNotNull("分组4：必须置起分组冲突待裁决提示", conflict)
        assertEquals("分组4：冲突计数应含该分组", 1, conflict!!.conflictCount)
        assertTrue(
            "分组4：提示必须点出分组名（实测=${conflict.entryTitles}）",
            conflict.entryTitles.any { it.contains(LOCAL_NAME) || it.contains(DISK_NAME) }
        )
        assertTrue("分组4：裁决前不得写盘", bytesBefore.contentEquals(vaultFile.readBytes()))
        assertEquals(
            "分组4：裁决前会话树保持本地版本",
            LOCAL_NAME,
            session.databaseFlow.value!!.rootGroup.findGroup(GROUP_TEST_SHARED)?.name
        )
        session.close()
    }

    @Test
    fun `分组4b 以本地为准裁决把本地组名落盘（磁盘时间更新也不例外）`() = runBlocking {
        val (session, vaultFile) = nameDivergenceScenario(diskNewer = true)
        assertTrue(
            "分组4b：首轮必须阻断",
            resolverOf(session, coordinatorOf(session)).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
                is KdbxResult.Failure
        )
        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
        assertTrue("分组4b：裁决必须成功: $result", result is KdbxResult.Success)
        assertEquals(
            "分组4b：本地组名必须落盘（时间戳更新也不得翻盘）",
            LOCAL_NAME,
            reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)?.name
        )
        session.close()
    }

    @Test
    fun `分组4c 以磁盘为准裁决把磁盘组名落盘（本地时间更新也不例外）`() = runBlocking {
        val (session, vaultFile) = nameDivergenceScenario(diskNewer = false)
        assertTrue(
            "分组4c：首轮必须阻断",
            resolverOf(session, coordinatorOf(session)).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
                is KdbxResult.Failure
        )
        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_KEEP_DISK)
        assertTrue("分组4c：裁决必须成功: $result", result is KdbxResult.Success)
        assertEquals(
            "分组4c：磁盘组名必须落盘（本地时间更新也不得翻盘）",
            DISK_NAME,
            reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)?.name
        )
        session.close()
    }

    @Test
    fun `分组4d 双方保留裁决把两侧组名都落盘且复制体UUID全新`() = runBlocking {
        val (session, vaultFile) = nameDivergenceScenario()
        assertTrue(
            "分组4d：首轮必须阻断",
            resolverOf(session, coordinatorOf(session)).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
                is KdbxResult.Failure
        )
        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_KEEP_BOTH)
        assertTrue("分组4d：裁决必须成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(vaultFile)
        val names = reparsed.rootGroup.allGroups().map { it.name }
        assertTrue("分组4d：本地组名必须保留（实测=$names）", names.contains(LOCAL_NAME))
        assertTrue(
            "分组4d：磁盘组名必须以冲突副本形式保留（实测=$names）",
            names.any { it.startsWith(DISK_NAME) && it != DISK_NAME }
        )
        // 复制体必须与原分组**同父**且 UUID 全新：同 UUID 会在回树时命中同一槽位互相覆盖
        val duplicated = reparsed.rootGroup.allGroups()
            .first { it.name.startsWith(DISK_NAME) && it.name != DISK_NAME }
        assertEquals("分组4d：复制体必须与原分组同父", GROUP_TEST_ROOT, duplicated.parentGroupId)
        assertTrue("分组4d：复制体 UUID 必须全新", duplicated.id != GROUP_TEST_SHARED)
        val ids = reparsed.rootGroup.allGroups().map { it.id.toHexString() }
        assertEquals("分组4d：不得出现重复分组 UUID（实测=$ids）", ids.size, ids.toSet().size)
        val entryIds = reparsed.rootGroup.allEntries().map { it.id.toHexString() }
        assertEquals("分组4d：不得出现重复条目 UUID（实测=$entryIds）", entryIds.size, entryIds.toSet().size)
        session.close()
    }

    @Test
    fun `分组5 基线缺失时同名分叉同样不得静默取一侧且不推断删除`() = runBlocking {
        val (session, vaultFile) = createdSession()
        persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, BASE_NAME))))
        // 基线缺失（模拟未登记 / 超限未留存）⇒ 空白底版，任何双侧字段差异都无可信依据
        session.clearPersistedBaselineForTesting()
        writeDisk(
            vaultFile,
            session,
            rootOf(
                subgroups = listOf(
                    group(GROUP_TEST_SHARED, DISK_NAME),
                    group(GROUP_TEST_DISK_ONLY, "Disk Only")
                )
            )
        )
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    subgroups = listOf(
                        group(GROUP_TEST_SHARED, LOCAL_NAME),
                        group(GROUP_TEST_LOCAL_ONLY, "Local Only")
                    )
                )
            )
        }
        val bytesBefore = vaultFile.readBytes()
        val coordinator = coordinatorOf(session)
        val blocked = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue(
            "分组5：基线缺失 + 同名分叉必须阻断待裁决（不得静默 LWW）: $blocked",
            blocked is KdbxResult.Failure
        )
        assertNotNull("分组5：必须置起待裁决提示", coordinator.mergeConflict.value)
        assertTrue("分组5：裁决前不得写盘", bytesBefore.contentEquals(vaultFile.readBytes()))

        val resolved = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
        assertTrue("分组5：裁决后必须成功: $resolved", resolved is KdbxResult.Success)
        val reparsed = reparse(vaultFile)
        assertNotNull("分组5：本地独有分组不得被推断删除", reparsed.rootGroup.findGroup(GROUP_TEST_LOCAL_ONLY))
        assertNotNull("分组5：磁盘独有分组保留", reparsed.rootGroup.findGroup(GROUP_TEST_DISK_ONLY))
        assertEquals(
            "分组5：以本地为准后组名取本地值",
            LOCAL_NAME,
            reparsed.rootGroup.findGroup(GROUP_TEST_SHARED)?.name
        )
        session.close()
    }

    @Test
    fun `分组6 双侧改成同一组名不构成冲突`() = runBlocking {
        val (session, vaultFile) = createdSession()
        val baseAt = Instant.now().minusSeconds(600)
        persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, BASE_NAME, at = baseAt))))
        writeDisk(vaultFile, session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Same Name"))))
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Same Name"))))
        }
        val coordinator = coordinatorOf(session)
        val result = resolverOf(session, coordinator).resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("分组6：双侧同值不构成冲突，必须直接成功: $result", result is KdbxResult.Success)
        assertNull("分组6：不得置起裁决提示", coordinator.mergeConflict.value)
        assertEquals(
            "分组6：结果取双方一致值",
            "Same Name",
            reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)?.name
        )
        session.close()
    }
}
