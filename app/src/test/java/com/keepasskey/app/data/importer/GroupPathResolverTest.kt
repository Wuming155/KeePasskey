package com.keepasskey.app.data.importer

import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ISSUE-P3-63 回归：分组路径解析的根组语义。
 *
 * 此前空路径（根级条目）解析返回 `groupId=null`，而会话树规范化后条目携带
 * 真实根组 id——重复导入根级条目时去重键（EntryKey.groupId）错配，会把已有
 * 条目误判为新增。修复后空路径返回**根分组的真实 id**。
 */
class GroupPathResolverTest {

    private val rootGroup = VaultGroup(id = "root000000000000", name = "Root", parentId = null)

    private val topLevel = VaultGroup(
        id = "top00000000000000",
        name = "Top",
        parentId = rootGroup.id
    )

    @Test
    fun `空路径返回根分组真实 id 而非 null`() = runTest {
        val resolver = GroupPathResolver(listOf(rootGroup)) { KdbxResult.Success(Unit) }

        val resolution = resolver.resolve(emptyList())

        assertEquals(rootGroup.id, resolution.groupId)
        assertFalse(resolution.creationFailed)
    }

    @Test
    fun `顶级路径按根组锚点匹配既有顶级分组`() = runTest {
        val created = mutableListOf<VaultGroup>()
        val resolver = GroupPathResolver(listOf(rootGroup, topLevel)) { group ->
            created += group
            KdbxResult.Success(Unit)
        }

        val resolution = resolver.resolve(listOf("Top"))

        assertEquals(topLevel.id, resolution.groupId)
        assertEquals(0, created.size)
    }

    @Test
    fun `未知顶级路径以根组 id 为父组创建而非 null`() = runTest {
        val created = mutableListOf<VaultGroup>()
        val resolver = GroupPathResolver(listOf(rootGroup)) { group ->
            created += group
            KdbxResult.Success(Unit)
        }

        val resolution = resolver.resolve(listOf("NewGroup"))

        assertEquals("NewGroup", resolution.groupId?.let { created.single { g -> g.id == it }.name })
        assertEquals(rootGroup.id, created.single().parentId)
    }

    @Test
    fun `空路径但已知分组缺失根组时回退 null 不崩溃`() = runTest {
        val resolver = GroupPathResolver(emptyList()) { KdbxResult.Success(Unit) }

        val resolution = resolver.resolve(emptyList())

        assertEquals(null, resolution.groupId)
        assertFalse(resolution.creationFailed)
    }
}
