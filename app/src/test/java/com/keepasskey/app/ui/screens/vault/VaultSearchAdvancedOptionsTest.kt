package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.settings.SearchAdvancedOptions
import com.keepasskey.app.ui.screens.settings.SearchField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 高级搜索选项单元测试（ISSUE-P3-439 AC①②）。
 *
 * 覆盖：字段范围勾选的收窄与默认全选零变化、大小写档（默认不敏感＝现状）、
 * 「排除已过期」的搜索态投影生效与默认关闭零变化。
 */
class VaultSearchAdvancedOptionsTest {

    private fun entry(
        title: String = "站点",
        username: String = "user",
        url: String = "https://example.com",
        notes: String = "",
        tags: List<String> = emptyList(),
        customFields: List<UiCustomField> = emptyList()
    ) = UiVaultEntry(
        id = "e1",
        title = title,
        username = username,
        url = url,
        notes = notes,
        tags = tags,
        customFields = customFields
    )

    // ===== AC①：字段范围勾选 =====

    @Test
    fun `默认全选时全部字段可命中`() {
        val target = entry(
            title = "标题甲",
            username = "乙用户",
            notes = "丙备注",
            tags = listOf("丁标签"),
            customFields = listOf(UiCustomField(id = "f1", key = "戊键", value = "己值"))
        )
        val options = SearchAdvancedOptions()
        assertTrue(options.isAllFields)
        assertTrue(matchesSearchQuery(target, "标题甲", options = options))
        assertTrue(matchesSearchQuery(target, "乙用户", options = options))
        assertTrue(matchesSearchQuery(target, "example.com", options = options))
        assertTrue(matchesSearchQuery(target, "丙备注", options = options))
        assertTrue(matchesSearchQuery(target, "丁标签", options = options))
        assertTrue(matchesSearchQuery(target, "戊键", options = options))
        assertTrue(matchesSearchQuery(target, "己值", options = options))
    }

    @Test
    fun `字段范围收窄后未勾选字段不再命中`() {
        val target = entry(title = "GitHub 工作账号", username = "dev@corp.com")
        val titleOnly = SearchAdvancedOptions(fields = setOf(SearchField.TITLE))
        assertTrue(matchesSearchQuery(target, "github", options = titleOnly))
        // 用户名不再参与命中
        assertFalse(matchesSearchQuery(target, "dev@", options = titleOnly))
        // URL / 备注亦然
        val notesOnly = SearchAdvancedOptions(fields = setOf(SearchField.NOTES))
        assertFalse(matchesSearchQuery(target, "github", options = notesOnly))
    }

    @Test
    fun `字段范围收窄不影响受保护字段保护口径`() {
        val fields = listOf(UiCustomField(id = "f1", key = "TOTP Seed", value = "", isProtected = true))
        val customOnly = SearchAdvancedOptions(fields = setOf(SearchField.CUSTOM_FIELDS))
        // 键属元数据仍可命中；受保护值依旧不物化
        assertTrue(matchesSearchQuery(entry(customFields = fields), "totp", options = customOnly))
        assertFalse(matchesSearchQuery(entry(customFields = fields), "JBSWY3DPEHPK3PXP", options = customOnly))
    }

    // ===== AC②：大小写档 =====

    @Test
    fun `默认大小写不敏感等于现状`() {
        val target = entry(title = "GitHub")
        val defaults = SearchAdvancedOptions()
        assertTrue(matchesSearchQuery(target, "github", options = defaults))
        assertTrue(matchesSearchQuery(target, "GITHUB", options = defaults))
    }

    @Test
    fun `大小写敏感档严格比对大小写`() {
        val target = entry(title = "GitHub")
        val sensitive = SearchAdvancedOptions(caseSensitive = true)
        assertTrue(matchesSearchQuery(target, "GitHub", options = sensitive))
        assertFalse(matchesSearchQuery(target, "github", options = sensitive))
        assertFalse(matchesSearchQuery(target, "GITHUB", options = sensitive))
    }

    // ===== AC①：「排除已过期」投影生效 =====

    private val rootGroup = VaultGroup(id = "g1", name = "根分组", parentId = null)

    private fun timedEntry(id: String, title: String, expiresAt: Instant?) = UiVaultEntry(
        id = id,
        title = title,
        username = "user",
        url = "https://example.com",
        groupId = "g1",
        expiresAt = expiresAt
    )

    private fun build(
        query: String,
        searchAdvanced: SearchAdvancedOptions
    ) = buildVaultListUiState(
        library = VaultListLibraryState(
            databases = emptyList(),
            allGroups = listOf(rootGroup),
            allEntries = listOf(
                timedEntry("e1", "普通条目", expiresAt = null),
                timedEntry("e2", "已过期条目", expiresAt = Instant.now().minusSeconds(3600)),
                timedEntry("e3", "未过期条目", expiresAt = Instant.now().plusSeconds(3600))
            )
        ),
        settings = UserSettings(),
        session = VaultListSessionState(
            currentGroupId = null,
            filterParams = VaultListFilterParams(
                query = query,
                isSearchActive = false,
                sortOption = VaultSortOption.DEFAULT
            ),
            userMessage = null,
            extended = ExtendedSettings(searchAdvanced = searchAdvanced)
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
    fun `搜索态开启排除已过期后过期条目不再命中`() {
        val uiState = build(
            query = "条目",
            searchAdvanced = SearchAdvancedOptions(excludeExpired = true)
        )
        val titles = uiState.entries.map { it.title }
        assertTrue("普通条目应命中", "普通条目" in titles)
        assertTrue("未过期条目应命中", "未过期条目" in titles)
        assertFalse("已过期条目应被排除", "已过期条目" in titles)
    }

    @Test
    fun `默认关闭时过期条目照常命中`() {
        val uiState = build(query = "条目", searchAdvanced = SearchAdvancedOptions())
        assertTrue(uiState.entries.any { it.title == "已过期条目" })
    }
}
