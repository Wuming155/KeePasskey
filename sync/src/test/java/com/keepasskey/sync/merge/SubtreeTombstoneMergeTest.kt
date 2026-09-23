package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `ISSUE-P2-284` AC②／AC③（`:sync:` 面）：他端存活副本经合并后被**子树墓碑**清除的回归。
 *
 * ## 锁定的缺陷
 *
 * 组硬删除只立组自身墓碑时，他端仍持有的子条目 / 子组副本在合并中按
 * 「删除 vs 修改 ⇒ 修改胜」复活（墓碑按 UUID 精确匹配，父组墓碑不覆盖子项）。
 * 整改后本端墓碑覆盖整棵子树 ⇒ 合并裁决「删除时间晚于对端修改时间」，
 * 他端副本必须**不再复活**。
 */
class SubtreeTombstoneMergeTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-02-01T00:00:00Z")

    /** 删除时刻（晚于对端对子条目的最后修改——墓碑胜的关键前提）。 */
    private val tDelete: Instant = Instant.parse("2026-03-01T00:00:00Z")

    private fun entry(id: KdbxUuid, parentId: KdbxUuid, mod: Instant, title: String) = KdbxEntry(
        id = id,
        parentGroupId = parentId,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false)),
        times = KdbxTimes(creationTime = t0, lastModificationTime = mod)
    )

    private fun subtree(titleSuffix: String, entryMod: Instant): KdbxGroup = KdbxGroup(
        id = GROUP_ID,
        parentGroupId = ROOT_ID,
        name = "目标组",
        entries = listOf(entry(ENTRY_ID, GROUP_ID, entryMod, "条目-$titleSuffix")),
        subgroups = listOf(
            KdbxGroup(
                id = SUBGROUP_ID,
                parentGroupId = GROUP_ID,
                name = "子组",
                entries = listOf(entry(SUBENTRY_ID, SUBGROUP_ID, t0, "子组条目")),
                times = KdbxTimes(creationTime = t0, lastModificationTime = entryMod)
            )
        ),
        times = KdbxTimes(creationTime = t0, lastModificationTime = entryMod)
    )

    @Test
    fun `子树墓碑覆盖下：他端存活副本（含删除前的修改）经合并不再复活`() {
        // base：三方共有的祖先（含整棵子树）
        val base = KdbxDatabaseLite(rootGroup = rootWith(subtree("base", t0)))
        // local：组已硬删除，墓碑覆盖 {组, 子组, 条目, 子组条目}
        val tombstones = listOf(GROUP_ID, SUBGROUP_ID, ENTRY_ID, SUBENTRY_ID)
            .map { DeletedObject(id = it, deletionTime = tDelete) }
        val local = KdbxDatabaseLite(
            rootGroup = KdbxGroup(id = ROOT_ID, name = "Root"),
            deletedObjects = tombstones
        )
        // remote：仍持有整棵子树，且条目在「删除之前」有过修改（t1 < tDelete ⇒ 墓碑必须胜）
        val remote = KdbxDatabaseLite(rootGroup = rootWith(subtree("remote-edit", t1)))

        val result = KdbxMerger.mergeDatabases(base, local, remote)

        val survivingGroupIds = result.mergedRoot.allGroups().map { it.id }.toSet()
        val survivingEntryIds = result.mergedRoot.allEntries().map { it.id }.toSet()
        assertFalse("目标组不得复活", GROUP_ID in survivingGroupIds)
        assertFalse("子组不得复活", SUBGROUP_ID in survivingGroupIds)
        assertFalse("子条目不得复活（其修改早于删除时刻）", ENTRY_ID in survivingEntryIds)
        assertFalse("子组条目不得复活", SUBENTRY_ID in survivingEntryIds)
    }

    @Test
    fun `对端在删除时刻之后修改条目：按官方口径复活（墓碑不恒胜）`() {
        val tAfterDelete = tDelete.plusSeconds(3600)
        val base = KdbxDatabaseLite(rootGroup = rootWith(subtree("base", t0)))
        val tombstones = listOf(GROUP_ID, ENTRY_ID)
            .map { DeletedObject(id = it, deletionTime = tDelete) }
        val local = KdbxDatabaseLite(
            rootGroup = KdbxGroup(id = ROOT_ID, name = "Root"),
            deletedObjects = tombstones
        )
        val remote = KdbxDatabaseLite(rootGroup = rootWith(subtree("remote-recreated", tAfterDelete)))

        val result = KdbxMerger.mergeDatabases(base, local, remote)

        assertTrue(
            "删除时刻之后的修改必须复活该条目（官方 PwDeletedObject 时间裁决）",
            result.mergedRoot.allEntries().any { it.id == ENTRY_ID }
        )
        assertTrue(
            "删除时刻之后修改的分组必须复活",
            result.mergedRoot.allGroups().any { it.id == GROUP_ID }
        )
    }

    private fun rootWith(subtreeGroup: KdbxGroup) =
        KdbxGroup(id = ROOT_ID, name = "Root", subgroups = listOf(subtreeGroup))

    private companion object {
        val ROOT_ID: KdbxUuid = KdbxUuid.random()
        val GROUP_ID: KdbxUuid = KdbxUuid.random()
        val SUBGROUP_ID: KdbxUuid = KdbxUuid.random()
        val ENTRY_ID: KdbxUuid = KdbxUuid.random()
        val SUBENTRY_ID: KdbxUuid = KdbxUuid.random()
    }
}
