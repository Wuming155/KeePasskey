package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-P2-279 AC④：条目 / 分组合并的「判修改集 = 实际合并集 = 上报集」回归。
 *
 * ## 锁定的缺陷
 *
 * 整改前 `KdbxEntryMerger.isModified` / `isGroupModified` 把图标（`iconId` / `customIconId`）、
 * `overrideUrl`、`qualityCheck` 计入判修改集，但合并产物（`local.copy`）原样保留本地值、
 * `diffFields` 又永不收录这些字段 ⇒ 双侧修改时远端改动**静默丢失且不报冲突**。
 *
 * 整改后判定 / 合并 / 上报由同一词汇表驱动（条目 `MERGED_SCALAR_FIELDS` /
 * 分组 `MERGED_GROUP_FIELDS`）：单侧变更取该侧（**不丢**），双侧异值 LWW 裁决并进
 * `diffFields`（**不静默**）。
 */
class KdbxMergerScalarFieldsTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-02-01T00:00:00Z")
    private val t2: Instant = Instant.parse("2026-03-01T00:00:00Z")

    private fun entry(
        mod: Instant,
        iconId: Int = 1,
        customIconId: KdbxUuid? = null,
        overrideUrl: String? = null,
        qualityCheck: Boolean = true,
        title: String = "条目"
    ): KdbxEntry = KdbxEntry(
        id = ENTRY_ID,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false)),
        iconId = iconId,
        customIconId = customIconId,
        overrideUrl = overrideUrl,
        qualityCheck = qualityCheck,
        times = KdbxTimes(creationTime = t0, lastModificationTime = mod)
    )

    /** 驱动「双侧均修改 ⇒ 字段级合并」分支并返回 (合并条目, 冲突对)。 */
    private fun mergeBothModified(
        base: KdbxEntry,
        local: KdbxEntry,
        remote: KdbxEntry
    ): Pair<KdbxEntry, ConflictedEntryPair?> {
        val (surviving, conflicts) = KdbxEntryMerger.mergeSurvivingEntries(
            allEntryUuids = setOf(ENTRY_ID),
            baseEntries = mapOf(ENTRY_ID to base),
            localEntries = mapOf(ENTRY_ID to local),
            remoteEntries = mapOf(ENTRY_ID to remote),
            localDeleted = emptyMap(),
            remoteDeleted = emptyMap()
        )
        assertEquals("前提：条目必须存活于合并产物", 1, surviving.size)
        return surviving.single() to conflicts.singleOrNull()
    }

    @Test
    fun `双侧各改不同图标字段：两侧改动都不丢（AC④ 不丢）`() {
        val remoteIcon = KdbxUuid.random()
        val base = entry(mod = t0)
        val local = entry(mod = t1, iconId = 7)                       // 本地改 iconId
        val remote = entry(mod = t2, customIconId = remoteIcon)       // 远端改 customIconId

        val (merged, conflict) = mergeBothModified(base, local, remote)

        assertEquals("本地的 iconId 改动必须保留", 7, merged.iconId)
        assertEquals("远端的 customIconId 改动必须保留（不得静默丢）", remoteIcon, merged.customIconId)
        assertNull("不同字段的分歧属干净合并，不应产生冲突对", conflict)
    }

    @Test
    fun `同改 iconId 异值：LWW 取胜且进冲突清单（AC④ 不静默）`() {
        val base = entry(mod = t0, iconId = 1)
        val local = entry(mod = t1, iconId = 2)
        val remote = entry(mod = t2, iconId = 3)

        val (merged, conflict) = mergeBothModified(base, local, remote)

        assertEquals("双侧异值须按 LWW 裁决（远端较新）", 3, merged.iconId)
        assertTrue(
            "图标分歧必须进冲突清单（modifiedFields 留痕）",
            conflict!!.modifiedFields.any { it.contains("图标") }
        )
    }

    @Test
    fun `远端改 overrideUrl 与 qualityCheck：合并产物采纳远端值（AC②／AC③）`() {
        val base = entry(mod = t0)
        val local = entry(mod = t1, title = "本地改标题")              // 本地改标准字段使双侧均修改
        val remote = entry(mod = t2, overrideUrl = "https://override.example", qualityCheck = false)

        val (merged, conflict) = mergeBothModified(base, local, remote)

        assertEquals("远端 overrideUrl 改动必须保留", "https://override.example", merged.overrideUrl)
        assertEquals("远端 qualityCheck 改动必须保留", false, merged.qualityCheck)
        assertNull("单侧改动不同字段属干净合并，不应产生冲突对", conflict)
    }

    @Test
    fun `isModified 与词汇表一致：四个标量字段逐一可判改`() {
        val base = entry(mod = t0)
        val cases = listOf(
            entry(mod = t0, iconId = 9) to "iconId",
            entry(mod = t0, customIconId = KdbxUuid.random()) to "customIconId",
            entry(mod = t0, overrideUrl = "https://x.example") to "overrideUrl",
            entry(mod = t0, qualityCheck = false) to "qualityCheck"
        )
        for ((candidate, name) in cases) {
            assertTrue("isModified 必须判出 $name 的改动", KdbxEntryMerger.isModified(base, candidate))
        }
    }

    // ── 分组侧 ──────────────────────────────────────────────────────────

    private fun group(
        mod: Instant,
        name: String = "分组",
        customIconId: KdbxUuid? = null
    ): KdbxGroup = KdbxGroup(
        id = GROUP_ID,
        name = name,
        customIconId = customIconId,
        times = KdbxTimes(creationTime = t0, lastModificationTime = mod)
    )

    @Test
    fun `分组双侧各改不同字段：customIconId 与 name 都不丢（AC④ 分组侧）`() {
        val localIcon = KdbxUuid.random()
        val base = group(mod = t0)
        val local = group(mod = t1, customIconId = localIcon)         // 本地改 customIconId
        val remote = group(mod = t2, name = "远端改名")               // 远端改 name

        val surviving = KdbxGroupMerger.mergeSurvivingGroups(
            allGroupUuids = setOf(GROUP_ID),
            baseGroups = mapOf(GROUP_ID to base),
            localGroups = mapOf(GROUP_ID to local),
            remoteGroups = mapOf(GROUP_ID to remote),
            localDeleted = emptyMap(),
            remoteDeleted = emptyMap()
        )

        val merged = surviving.getValue(GROUP_ID)
        assertEquals("本地的 customIconId 改动必须保留（不得静默丢）", localIcon, merged.customIconId)
        assertEquals("远端的 name 改动必须保留", "远端改名", merged.name)
    }

    @Test
    fun `分组同改 customIconId 异值：LWW 取胜`() {
        val localIcon = KdbxUuid.random()
        val remoteIcon = KdbxUuid.random()
        val base = group(mod = t0)
        val local = group(mod = t1, customIconId = localIcon)
        val remote = group(mod = t2, customIconId = remoteIcon)

        val surviving = KdbxGroupMerger.mergeSurvivingGroups(
            allGroupUuids = setOf(GROUP_ID),
            baseGroups = mapOf(GROUP_ID to base),
            localGroups = mapOf(GROUP_ID to local),
            remoteGroups = mapOf(GROUP_ID to remote),
            localDeleted = emptyMap(),
            remoteDeleted = emptyMap()
        )

        assertEquals("双侧异值须按 LWW 裁决（远端较新）", remoteIcon, surviving.getValue(GROUP_ID).customIconId)
    }

    private companion object {
        val ENTRY_ID: KdbxUuid = KdbxUuid.random()
        val GROUP_ID: KdbxUuid = KdbxUuid.random()
    }
}
