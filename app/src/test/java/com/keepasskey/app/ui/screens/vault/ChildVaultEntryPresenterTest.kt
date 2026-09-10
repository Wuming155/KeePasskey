package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.childdb.ChildDatabaseEntryProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-30：子库投影 → 列表只读分区的装配单测（纯函数，无 Android 依赖）。
 *
 * 覆盖：空输入、按挂载归拢与顺序保持、展示路径口径（根级回退别名 / 子分组展开分隔符）、
 * 跨挂载同名 UUID 的行 key 唯一性（LazyColumn 重复 key 会直接崩溃，必须守护）。
 */
class ChildVaultEntryPresenterTest {

    private fun projection(
        mountId: String = MOUNT_ID,
        mountAlias: String = MOUNT_ALIAS,
        entryUuid: String = "uuid-1",
        title: String = "条目",
        groupPath: String = ""
    ): ChildDatabaseEntryProjection = ChildDatabaseEntryProjection(
        mountId = mountId,
        mountAlias = mountAlias,
        entryUuid = entryUuid,
        title = title,
        username = "user-$title",
        url = "https://example.com/$title",
        notes = "",
        groupPath = groupPath,
        tags = emptyList(),
        iconId = 0,
        hasPassword = true
    )

    @Test
    fun `没有已打开子库时不产出任何分区`() {
        assertTrue(ChildVaultEntryPresenter.groupsOf(emptyList()).isEmpty())
    }

    @Test
    fun `按挂载归拢并保持投影给出的顺序`() {
        val groups = ChildVaultEntryPresenter.groupsOf(
            listOf(
                projection(mountId = "m1", mountAlias = "子库一", entryUuid = "a", title = "A"),
                projection(mountId = "m2", mountAlias = "子库二", entryUuid = "b", title = "B"),
                projection(mountId = "m1", mountAlias = "子库一", entryUuid = "c", title = "C")
            )
        )

        assertEquals(listOf("m1", "m2"), groups.map { it.mountId })
        assertEquals(listOf("子库一", "子库二"), groups.map { it.mountAlias })
        assertEquals(listOf("A", "C"), groups[0].entries.map { it.title })
        assertEquals(listOf("B"), groups[1].entries.map { it.title })
    }

    @Test
    fun `根级条目路径回退为挂载别名`() {
        val groups = ChildVaultEntryPresenter.groupsOf(
            listOf(projection(mountAlias = "工作子库", groupPath = ""))
        )

        assertEquals("工作子库", groups.single().entries.single().displayPath)
    }

    @Test
    fun `子分组路径以统一分隔符展开且带挂载别名前缀`() {
        val groups = ChildVaultEntryPresenter.groupsOf(
            listOf(projection(mountAlias = "工作子库", groupPath = "分组一/更深分组"))
        )

        assertEquals(
            "工作子库" + GroupPathPresenter.SEPARATOR + "分组一" + GroupPathPresenter.SEPARATOR + "更深分组",
            groups.single().entries.single().displayPath
        )
    }

    @Test
    fun `跨挂载的同名 UUID 生成互不相同的行 key`() {
        val groups = ChildVaultEntryPresenter.groupsOf(
            listOf(
                projection(mountId = "m1", mountAlias = "子库一", entryUuid = "same-uuid"),
                projection(mountId = "m2", mountAlias = "子库二", entryUuid = "same-uuid")
            )
        )

        val keys = groups.flatMap { it.entries }.map { it.rowKey }
        assertEquals("行 key 必须两两不同", keys.size, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `展示行只携带渲染字段且与投影同步`() {
        val groups = ChildVaultEntryPresenter.groupsOf(
            listOf(
                projection(
                    mountId = "m-9",
                    mountAlias = "别名",
                    entryUuid = "uuid-9",
                    title = "标题",
                    groupPath = "子"
                )
            )
        )

        val row = groups.single().entries.single()
        assertEquals("m-9", row.mountId)
        assertEquals("m-9:uuid-9", row.rowKey)
        assertEquals("别名", row.mountAlias)
        assertEquals("uuid-9", row.entryUuid)
        assertEquals("标题", row.title)
        assertEquals("user-标题", row.username)
        assertEquals("https://example.com/标题", row.url)
    }

    private companion object {
        const val MOUNT_ID = "mount-1"
        const val MOUNT_ALIAS = "子库一"
    }
}
