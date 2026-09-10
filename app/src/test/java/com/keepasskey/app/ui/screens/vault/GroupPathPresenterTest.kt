package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.VaultGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISSUE-P3-17 分组完整路径解析单测（搜索结果行与详情页「所属分组」共用同一纯函数）。
 */
class GroupPathPresenterTest {

    private val groups = listOf(
        VaultGroup(id = "g_root", name = "工作与生产力", parentId = null),
        VaultGroup(id = "g_dev", name = "研发与基础设施", parentId = "g_root"),
        VaultGroup(id = "g_k8s", name = "K8s 集群", parentId = "g_dev"),
        VaultGroup(id = "g_orphan", name = "父链断裂分组", parentId = "g_missing")
    )

    @Test
    fun `多级分组返回自根到该分组的完整路径`() {
        assertEquals(
            "工作与生产力 / 研发与基础设施 / K8s 集群",
            GroupPathPresenter.fullPathOf(groups, "g_k8s")
        )
    }

    @Test
    fun `顶层分组路径即其自身名称`() {
        assertEquals("工作与生产力", GroupPathPresenter.fullPathOf(groups, "g_root"))
    }

    @Test
    fun `分组 id 为空或不存在时返回 null`() {
        assertNull(GroupPathPresenter.fullPathOf(groups, null))
        assertNull(GroupPathPresenter.fullPathOf(groups, "g_not_exist"))
    }

    @Test
    fun `父链断裂时按已解析层级截断而不丢归属`() {
        assertEquals("父链断裂分组", GroupPathPresenter.fullPathOf(groups, "g_orphan"))
    }

    @Test
    fun `父链成环时截断不死循环`() {
        val cyclic = listOf(
            VaultGroup(id = "a", name = "A", parentId = "b"),
            VaultGroup(id = "b", name = "B", parentId = "a")
        )
        // 环上溯被已访问集合截断：结果为环内两节点各出现一次，且必然返回（不挂死）
        val path = GroupPathPresenter.fullPathOf(cyclic, "a")
        assertEquals("B / A", path)
    }

    @Test
    fun `批量解析按分组 id 给出完整路径`() {
        val paths = GroupPathPresenter.pathsOf(groups)
        assertEquals("工作与生产力 / 研发与基础设施", paths["g_dev"])
        assertEquals("工作与生产力", paths["g_root"])
        assertEquals("父链断裂分组", paths["g_orphan"])
    }
}
