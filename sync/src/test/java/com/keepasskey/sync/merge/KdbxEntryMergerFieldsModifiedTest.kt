package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `ISSUE-P3-543`：合并侧修改判定（[KdbxEntryMerger.isModified]）的 `fields` 比较口径
 * 与上传侧 `KdbxContentComparator` **同源**（同引 [KdbxEntry.fieldsContentEquals]）。
 *
 * ## 锁定的缺陷
 *
 * 整改前该处走 `base.fields != current.fields`（`Map` → [ProtectedString.equals]，**把
 * `isProtected` 计入等值**）。而标准五字段的该标志**不参与序列化往返**（写侧库级
 * MemoryProtection 无条件覆盖 per-value）⇒「解析出的 base」与「内存构造的 current」
 * 可能同内容却标志不同，被判「已修改」。取证探针（本批临时探针，已删）读数：
 * 同内容 / 异标志 ⇒ `isModified=true`（双向）；删除 vs 修改分支据此把
 * **内容未变的远端条目判为「已修改」而复活**（`survived=1`）。
 */
class KdbxEntryMergerFieldsModifiedTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** 「解析实例」：非口令标准字段标志 false（＝写侧库级默认产物读回的形态）。 */
    private fun parsed(titleProtected: Boolean = false): KdbxEntry = KdbxEntry(
        id = ENTRY_ID,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("标题", isProtected = titleProtected),
            KdbxConstants.Fields.USER_NAME to ProtectedString("alice", isProtected = titleProtected),
            KdbxConstants.Fields.PASSWORD to ProtectedString("pw", isProtected = true)
        ),
        times = KdbxTimes(creationTime = t0, lastModificationTime = t0)
    )

    /** 「内存构造实例」：同内容，标准五字段按 `ProtectedString(text)` 默认（true）。 */
    private fun inMemory(title: String = "标题"): KdbxEntry = KdbxEntry(
        id = ENTRY_ID,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = true),
            KdbxConstants.Fields.USER_NAME to ProtectedString("alice", isProtected = true),
            KdbxConstants.Fields.PASSWORD to ProtectedString("pw", isProtected = true)
        ),
        times = KdbxTimes(creationTime = t0, lastModificationTime = t0)
    )

    @Test
    fun `标准字段仅受保护标志不同不得判为已修改`() {
        val base = parsed()
        val current = inMemory()

        assertFalse(
            "同内容、仅标志不同（标准五字段）不得判为已修改——否则冲突裁决 / 「双方都改」产生噪声",
            KdbxEntryMerger.isModified(base, current)
        )
        assertFalse("（参数对调后同样成立）", KdbxEntryMerger.isModified(current, base))
    }

    @Test
    fun `标准字段内容不同仍必须判为已修改`() {
        val base = parsed()
        val current = inMemory(title = "改过的标题")

        assertTrue(KdbxEntryMerger.isModified(base, current))
        assertTrue("（参数对调后同样成立）", KdbxEntryMerger.isModified(current, base))
    }

    @Test
    fun `非标准字段仅受保护标志不同仍判为已修改`() {
        val base = KdbxEntry(
            id = ENTRY_ID,
            fields = mapOf("Custom" to ProtectedString("v", isProtected = true)),
            times = KdbxTimes(creationTime = t0, lastModificationTime = t0)
        )
        val current = base.copy(
            fields = mapOf("Custom" to ProtectedString("v", isProtected = false))
        )

        assertTrue(
            "非标准字段的 per-value 标志是被序列化的真值 ⇒ 标志差异仍须判为已修改",
            KdbxEntryMerger.isModified(base, current)
        )
        assertTrue("（参数对调后同样成立）", KdbxEntryMerger.isModified(current, base))
    }

    @Test
    fun `本地删除与内容未变的远端：不得误判远端已修改而复活条目`() {
        val base = parsed()
        val remote = inMemory()

        val (surviving, conflicts) = KdbxEntryMerger.mergeSurvivingEntries(
            allEntryUuids = setOf(ENTRY_ID),
            baseEntries = mapOf(ENTRY_ID to base),
            localEntries = emptyMap(),
            remoteEntries = mapOf(ENTRY_ID to remote),
            localDeleted = emptyMap(),
            remoteDeleted = emptyMap()
        )

        assertEquals(
            "远端内容未变（仅标准字段标志不同）⇒ 删除必须被确认，条目不得复活",
            0,
            surviving.size
        )
        assertEquals("无存活条目即无冲突对", 0, conflicts.size)
    }

    @Test
    fun `同口径对照：解析实例之间（标志一致）不得判为已修改`() {
        assertFalse(
            "对照读数：判据只对标志分叉敏感，非恒真",
            KdbxEntryMerger.isModified(parsed(), parsed())
        )
    }

    private companion object {
        private val ENTRY_ID: KdbxUuid = KdbxUuid.random()
    }
}
