package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-P2-597：三方合并补齐条目语义——AutoType 整体配置 / 前景与背景色 / **联合到期**。
 *
 * 锁定「判定（`isModified`）= 实际合并 = 上报（`diffFields`）」同一词汇表；双边同值不产生虚假冲突；
 * 到期按**联合语义**裁决，绝不出现「两端都未选择的混合状态」。
 */
class KdbxEntryMergerSemanticsExpansionTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-02-01T00:00:00Z")
    private val t2: Instant = Instant.parse("2026-03-01T00:00:00Z")

    private fun entry(
        mod: Instant,
        title: String = "条目",
        autoType: KdbxAutoType? = null,
        background: String? = null,
        foreground: String? = null,
        expires: Boolean = false,
        expiryTime: Instant = t0
    ): KdbxEntry = KdbxEntry(
        id = ENTRY_ID,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false)),
        autoType = autoType,
        backgroundColor = background,
        foregroundColor = foreground,
        times = KdbxTimes(creationTime = t0, lastModificationTime = mod, expires = expires, expiryTime = expiryTime)
    )

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
    fun `isModified 检出 AutoType 与颜色与到期变化`() {
        val base = entry(t0)
        assertTrue(
            "远端只改 autoType 必须判为已修改（否则整表被本地覆盖、远端改动静默丢失）",
            KdbxEntryMerger.isModified(base, base.copy(autoType = KdbxAutoType(enabled = false)))
        )
        assertTrue("远端只改背景色必须判为已修改", KdbxEntryMerger.isModified(base, base.copy(backgroundColor = "#FF0000")))
        assertTrue("远端只改前景色必须判为已修改", KdbxEntryMerger.isModified(base, base.copy(foregroundColor = "#00FF00")))
        assertTrue(
            "远端启用到期必须判为已修改",
            KdbxEntryMerger.isModified(base, base.copy(times = base.times.copy(expires = true, expiryTime = t2)))
        )
    }

    @Test
    fun `本地改标题远端改到期与AutoType与颜色：三项均不丢且无冲突`() {
        val base = entry(t0, title = "原标题")
        val local = entry(t1, title = "本地改名")
        val remote = entry(
            t2,
            title = "原标题",
            autoType = KdbxAutoType(enabled = false, defaultSequence = "{USERNAME}{TAB}"),
            background = "#111111",
            expires = true,
            expiryTime = t2
        )

        val (merged, conflict) = mergeBothModified(base, local, remote)

        assertEquals("本地标题改动保留", "本地改名", merged.title)
        assertEquals("远端 AutoType 改动保留", false, merged.autoType?.enabled)
        assertEquals("远端背景色改动保留", "#111111", merged.backgroundColor)
        assertTrue("远端到期改动保留", merged.times.expires)
        assertEquals("到期时刻与开关来自同一侧（联合）", t2, merged.times.expiryTime)
        assertNull("不同字段的分歧属干净合并，不应产生冲突", conflict)
    }

    @Test
    fun `双侧异值改到期：联合裁决进冲突清单且不产生混合状态`() {
        val base = entry(t0)
        val local = entry(t1, expires = true, expiryTime = t1)
        val remote = entry(t2, expires = true, expiryTime = t2)

        val (merged, conflict) = mergeBothModified(base, local, remote)

        assertTrue(
            "联合到期差异必须进冲突清单",
            conflict!!.modifiedFields.contains(KdbxMerger.CONFLICT_KEY_EXPIRES)
        )
        assertTrue("LWW（远端较新）整组采用远端：expires 保持生效", merged.times.expires)
        assertEquals("且到期时刻来自同一侧", t2, merged.times.expiryTime)
    }

    @Test
    fun `双侧同值改 AutoType 不产生虚假冲突`() {
        val base = entry(t0)
        val changed = KdbxAutoType(enabled = false)
        val local = entry(t1, autoType = changed)
        val remote = entry(t2, autoType = changed)

        val (merged, conflict) = mergeBothModified(base, local, remote)

        assertEquals(false, merged.autoType?.enabled)
        assertNull("双侧同值不得产生虚假冲突", conflict)
    }

    @Test
    fun `禁用到期时旧日期差异不判为已修改`() {
        val base = entry(t0)
        val candidate = base.copy(times = base.times.copy(expires = false, expiryTime = t2))

        assertTrue(
            "expires == false 时 expiryTime 不生效 ⇒ 不得判为已修改（避免无编辑也复活条目）",
            !KdbxEntryMerger.isModified(base, candidate)
        )
    }

    @Test
    fun `冲突裁决采用远端到期与AutoType与颜色`() {
        val local = entry(t1)
        val remote = entry(
            t2,
            autoType = KdbxAutoType(enabled = false),
            background = "#222222",
            foreground = "#333333",
            expires = true,
            expiryTime = t2
        )
        val pair = ConflictedEntryPair(
            entryId = ENTRY_ID.toHexString(),
            localEntry = local,
            remoteEntry = remote,
            modifiedFields = listOf(
                KdbxMerger.CONFLICT_KEY_AUTO_TYPE,
                KdbxMerger.CONFLICT_KEY_EXPIRES,
                KdbxMerger.CONFLICT_KEY_BACKGROUND_COLOR,
                KdbxMerger.CONFLICT_KEY_FOREGROUND_COLOR
            )
        )

        val resolved = KdbxMerger.resolveConflictByFields(
            pair,
            pair.modifiedFields.associateWith { FieldResolutionChoice.KEEP_REMOTE }
        )

        assertEquals(false, resolved.autoType?.enabled)
        assertEquals("#222222", resolved.backgroundColor)
        assertEquals("#333333", resolved.foregroundColor)
        assertTrue(resolved.times.expires)
        assertEquals(t2, resolved.times.expiryTime)
    }

    private companion object {
        val ENTRY_ID: KdbxUuid = KdbxUuid.random()
    }
}
