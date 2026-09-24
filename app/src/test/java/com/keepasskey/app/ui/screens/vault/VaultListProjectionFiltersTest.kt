package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表筛选（标签 / 收藏档）投影单元测试（ISSUE-P3-297 处置③）。
 *
 * 覆盖：收藏档、标签档、两档叠加、候选标签装配（去重 + 大小写不敏感排序）、
 * 「是否有收藏」可见性判据，以及筛选与搜索的独立性（无查询时筛选仍生效）。
 */
class VaultListProjectionFiltersTest {

    private val rootGroup = VaultGroup(id = "g1", name = "根分组", parentId = null)

    private fun entry(
        id: String,
        title: String = "站点",
        tags: List<String> = emptyList(),
        isFavorite: Boolean = false
    ) = UiVaultEntry(
        id = id,
        title = title,
        username = "user",
        url = "https://example.com",
        groupId = "g1",
        tags = tags,
        isFavorite = isFavorite
    )

    private val allEntries = listOf(
        entry(id = "e1", title = "GitHub", tags = listOf("工作", "开发")),
        entry(id = "e2", title = "银行", tags = listOf("财务"), isFavorite = true),
        entry(id = "e3", title = "邮箱", tags = listOf("工作"), isFavorite = true)
    )

    private fun build(
        selectedTag: String? = null,
        favoriteOnly: Boolean = false,
        query: String = "",
        entries: List<UiVaultEntry> = allEntries
    ) = buildVaultListUiState(
        library = VaultListLibraryState(
            databases = emptyList(),
            allGroups = listOf(rootGroup),
            allEntries = entries
        ),
        settings = UserSettings(),
        session = VaultListSessionState(
            currentGroupId = null,
            filterParams = VaultListFilterParams(
                query = query,
                isSearchActive = false,
                sortOption = VaultSortOption.DEFAULT,
                selectedTag = selectedTag,
                favoriteOnly = favoriteOnly
            ),
            userMessage = null
        ),
        batchSyncDecorations = VaultListBatchSyncDecorations(
            batchAndSync = VaultListBatchAndSyncState(
                isBatchMode = false,
                selectedEntryIds = emptySet(),
                isSyncing = false,
                lastSyncTimeText = ""
            ),
            decorations = EntryDecorations.EMPTY,
            groupIcons = emptyMap()
        )
    )

    @Test
    fun `无筛选档时不过滤条目`() {
        val state = build()
        assertEquals(setOf("e1", "e2", "e3"), state.entries.map { it.id }.toSet())
        assertNull(state.selectedTag)
        assertFalse(state.favoriteOnly)
    }

    @Test
    fun `收藏档只保留收藏条目`() {
        val state = build(favoriteOnly = true)
        assertEquals(setOf("e2", "e3"), state.entries.map { it.id }.toSet())
        assertTrue(state.favoriteOnly)
    }

    @Test
    fun `标签档只保留命中标签的条目`() {
        val state = build(selectedTag = "财务")
        assertEquals(setOf("e2"), state.entries.map { it.id }.toSet())
        assertEquals("财务", state.selectedTag)
    }

    @Test
    fun `标签与收藏两档可叠加`() {
        val state = build(selectedTag = "工作", favoriteOnly = true)
        assertEquals(setOf("e3"), state.entries.map { it.id }.toSet())
    }

    @Test
    fun `筛选档在搜索关键词为空时依然生效`() {
        // 回归口径：筛选与搜索是两个独立合取项，未搜索时不得被空查询旁路
        val state = build(favoriteOnly = true, query = "   ")
        assertEquals(setOf("e2", "e3"), state.entries.map { it.id }.toSet())
    }

    @Test
    fun `候选标签按精确值去重并大小写不敏感排序`() {
        // 去重按精确值（KDBX 标签区分大小写，选中档须与 tags 精确匹配）；
        // 排序用大小写不敏感比较器
        val entries = listOf(
            entry(id = "e1", tags = listOf("Work", "财务")),
            entry(id = "e2", tags = listOf("work", "开发"))
        )
        val state = build(entries = entries)
        assertEquals(listOf("Work", "work", "开发", "财务"), state.availableTags)
    }

    @Test
    fun `收藏可见性判据随条目集合`() {
        assertTrue(build().hasFavoriteEntries)
        assertFalse(
            build(entries = listOf(entry(id = "e1")))
                .hasFavoriteEntries
        )
    }

    @Test
    fun `无标签条目时候选为空表`() {
        val state = build(entries = listOf(entry(id = "e1")))
        assertTrue(state.availableTags.isEmpty())
    }
}
