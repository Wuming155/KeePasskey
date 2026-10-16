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

    @Test
    fun `分组4 同一分组双侧修改为字段级LWW且不进条目冲突清单`() = runBlocking {
        // 方向一：本地修改时刻更晚 ⇒ 本地名胜
        run {
            val (session, vaultFile) = createdSession()
            val baseAt = Instant.now().minusSeconds(600)
            val diskAt = Instant.now().minusSeconds(300)
            val localAt = Instant.now().minusSeconds(60)
            persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Base Name", at = baseAt))))
            writeDisk(
                vaultFile,
                session,
                rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Disk Name", at = diskAt)))
            )
            session.updateDatabaseMeta {
                it.copy(rootGroup = rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Local Name", at = localAt))))
            }

            val coordinator = coordinatorOf(session)
            val result = resolverOf(session, coordinator)
                .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("分组4：合并必须成功: $result", result is KdbxResult.Success)
            assertNull(
                "分组4（边界钉住）：分组冲突不进条目冲突清单——当前口径为字段级 LWW，不置裁决提示",
                coordinator.mergeConflict.value
            )
            assertEquals(
                "分组4：双侧修改按最后修改时间取胜（本地更晚 ⇒ 本地名）",
                "Local Name",
                reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)?.name
            )
            session.close()
        }
        // 方向二：磁盘修改时刻更晚 ⇒ 磁盘名胜
        run {
            val (session, vaultFile) = createdSession()
            val baseAt = Instant.now().minusSeconds(600)
            val localAt = Instant.now().minusSeconds(300)
            val diskAt = Instant.now().minusSeconds(60)
            persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Base Name", at = baseAt))))
            writeDisk(
                vaultFile,
                session,
                rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Disk Name", at = diskAt)))
            )
            session.updateDatabaseMeta {
                it.copy(rootGroup = rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Local Name", at = localAt))))
            }

            val result = resolverOf(session, coordinatorOf(session))
                .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("分组4：合并必须成功: $result", result is KdbxResult.Success)
            assertEquals(
                "分组4：双侧修改按最后修改时间取胜（磁盘更晚 ⇒ 磁盘名）",
                "Disk Name",
                reparse(vaultFile).rootGroup.findGroup(GROUP_TEST_SHARED)?.name
            )
            session.close()
        }
    }

    @Test
    fun `分组5 基线缺失不推断删除且双侧修改不崩溃`() = runBlocking {
        val (session, vaultFile) = createdSession()
        persistRoot(session, rootOf(subgroups = listOf(group(GROUP_TEST_SHARED, "Base Name"))))
        // 基线缺失（模拟未登记 / 超限未留存）
        session.clearPersistedBaselineForTesting()
        // 磁盘：共享分组改名 + 磁盘独有分组
        writeDisk(
            vaultFile,
            session,
            rootOf(
                subgroups = listOf(
                    group(GROUP_TEST_SHARED, "Disk Name"),
                    group(GROUP_TEST_DISK_ONLY, "Disk Only")
                )
            )
        )
        // 本地：共享分组改名 + 本地独有分组
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    subgroups = listOf(
                        group(GROUP_TEST_SHARED, "Local Name"),
                        group(GROUP_TEST_LOCAL_ONLY, "Local Only")
                    )
                )
            )
        }

        val result = resolverOf(session, coordinatorOf(session))
            .resolve(ExternalModificationChoice.MERGE_AND_SAVE)
        assertTrue("分组5：基线缺失时无重叠修改必须直接合并成功: $result", result is KdbxResult.Success)

        val reparsed = reparse(vaultFile)
        assertNotNull("分组5：本地独有分组不得被推断删除", reparsed.rootGroup.findGroup(GROUP_TEST_LOCAL_ONLY))
        assertNotNull("分组5：磁盘独有分组保留", reparsed.rootGroup.findGroup(GROUP_TEST_DISK_ONLY))
        val shared = reparsed.rootGroup.findGroup(GROUP_TEST_SHARED)
        assertNotNull("分组5：双侧均存在的分组不得消失", shared)
        assertTrue(
            "分组5：双侧改名必须有确定结果（实测=${shared?.name}）",
            shared?.name == "Local Name" || shared?.name == "Disk Name"
        )
        session.close()
    }
}
