package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-156 回归：`SessionTreeEditor` 的**路径复制契约**与替换关系回报。
 *
 * 路径复制既是 O(分组数) 对象复制的消除，也是增量定点擦除（
 * `KdbxGroup.eraseSupersededSensitiveData`）的**正确性前提**——新树除替换位置外必须与旧树
 * 按**同一对象引用**共享全部子树。故此处以 `assertSame` 逐处锁定「未命中分支按引用复用」，
 * 防止后人改回整树 `copy`（改回即让增量擦除的候选前提失效）。
 */
class SessionTreeEditorPathCopyTest {

    private fun uuid(seed: Byte) = KdbxUuid(ByteArray(16) { seed })

    private fun entry(idSeed: Byte, parentGroupId: KdbxUuid?, password: String = "secret-$idSeed") = KdbxEntry(
        id = uuid(idSeed),
        parentGroupId = parentGroupId,
        fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString(password, isProtected = true))
    )

    /**
     * 三层树：`root` → { `untouched`（含 1 条目）, `target` → `deep` }
     */
    private class Tree(val root: KdbxGroup, val untouched: KdbxGroup, val target: KdbxGroup, val deep: KdbxGroup)

    private fun tree(): Tree {
        val root = KdbxGroup(id = uuid(1), name = "root")
        val untouched = KdbxGroup(
            id = uuid(2),
            parentGroupId = root.id,
            name = "untouched",
            entries = listOf(entry(20, uuid(2)))
        )
        val deep = KdbxGroup(id = uuid(4), parentGroupId = uuid(3), name = "deep")
        val target = KdbxGroup(id = uuid(3), parentGroupId = root.id, name = "target", subgroups = listOf(deep))
        return Tree(root.copy(subgroups = listOf(untouched, target)), untouched, target, deep)
    }

    @Test
    fun `条目落树只重建从根到目标的分组链_未命中分支按同一实例复用`() {
        val tree = tree()
        val added = entry(30, tree.deep.id)

        val edit = SessionTreeEditor.updateOrAddEntry(tree.root, added)

        assertSame("命中路径之外的兄弟子树必须按同一实例复用", tree.untouched, edit.root.subgroups[0])
        assertSame("未命中分支的条目列表同样必须按同一实例复用", tree.untouched.entries, edit.root.subgroups[0].entries)
        assertTrue("命中路径上的分组需重建", edit.root.subgroups[1] !== tree.target)
        assertTrue("命中路径上的分组需重建（深层）", edit.root.subgroups[1].subgroups[0] !== tree.deep)
        assertSame(
            "新条目落在目标父组",
            added,
            edit.root.subgroups[1].subgroups[0].entries.single()
        )
        assertEquals("原树的目标父组保持空（不得原地改写）", 0, tree.deep.entries.size)
        assertNull("新增条目无替换关系", edit.replaced)
        assertSame(added, edit.replacement)
    }

    @Test
    fun `目标父组不在本树时原样返回同一根实例`() {
        val tree = tree()
        val orphan = entry(31, uuid(99))

        val edit = SessionTreeEditor.updateOrAddEntry(tree.root, orphan)

        assertSame("找不到目标父组不得整树重建", tree.root, edit.root)
        assertNull("无替换关系", edit.replaced)
    }

    @Test
    fun `替换既有条目时回报被替换的旧条目与同位置上线的条目`() {
        val root = KdbxGroup(id = uuid(1), name = "root")
        val old = entry(30, root.id, password = "old")
        val rootWithEntry = root.copy(entries = listOf(old))
        val replacement = old.withField(
            KdbxConstants.Fields.PASSWORD,
            ProtectedString("new", isProtected = true)
        )

        val edit = SessionTreeEditor.updateOrAddEntry(rootWithEntry, replacement)

        assertSame("必须回报被替换下线的旧条目", old, edit.replaced)
        assertSame("必须回报同位置上线的条目", replacement, edit.replacement)
        assertSame(replacement, edit.root.entries.single())
    }

    @Test
    fun `null 父组条目落树时仍回报替换关系与规范化后的上线条目`() {
        val root = KdbxGroup(id = uuid(1), name = "root")
        val old = entry(30, null, password = "old")
        val rootWithEntry = root.copy(entries = listOf(old))
        val edited = old.copy(parentGroupId = null)

        val edit = SessionTreeEditor.updateOrAddEntry(rootWithEntry, edited)

        assertSame(old, edit.replaced)
        assertEquals(root.id, edit.replacement!!.parentGroupId)
        assertEquals(root.id, edit.root.entries.single().parentGroupId)
    }

    @Test
    fun `分组替换回报旧分组与上线分组_且无 null 父组需修正时原样复用传入实例`() {
        val root = KdbxGroup(id = uuid(1), name = "root")
        val child = entry(30, uuid(3))
        val existing = KdbxGroup(id = uuid(3), parentGroupId = root.id, name = "sub", entries = listOf(child))
        val rootWithGroup = root.copy(subgroups = listOf(existing))
        // VaultGroupCoordinator 语义：基于既有分组 copy 仅改元数据（子项按引用共享）
        val renamed = existing.copy(name = "renamed")

        val edit = SessionTreeEditor.updateOrAddGroup(rootWithGroup, renamed)

        assertSame("必须回报被替换下线的旧分组", existing, edit.replaced)
        assertSame("无 null 父组需修正 ⇒ 原样复用传入分组实例", renamed, edit.replacement)
        assertSame("既有子项按同一实例复用", existing.entries, edit.replacement!!.entries)
        assertSame(child, edit.root.subgroups.single().entries.single())
    }

    @Test
    fun `分组替换时传入分组无子项则回报「合并了既有子项」的上线分组`() {
        val root = KdbxGroup(id = uuid(1), name = "root")
        val child = entry(30, uuid(3))
        val existing = KdbxGroup(id = uuid(3), parentGroupId = root.id, name = "sub", entries = listOf(child))
        val rootWithGroup = root.copy(subgroups = listOf(existing))
        val bare = KdbxGroup(id = uuid(3), parentGroupId = root.id, name = "sub")

        val edit = SessionTreeEditor.updateOrAddGroup(rootWithGroup, bare)

        assertSame(existing, edit.replaced)
        val placed = edit.replacement
        assertNotNull(placed)
        assertSame("P0-1 保护：既有子项并入上线分组", existing.entries, placed!!.entries)
        assertEquals("上线分组保留传入元数据", "sub", placed.name)
    }

    @Test
    fun `新增分组无替换关系_未命中分支同样按引用复用`() {
        val tree = tree()
        val fresh = KdbxGroup(id = uuid(5), parentGroupId = tree.deep.id, name = "fresh")

        val edit = SessionTreeEditor.updateOrAddGroup(tree.root, fresh)

        assertNull("新增分组无替换关系", edit.replaced)
        assertSame("未命中分支必须按同一实例复用", tree.untouched, edit.root.subgroups[0])
        assertSame(fresh, edit.root.subgroups[1].subgroups[0].subgroups.single())
    }

    @Test
    fun `整根替换回报旧根与新根_且 P0-1 保护生效`() {
        val root = KdbxGroup(id = uuid(1), name = "root", entries = listOf(entry(30, uuid(1))))
        val bare = KdbxGroup(id = uuid(1), name = "renamed")

        val edit = SessionTreeEditor.replaceRootGroup(root, bare)

        assertSame(root, edit.replaced)
        assertNotNull(edit.replacement)
        assertSame("既有子项并入新根（P0-1 保护）", root.entries, edit.replacement!!.entries)
        assertSame("上线新根实例即 `preserveChildrenIfMissing` 的产物", edit.replacement, edit.root)
        assertEquals("renamed", edit.root.name)
    }

    @Test
    fun `删除路径未命中时返回同一实例_命中时仅重建路径上的分组`() {
        val tree = tree()

        assertSame("未命中条目的删除不得重建任何分组", tree.root, SessionTreeEditor.removeEntry(tree.root, uuid(99)))
        assertSame("未命中分组的删除不得重建任何分组", tree.root, SessionTreeEditor.removeGroup(tree.root, uuid(99)))

        val removedEntry = SessionTreeEditor.removeEntry(tree.root, uuid(20))
        assertSame("未命中分支按同一实例复用", tree.target, removedEntry.subgroups[1])
        assertEquals("命中分支的条目被移除", 0, removedEntry.subgroups[0].entries.size)

        val removedGroup = SessionTreeEditor.removeGroup(tree.root, uuid(2))
        assertSame("未命中分支按同一实例复用", tree.target, removedGroup.subgroups.single())
    }
}