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
        entries: List<UiVaultEntry> = allEntries,
        groups: List<VaultGroup> = listOf(rootGroup)
    ) = buildVaultListUiState(
        library = VaultListLibraryState(
            databases = emptyList(),
            allGroups = groups,
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

    // ---------------- ISSUE-P3-360 AC⑤：首载骨架态 ----------------

    @Test
    fun `默认构造为首载中而真实投影恒为已加载`() {
        // ViewModel `stateIn(initialValue = VaultListUiState())` = 首载骨架态；
        // buildVaultListUiState 是首条真实投影，必须以 isLoading=false 落地，
        // 否则列表永远停在骨架、真实内容永不出现（骨架最危险的失效形态）。
        assertTrue("默认构造必须是首载态（渲染骨架）", VaultListUiState().isLoading)
        assertFalse("真实投影必须为已加载（渲染内容）", build().isLoading)
    }

    // ---------------- ISSUE-P3-469：模板供给（官方模板组优先，按名回落） ----------------

    private fun templateEntry(id: String, groupId: String) =
        UiVaultEntry(id = id, title = "模板-$id", username = "", url = "", groupId = groupId)

    @Test
    fun `官方模板组按 Meta 标注入选（组名非「模板」也认）`() {
        // ISSUE-P3-469 读侧：第三方库的官方模板组通常不叫「模板」，只按名扫描会漏掉它。
        val templateGroup = VaultGroup(id = "tpl", name = "Templates", parentId = null, isTemplate = true)
        val entries = allEntries + templateEntry(id = "t1", groupId = "tpl")

        val state = build(entries = entries, groups = listOf(rootGroup, templateGroup))

        assertEquals(setOf("t1"), state.templateEntries.map { it.id }.toSet())
    }

    @Test
    fun `未登记 Meta 的历史库回落按组名「模板」扫描`() {
        // 既有路径逐字保留：Meta 未登记时仍按固定组名「模板」识别（isTemplate=false）。
        val legacyGroup = VaultGroup(id = "tpl", name = "模板", parentId = null, isTemplate = false)
        val entries = allEntries + templateEntry(id = "t1", groupId = "tpl")

        val state = build(entries = entries, groups = listOf(rootGroup, legacyGroup))

        assertEquals(setOf("t1"), state.templateEntries.map { it.id }.toSet())
    }

    @Test
    fun `有官方模板组时不再按名兜底（避免同名普通组混入模板供给）`() {
        // 优先口径的鉴别力：官方组与同名普通组并存时只取官方组，否则同名普通组条目会被误当模板。
        val official = VaultGroup(id = "tpl-official", name = "Templates", parentId = null, isTemplate = true)
        val decoy = VaultGroup(id = "tpl-decoy", name = "模板", parentId = null, isTemplate = false)
        val entries = allEntries +
            templateEntry(id = "official", groupId = "tpl-official") +
            templateEntry(id = "decoy", groupId = "tpl-decoy")

        val state = build(entries = entries, groups = listOf(rootGroup, official, decoy))

        assertEquals(setOf("official"), state.templateEntries.map { it.id }.toSet())
    }
}
