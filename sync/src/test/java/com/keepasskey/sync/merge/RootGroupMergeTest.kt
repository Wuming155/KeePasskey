package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ENG-20261111-05`：根组**普通属性**参与三方合并的行为覆盖。
 *
 * 缺陷形态：根组不进入 `KdbxGroupMerger.mergeSurvivingGroups` 的存活裁决（根 UUID 与身份固定），
 * 组装阶段的根分支只在 `localRoot` 上替换 times 与 customData ⇒ 远端单侧改的根组名称 / 备注 / 图标
 * 被静默丢弃，双侧同字段分歧也不产生根组冲突。
 *
 * 本组用例锁定：单侧变更必须保留、双侧独立变更两侧都不丢、同字段双侧异值必须进
 * `MergeResult.groupConflicts` 且可按字段重裁；同时钉住根身份（UUID）、既有 times LWW 与
 * 「不同根 UUID 的远端根组不覆盖本地根身份属性」（`.kdbx` 并入路径）这两条既有边界。
 */
class RootGroupMergeTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-02-01T00:00:00Z")
    private val t2: Instant = Instant.parse("2026-03-01T00:00:00Z")

    private fun times(mod: Instant): KdbxTimes = KdbxTimes(creationTime = t0, lastModificationTime = mod)

    private fun root(
        id: KdbxUuid = ROOT_ID,
        name: String = "库名",
        notes: String = "",
        iconId: Int = 48,
        customIconId: KdbxUuid? = null,
        customData: Map<String, String> = emptyMap(),
        times: KdbxTimes = times(t0),
        entries: List<KdbxEntry> = emptyList(),
        subgroups: List<KdbxGroup> = emptyList()
    ): KdbxGroup = KdbxGroup(
        id = id,
        name = name,
        notes = notes,
        iconId = iconId,
        customIconId = customIconId,
        customData = customData,
        times = times,
        entries = entries,
        subgroups = subgroups
    )

    private fun entry(id: KdbxUuid = ENTRY_ID, title: String, mod: Instant = t0): KdbxEntry = KdbxEntry(
        id = id,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)),
        times = times(mod)
    )

    private fun merge(base: KdbxGroup, local: KdbxGroup, remote: KdbxGroup): MergeResult =
        KdbxMerger.mergeDatabases(
            base = KdbxDatabaseLite(rootGroup = base),
            local = KdbxDatabaseLite(rootGroup = local),
            remote = KdbxDatabaseLite(rootGroup = remote)
        )

    @Test
    fun `远端单侧改根组名称与备注：合并产物采纳远端且不产生根组冲突`() {
        val base = root(entries = listOf(entry(title = "站点")))
        // 触发合并的本地改动：只改条目，根组本身未动
        val local = root(times = times(t1), entries = listOf(entry(title = "站点改", mod = t1)))
        val remoteTimes = KdbxTimes(
            creationTime = t0,
            lastModificationTime = t2,
            usageCount = 3
        )
        val remote = root(
            name = "远端改名",
            notes = "远端备注",
            times = remoteTimes,
            entries = listOf(entry(title = "站点"))
        )

        val result = merge(base, local, remote)

        assertEquals("根身份（UUID）必须保持本地根组", ROOT_ID, result.mergedRoot.id)
        assertEquals("远端单侧改的根组名称不得静默丢弃", "远端改名", result.mergedRoot.name)
        assertEquals("远端单侧改的根组备注不得静默丢弃", "远端备注", result.mergedRoot.notes)
        assertEquals("本地条目改动照常并入", "站点改", result.mergedRoot.entries.single().title)
        assertEquals("根组 times 仍取较晚一侧的整份时间戳（既有 LWW 口径）", remoteTimes, result.mergedRoot.times)
        assertTrue("单侧变更不是分歧，不得登记根组冲突", result.groupConflicts.isEmpty())
    }

    @Test
    fun `本地单侧改根组名称与图标：合并产物保留本地且不产生根组冲突`() {
        val base = root()
        val local = root(name = "本地改名", iconId = 3, times = times(t1))
        val remote = base

        val result = merge(base, local, remote)

        assertEquals("本地改名不得被远端旧值回退", "本地改名", result.mergedRoot.name)
        assertEquals("本地图标改动不得丢失", 3, result.mergedRoot.iconId)
        assertTrue("单侧变更不是分歧，不得登记根组冲突", result.groupConflicts.isEmpty())
    }

    @Test
    fun `双侧各改根组不同属性：两侧改动都不丢且不产生冲突`() {
        val base = root(name = "旧名", notes = "旧备注", iconId = 48)
        val local = root(name = "本地改名", notes = "旧备注", iconId = 48, times = times(t1))
        val remote = root(name = "旧名", notes = "远端备注", iconId = 7, times = times(t2))

        val result = merge(base, local, remote)

        assertEquals("本地改的名称必须保留", "本地改名", result.mergedRoot.name)
        assertEquals("远端改的备注必须保留", "远端备注", result.mergedRoot.notes)
        assertEquals("远端改的图标必须保留", 7, result.mergedRoot.iconId)
        assertTrue("不同字段的分歧属干净合并，不得登记根组冲突", result.groupConflicts.isEmpty())
    }

    @Test
    fun `双侧同改根组名称异值：按 LWW 给出临时值并登记根组冲突`() {
        val subgroup = KdbxGroup(
            id = SUBGROUP_ID,
            parentGroupId = ROOT_ID,
            name = "子组",
            entries = listOf(entry(id = SUBGROUP_ENTRY_ID, title = "子组条目"))
        )
        val base = root(name = "旧名", subgroups = listOf(subgroup))
        val local = root(name = "本地名", times = times(t1), subgroups = listOf(subgroup))
        val remote = root(name = "远端名", times = times(t2), subgroups = listOf(subgroup))

        val result = merge(base, local, remote)

        assertEquals("双侧异值按 LWW 给出可用的临时值（远端较新）", "远端名", result.mergedRoot.name)
        assertEquals("根组同字段双侧异值必须登记冲突（不得静默定案）", 1, result.groupConflicts.size)
        val pair = result.groupConflicts.single()
        assertEquals("冲突必须指向根组 UUID", ROOT_ID.toHexString(), pair.groupId)
        assertEquals("展示名取本地优先", "本地名", pair.groupName)
        assertEquals(
            "冲突字段键必须指向根组名称（与子组同一词汇表）",
            listOf(KdbxGroupConflictKeys.NAME),
            pair.modifiedFields
        )

        val keepLocal = KdbxGroupConflictResolver.resolveFieldsOnly(
            merged = result.mergedRoot,
            pair = pair,
            choice = ConflictResolutionChoice.KEEP_LOCAL
        )
        val keepRemote = KdbxGroupConflictResolver.resolveFieldsOnly(
            merged = result.mergedRoot,
            pair = pair,
            choice = ConflictResolutionChoice.KEEP_REMOTE
        )
        assertEquals("以本地为准必须落定本地名称", "本地名", keepLocal.name)
        assertEquals("以远端为准必须落定远端名称", "远端名", keepRemote.name)
        assertEquals("重裁不得改变根身份", ROOT_ID, keepLocal.id)
        assertNotNull(
            "重裁根组字段必须保留已组装好的子树",
            keepLocal.findEntry(SUBGROUP_ENTRY_ID)
        )
    }

    @Test
    fun `双侧改成同一根组名不构成冲突`() {
        val base = root(name = "旧名")
        val local = root(name = "新名", times = times(t1))
        val remote = root(name = "新名", times = times(t2))

        val result = merge(base, local, remote)

        assertEquals("双侧同值取该值", "新名", result.mergedRoot.name)
        assertTrue("双侧同值不是分歧，不得登记根组冲突", result.groupConflicts.isEmpty())
    }

    @Test
    fun `不同根 UUID 的远端根组不覆盖本地根身份属性`() {
        // `.kdbx` 并入路径：remote 是另一个库的根组（不同 UUID），base 为本地根的空壳
        val localRoot = root(name = "当前库")
        val base = localRoot.copy(entries = emptyList(), subgroups = emptyList())
        val remoteRoot = root(
            id = OTHER_ROOT_ID,
            name = "被导入库",
            notes = "被导入备注",
            times = times(t2)
        )

        val result = merge(base, localRoot, remoteRoot)

        assertEquals("根身份仍为本地根组", ROOT_ID, result.mergedRoot.id)
        assertEquals("他库根组名称不得覆盖当前库根组", "当前库", result.mergedRoot.name)
        assertEquals("他库根组备注不得覆盖当前库根组", "", result.mergedRoot.notes)
        assertTrue("不同实体的根组不构成字段冲突", result.groupConflicts.isEmpty())
    }

    private companion object {
        val ROOT_ID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )
        val OTHER_ROOT_ID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        )
        val ENTRY_ID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3)
        )
        val SUBGROUP_ID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4)
        )
        val SUBGROUP_ENTRY_ID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5)
        )
    }
}
