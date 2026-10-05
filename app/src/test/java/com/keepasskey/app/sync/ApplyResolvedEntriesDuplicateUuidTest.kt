package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `applyResolvedEntriesToGroup` 落位语义锁定（ISSUE-P3-488）。
 *
 * §444（ISSUE-P3-485）把组内 `indexOfFirst` 改为一次建表时用了顺序覆盖（last-wins），
 * 与原 `indexOfFirst`（first-wins）在 `group.entries` 自身含重复 UUID 时分歧。
 * 本用例锁定与逐条覆盖逐例等价的三类边界：
 * 1. `working` 内重复 → 落位**首个**槽位；
 * 2. `own` 内重复（替换路径）→ 后者胜出；
 * 3. `own` 内重复（追加后又替换）→ 单条追加、内容为后者。
 */
class ApplyResolvedEntriesDuplicateUuidTest {

    private fun uuidOf(hex: String): KdbxUuid =
        KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private fun entry(id: KdbxUuid, title: String) = KdbxEntry(
        id = id,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false))
    )

    private fun groupOf(groupId: KdbxUuid, vararg entries: KdbxEntry) = KdbxGroup(
        id = groupId,
        name = "G",
        entries = entries.toList()
    )

    @Test
    fun `working内重复UUID时落位首个槽位`() {
        val groupId = uuidOf("00000000000000000000000000000010")
        val dupId = uuidOf("00000000000000000000000000000011")
        val group = groupOf(groupId, entry(dupId, "A"), entry(dupId, "B"))
        val resolved = entry(dupId, "X")

        val result = applyResolvedEntriesToGroup(group, mapOf(groupId to listOf(resolved)))

        assertEquals(2, result.entries.size)
        assertEquals("X", result.entries[0].title)
        assertEquals("B", result.entries[1].title)
    }

    @Test
    fun `own内重复替换路径以后者为准`() {
        val groupId = uuidOf("00000000000000000000000000000020")
        val id = uuidOf("00000000000000000000000000000021")
        val group = groupOf(groupId, entry(id, "A"))

        val result = applyResolvedEntriesToGroup(
            group,
            mapOf(groupId to listOf(entry(id, "X1"), entry(id, "X2")))
        )

        assertEquals(1, result.entries.size)
        assertEquals("X2", result.entries[0].title)
    }

    @Test
    fun `own内重复追加路径只追加一条且内容为后者`() {
        val groupId = uuidOf("00000000000000000000000000000030")
        val baseId = uuidOf("00000000000000000000000000000031")
        val newId = uuidOf("00000000000000000000000000000032")
        val group = groupOf(groupId, entry(baseId, "A"))

        val result = applyResolvedEntriesToGroup(
            group,
            mapOf(groupId to listOf(entry(newId, "N1"), entry(newId, "N2")))
        )

        assertEquals(2, result.entries.size)
        assertEquals("A", result.entries[0].title)
        assertEquals("N2", result.entries[1].title)
    }

    @Test
    fun `无命中时返回同一实例且保持原顺序`() {
        val groupId = uuidOf("00000000000000000000000000000040")
        val idA = uuidOf("00000000000000000000000000000041")
        val idB = uuidOf("00000000000000000000000000000042")
        val otherId = uuidOf("00000000000000000000000000000043")
        val group = groupOf(groupId, entry(idA, "A"), entry(idB, "B"))

        val untouched = applyResolvedEntriesToGroup(group, mapOf(otherId to listOf(entry(idA, "X"))))
        assertTrue(untouched === group)

        val replaced = applyResolvedEntriesToGroup(
            group,
            mapOf(groupId to listOf(entry(idB, "B2"), entry(uuidOf("00000000000000000000000000000044"), "C")))
        )
        assertEquals(listOf("A", "B2", "C"), replaced.entries.map { it.title })
    }
}
