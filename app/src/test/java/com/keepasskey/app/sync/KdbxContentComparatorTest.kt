package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxCustomField
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
 * `ISSUE-P2-91`：会话库「内容是否变化」判定的**语义锁定**用例。
 *
 * 该判定是「本地编辑是否会被静默丢弃」的唯一判据——判「无变化」时 `SyncCycleRunner`
 * 直接复用旧缓存字节上传；而改造前它**零用例覆盖**（全仓无任何用例引用
 * `resolveLocalContentChanged`）。本类把它的**每一条比较臂**与**三条刻意排除项**
 * （`times`、历史内容、以及合并侧才需要的 `lastModificationTime`）都变成可执行断言。
 *
 * 与合并侧 `KdbxEntryMerger.isModified` 的口径差异（本判定刻意不比 `times`）见
 * [KdbxContentComparator] 类 KDoc 与 `docs/architecture/已知工程限界.md` §10。
 *
 * **覆盖缺口（如实声明，不得读作已验证）**：根级 `changed()`（`deletedObjects` 比较 +
 * 委托分组比较）本类**未覆盖**——构造 `KdbxDatabase` 需一并提供 `KdbxHeader` 与
 * `DeletedObject` 夹具，本批未做；本类覆盖的是其中真正承载判定逻辑的
 * [KdbxContentComparator.entryChanged] 与 [KdbxContentComparator.groupChanged]。
 */
class KdbxContentComparatorTest {

    @Test
    fun `条目内容字段的每一处变化都必须被检出`() {
        val mutations = listOf(
            "fields" to base.copy(fields = base.fields + ("User" to ProtectedString("u"))),
            "customFields" to base.copy(customFields = listOf(KdbxCustomField("k", ProtectedString("v")))),
            "tags" to base.copy(tags = listOf("t")),
            "attachments" to base.copy(attachments = listOf(KdbxAttachment("a.txt"))),
            "iconId" to base.copy(iconId = 7),
            "customIconId" to base.copy(customIconId = KdbxUuid.random()),
            "overrideUrl" to base.copy(overrideUrl = "cmd://run"),
            "qualityCheck" to base.copy(qualityCheck = false),
            "parentGroupId" to base.copy(parentGroupId = KdbxUuid.random()),
            "previousParentGroup" to base.copy(previousParentGroup = KdbxUuid.random()),
            "customData" to base.copy(customData = mapOf("k" to "v")),
            "autoType" to base.copy(autoType = KdbxAutoType(enabled = false)),
            "backgroundColor" to base.copy(backgroundColor = "#FF0000"),
            "foregroundColor" to base.copy(foregroundColor = "#00FF00"),
            "history 条数" to base.copy(history = listOf(base))
        )
        mutations.forEach { (name, mutated) ->
            assertTrue(
                "$name 变化必须判为内容变更（漏判即「本地编辑被静默丢弃」）",
                KdbxContentComparator.entryChanged(mutated, base)
            )
            assertTrue(
                "$name 变化必须判为内容变更（参数对调后同样成立）",
                KdbxContentComparator.entryChanged(base, mutated)
            )
        }
    }

    @Test
    fun `相同条目与空副本不得判为变更`() {
        assertFalse(KdbxContentComparator.entryChanged(base, base))
        assertFalse(KdbxContentComparator.entryChanged(base, base.copy()))
    }

