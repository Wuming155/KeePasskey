package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `ISSUE-P3-160`：批量删除（`removeEntries`：按 id 集合**单趟**剪枝）的等价性与路径复制契约。
 *
 * 该改动的判据是「不再产生某类工作」（不再为每个 id 各走一次整棵树、不再无条件为沿途每个
 * 分组分配列表），对运行时不可直接观测 ⇒ 本类以两条可观测的**契约**守护：
 * ① 与逐条 `removeEntry` 的树结构逐字等价；② 未命中子树按**同一实例**复用
 * （`ISSUE-P3-118` 的路径复制契约——`assertSame` 是这条契约的唯一守护面）。
 */
class SessionTreeEditorBatchRemovalTest {

    @Test
    fun `批量删除与逐条删除的树结构逐字等价`() {
        val root = sampleTree()
        val ids = setOf(entryA, entryB, entryD)

        val batched = SessionTreeEditor.removeEntries(root, ids)
        val perId = ids.fold(root) { acc, id -> SessionTreeEditor.removeEntry(acc, id) }

        assertEquals(
            "两种实现的树结构（分组层级 + 各分组条目顺序）必须逐字相同",
            render(perId),
            render(batched)
        )
    }

    @Test
    fun `深层与根级条目可一次删净而兄弟条目保留`() {
        val root = sampleTree()

        val updated = SessionTreeEditor.removeEntries(root, setOf(entryA, entryC, entryD))

        assertEquals(
            "根级只剩未被选中的条目且顺序不变",
            listOf(entryB.toHexString()),
            updated.entries.map { it.id.toHexString() }
        )
        assertEquals("二层分组的目标条目被删除", emptyList<String>(), updated.subgroups[0].entries.map { it.id.toHexString() })
        assertEquals(
            "三层（叶子）分组的目标条目被删除",
            emptyList<String>(),
            updated.subgroups[0].subgroups[0].entries.map { it.id.toHexString() }
        )
        assertEquals("未命中分组的内容不受影响", 1, updated.subgroups[1].entries.size)
    }

    @Test
    fun `未命中任何目标 id 的子树必须按同一实例复用`() {
        val root = sampleTree()
        val untouched = root.subgroups[1]

        val updated = SessionTreeEditor.removeEntries(root, setOf(entryA))

        assertNotSame("命中根级条目时根实例必须更换（copy-on-write）", root, updated)
        assertSame(
            "未命中的子树不得被复制（ISSUE-P3-118 路径复制契约；assertSame 是该契约的唯一守护面）",
            untouched,
            updated.subgroups[1]
        )
        assertSame(
            "未命中分组内部亦须逐层复用实例",
            untouched.entries[0],
            updated.subgroups[1].entries[0]
        )
    }

    @Test
    fun `空集合与全不存在 id 均返回同一实例`() {
        val root = sampleTree()

        assertSame("空集合必须直接返回同一实例", root, SessionTreeEditor.removeEntries(root, emptySet()))

        val missing = setOf(KdbxUuid.random())
        assertSame("目标 id 全不存在时必须返回同一实例（零复制）", root, SessionTreeEditor.removeEntries(root, missing))
    }

    @Test
    fun `单条删除未命中时同样不得更换实例`() {
        val root = sampleTree()

        assertSame(
            "单条删除未命中不做任何复制（原实现虽也返回同一实例，但无条件 map 出了整棵子分组列表）",
            root,
            SessionTreeEditor.removeEntry(root, KdbxUuid.random())
        )
    }

    private companion object {
        val entryA: KdbxUuid = KdbxUuid.random()
        val entryB: KdbxUuid = KdbxUuid.random()
        val entryC: KdbxUuid = KdbxUuid.random()
        val entryD: KdbxUuid = KdbxUuid.random()

        /** root ├ A,B ├ deep(D ├ leaf(C)) └ untouched(1 条无关条目) */
        fun sampleTree(): KdbxGroup {
            val leaf = KdbxGroup(name = "leaf", entries = listOf(KdbxEntry(id = entryC)))
            val deep = KdbxGroup(
                name = "deep",
                entries = listOf(KdbxEntry(id = entryD)),
                subgroups = listOf(leaf)
            )
            return KdbxGroup(
                name = "root",
                entries = listOf(KdbxEntry(id = entryA), KdbxEntry(id = entryB)),
                subgroups = listOf(deep, KdbxGroup(name = "untouched", entries = listOf(KdbxEntry())))
            )
        }

        fun render(group: KdbxGroup): String =
            "${group.name}[${group.entries.joinToString(",") { it.id.toHexString() }}]" +
                "(${group.subgroups.joinToString(",") { render(it) }})"
    }
}