    @Test
    fun `仅 times 的变化刻意不判为内容变更`() {
        val timeMutations = listOf(
            "lastModificationTime" to KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1_700_000_000_000L)),
            "usageCount" to base.times.copy(usageCount = 42L),
            "lastAccessTime" to base.times.copy(lastAccessTime = Instant.ofEpochMilli(1L)),
            "expires 语义" to base.times.copy(expires = true, expiryTime = Instant.ofEpochMilli(2L))
        )
        timeMutations.forEach { (name, times) ->
            val mutated = base.copy(times = times)
            // 理由：KdbxTimes 混装「使用性」字段（lastAccessTime / usageCount）与
            // lastModificationTime，而 KdbxEntry.withField 经 withModified() 一次性改写三者
            // ⇒ 纳入比较会让「触碰但内容等同」被判成变更，触发重序列化 + 上传 + 前移远端 ETag
            //（正是本检测器存在的理由）。生产代码亦无「只改 times」的编辑入口。
            assertFalse(
                "$name 属使用性 / 合并性变动，不得判为需要重新上传的内容变更",
                KdbxContentComparator.entryChanged(mutated, base)
            )
            assertFalse(
                "$name （参数对调后同样不判为变更）",
                KdbxContentComparator.entryChanged(base, mutated)
            )
        }
    }

    @Test
    fun `历史条数相同而内容不同刻意不判为变更`() {
        val sharedId = KdbxUuid.random()
        val oldSnapshot = KdbxEntry(id = sharedId, fields = mapOf("Title" to ProtectedString("旧")))
        val newSnapshot = KdbxEntry(id = sharedId, fields = mapOf("Title" to ProtectedString("新")))
        val a = base.copy(history = listOf(oldSnapshot))
        val b = base.copy(history = listOf(newSnapshot))
        // 刻意的已知不覆盖：可达的历史变更（追加快照 / 按保留期修剪 / 合并并集）必然改变条数；
        // 若日后引入「等条数原地改写历史」的路径，本断言即失效判据，须同步改判据。
        assertFalse(
            "本判定只比历史条数（该口径登记于已知工程限界 §10）",
            KdbxContentComparator.entryChanged(a, b)
        )
    }

    @Test
    fun `分组字段的每一处变化都必须被检出`() {
        val mutations = listOf(
            "id" to baseGroup.copy(id = KdbxUuid.random()),
            "name" to baseGroup.copy(name = "改名"),
            "notes" to baseGroup.copy(notes = "备注"),
            "iconId" to baseGroup.copy(iconId = 49),
            "customIconId" to baseGroup.copy(customIconId = KdbxUuid.random()),
            "parentGroupId" to baseGroup.copy(parentGroupId = KdbxUuid.random()),
            "tags" to baseGroup.copy(tags = listOf("重要")),
            "customData" to baseGroup.copy(customData = mapOf("k" to "v")),
            "条目内容" to baseGroup.copy(entries = listOf(base.copy(iconId = 7))),
            "条目条数" to baseGroup.copy(entries = baseGroup.entries + KdbxEntry()),
            "子分组条数" to baseGroup.copy(subgroups = listOf(KdbxGroup(name = "S"))),
            "子分组内容" to baseGroup.copy(
                subgroups = listOf(KdbxGroup(id = childGroupId, name = "S", notes = "子组改名"))
            )
        )
        mutations.forEach { (name, mutated) ->
            assertTrue(
                "分组 $name 变化必须判为内容变更",
                KdbxContentComparator.groupChanged(mutated, baseGroup)
            )
        }

        // 「新树里找不到同 id 条目 / 子组」也属变化（`?: return true` 两条分支）
        assertTrue(
            "条目 id 在新树中缺失必须判为变更",
            KdbxContentComparator.groupChanged(
                baseGroup.copy(entries = listOf(KdbxEntry(parentGroupId = null))),
                baseGroup
            )
        )
        assertTrue(
            "子分组 id 在新树中缺失必须判为变更",
            KdbxContentComparator.groupChanged(
                baseGroup.copy(subgroups = listOf(KdbxGroup(name = "S"))),
                baseGroup.copy(subgroups = listOf(KdbxGroup(parentGroupId = null, name = "S")))
            )
        )
    }

    @Test
    fun `相同分组与仅 times 变化的分组不判为变更`() {
        assertFalse(KdbxContentComparator.groupChanged(baseGroup, baseGroup))
        assertFalse(
            "分组 times 与条目 times 同理不入判据",
            KdbxContentComparator.groupChanged(
                baseGroup.copy(
                    times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(9L)),
                    entries = listOf(base.copy(times = KdbxTimes(usageCount = 3L)))
                ),
                baseGroup
            )
        )
    }

    private companion object {
        val base: KdbxEntry = KdbxEntry(
            fields = mapOf("Title" to ProtectedString("T", false))
        )

        val childGroupId: KdbxUuid = KdbxUuid.random()

        val baseGroup: KdbxGroup = KdbxGroup(
            name = "G",
            entries = listOf(base),
            subgroups = listOf(KdbxGroup(id = childGroupId, name = "S"))
        )
    }
}
